package ro.ak.vodscrobbler

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * Inima scrobblerului. E un „ascultător de notificări” doar pentru că așa Android ne dă voie
 * să citim sesiunile media active (`MediaSessionManager.getActiveSessions`). Notificările în sine
 * nu le citim și nu le păstrăm.
 *
 * Pentru fiecare aplicație care redă ceva urmărim titlul, poziția, durata și starea
 * (redare / pauză / oprit), iar la pauză, la oprire și o dată pe minut în timpul redării
 * trimitem poziția către AK VOD Unify.
 */
class MediaListenerService : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null
    private val tracked = mutableMapOf<MediaSessionTokenKey, Tracked>()

    private class Tracked(val controller: MediaController, val tracker: SessionTracker, val callback: MediaController.Callback)

    /** Cheie stabilă pentru o sesiune (token-ul își implementează equals/hashCode). */
    private data class MediaSessionTokenKey(val token: Any)

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        refreshSessions(list.orEmpty())
    }

    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            tracked.values.forEach { t -> dispatch(t.tracker.onTick(now)) }
            publishLive()
            handler.postDelayed(this, TICK_CHECK_MS)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        ScrobblerState.listenerConnected = true
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        sessionManager = msm
        val me = ComponentName(this, MediaListenerService::class.java)
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, me, handler)
            refreshSessions(msm.getActiveSessions(me))
        } catch (e: SecurityException) {
            Log.w(TAG, "Fără acces la sesiunile media", e)
            ScrobblerState.listenerConnected = false
        }
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, TICK_CHECK_MS)
        Forwarder.get(this).flushPending()
    }

    override fun onListenerDisconnected() {
        val now = SystemClock.elapsedRealtime()
        tracked.values.forEach { t ->
            dispatch(t.tracker.onGone(now))
            t.controller.unregisterCallback(t.callback)
        }
        tracked.clear()
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        handler.removeCallbacks(ticker)
        ScrobblerState.listenerConnected = false
        publishLive()
        // Cerem sistemului să ne lege din nou (de ex. după o actualizare).
        NotificationListenerService.requestRebind(ComponentName(this, MediaListenerService::class.java))
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun refreshSessions(controllers: List<MediaController>) {
        val now = SystemClock.elapsedRealtime()
        val current = controllers.filter { it.packageName != packageName }.associateBy { MediaSessionTokenKey(it.sessionToken) }

        // Sesiuni dispărute: trimitem oprirea cu ultima poziție.
        (tracked.keys - current.keys).forEach { key ->
            tracked.remove(key)?.let { t ->
                dispatch(t.tracker.onGone(now))
                t.controller.unregisterCallback(t.callback)
            }
        }

        // Sesiuni noi.
        current.forEach { (key, controller) ->
            if (key in tracked) return@forEach
            val tracker = SessionTracker(controller.packageName, appLabel(controller.packageName))
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    dispatch(tracker.onMetadata(metadata?.toInfo(), SystemClock.elapsedRealtime()))
                    publishLive()
                }

                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    applyState(tracker, state)
                    publishLive()
                }

                override fun onSessionDestroyed() {
                    tracked.remove(key)?.let { t ->
                        dispatch(t.tracker.onGone(SystemClock.elapsedRealtime()))
                        t.controller.unregisterCallback(this)
                    }
                    publishLive()
                }
            }
            controller.registerCallback(callback, handler)
            tracked[key] = Tracked(controller, tracker, callback)
            // Starea inițială.
            dispatch(tracker.onMetadata(controller.metadata?.toInfo(), now))
            applyState(tracker, controller.playbackState)
        }
        publishLive()
    }

    private fun applyState(tracker: SessionTracker, state: PlaybackState?) {
        val now = SystemClock.elapsedRealtime()
        val s = when (state?.state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> PlayState.PLAYING
            PlaybackState.STATE_PAUSED -> PlayState.PAUSED
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> PlayState.BUFFERING
            PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE, PlaybackState.STATE_ERROR -> PlayState.STOPPED
            null -> PlayState.OTHER
            else -> PlayState.OTHER
        }
        val position = state?.position?.takeIf { it != PlaybackState.PLAYBACK_POSITION_UNKNOWN && it >= 0 }
        dispatch(tracker.onPlaybackState(s, position, state?.lastPositionUpdateTime ?: 0L, state?.playbackSpeed ?: 1f, now))
    }

    private fun dispatch(events: List<Outgoing>) {
        if (events.isEmpty()) return
        val forwarder = Forwarder.get(this)
        events.forEach(forwarder::send)
    }

    private fun publishLive() {
        val now = SystemClock.elapsedRealtime()
        ScrobblerState.live = tracked.values.map { t ->
            LiveSession(
                packageName = t.tracker.packageName,
                appLabel = t.tracker.appLabel,
                state = t.tracker.state,
                positionMs = t.tracker.position(now),
                media = t.tracker.media,
            )
        }
    }

    private fun appLabel(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    companion object {
        private const val TAG = "AKScrobbler"
        private const val TICK_CHECK_MS = 15_000L
        private const val PREFIX = "android.media.metadata."

        /** Toate câmpurile text din metadate (fără imagini), cu nume scurte: "title", "artist"… */
        fun MediaMetadata.toInfo(): MediaInfo {
            val fields = linkedMapOf<String, String>()
            for (key in keySet()) {
                val short = key.removePrefix(PREFIX).lowercase()
                if ("art" in short.split('_') || short.contains("icon")) continue
                if (short in LONG_KEYS) {
                    val v = getLong(key)
                    if (v > 0) fields[short] = v.toString()
                    continue
                }
                val text = getText(key)?.toString()?.trim()
                if (!text.isNullOrEmpty()) fields[short] = text
            }
            val duration = getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 }
            return MediaInfo(fields, duration)
        }

        private val LONG_KEYS = setOf("duration", "year", "track_number", "disc_number", "num_tracks", "bt_folder_type", "media_type")
    }
}
