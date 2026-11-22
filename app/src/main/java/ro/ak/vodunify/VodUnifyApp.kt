package ro.ak.vodunify

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import ro.ak.vodunify.core.calendar.android.CalendarWork
import ro.ak.vodunify.core.playback.AppRegistry
import ro.ak.vodunify.core.publish.PublishRepository
import javax.inject.Inject

@HiltAndroidApp
class VodUnifyApp : Application() {
    /** Pornit odată cu aplicația: ascultă sincronizările ca să verifice și (automat) să publice progresul. */
    @Inject lateinit var publisher: PublishRepository

    override fun onCreate() {
        super.onCreate()
        // Aplicațiile și serviciile adăugate / eliminate de tine (citite sincron de liste).
        AppRegistry.init(this)
        // Setările de interfață (derulare Acasă, aspect Lista mea).
        ro.ak.vodunify.core.prefs.UiPrefs.init(this)
        // Calendar: notificarea zilnică și canalul „Azi apare”, după setări.
        CalendarWork.start(this)
    }
}
