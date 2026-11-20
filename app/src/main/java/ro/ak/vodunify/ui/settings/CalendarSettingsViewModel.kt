package ro.ak.vodunify.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ro.ak.vodunify.BuildConfig
import ro.ak.vodunify.core.calendar.android.CalendarPreferences
import ro.ak.vodunify.core.calendar.android.CalendarRepository
import ro.ak.vodunify.core.calendar.android.CalendarWorker
import ro.ak.vodunify.core.calendar.android.TodayChannel
import ro.ak.vodunify.core.calendar.android.TodayNotifier
import ro.ak.vodunify.core.calendar.model.CalendarSettings
import ro.ak.vodunify.core.calendar.model.ContentFilter
import ro.ak.vodunify.core.calendar.model.NotificationMode
import ro.ak.vodunify.core.calendar.model.QualityThresholds
import javax.inject.Inject

@HiltViewModel
class CalendarSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val calendar: CalendarRepository,
) : ViewModel() {

    val settings: StateFlow<CalendarSettings?> = calendar.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val omdbBuiltIn: Boolean get() = BuildConfig.OMDB_API_KEY.isNotBlank()
    val tvChannelSupported: Boolean get() = TodayChannel.isSupported(context)
    fun canNotify(): Boolean = TodayNotifier.canNotify(context)

    private fun edit(transform: (CalendarSettings) -> CalendarSettings) {
        viewModelScope.launch { calendar.updateSettings(transform) }
    }

    fun setAppInstalled(appId: String, installed: Boolean) = edit { s -> s.updateApp(appId) { it.copy(installed = installed) } }
    fun toggleAppRegion(appId: String) = edit { s -> s.updateApp(appId) { it.copy(region = it.region.toggled()) } }

    fun detectApps() {
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { CalendarPreferences.detectInstalled(context) }.getOrDefault(emptySet()) }
            calendar.updateSettings { s ->
                found.fold(s) { acc, app -> acc.updateApp(app.id) { it.copy(installed = true) } }
            }
            _message.value = if (found.isEmpty()) "N-am găsit aplicații de streaming din listă pe dispozitiv"
            else "Găsite: " + found.joinToString(", ") { it.name }
        }
    }

    fun setOmdbKey(key: String) = edit { it.copy(omdbApiKey = key.trim()) }

    fun updateShowThresholds(transform: (QualityThresholds) -> QualityThresholds) = edit { it.copy(thresholds = transform(it.thresholds)) }
    fun updateMovieThresholds(transform: (QualityThresholds) -> QualityThresholds) = edit { it.copy(movieThresholds = transform(it.movieThresholds)) }

    fun toggleCategory(c: ContentFilter) = edit {
        it.copy(hiddenCategories = if (c in it.hiddenCategories) it.hiddenCategories - c else it.hiddenCategories + c)
    }

    fun setNotificationMode(mode: NotificationMode) = edit { it.copy(notifications = it.notifications.copy(mode = mode)) }
    fun shiftNotificationHour(delta: Int) = edit {
        it.copy(notifications = it.notifications.copy(hour = (it.notifications.hour + delta + 24) % 24))
    }
    fun toggleIncludeMovies() = edit { it.copy(notifications = it.notifications.copy(includeMovies = !it.notifications.includeMovies)) }
    fun toggleTvChannel() = edit { it.copy(tvChannel = !it.tvChannel) }

    fun showTodayNow() {
        runCatching { CalendarWorker.refreshNow(context, notify = true) }
        _message.value = "Se pregătește ce apare azi (notificare + canal TV)…"
    }

    fun unfollowShow(id: Int) = edit { it.unfollow(id) }
    fun unhideShow(id: Int) = edit { it.unhide(id) }
    fun unfollowMovie(id: Int) = edit { it.unfollowMovie(id) }
    fun unhideMovie(id: Int) = edit { it.unhideMovie(id) }

    fun consumeMessage() {
        _message.value = null
    }
}
