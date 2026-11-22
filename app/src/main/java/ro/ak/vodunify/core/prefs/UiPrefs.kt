package ro.ak.vodunify.core.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Cum arată Lista mea: grila de postere sau rânduri orizontale pe categorii (ca în Acasă). */
enum class LibraryLayout(val label: String, val detail: String) {
    ROWS("Rânduri pe categorii", "Ca în Acasă: un rând orizontal pentru fiecare categorie"),
    GRID("Grilă cu filtru", "Toate posterele într-o grilă, alegi categoria din filtru"),
}

/**
 * Setările de interfață (Setări → Interfață), citite sincron de ecrane, păstrate în SharedPreferences.
 */
object UiPrefs {
    private var prefs: SharedPreferences? = null

    private val _rowAnimation = MutableStateFlow(false)

    /** Acasă pe TV: rândul cu focus urcă animat (true) sau apare pe loc (false, implicit). */
    val rowAnimation: StateFlow<Boolean> = _rowAnimation.asStateFlow()

    private val _libraryLayout = MutableStateFlow(LibraryLayout.ROWS)
    val libraryLayout: StateFlow<LibraryLayout> = _libraryLayout.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("ui_settings", Context.MODE_PRIVATE)
        prefs = p
        _rowAnimation.value = p.getBoolean("row_animation", false)
        _libraryLayout.value = LibraryLayout.entries.firstOrNull { it.name == p.getString("library_layout", null) } ?: LibraryLayout.ROWS
    }

    fun setRowAnimation(value: Boolean) {
        _rowAnimation.value = value
        prefs?.edit()?.putBoolean("row_animation", value)?.apply()
    }

    fun setLibraryLayout(value: LibraryLayout) {
        _libraryLayout.value = value
        prefs?.edit()?.putString("library_layout", value.name)?.apply()
    }
}
