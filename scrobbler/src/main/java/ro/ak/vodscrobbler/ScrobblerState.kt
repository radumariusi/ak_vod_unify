package ro.ak.vodscrobbler

/** Ce se redă acum, pentru ecranul „Ce văd acum”. */
data class LiveSession(
    val packageName: String,
    val appLabel: String,
    val state: PlayState,
    val positionMs: Long?,
    val media: MediaInfo?,
    /** Timpul de redare numărat de noi, când aplicația nu dă poziția. */
    val playedMs: Long = 0L,
)

/** Starea curentă, în memorie. Ecranele și serviciile o actualizează pe firul principal. */
object ScrobblerState {
    private val statusListeners = LinkedHashSet<() -> Unit>()

    var listenerConnected: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyStatusChanged()
        }

    var live: List<LiveSession> = emptyList()
        set(value) {
            val previousApp = playingApp
            field = value
            if (previousApp != playingApp) notifyStatusChanged()
        }

    var keepAliveRunning: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyStatusChanged()
        }

    val playingApp: String?
        get() = live.firstOrNull { it.state == PlayState.PLAYING }?.appLabel

    fun observeStatus(listener: () -> Unit) {
        statusListeners.add(listener)
    }

    fun stopObservingStatus(listener: () -> Unit) {
        statusListeners.remove(listener)
    }

    private fun notifyStatusChanged() {
        statusListeners.toList().forEach { it() }
    }
}
