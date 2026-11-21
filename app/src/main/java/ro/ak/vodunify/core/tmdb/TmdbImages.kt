package ro.ak.vodunify.core.tmdb

/** URL-uri imagini TMDb. Mărimile sunt alese pentru TV 1080p și telefoane. */
object TmdbImages {
    private const val BASE = "https://image.tmdb.org/t/p/"

    fun poster(path: String?) = url("w342", path)
    fun posterLarge(path: String?) = url("w500", path)
    fun backdrop(path: String?) = url("w1280", path)
    fun still(path: String?) = url("w300", path)
    fun profile(path: String?) = url("w185", path)
    fun logo(path: String?) = url("w154", path)

    /** Sigla unui titlu (logo-ul cu numele), destul de clară pe TV. */
    fun titleLogo(path: String?) = url("w500", path)

    fun url(size: String, path: String?): String? =
        path?.takeIf { it.isNotBlank() }?.let { BASE + size + (if (it.startsWith("/")) it else "/$it") }
}
