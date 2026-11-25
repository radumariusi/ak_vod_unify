package ro.ak.vodscrobbler

import android.content.Context
import android.content.res.Configuration
import org.json.JSONArray
import org.json.JSONObject
import ro.ak.vodimport.NetflixRecent
import ro.ak.vodimport.WebLoginActivity

/**
 * Autentificarea Netflix în AK Scrobbler. Păstrează sesiunea WebView și ultima activitate
 * local în Scrobbler, fără a contacta AK VOD Unify în fundal; Unify o preia când intră în prim-plan.
 */
class NetflixLoginActivity : WebLoginActivity() {
    override val recentOnly = true

    override fun onStart() {
        super.onStart()
        sizeScrobblerWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        sizeScrobblerWindow()
    }

    override fun saveHistory(service: String, payload: JSONObject): String {
        val items = payload.getJSONArray("items")
        val recent = JSONArray()
        for (i in 0 until minOf(items.length(), 200)) recent.put(items.get(i))
        NetflixLocalCache.save(applicationContext, NetflixRecent.result("ok", items = recent))
        return "Sesiunea Netflix este activă. AK VOD Unify va prelua automat activitatea la deschidere."
    }
}

internal object NetflixLocalCache {
    private const val PREFS = "netflix_local"
    private const val KEY_JSON = "last_result"
    private const val KEY_AT = "last_at"

    fun save(context: Context, result: JSONObject) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_JSON, result.toString())
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
    }

    fun readIfFresh(context: Context, maxAgeMs: Long): String? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = p.getLong(KEY_AT, 0L)
        if (System.currentTimeMillis() - at > maxAgeMs) return null
        return p.getString(KEY_JSON, null)
    }
}
