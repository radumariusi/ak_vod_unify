package ro.ak.vodunify.core.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ro.ak.vodunify.core.playback.BadgePriority
import ro.ak.vodunify.core.playback.PosterBadgeMode
import ro.ak.vodunify.core.playback.PosterBadgePolicy
import ro.ak.vodunify.core.playback.PosterBadgeSettings
import ro.ak.vodunify.core.playback.PosterStage

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

    private val _calendarMonthRows = MutableStateFlow(3)
    val calendarMonthRows: StateFlow<Int> = _calendarMonthRows.asStateFlow()

    private val _posterBadges = MutableStateFlow(PosterBadgeSettings())
    val posterBadges = _posterBadges.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("ui_settings", Context.MODE_PRIVATE)
        prefs = p
        _rowAnimation.value = p.getBoolean("row_animation", false)
        _calendarMonthRows.value = p.getInt("calendar_month_rows", 3).coerceIn(2, 3)
        _libraryLayout.value = LibraryLayout.entries.firstOrNull { it.name == p.getString("library_layout", null) } ?: LibraryLayout.ROWS
        _posterBadges.value = PosterBadgeSettings(
            policies = PosterStage.entries.associateWith { stage ->
                val fallback = PosterBadgePolicy.default(stage)
                val mode = PosterBadgeMode.entries.firstOrNull {
                    it.name == p.getString("badge_mode_${stage.name}", null) &&
                        (it != PosterBadgeMode.ALL_WITH_LAST || stage != PosterStage.UNWATCHED)
                } ?: fallback.mode
                val order = tokens(p.getString("badge_priorities_${stage.name}", null))
                    .mapNotNull { token -> fallback.priorities.firstOrNull { it.name == token } }
                PosterBadgePolicy(mode, (order + fallback.priorities).distinct())
            },
            serviceOrder = p.getString("badge_service_order", null)?.let { tokens(it).distinct() }
                ?: PosterBadgeSettings.defaultServiceOrder,
        )
    }

    fun setRowAnimation(value: Boolean) {
        _rowAnimation.value = value
        prefs?.edit()?.putBoolean("row_animation", value)?.apply()
    }

    fun setLibraryLayout(value: LibraryLayout) {
        _libraryLayout.value = value
        prefs?.edit()?.putString("library_layout", value.name)?.apply()
    }

    fun setCalendarMonthRows(value: Int) {
        val rows = value.coerceIn(2, 3)
        _calendarMonthRows.value = rows
        prefs?.edit()?.putInt("calendar_month_rows", rows)?.apply()
    }

    fun setPosterBadgeMode(stage: PosterStage, mode: PosterBadgeMode) {
        setPosterBadgePolicy(stage, _posterBadges.value.policy(stage).copy(mode = mode))
    }

    fun setPosterBadgePriorities(stage: PosterStage, priorities: List<BadgePriority>) {
        setPosterBadgePolicy(stage, _posterBadges.value.policy(stage).copy(priorities = priorities))
    }

    private fun setPosterBadgePolicy(stage: PosterStage, policy: PosterBadgePolicy) {
        val current = _posterBadges.value
        _posterBadges.value = current.copy(policies = current.policies + (stage to policy))
        prefs?.edit()?.putString("badge_mode_${stage.name}", policy.mode.name)
            ?.putString("badge_priorities_${stage.name}", policy.priorities.joinToString(",") { it.name })?.apply()
    }

    fun setPosterBadgeServiceOrder(order: List<String>) {
        _posterBadges.value = _posterBadges.value.copy(serviceOrder = order)
        prefs?.edit()?.putString("badge_service_order", order.joinToString(","))?.apply()
    }

    private fun tokens(value: String?): List<String> = value.orEmpty().split(',').filter { it.isNotEmpty() }
}
