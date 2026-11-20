package ro.ak.vodunify.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import ro.ak.vodunify.core.catalog.StudioKind
import ro.ak.vodunify.core.catalog.TitleKind

/** Acțiuni de navigare în catalog, disponibile oricărui ecran fără a le pasa prin parametri. */
data class CatalogActions(
    val openTitle: (TitleKind, Long) -> Unit = { _, _ -> },
    val openPerson: (Long) -> Unit = {},
    val openStudio: (StudioKind, Long) -> Unit = { _, _ -> },
    val openSettings: () -> Unit = {},
)

val LocalCatalogActions = staticCompositionLocalOf { CatalogActions() }

object Routes {
    const val TITLE = "title/{kind}/{id}"
    const val PERSON = "person/{id}"
    const val STUDIO = "studio/{kind}/{id}"

    fun title(kind: TitleKind, id: Long) = "title/${kind.path}/$id"
    fun person(id: Long) = "person/$id"
    fun studio(kind: StudioKind, id: Long) = "studio/${kind.path}/$id"
}
