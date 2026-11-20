package ro.ak.vodunify.core.tmdb

/**
 * TMDb acceptă două tipuri de credențiale:
 *  - „API Read Access Token” (v4, JWT lung „eyJ…”) → header `Authorization: Bearer …` (recomandat);
 *  - „API Key” (v3, 32 caractere hex) → parametru `api_key`.
 * Detectăm automat tipul ca să nu crape aplicația dacă s-a pus cheia greșită în secret.
 */
sealed interface TmdbAuth {
    data class Bearer(val token: String) : TmdbAuth
    data class ApiKey(val key: String) : TmdbAuth
    data object Missing : TmdbAuth

    companion object {
        private val v3Key = Regex("^[0-9a-fA-F]{32}$")

        fun from(raw: String?): TmdbAuth {
            val value = raw?.trim().orEmpty().removePrefix("Bearer ").trim()
            return when {
                value.isEmpty() -> Missing
                v3Key.matches(value) -> ApiKey(value)
                else -> Bearer(value)
            }
        }
    }
}

/** Aruncată când nu există TMDB_API_KEY compilată în aplicație. UI-ul o tratează explicit. */
class TmdbNotConfiguredException : IllegalStateException("Lipsește cheia TMDb (TMDB_API_KEY)")

/** Cheie respinsă de TMDb (401). */
class TmdbUnauthorizedException : IllegalStateException("TMDb a respins cheia (401). Verifică secretul TMDB_API_KEY.")

class TmdbHttpException(val code: Int) : java.io.IOException("TMDb a răspuns cu codul $code")
