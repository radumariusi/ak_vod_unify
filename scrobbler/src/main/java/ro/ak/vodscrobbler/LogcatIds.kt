package ro.ak.vodscrobbler

/** Extragerea codurilor din liniile de logcat (pură, testabilă). */
object LogcatIds {
    private val NETFLIX = listOf(
        Regex("""(?i)(?:movie_?id|video_?id|viewable_?id|playable_?id|episode_?id|title_?id)["'\s:=]{1,4}(\d{6,9})(?!\d)"""),
        Regex("""(?i)/watch/(\d{6,9})(?!\d)"""),
    )
    private val PRIME = Regex("""amzn1\.dv\.gti\.[0-9a-fA-F-]{20,}""")

    fun extract(packageName: String, lines: List<String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        if (packageName.startsWith("com.netflix")) {
            // Ultima apariție = titlul pornit cel mai recent.
            lines.asReversed().firstNotNullOfOrNull { l -> NETFLIX.firstNotNullOfOrNull { it.find(l)?.groupValues?.get(1) } }
                ?.let { out["netflix_id"] = it }
        }
        if (packageName.startsWith("com.amazon")) {
            lines.asReversed().firstNotNullOfOrNull { PRIME.find(it)?.value }?.let { out["prime_gti"] = it }
        }
        return out
    }
}
