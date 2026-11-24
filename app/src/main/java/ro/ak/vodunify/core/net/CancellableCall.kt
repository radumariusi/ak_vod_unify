package ro.ak.vodunify.core.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

/** Citește și închide răspunsul pe firul OkHttp. Anularea ecranului oprește și cererea HTTP. */
internal suspend fun <T> Call.readResponse(read: (Response) -> T): T = suspendCancellableCoroutine { pending ->
    pending.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            pending.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            pending.resumeWith(runCatching { response.use(read) })
        }
    })
}
