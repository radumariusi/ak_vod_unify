package ro.ak.vodunify

import android.app.Application
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ro.ak.vodunify.core.calendar.android.CalendarWork
import ro.ak.vodunify.core.imports.HistoryImportRepository
import ro.ak.vodunify.core.playback.AppRegistry
import ro.ak.vodunify.core.publish.PublishRepository
import javax.inject.Inject

@HiltAndroidApp
class VodUnifyApp : Application() {
    /** Pornit odată cu aplicația: ascultă sincronizările ca să verifice și (automat) să publice progresul. */
    @Inject lateinit var publisher: Lazy<PublishRepository>

    override fun onCreate() {
        super.onCreate()
        // Aplicațiile și serviciile adăugate / eliminate de tine (citite sincron de liste).
        AppRegistry.init(this)
        // Setările de interfață (derulare Acasă, aspect Lista mea).
        ro.ak.vodunify.core.prefs.UiPrefs.init(this)
        val startup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        startup.launch { publisher.get() }
        // Calendar: notificarea zilnică și canalul „Azi apare”, după setări.
        CalendarWork.start(this)
        // Nu citim coada de import de pe firul interfeței.
        startup.launch { HistoryImportRepository.resumeIfPending(this@VodUnifyApp) }
    }
}
