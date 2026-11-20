package ro.ak.vodunify.core.playback

/** Ce vrea utilizatorul să vadă: un film, un serial (pagina lui) sau un episod anume. */
data class PlaybackRequest(
    val type: Type,
    val tmdbId: Long,
    /** Pentru episoade: id-ul IMDb al **serialului**. */
    val imdbId: String?,
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val runtimeMinutes: Int? = null,
) {
    enum class Type { MOVIE, SHOW, EPISODE }

    /** Cheie stabilă, compatibilă cu tabela `media` ("movie:tmdb:603", "tv:tmdb:1399:s1e2"). */
    val key: String
        get() = when (type) {
            Type.MOVIE -> "movie:tmdb:$tmdbId"
            Type.SHOW -> "tv:tmdb:$tmdbId"
            Type.EPISODE -> "tv:tmdb:$tmdbId:s${season ?: 0}e${episode ?: 0}"
        }

    /** Eticheta scurtă: „S1 E2 · Titlu” pentru episoade. */
    val label: String
        get() = when (type) {
            Type.EPISODE -> buildString {
                append("S${season ?: "?"} E${episode ?: "?"}")
                episodeTitle?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
            }
            else -> listOfNotNull(title, year?.toString()).joinToString(" · ")
        }
}

/** Cât de precis ajunge linkul în aplicația-țintă, de la cel mai bun la cel mai slab. */
enum class LinkPrecision(val label: String) {
    EPISODE("Direct la episod"),
    TITLE("Pagina titlului"),
    SERIES("Pagina serialului"),
    SEARCH("Căutare în aplicație"),
    APP("Doar deschide aplicația"),
}

/** Un link candidat; se folosește doar dacă aplicația instalată îl acceptă (validat cu PackageManager). */
data class LinkCandidate(val precision: LinkPrecision, val uri: String)

/** Starea poziției de reluare afișată lângă butoane. */
enum class ResumeState(val label: String) {
    /** Poziție exactă, confirmată recent de un tracker. */
    SYNCED("Poziție sincronizată"),

    /** Există o poziție, dar un tracker are încă o actualizare în curs. */
    PENDING("În așteptare"),

    /** Calculată din procent × durată (trackerele dau doar procentul). */
    ESTIMATED("Poziție estimată"),

    /** Nu avem poziție: reluarea se face manual în aplicația-țintă. */
    MANUAL("Reluare manuală"),
}

data class ResumeInfo(
    val state: ResumeState,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    /** Ex. "trakt". */
    val source: String? = null,
) {
    val hasPosition: Boolean get() = positionMs != null && positionMs > 0

    companion object {
        val Manual = ResumeInfo(ResumeState.MANUAL)
    }
}

/** „mm:ss” sau „h:mm:ss”. */
fun formatPosition(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
