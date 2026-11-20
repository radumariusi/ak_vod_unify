package ro.ak.vodunify.core.publish

/** Sursa pozițiilor setate de tine în aplicație („Setează minutul”). */
const val LOCAL_SOURCE = "local"

/** Cum se trimite progresul către trackere, pe profil. */
enum class PublishMode(val label: String, val description: String) {
    AUTOMATIC(
        "Automat",
        "După „Setează minutul” și după fiecare sincronizare, poziția cea mai nouă se trimite în destinațiile alese.",
    ),
    ON_DEMAND(
        "La cerere",
        "Nimic nu pleacă singur: apeși „Publică acum” în „Alege unde vezi” sau în Diagnostic rute.",
    ),
    VIEW_ONLY(
        "Doar vizualizare",
        "Aplicația doar citește din trackere. „Setează minutul” rămâne doar pe acest dispozitiv.",
    ),
}

/** Starea unei publicări, salvată în publish_records. */
enum class PublishStatus(val label: String) {
    /** Scrierea a plecat; încă nu am citit înapoi poziția. */
    SENT("Trimis, se verifică"),

    /** Am citit poziția înapoi din destinație și se potrivește. Doar acum e „publicat”. */
    PUBLISHED("Publicat"),

    /** Destinația a acceptat scrierea, dar la citire nu arată (încă) poziția. Se reverifică la sincronizare. */
    NOT_CONFIRMED("Trimis, neconfirmat"),

    FAILED("Eroare"),
    ;

    companion object {
        fun of(name: String?): PublishStatus? = entries.firstOrNull { it.name == name }
    }
}

/** Un profil de publicare: unde și cum se trimite progresul. */
data class PublishProfile(
    val index: Int,
    val name: String,
    val mode: PublishMode,
    /** Id-urile trackerelor-destinație ("trakt", "simkl"). */
    val destinations: Set<String>,
)

data class PublishSettings(
    val activeIndex: Int = 0,
    val profiles: List<PublishProfile> = defaultProfiles(),
) {
    val active: PublishProfile get() = profiles.getOrElse(activeIndex) { profiles.first() }

    companion object {
        const val PROFILE_COUNT = 3
        val DEFAULT_DESTINATIONS = setOf("trakt", "simkl")

        fun defaultName(index: Int) = if (index == 0) "Principal" else "Profil ${index + 1}"

        fun defaultProfiles() = (0 until PROFILE_COUNT).map {
            PublishProfile(it, defaultName(it), PublishMode.ON_DEMAND, DEFAULT_DESTINATIONS)
        }
    }
}

/** Rezultatul publicării către o destinație, pentru mesaje în UI. */
data class PublishOutcome(
    val destination: String,
    val destinationLabel: String,
    /** Null = nu s-a trimis nimic (vezi [skipReason]). */
    val status: PublishStatus?,
    val skipReason: String? = null,
    val error: String? = null,
) {
    val summary: String
        get() = "$destinationLabel: " + when {
            status == PublishStatus.FAILED -> "eroare" + (error?.let { " ($it)" } ?: "")
            status != null -> status.label.lowercase()
            else -> skipReason ?: "nimic de trimis"
        }
}

fun List<PublishOutcome>.summary(): String =
    if (isEmpty()) "Nicio destinație conectată în profilul activ" else joinToString(" · ") { it.summary }
