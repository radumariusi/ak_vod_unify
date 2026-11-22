package ro.ak.vodunify.core.playback

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ce ai schimbat tu în lista de aplicații și servicii (Setări → Aplicații și servicii):
 * - aplicația instalată aleasă pentru o intrare din listă (ex. Shova TV, când pachetul nu e cunoscut);
 * - intrările eliminate (aplicații dezinstalate sau pe care nu le folosești);
 * - aplicațiile și serviciile adăugate de tine (în locurile libere [TargetApp.isCustom] / [StreamingService.isCustom]):
 *   numele și, la servicii, furnizorul TMDb (pentru disponibilitate și siglă).
 * Citit sincron (enumurile își iau numele de aici), păstrat în SharedPreferences.
 */
object AppRegistry {
    private var prefs: SharedPreferences? = null
    private val _version = MutableStateFlow(0)

    /** Crește la fiecare schimbare, ca listele să se recitească. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("app_registry", Context.MODE_PRIVATE)
    }

    private fun changed() {
        _version.value += 1
    }

    /** Pachetul ales de tine pentru o aplicație din listă. */
    fun packageFor(app: TargetApp): String? = prefs?.getString("pkg_${app.id}", null)

    fun isHidden(id: String): Boolean = prefs?.getStringSet("hidden", emptySet())?.contains(id) == true

    fun customName(id: String): String? = prefs?.getString("name_$id", null)

    fun customProvider(serviceId: String): Int? = prefs?.getInt("provider_$serviceId", -1)?.takeIf { it >= 0 }

    /** Leagă aplicația instalată [packageName] de intrarea [app] (și o readuce dacă era eliminată). */
    fun assign(app: TargetApp, packageName: String, name: String? = null) {
        val p = prefs ?: return
        p.edit().apply {
            putString("pkg_${app.id}", packageName)
            if (name != null) putString("name_${app.id}", name)
            putStringSet("hidden", p.getStringSet("hidden", emptySet()).orEmpty() - app.id)
        }.apply()
        changed()
    }

    /** Serviciu nou: furnizorul TMDb [providerId] cu numele lui, deschis în aplicația instalată [packageName]. */
    fun addService(service: StreamingService, providerId: Int, name: String, packageName: String) {
        val p = prefs ?: return
        val app = TargetApp.byId(service.appId) ?: return
        p.edit().apply {
            putInt("provider_${service.id}", providerId)
            putString("name_${service.id}", name)
            putString("pkg_${app.id}", packageName)
            putString("name_${app.id}", name)
            putStringSet("hidden", p.getStringSet("hidden", emptySet()).orEmpty() - app.id - service.id)
        }.apply()
        changed()
    }

    /** Scoate o intrare din liste; cele adăugate de tine se șterg de tot (locul se eliberează). */
    fun remove(app: TargetApp) {
        val p = prefs ?: return
        p.edit().apply {
            if (app.isCustom) {
                remove("pkg_${app.id}")
                remove("name_${app.id}")
                StreamingService.entries.firstOrNull { it.appId == app.id }?.let { s ->
                    remove("provider_${s.id}")
                    remove("name_${s.id}")
                }
            } else {
                remove("pkg_${app.id}")
                putStringSet("hidden", p.getStringSet("hidden", emptySet()).orEmpty() + app.id)
            }
        }.apply()
        changed()
    }

    /** Readuce în liste o intrare eliminată. */
    fun restore(app: TargetApp) {
        val p = prefs ?: return
        p.edit().putStringSet("hidden", p.getStringSet("hidden", emptySet()).orEmpty() - app.id).apply()
        changed()
    }
}
