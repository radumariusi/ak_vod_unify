package ro.ak.vodunify

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import ro.ak.vodunify.core.publish.PublishRepository
import javax.inject.Inject

@HiltAndroidApp
class VodUnifyApp : Application() {
    /** Pornit odată cu aplicația: ascultă sincronizările ca să verifice și (automat) să publice progresul. */
    @Inject lateinit var publisher: PublishRepository
}
