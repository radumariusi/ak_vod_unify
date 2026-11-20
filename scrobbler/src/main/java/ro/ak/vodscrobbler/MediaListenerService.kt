package ro.ak.vodscrobbler

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Inima scrobblerului. E un „ascultător de notificări” pentru că așa Android ne dă voie
 * să citim sesiunile media active (`MediaSessionManager.getActiveSessions`). Din notificări citim
 * doar pe cele ale playerelor (titlul / textul lor), ca rezervă când aplicația nu pune titlul în
 * sesiunea media.
 *
 * Pentru fiecare aplicație care redă ceva urmărim titlul, poziția, durata și starea
 * (redare / pauză / oprit), iar la pauză, la oprire și o dată pe minut în timpul redării
 * trimitem poziția către AK VOD Unify. Tot ce vedem (și de ce trimitem sau nu) intră în
 * [DiagLog], ca să putem afla exact ce expune fiecare aplicație.
 */
class MediaListenerService : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null
    private val tracked = mutableMapOf<MediaSessionTokenKey, Tracked>()

    /** Ultima notificare de player, pe aplicație: rezervă pentru titlu. */
    private val mediaNotifications = mutableMapOf<String, NotificationText>()

    private data class NotificationText(val title: String?, val text: String?, val subText: String?)

    private class Tracked(
        val controller: MediaController,
        val tracker: SessionTracker,
        val callback: MediaController.Callback,
        val tag: String,
    ) {
        var lastSkip: String? = null
        var lastStateLogged: Int? = null
        var lastPositionKnown: Boolean? = null
        var lastStateLogAt: Long = 0L
    }

    /** Cheie stabilă pentru o sesiune (token-ul își implementează equals/hashCode). */
    private data class MediaSessionTokenKey(val token: Any)

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        val l = list.orEmpty()
        DiagLog.add("Sesiuni active acum: ${if (l.isEmpty()) "niciuna" else l.joinToString { it.packageName }}")
        refreshSessions(l)
    }

    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            tracked.values.toList().forEach { t -> dispatch(t.tracker.onTick(now)) }
            publishLive()
            handler.postDelayed(this, TICK_CHECK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        DiagLog.init(this)
        ScrobblerState.listenerConnected = true
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        sessionManager = msm
        val me = ComponentName(this, MediaListenerService::class.java)
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, me, handler)
            val list = msm.getActiveSessions(me)
            DiagLog.add("Ascultător pornit. Sesiuni active: ${if (list.isEmpty()) "niciuna" else list.joinToString { it.packageName }}")
            refreshSessions(list)
        } catch (e: SecurityException) {
            Log.w(TAG, "Fără acces la sesiunile media", e)
            DiagLog.add("EROARE: fără acces la sesiunile media (${e.message})")
            ScrobblerState.listenerConnected = false
        }
        runCatching { activeNotifications?.forEach(::rememberNotification) }
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, TICK_CHECK_MS)
        Forwarder.get(this).flushPending()
        KeepAliveService.start(this)
    }

    override fun onListenerDisconnected() {
        DiagLog.add("Ascultător oprit de Android")
        val now = SystemClock.elapsedRealtime()
        tracked.values.toList().forEach { t ->
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

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let(::rememberNotification)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Păstrăm doar notificările de player (cu sesiune media) sau ale aplicațiilor care redau acum. */
    private fun rememberNotification(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val extras = sbn.notification?.extras ?: return
        val isMedia = extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
            sbn.notification?.category == Notification.CATEGORY_TRANSPORT ||
            tracked.values.any { it.tracker.packageName == sbn.packageName }
        if (!isMedia) return
        val n = NotificationText(
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() },
        )
        if (mediaNotifications[sbn.packageName] == n) return
        mediaNotifications[sbn.packageName] = n
        DiagLog.add(
            "Notificare player ${appLabel(sbn.packageName)}: titlu=${n.title ?: "-"} · text=${n.text ?: "-"} · sub=${n.subText ?: "-"}",
        )
    }

    private fun refreshSessions(controllers: List<MediaController>) {
        val now = SystemClock.elapsedRealtime()
        val current = controllers.filter { it.packageName != packageName }.associateBy { MediaSessionTokenKey(it.sessionToken) }

        // Sesiuni dispărute: trimitem oprirea cu ultima poziție.
        (tracked.keys - current.keys).forEach { key ->
            tracked.remove(key)?.let { t ->
                DiagLog.add("${t.tag}: sesiunea a dispărut")
                dispatch(t.tracker.onGone(now))
                t.controller.unregisterCallback(t.callback)
            }
        }

        // Sesiuni noi.
        current.forEach { (key, controller) ->
            if (key in tracked) return@forEach
            val label = appLabel(controller.packageName)
            val tag = "$label#${Integer.toHexString(key.hashCode()).takeLast(4)}"
            val tracker = SessionTracker(controller.packageName, label)
            lateinit var entry: Tracked
            tracker.onSkip = { reason, why ->
                val line = "${reason.wire}: $why"
                if (entry.lastSkip != line) {
                    entry.lastSkip = line
                    DiagLog.add("$tag: NU trimit (${reason.wire}) → $why")
                }
            }
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    DiagLog.add("$tag: metadate noi\n${describe(metadata)}")
                    dispatch(tracker.onMetadata(metadata?.toInfo(), SystemClock.elapsedRealtime()))
                    publishLive()
                }

                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    applyState(entry, state)
                    publishLive()
                }

                override fun onQueueTitleChanged(title: CharSequence?) {
                    DiagLog.add("$tag: titlu coadă = ${title ?: "-"}")
                }

                override fun onExtrasChanged(extras: Bundle?) {
                    DiagLog.add("$tag: extras sesiune = ${extras.dump()}")
                }

                override fun onSessionDestroyed() {
                    tracked.remove(key)?.let { t ->
                        DiagLog.add("$tag: sesiune închisă de aplicație")
                        dispatch(t.tracker.onGone(SystemClock.elapsedRealtime()))
                        t.controller.unregisterCallback(this)
                    }
                    publishLive()
                }
            }
            entry = Tracked(controller, tracker, callback, tag)
            controller.registerCallback(callback, handler)
            tracked[key] = entry
            DiagLog.add(
                "$tag: sesiune nouă (${controller.packageName})\n" +
                    "  titlu coadă: ${controller.queueTitle ?: "-"} · elemente în coadă: ${controller.queue?.size ?: 0}\n" +
                    "  extras sesiune: ${controller.extras.dump()}\n" +
                    describe(controller.metadata),
            )
            // Starea inițială.
            dispatch(tracker.onMetadata(controller.metadata?.toInfo(), now))
            applyState(entry, controller.playbackState)
        }
        publishLive()
    }

    private fun applyState(t: Tracked, state: PlaybackState?) {
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
        // Jurnal: la fiecare schimbare de stare, iar altfel cel mult o dată la 30 s pe sesiune.
        if (t.lastStateLogged != state?.state || t.lastPositionKnown != (position != null) || now - t.lastStateLogAt > 30_000) {
            t.lastStateLogged = state?.state
            t.lastPositionKnown = position != null
            t.lastStateLogAt = now
            DiagLog.add("${t.tag}: ${describe(state)}")
        }
        dispatch(t.tracker.onPlaybackState(s, position, state?.lastPositionUpdateTime ?: 0L, state?.playbackSpeed ?: 1f, now))
    }

    private fun dispatch(events: List<Outgoing>) {
        if (events.isEmpty()) return
        val forwarder = Forwarder.get(this)
        events.map(::enrich).forEach { o ->
            val title = o.media.fields["title"] ?: o.media.fields["display_title"] ?: "(fără titlu)"
            DiagLog.add(
                "${o.appLabel}: TRIMIT ${o.reason.wire} · $title · minut ${StatusActivity.clock(o.positionMs)}" +
                    " / ${o.media.durationMs?.let(StatusActivity::clock) ?: "?"}",
            )
            forwarder.send(o)
        }
    }

    /**
     * Completează ce lipsește din evenimentul unei sesiuni cu ce știm din altă parte:
     *  - durata / câmpurile de la altă sesiune a aceleiași aplicații (Apple TV are două);
     *  - titlul din notificarea playerului sau din titlul cozii, dacă sesiunea nu-l are.
     * Dacă tot nu avem titlu, marcăm evenimentul „neidentificat” (aplicație + durată + minut).
     */
    private fun enrich(o: Outgoing): Outgoing {
        val siblings = tracked.values.filter { it.tracker.packageName == o.packageName }
        val fields = LinkedHashMap(o.media.fields)
        val duration = o.media.durationMs?.takeIf { it > 0 }
            ?: siblings.firstNotNullOfOrNull { it.tracker.media?.durationMs?.takeIf { d -> d > 0 } }
        if (!MediaInfo(fields, null).hasTitle) {
            siblings.firstOrNull { it.tracker.media?.hasTitle == true }?.tracker?.media?.fields?.forEach { (k, v) ->
                fields.putIfAbsent(k, v)
            }
        }
        if (!MediaInfo(fields, null).hasTitle) {
            mediaNotifications[o.packageName]?.let { n ->
                val title = n.title?.takeIf { !it.equals(o.appLabel, ignoreCase = true) }
                if (title != null) {
                    fields["title"] = title
                    n.text?.let { fields["display_subtitle"] = it }
                }
            }
        }
        if (!MediaInfo(fields, null).hasTitle) {
            siblings.firstNotNullOfOrNull { t -> queueTitle(t.controller) }?.let {
                fields["title"] = it
            }
        }
        // Fără chei suplimentare: AK VOD Unify folosește orice câmp text ca posibil titlu.
        if (duration != null) fields["duration"] = duration.toString()
        return o.copy(media = MediaInfo(fields, duration))
    }

    private fun queueTitle(c: MediaController): String? = runCatching {
        val active = c.playbackState?.activeQueueItemId
        val item = c.queue?.firstOrNull { it.queueId == active } ?: c.queue?.firstOrNull()
        (item?.description?.title ?: c.queueTitle)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

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

        /** Toate cheile brute din metadate, cu tipul lor, pentru jurnal. */
        fun describe(md: MediaMetadata?): String {
            if (md == null) return "  metadate: niciuna (aplicația nu trimite nimic)"
            val keys = md.keySet().sorted()
            val lines = keys.map { key ->
                val short = key.removePrefix(PREFIX)
                val value = when {
                    md.getBitmap(key) != null -> "[imagine]"
                    md.getRating(key) != null -> "[rating ${md.getRating(key)}]"
                    md.getText(key) != null -> "\"${md.getText(key).toString().take(120)}\""
                    else -> md.getLong(key).toString()
                }
                "  $short = $value"
            }
            val d = md.description
            val desc = "  descriere: titlu=${d?.title ?: "-"} · subtitlu=${d?.subtitle ?: "-"} · text=${d?.description ?: "-"}"
            return if (lines.isEmpty()) "  metadate: goale (0 chei)\n$desc" else "  metadate (${keys.size} chei):\n" + lines.joinToString("\n") + "\n" + desc
        }

        fun describe(s: PlaybackState?): String {
            if (s == null) return "stare: niciuna"
            val name = when (s.state) {
                PlaybackState.STATE_NONE -> "NONE"
                PlaybackState.STATE_STOPPED -> "STOPPED"
                PlaybackState.STATE_PAUSED -> "PAUSED"
                PlaybackState.STATE_PLAYING -> "PLAYING"
                PlaybackState.STATE_FAST_FORWARDING -> "FAST_FORWARD"
                PlaybackState.STATE_REWINDING -> "REWIND"
                PlaybackState.STATE_BUFFERING -> "BUFFERING"
                PlaybackState.STATE_ERROR -> "ERROR"
                PlaybackState.STATE_CONNECTING -> "CONNECTING"
                else -> "ALTA"
            }
            val pos = if (s.position == PlaybackState.PLAYBACK_POSITION_UNKNOWN) "necunoscută" else StatusActivity.clock(s.position.coerceAtLeast(0))
            return buildString {
                append("stare=$name(${s.state}) poziție=$pos (${s.position} ms) viteză=${s.playbackSpeed}")
                append(" bufferat=${s.bufferedPosition} acțiuni=0x${java.lang.Long.toHexString(s.actions)}")
                append(" coadă activă=${s.activeQueueItemId}")
                s.errorMessage?.let { append(" eroare=\"$it\"") }
                val extras = s.extras
                if (extras != null && !extras.isEmpty) append(" extras=${extras.dump()}")
                if (s.customActions.isNotEmpty()) append(" acțiuni proprii=${s.customActions.joinToString { it.name }}")
            }
        }

        @Suppress("DEPRECATION")
        fun Bundle?.dump(): String {
            if (this == null || isEmpty) return "-"
            return runCatching {
                keySet().sorted().joinToString(", ", "{", "}") { k -> "$k=${get(k)?.toString()?.take(80)}" }
            }.getOrDefault("(nu pot citi)")
        }
    }
}
