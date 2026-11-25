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

        /** Ce dă aplicația în sesiunea media (fără completări). */
        var nativeInfo: MediaInfo? = null
        val screenTimeResolver = ScreenTimeResolver()

        /** Titlul citit de pe ecran pentru această redare (dacă aplicația nu-l dă). */
        var screenGuess: ScreenGuess? = null
        var startedAt: Long = SystemClock.elapsedRealtime()
        var everPlayed = false

        /** Coduri găsite în jurnalul sistemului (netflix_id…). */
        val extraFields = linkedMapOf<String, String>()
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

    private var tickScheduled = false
    private val ticker = Runnable {
        tickScheduled = false
        val now = SystemClock.elapsedRealtime()
        tracked.values.toList().forEach { t -> dispatch(t.tracker.onTick(now)) }
        tickScreenOnly(now)
        publishLive()
    }

    private fun updateTicker() {
        val needsTick = ScrobblerState.listenerConnected && (tracked.isNotEmpty() || screenOnly.isNotEmpty())
        if (tickScheduled == needsTick) return
        tickScheduled = needsTick
        if (needsTick) handler.postDelayed(ticker, TICK_CHECK_MS) else handler.removeCallbacks(ticker)
    }

    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        ScreenState.listener = ::onScreen
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
            activeListener = this
            DiagLog.add("Ascultător pornit. Sesiuni active: ${if (list.isEmpty()) "niciuna" else list.joinToString { it.packageName }}")
            refreshSessions(list)
        } catch (e: SecurityException) {
            Log.w(TAG, "Fără acces la sesiunile media", e)
            DiagLog.add("EROARE: fără acces la sesiunile media (${e.message})")
            ScrobblerState.listenerConnected = false
        }
        runCatching { activeNotifications?.forEach(::rememberNotification) }
        updateTicker()
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
        sessionManager = null
        if (activeListener === this) activeListener = null
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
        if (ScreenState.listener == ::onScreen) ScreenState.listener = null
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        tracked.values.forEach { it.controller.unregisterCallback(it.callback) }
        if (activeListener === this) {
            activeListener = null
            ScrobblerState.listenerConnected = false
            ScrobblerState.live = emptyList()
        }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun refreshNow(): Boolean {
        val manager = sessionManager ?: return false
        return runCatching {
            refreshSessions(manager.getActiveSessions(Access.component(this)))
            tracked.values.toList().forEach { entry ->
                applyState(entry, entry.controller.playbackState)
            }
            publishLive()
        }.isSuccess
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
        // Sesiunile acestei aplicații fără titlu îl pot lua acum din notificare.
        val now = SystemClock.elapsedRealtime()
        tracked.values.filter { it.tracker.packageName == sbn.packageName && !hasTextTitle(it) }.forEach { t ->
            val hadTitle = t.tracker.media?.hasTitle == true
            dispatch(t.tracker.onMetadata(merged(t), now, correction = hadTitle))
        }
        publishLive()
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
                    val info = metadata?.toInfo()
                    if (entry.nativeInfo?.identity != info?.identity) entry.screenTimeResolver.reset()
                    entry.nativeInfo = info
                    dispatch(tracker.onMetadata(merged(entry), SystemClock.elapsedRealtime()))
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
            entry.nativeInfo = controller.metadata?.toInfo()
            extractGtiFromBundle(controller.extras, entry.extraFields)
            extractAdFromBundle(controller.extras, entry.extraFields)
            controller.registerCallback(callback, handler)
            tracked[key] = entry
            ScreenState.rememberMediaApp(this, controller.packageName)
            DiagLog.add(
                "$tag: sesiune nouă (${controller.packageName})\n" +
                    "  titlu coadă: ${controller.queueTitle ?: "-"} · elemente în coadă: ${controller.queue?.size ?: 0}\n" +
                    "  extras sesiune: ${controller.extras.dump()}\n" +
                    describe(controller.metadata),
            )
            // Starea inițială.
            dispatch(tracker.onMetadata(merged(entry), now))
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
        if (s == PlayState.PLAYING && !t.everPlayed) {
            t.everPlayed = true
            onPlaybackStarted(t, now)
            // Jurnalul sistemului: verificăm rapid și din nou după câteva secunde (logcat se rotește repede pe TV).
            handler.postDelayed({ probeLogcat(t) }, 2_500L)
            handler.postDelayed({ probeLogcat(t) }, LOGCAT_DELAY_MS)
        }
        if (s == PlayState.PAUSED && t.tracker.state == PlayState.PLAYING) {
            probeLogcat(t)
            if (!hasTextTitle(t) && t.screenGuess == null) {
                ScreenState.requestSnapshot(t.tracker.packageName)
                handler.postDelayed({ recheckScreenPause(t) }, 1_200L)
            }
        }
        extractGtiFromBundle(state?.extras, t.extraFields)
        val wasAd = t.extraFields["is_ad"] == "1"
        extractAdFromBundle(state?.extras, t.extraFields)
        val isAdNow = t.extraFields["is_ad"] == "1"
        if (isAdNow && !wasAd) {
            dispatch(t.tracker.onAdDetected(now))
        } else if (!isAdNow && wasAd) {
            dispatch(t.tracker.onMetadata(merged(t), now))
        }
        dispatch(t.tracker.onPlaybackState(s, position, state?.lastPositionUpdateTime ?: 0L, state?.playbackSpeed ?: 1f, now))
    }

    private fun recheckScreenPause(t: Tracked) {
        if (!tracked.values.contains(t) || t.tracker.state != PlayState.PAUSED || hasTextTitle(t) || t.screenGuess != null) return
        val snap = ScreenState.latest(t.tracker.packageName) ?: return
        val guess = snap.guess ?: return
        t.screenGuess = guess
        DiagLog.add("${t.tag}: titlu citit după pauză de pe ecran: ${guess.title} · ${guess.subtitle ?: "-"}")
        dispatch(t.tracker.onMetadata(merged(t), SystemClock.elapsedRealtime()))
        publishLive()
    }

    private fun hasTextTitle(t: Tracked) = t.nativeInfo?.fields?.keys?.any { it in TEXT_TITLE_KEYS } == true

    private val loggedLogcat = LinkedHashSet<String>()

    /** Caută codul titlului în logcat pentru sesiunile fără titlu (Netflix). */
    private fun probeLogcat(t: Tracked) {
        if (hasTextTitle(t)) return
        if (!tracked.values.contains(t)) return
        LogcatProbe.probe(this, t.tracker.packageName) { r ->
            handler.post {
                // Liniile brute de sistem nu se mai scriu în jurnal (acopereau tot);
                // păstrăm doar cele care conțin codul găsit, maxim 5.
                val fresh = r.lines.filter { loggedLogcat.add(it) }
                while (loggedLogcat.size > 2000) loggedLogcat.remove(loggedLogcat.first())
                if (r.ids.isEmpty()) {
                    if (fresh.isNotEmpty()) DiagLog.add("${t.tag}: niciun cod de titlu în logcat (${fresh.size} linii verificate)")
                    return@post
                }
                fresh.filter { line -> r.ids.values.any { line.contains(it) } }.take(5)
                    .forEach { DiagLog.add("LOGCAT ${t.tag}: ${it.take(220)}") }
                if (r.ids.all { (k, v) -> t.extraFields[k] == v }) return@post
                t.extraFields.putAll(r.ids)
                DiagLog.add("${t.tag}: cod titlu din logcat: ${r.ids}")
                dispatch(t.tracker.onMetadata(merged(t), SystemClock.elapsedRealtime()))
                publishLive()
            }
        }
    }

    /**
     * Prima redare a unei sesiuni fără titlu: luăm titlul de pe pagina de detalii deschisă chiar
     * înainte (cea cu butonul „Redă”), sau de pe ecranul playerului dacă l-am citit deja.
     */
    private fun onPlaybackStarted(t: Tracked, now: Long) {
        if (hasTextTitle(t) || t.screenGuess != null) return
        val pkg = t.tracker.packageName
        val latest = ScreenState.latest(pkg)?.takeIf { it.guess != null && now - it.at < 15_000 && (!it.times.isEmpty || it.guess.subtitle != null) }
        val details = ScreenState.lastDetails(pkg)?.takeIf { now - it.at < DETAILS_MAX_AGE_MS }
        val pick = latest ?: details ?: return
        t.screenGuess = pick.guess
        DiagLog.add("${t.tag}: titlu luat de pe ${if (pick === latest) "ecranul playerului" else "pagina titlului"}: ${pick.guess?.title} · ${pick.guess?.subtitle ?: "-"}")
        dispatch(t.tracker.onMetadata(merged(t), now))
    }

    /** Ce s-a citit de pe ecran: titlul și minutul, pentru sesiunile aplicației respective. */
    private fun onScreen(snap: ScreenSnapshot) {
        val now = SystemClock.elapsedRealtime()
        val sessions = tracked.values.filter { it.tracker.packageName == snap.packageName }
        if (sessions.isEmpty()) {
            onScreenOnly(snap, now)
            return
        }
        // Doar ecranul playerului (redare în curs, pauză cu timpi sau linie de episod), nu meniurile.
        val active = sessions.filter { it.tracker.state == PlayState.PLAYING || it.tracker.state == PlayState.PAUSED || it.tracker.state == PlayState.BUFFERING }
        if (active.isEmpty()) return
        val playing = active.any { it.tracker.state == PlayState.PLAYING }
        val isPlayer = playing || !snap.times.isEmpty || snap.guess?.subtitle != null
        if (!isPlayer) return
        if (snap.isAd) {
            for (t in active) {
                dispatch(t.tracker.onAdDetected(now))
            }
            publishLive()
            return
        }
        for (t in active) {
            val guess = snap.guess
            if (guess != null && !hasTextTitle(t) && guess != t.screenGuess) {
                val previous = t.screenGuess
                val oldCode = ScreenPlayback.episodeCode(previous?.subtitle)
                val newCode = ScreenPlayback.episodeCode(guess.subtitle)
                if (oldCode == null || oldCode != newCode) t.screenTimeResolver.reset()
                t.screenGuess = guess
                DiagLog.add("${t.tag}: titlu de pe ecran: ${guess.title} · ${guess.subtitle ?: "-"}")
                dispatch(t.tracker.onMetadata(merged(t), now, correction = previous != null && (oldCode == null || oldCode == newCode)))
            }
            if (!snap.times.isEmpty) {
                val nativeDuration = t.nativeInfo?.durationMs
                val times = t.screenTimeResolver.resolve(snap.times, nativeDuration)
                t.tracker.onScreenTimes(times.position(nativeDuration ?: t.tracker.media?.durationMs),
                    times.durationMs.takeIf { nativeDuration == null }, now, fromScreen = true)
            }
        }
        publishLive()
    }

    /** Redări fără sesiune media (Nuvio, DebridStream…), urmărite doar de pe ecran. */
    private val screenOnly = HashMap<String, ScreenPlayback>()

    private fun onScreenOnly(snap: ScreenSnapshot, now: Long) {
        val pkg = snap.packageName
        var p = screenOnly[pkg]
        if (p == null) {
            if (!ScreenState.KNOWN_VIDEO_PREFIXES.any { pkg.startsWith(it) } || !ScreenPlayback.looksLikePlayer(snap.times)) return
            p = ScreenPlayback(pkg, appLabel(pkg))
            val tag = "${p.appLabel}#ecran"
            var lastSkip: String? = null
            p.tracker.onSkip = { reason, why ->
                val line = "${reason.wire}: $why"
                if (lastSkip != line) {
                    lastSkip = line
                    DiagLog.add("$tag: NU trimit (${reason.wire}) → $why")
                }
            }
            screenOnly[pkg] = p
            ScreenState.screenOnlyPackages = screenOnly.keys.toSet()
            ScreenState.rememberMediaApp(this, pkg)
            DiagLog.add("$tag: redare fără sesiune media; o urmăresc de pe ecran")
        }
        val details = ScreenState.lastDetails(pkg)?.takeIf { now - it.at < DETAILS_MAX_AGE_MS }?.guess
        val before = p.guess
        dispatch(p.onScreen(snap.guess, snap.times, now, details))
        if (p.guess != before) DiagLog.add("${p.appLabel}#ecran: titlu de pe ecran: ${p.guess?.title} · ${p.guess?.subtitle ?: "-"}")
        updateTicker()
    }

    private fun tickScreenOnly(now: Long) {
        if (screenOnly.isEmpty()) return
        val fg = ScreenState.foregroundPackage
        screenOnly.values.toList().forEach { p ->
            dispatch(p.onTick(now, fg))
            if (p.finished) {
                DiagLog.add("${p.appLabel}#ecran: aplicația nu mai e pe ecran → oprire")
                screenOnly.remove(p.packageName)
            }
        }
        ScreenState.screenOnlyPackages = screenOnly.keys.toSet()
    }

    /**
     * Ce știm despre conținut: sesiunea media, iar dacă ea nu are titlul, notificarea playerului
     * sau ecranul. Cheia „title_source” spune de unde e titlul (pentru jurnal).
     */
    private fun merged(t: Tracked): MediaInfo? {
        val native = t.nativeInfo
        val nativeHasText = native?.fields?.keys?.any { it in TEXT_TITLE_KEYS } == true
        if (nativeHasText && t.extraFields.isEmpty()) return native
        val fields = LinkedHashMap(native?.fields.orEmpty())
        fields.putAll(t.extraFields)
        if (nativeHasText) return MediaInfo(fields, native?.durationMs)
        val n = mediaNotifications[t.tracker.packageName]
        val notifTitle = n?.title?.takeIf { !it.equals(t.tracker.appLabel, ignoreCase = true) }
        val g = t.screenGuess
        when {
            notifTitle != null -> {
                fields["title"] = notifTitle
                n?.text?.let { fields["display_subtitle"] = it }
                fields["title_source"] = "notification"
            }
            g != null -> {
                fields["title"] = g.title
                g.subtitle?.let { fields["display_subtitle"] = it }
                fields["title_source"] = "screen"
            }
            else -> if (native == null && t.extraFields.isEmpty()) return null
        }
        return MediaInfo(fields, native?.durationMs)
    }

    private fun dispatch(events: List<Outgoing>) {
        if (events.isEmpty()) return
        val forwarder = Forwarder.get(this)
        val now = SystemClock.elapsedRealtime()
        events.map(::enrich).filterNot { o ->
            // Apple TV are două sesiuni: dacă alta are minutul real, nu trimitem estimarea.
            o.estimated && tracked.values.any { it.tracker.packageName == o.packageName && it.tracker.playId != o.playId && it.tracker.position(now) != null }
        }.forEach { o ->
            val title = o.media.fields["title"] ?: o.media.fields["display_title"] ?: "(fără titlu)"
            DiagLog.add(
                "${o.appLabel}: TRIMIT ${o.reason.wire} · $title · minut ${StatusActivity.clock(o.positionMs)}${if (o.estimated) " (estimat)" else ""}" +
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
        updateTicker()
        val now = SystemClock.elapsedRealtime()
        ScreenState.skipScreenForPackages = tracked.values
            .filter { t ->
                val active = t.tracker.state == PlayState.PLAYING || t.tracker.state == PlayState.PAUSED || t.tracker.state == PlayState.BUFFERING
                active && hasTextTitle(t) && t.tracker.hasSessionPosition &&
                    ScreenState.KNOWN_VIDEO_PREFIXES.none { t.tracker.packageName.startsWith(it) }
            }
            .map { it.tracker.packageName }
            .toSet()
        ScrobblerState.live = tracked.values.map { t ->
            LiveSession(
                packageName = t.tracker.packageName,
                appLabel = t.tracker.appLabel,
                state = t.tracker.state,
                positionMs = t.tracker.position(now),
                media = t.tracker.media,
                playedMs = t.tracker.playedTime(now),
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
        private var activeListener: MediaListenerService? = null

        fun reconnect(context: Context) {
            if (!Access.isListenerEnabled(context) || activeListener?.refreshNow() == true) return
            runCatching { NotificationListenerService.requestRebind(Access.component(context)) }
                .onFailure { Log.w(TAG, "Nu pot cere reconectarea ascultătorului acum", it) }
        }

        private const val TAG = "AKScrobbler"
        private const val TICK_CHECK_MS = 15_000L

        /** Pagina titlului (cu „Redă”) contează doar dacă redarea a pornit la scurt timp după. */
        private const val DETAILS_MAX_AGE_MS = 3 * 60_000L
        private const val LOGCAT_DELAY_MS = 8_000L

        /** Câmpurile care conțin cu adevărat un titlu (nu doar coduri). */
        private val TEXT_TITLE_KEYS = setOf("title", "display_title", "display_subtitle", "artist", "album", "album_artist")
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
            description?.let { d ->
                d.mediaId?.trim()?.takeIf { it.isNotEmpty() }?.let { fields.putIfAbsent("media_id", it) }
                d.title?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { fields.putIfAbsent("title", it) }
                d.subtitle?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { fields.putIfAbsent("display_subtitle", it) }
                d.description?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { fields.putIfAbsent("display_description", it) }
            }
            val duration = getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 }
            return MediaInfo(fields, duration)
        }

        private val PRIME_GTI_RE = Regex("""amzn1\.dv\.gti\.[0-9a-fA-F-]{20,}""")

        @Suppress("DEPRECATION")
        fun extractGtiFromBundle(extras: Bundle?, out: MutableMap<String, String>) {
            if (extras == null || extras.isEmpty || "prime_gti" in out) return
            runCatching {
                for (k in extras.keySet()) {
                    val v = extras.get(k)?.toString() ?: continue
                    PRIME_GTI_RE.find(v)?.value?.let {
                        out["prime_gti"] = it
                        return
                    }
                }
            }
        }

        private val LONG_KEYS = setOf("duration", "year", "track_number", "disc_number", "num_tracks", "bt_folder_type", "media_type", "advertisement")

        @Suppress("DEPRECATION")
        fun extractAdFromBundle(extras: Bundle?, out: MutableMap<String, String>) {
            out.remove("is_ad")
            if (extras == null || extras.isEmpty) return
            runCatching {
                for (k in extras.keySet()) {
                    val low = k.lowercase()
                    if (low == "advertisement" || low == "is_ad" || low == "is_advertisement" || low.endsWith(".advertisement") || low.endsWith(".is_ad")) {
                        val v = extras.get(k)
                        if (v == true || v == 1 || v == 1L || v?.toString()?.equals("true", true) == true || v?.toString() == "1") {
                            out["is_ad"] = "1"
                            return
                        }
                    }
                }
            }
        }

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
