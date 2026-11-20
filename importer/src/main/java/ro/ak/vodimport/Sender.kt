package ro.ak.vodimport

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Trimite istoricul către AK VOD Unify, în loturi (Binder are limită de mărime). */
object Sender {
    private const val CHUNK = 300

    sealed interface Result {
        data class Sent(val count: Int) : Result
        data class Failed(val message: String) : Result
    }

    fun send(context: Context, service: String, profile: String, items: JSONArray): Result {
        val uri = Uri.parse("content://${BuildConfig.MAIN_APP_ID}.scrobble/history")
        var sent = 0
        var start = 0
        while (start < items.length()) {
            val end = minOf(start + CHUNK, items.length())
            val chunk = JSONArray()
            for (i in start until end) chunk.put(items.get(i))
            val payload = JSONObject().apply {
                put("service", service)
                put("route", BuildConfig.ROUTE)
                put("profile", profile)
                put("items", chunk)
                put("last", end >= items.length())
            }
            val ok = try {
                context.contentResolver.insert(uri, ContentValues().apply { put("payload", payload.toString()) }) != null
            } catch (e: Exception) {
                return Result.Failed(
                    if (e is SecurityException) {
                        "AK VOD Unify are altă semnătură. Instalează toate aplicațiile din același release."
                    } else {
                        "AK VOD Unify nu e instalat (${BuildConfig.MAIN_APP_ID})."
                    },
                )
            }
            if (!ok) return Result.Failed("AK VOD Unify n-a acceptat lotul. Reîncearcă.")
            sent += end - start
            start = end
        }
        return Result.Sent(sent)
    }
}
