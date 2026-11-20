package ro.ak.vodunify.core.calendar.model

/**
 * A streaming service the user can have installed on the TV.
 *
 * @property tmdbProviderIds TMDB / JustWatch watch-provider ids that map to this app
 *   (some services have several ids, e.g. Amazon has one per region and a channel id).
 * @property tmdbNetworkIds TMDB network ids used when the service is the show's original network.
 * @property androidTvPackages known package names (Android TV build first, then the phone build).
 *   When none of them is installed, the app also looks for an installed app whose name
 *   contains [name] / [launcherNames], so a different package (e.g. a regional build) still works.
 * @property aliases names TMDB uses in the notes of movie release dates (e.g. "Netflix",
 *   "Disney Plus"), so a digital release can be recognized as a premiere on this service.
 * @property globalDrop true when the service releases its titles at the same moment in
 *   every country, at 00:00 Pacific time (Netflix, Disney+, Prime Video, …).
 */
data class StreamingApp(
    val id: String,
    val name: String,
    val tmdbProviderIds: Set<Int>,
    val tmdbNetworkIds: Set<Int> = emptySet(),
    val androidTvPackages: List<String> = emptyList(),
    val aliases: Set<String> = emptySet(),
    val globalDrop: Boolean = false,
    /** Words to look for in installed apps' names when no known package is installed. */
    val launcherNames: List<String> = emptyList(),
) {
    /** True when a TMDB release note (e.g. "Netflix", "Streaming on Max") names this service. */
    fun matchesNote(note: String?): Boolean {
        if (note.isNullOrBlank()) return false
        val words = Regex("[^a-z0-9+]+").split(note.lowercase()).filter { it.isNotBlank() }.joinToString(" ")
        val padded = " $words "
        return (aliases + name).any { alias ->
            val a = Regex("[^a-z0-9+]+").split(alias.lowercase()).filter { it.isNotBlank() }.joinToString(" ")
            a.isNotBlank() && padded.contains(" $a ")
        }
    }
}

/**
 * Built-in list of streaming apps shown in Settings. Provider ids come from
 * TMDB `/watch/providers/tv`; they can be refreshed later from the API.
 */
object StreamingCatalog {
    val all: List<StreamingApp> = listOf(
        StreamingApp("netflix", "Netflix", setOf(8, 1796), setOf(213), listOf("com.netflix.ninja", "com.netflix.mediaclient"), setOf("Netflix"), globalDrop = true),
        StreamingApp("max", "Max / HBO Max", setOf(1899, 384), setOf(49, 3186), listOf("com.wbd.stream", "com.hbo.hbonow"), setOf("Max", "HBO Max", "HBO"), globalDrop = true, launcherNames = listOf("HBO Max", "Max")),
        StreamingApp("disney", "Disney+", setOf(337), setOf(2739), listOf("com.disney.disneyplus"), setOf("Disney+", "Disney Plus"), globalDrop = true),
        StreamingApp("prime", "Prime Video", setOf(9, 119), setOf(1024), listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"), setOf("Prime Video", "Amazon Prime Video", "Amazon Prime", "Amazon"), globalDrop = true, launcherNames = listOf("Prime Video")),
        StreamingApp("appletv", "Apple TV+", setOf(350), setOf(2552), listOf("com.apple.atve.androidtv.appletv"), setOf("Apple TV+", "Apple TV Plus"), globalDrop = true, launcherNames = listOf("Apple TV")),
        StreamingApp("skyshowtime", "SkyShowtime", setOf(1773), emptySet(), emptyList(), setOf("SkyShowtime")),
        StreamingApp("paramount", "Paramount+", setOf(531, 582), setOf(4330), listOf("com.cbs.ott", "com.cbs.app"), setOf("Paramount+", "Paramount Plus"), globalDrop = true),
        StreamingApp("hulu", "Hulu", setOf(15), setOf(453), listOf("com.hulu.livingroomplus", "com.hulu.plus"), setOf("Hulu"), globalDrop = true),
        StreamingApp("peacock", "Peacock", setOf(386, 387), setOf(3353), listOf("com.peacocktv.peacockandroid"), setOf("Peacock"), globalDrop = true),
        StreamingApp("crunchyroll", "Crunchyroll", setOf(283), setOf(1112), listOf("com.crunchyroll.crunchyroid"), setOf("Crunchyroll")),
        StreamingApp("voyo", "Voyo", setOf(1859), emptySet(), emptyList(), setOf("Voyo")),
    )

    fun byId(id: String): StreamingApp? = all.firstOrNull { it.id == id }
}
