package ro.ak.vodunify.core.imports

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

@EntryPoint
@InstallIn(SingletonComponent::class)
interface HistoryImportEntryPoint {
    fun imports(): HistoryImportRepository
}

/** Potrivește în TMDb istoricul din coadă (vezi [HistoryImportRepository]). */
class HistoryImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        EntryPointAccessors.fromApplication(applicationContext, HistoryImportEntryPoint::class.java).imports().processQueue()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("HistoryImportWorker", "import eșuat, reîncerc", e)
        // Progresul e salvat pe bucăți; reîncercăm cât timp mai e ceva în coadă.
        Result.retry()
    }
}
