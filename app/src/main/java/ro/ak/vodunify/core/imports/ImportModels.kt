package ro.ak.vodunify.core.imports

import kotlinx.serialization.Serializable

/** Sursele de istoric importat (din AK Import sau din CSV), cu numele afișat. */
object ImportSources {
    const val NETFLIX = "netflix"
    const val PRIME_VIDEO = "primevideo"

    val labels: Map<String, String> = linkedMapOf(NETFLIX to "Netflix", PRIME_VIDEO to "Prime Video")
}

/**
 * Un element din istoricul unui serviciu, așa cum vine (din AK Import sau din CSV).
 * Pentru episoade, [series] / [season] / [episode] pot lipsi: atunci totul e în [title]
 * („Stranger Things: Sezonul 1: Capitolul unu”).
 */
@Serializable
data class ImportItem(
    val series: String? = null,
    /** Eticheta sezonului, cum o scrie serviciul („Season 2”, „Sezonul 2”, „Limited Series”). */
    val season: String? = null,
    val episode: String? = null,
    val title: String = "",
    /** Ziua / momentul vizionării (ms); 0 = necunoscut. */
    val date: Long = 0,
    /** Poziția la care ai rămas, în secunde (Netflix o dă). */
    val bookmark: Long? = null,
    /** Durata, în secunde. */
    val duration: Long? = null,
    val id: String? = null,
)

/** Un lot trimis de AK Import (sau citit din CSV). */
@Serializable
data class ImportBatch(
    val service: String,
    /** RO / US: aplicația AK Import din care vine (doar informativ). */
    val route: String = "",
    val profile: String = "",
    val items: List<ImportItem> = emptyList(),
    /** True pentru ultimul lot dintr-un import. */
    val last: Boolean = true,
)

/** Starea importului pentru un serviciu (afișată în Setări). */
@Serializable
data class ImportServiceStats(
    val service: String,
    val received: Int = 0,
    val matched: Int = 0,
    val unmatched: Int = 0,
    val resumePoints: Int = 0,
    val lastImportAt: Long = 0,
    val profile: String = "",
    val route: String = "",
    /** Câteva titluri negăsite, ca să vezi ce n-a mers. */
    val unmatchedSamples: List<String> = emptyList(),
)
