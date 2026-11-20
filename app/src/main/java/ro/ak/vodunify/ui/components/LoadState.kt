package ro.ak.vodunify.ui.components

import ro.ak.vodunify.core.tmdb.TmdbHttpException
import ro.ak.vodunify.core.tmdb.TmdbNotConfiguredException
import ro.ak.vodunify.core.tmdb.TmdbUnauthorizedException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** Stare generică de încărcare pentru ecranele de catalog. */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Data<T>(val value: T) : LoadState<T>
    data class Failed(val error: CatalogError) : LoadState<Nothing>
}

sealed interface CatalogError {
    /** Nu există TMDB_API_KEY compilată în aplicație. */
    data object NotConfigured : CatalogError

    /** Cheia există, dar TMDb o respinge. */
    data object Unauthorized : CatalogError

    data class Other(val message: String) : CatalogError
}

fun Throwable.toCatalogError(): CatalogError = when (this) {
    is TmdbNotConfiguredException -> CatalogError.NotConfigured
    is TmdbUnauthorizedException -> CatalogError.Unauthorized
    is TmdbHttpException -> CatalogError.Other("TMDb a răspuns cu eroarea $code. Încearcă din nou mai târziu.")
    is IOException -> CatalogError.Other("Nu există conexiune la internet sau TMDb nu răspunde.")
    else -> CatalogError.Other(message ?: "Eroare necunoscută")
}

/** Rulează [block] și împachetează rezultatul; anularea corutinei nu se transformă în eroare. */
suspend fun <T> loadCatching(block: suspend () -> T): LoadState<T> = try {
    LoadState.Data(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    LoadState.Failed(e.toCatalogError())
}
