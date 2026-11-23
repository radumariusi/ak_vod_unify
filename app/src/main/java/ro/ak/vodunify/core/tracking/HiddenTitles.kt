package ro.ak.vodunify.core.tracking

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Titlurile scoase din Lista mea de mână (cheia titlului → momentul). Istoricul lor rămâne (importat sau
 * din AK Scrobbler), doar nu se mai arată în Lista mea. Revin singure dacă le vezi din nou după aceea
 * (o vizionare mai nouă decât momentul scoaterii) sau dacă le adaugi înapoi.
 */
@Singleton
class HiddenTitles @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("hidden_titles", Context.MODE_PRIVATE)
    private val _all = MutableStateFlow(read())
    val all: StateFlow<Map<String, Long>> = _all.asStateFlow()

    fun hide(titleKey: String, at: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(titleKey, at).apply()
        _all.value = _all.value + (titleKey to at)
    }

    fun show(titleKey: String) {
        if (titleKey !in _all.value) return
        prefs.edit().remove(titleKey).apply()
        _all.value = _all.value - titleKey
    }

    private fun read(): Map<String, Long> = prefs.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()

    companion object {
        /** Ascuns dacă a fost scos din listă după ultima activitate pe el. */
        fun isHidden(hidden: Map<String, Long>, titleKey: String, lastActivityAt: Long): Boolean =
            hidden[titleKey]?.let { lastActivityAt <= it } == true
    }
}
