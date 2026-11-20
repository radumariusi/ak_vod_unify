package ro.ak.vodscrobbler

/** Ce se redă acum, pentru ecranul „Ce văd acum”. */
data class LiveSession(
    val packageName: String,
    val appLabel: String,
    val state: PlayState,
    val positionMs: Long?,
    val media: MediaInfo?,
)

/** Starea curentă, în memorie (serviciul și ecranul rulează în același proces). */
object ScrobblerState {
    @Volatile var listenerConnected: Boolean = false

    @Volatile var live: List<LiveSession> = emptyList()
}
