package ro.ak.vodunify

import android.app.Application
import android.content.pm.PackageManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import ro.ak.vodunify.core.calendar.android.CalendarWork
import ro.ak.vodunify.core.imports.HistoryImportRepository
import ro.ak.vodunify.core.playback.AppRegistry
import ro.ak.vodunify.core.publish.PublishRepository
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class VodUnifyApp : Application(), ImageLoaderFactory {
    /** Pornit odată cu aplicația: ascultă sincronizările ca să verifice și (automat) să publice progresul. */
    @Inject lateinit var publisher: Lazy<PublishRepository>
    @Inject lateinit var okHttp: Lazy<OkHttpClient>

    override fun onCreate() {
        super.onCreate()
        ro.ak.vodunify.core.diagnostics.AppDiagLog.installCrashHandler(this)
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

    override fun newImageLoader(): ImageLoader {
        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        return ImageLoader.Builder(this)
            .okHttpClient { okHttp.get() }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(if (isTv) 0.15 else 0.22)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "image_cache"))
                    .maxSizeBytes(128L * 1024 * 1024)
                    .build()
            }
            .crossfade(180)
            .respectCacheHeaders(false)
            .build()
    }
}
