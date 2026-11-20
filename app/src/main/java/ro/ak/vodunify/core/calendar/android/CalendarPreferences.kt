package ro.ak.vodunify.core.calendar.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import ro.ak.vodunify.core.calendar.model.CalendarSettings
import ro.ak.vodunify.core.calendar.model.Region
import ro.ak.vodunify.core.calendar.model.StreamingApp
import ro.ak.vodunify.core.calendar.model.StreamingCatalog
import ro.ak.vodunify.core.playback.PlaybackPreferences
import ro.ak.vodunify.core.prefs.Market
import javax.inject.Inject
import javax.inject.Singleton

private val Context.calendarStore: DataStore<Preferences> by preferencesDataStore(name = "calendar_settings")

/**
 * Setările Calendarului (aplicațiile tale și țara lor, praguri, categorii, urmărite/ascunse,
 * notificări), salvate ca JSON în DataStore. La prima pornire, aplicațiile de streaming
 * instalate pe dispozitiv sunt bifate automat, cu piața din Setări → Redare.
 */
@Singleton
class CalendarPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playback: PlaybackPreferences,
) {
    private val store = context.calendarStore
    private val key = stringPreferencesKey("settings_json")

    val settings: Flow<CalendarSettings> = store.data
        .map { prefs -> prefs[key]?.let(::decode) }
        .map { it ?: firstRunDefaults() }
        .distinctUntilChanged()

    suspend fun update(transform: (CalendarSettings) -> CalendarSettings) {
        val current = settings.first()
        val next = transform(current).normalized()
        store.edit { it[key] = encode(next) }
    }

    private fun decode(raw: String): CalendarSettings? =
        runCatching { json.decodeFromString(CalendarSettings.serializer(), raw).normalized() }.getOrNull()

    /** Listele din Trakt / SIMKL se completează la rulare, nu se salvează. */
    private fun encode(s: CalendarSettings): String =
        json.encodeToString(CalendarSettings.serializer(), s.copy(trackedShows = emptyList(), trackedMovies = emptyList()))

    private suspend fun firstRunDefaults(): CalendarSettings {
        val region = if (playback.settings.first().playbackMarket == Market.US) Region.US else Region.RO
        val installed = withContext(Dispatchers.IO) { runCatching { detectInstalled(context) }.getOrDefault(emptySet()) }
        return installed.fold(CalendarSettings()) { s, app ->
            s.updateApp(app.id) { it.copy(installed = true, region = region) }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }

        /** Aplicațiile din catalogul Calendarului găsite pe dispozitiv (după pachet sau după nume). */
        fun detectInstalled(context: Context): Set<StreamingApp> {
            val pm = context.packageManager
            val launchables = listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER).flatMap { category ->
                val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                    .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            }.filter { it.first != context.packageName }.distinct()
            val packages = launchables.map { it.first }.toSet()
            fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9+]"), "")
            return StreamingCatalog.all.filter { app ->
                app.androidTvPackages.any { it in packages } ||
                    (app.launcherNames + app.name).map(::norm).filter { it.isNotBlank() }.any { name ->
                        launchables.any { (_, label) -> norm(label) == name || norm(label).startsWith(name) }
                    }
            }.toSet()
        }
    }
}
