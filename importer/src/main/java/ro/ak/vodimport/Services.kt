package ro.ak.vodimport

import android.net.Uri

internal enum class Service(val id: String, val label: String, val start: String, val history: String, val domains: List<String>) {
    NETFLIX("netflix", "Netflix", NetflixRecent.BASE + "/login", NetflixRecent.HISTORY_URL, listOf("netflix.com")),
    PRIME("primevideo", "Prime Video", "https://www.primevideo.com/", "https://www.primevideo.com/settings/watch-history/",
        listOf("primevideo.com", "amazon.com", "amazon.co.uk", "amazon.de"));

    val script: String get() = if (this == NETFLIX) Scripts.NETFLIX else Scripts.PRIME

    fun trusted(url: String): Boolean {
        val uri = Uri.parse(url)
        val host = uri.host ?: return false
        return uri.scheme == "https" && domains.any { host == it || host.endsWith(".$it") }
    }

    fun isLogin(url: String): Boolean = Uri.parse(url).path?.let { it.contains("login", true) || it.contains("signin", true) } == true
}
