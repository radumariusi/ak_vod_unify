package ro.ak.vodimport

/** Un serviciu din care putem (sau nu) aduce istoricul. */
data class Service(
    val id: String,
    val name: String,
    /** Pagina de conectare / pornire în browserul din aplicație. */
    val startUrl: String?,
    /** Pagina pe care rulăm extragerea istoricului. */
    val historyUrl: String?,
    /** Domeniile pe care le acceptăm în browser pentru acest serviciu (siguranța punții JS). */
    val hosts: List<String>,
    val script: String?,
    val experimental: Boolean,
    val note: String,
    /** Scriptul poate rula de pe orice pagină a serviciului (nu doar de pe pagina de istoric). */
    val runAnywhere: Boolean = false,
) {
    val supported: Boolean get() = script != null
}

object Services {
    fun all(route: String): List<Service> = listOf(
        Service(
            id = "netflix",
            name = "Netflix",
            startUrl = "https://www.netflix.com/login",
            historyUrl = "https://www.netflix.com/viewingactivity",
            hosts = listOf("netflix.com"),
            script = Scripts.NETFLIX,
            experimental = false,
            note = "Tot istoricul profilului ales, cu minutul unde ai rămas. Alege profilul după conectare.",
            runAnywhere = true,
        ),
        Service(
            id = "primevideo",
            name = "Prime Video",
            startUrl = if (route == "US") "https://www.amazon.com/gp/video/storefront" else "https://www.primevideo.com/",
            historyUrl = if (route == "US") {
                "https://www.amazon.com/gp/video/settings/watch-history"
            } else {
                "https://www.primevideo.com/settings/watch-history/"
            },
            hosts = listOf("primevideo.com", "amazon.com", "amazon.co.uk", "amazon.de"),
            script = Scripts.PRIME,
            experimental = true,
            note = "Experimental: citim pagina „Istoric vizionări” (filmele; episoadele doar dacă site-ul le arată). Fără minut.",
        ),
        unsupported("disney", "Disney+"),
        unsupported("hbomax", "HBO Max"),
        unsupported("skyshowtime", "SkyShowtime"),
        unsupported("peacock", "Peacock"),
    )

    private fun unsupported(id: String, name: String) = Service(
        id = id,
        name = name,
        startUrl = null,
        historyUrl = null,
        hosts = emptyList(),
        script = null,
        experimental = false,
        note = "Site-ul nu are o pagină de istoric pe care s-o putem citi. Ce vezi de acum încolo prinde AK Scrobbler.",
    )
}
