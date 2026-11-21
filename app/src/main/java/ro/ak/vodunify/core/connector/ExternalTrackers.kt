package ro.ak.vodunify.core.connector

/**
 * Trakt și SIMKL sunt ascunse: baza de date locală e sursa de adevăr, iar „Continuă vizionarea” și
 * istoricul vin din AK Scrobbler (și, mai târziu, dintr-un import inițial al istoricului).
 *
 * Codul conectorilor rămâne neatins. Cu [ENABLED] = false:
 * - conectorii nu se înregistrează (fără login, sincronizare sau publicare);
 * - datele lor rămase în Room de la sincronizări vechi nu se mai afișează (nu se șterg);
 * - „Văzut” / „De văzut” se salvează local.
 * Reactivare: [ENABLED] = true.
 */
object ExternalTrackers {
    const val ENABLED = false

    /** Sursele trackerilor externi (id-urile conectorilor). */
    val SOURCES: Set<String> = setOf("trakt", "simkl", "wetrakr")

    /** True dacă datele din [source] se afișează. */
    fun isVisible(source: String): Boolean = ENABLED || source !in SOURCES
}
