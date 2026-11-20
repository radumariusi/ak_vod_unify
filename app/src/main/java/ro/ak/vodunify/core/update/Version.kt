package ro.ak.vodunify.core.update

/** Utilitare pure (testabile) pentru compararea versiunilor. */
object Version {
    private val versionCodeRegex = Regex("""vc(\d+)""", RegexOption.IGNORE_CASE)

    /** Extrage versionCode din numele APK-ului, ex. "ak-vod-unify-0.2.0-vc12.apk" -> 12. */
    fun versionCodeFromAssetName(name: String): Int? =
        versionCodeRegex.find(name)?.groupValues?.get(1)?.toIntOrNull()

    /** Compară "v1.2.3" cu "1.2.10". Ignoră prefixul "v" și sufixele "-debug", "-beta" etc. */
    fun compareNames(a: String, b: String): Int {
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    private fun parts(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-')
            .split('.')
            .map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    /**
     * Release-ul e mai nou dacă are versionCode mai mare; dacă versionCode lipsește
     * din numele asset-ului, se compară versionName.
     */
    fun isNewer(remoteCode: Int?, remoteName: String, localCode: Int, localName: String): Boolean =
        if (remoteCode != null) remoteCode > localCode else compareNames(remoteName, localName) > 0
}
