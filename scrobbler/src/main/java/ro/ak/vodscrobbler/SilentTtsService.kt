package ro.ak.vodscrobbler

import android.media.AudioFormat
import android.os.SystemClock
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService

/**
 * Motor de voce (TTS) MUT. Netflix, Peacock & co. vorbesc singure când văd modul cititor de ecran,
 * prin motorul de voce implicit al TV-ului (au amuțit când motorul Google a fost oprit prin ADB).
 * Dacă AK Scrobbler e motorul implicit
 * (`adb shell settings put secure tts_default_synth ro.ak.vodscrobbler`), primim exact textul pe
 * care aplicația voia să-l rostească și întoarcem doar liniște: TV-ul nu vorbește, iar noi aflăm
 * ce e pe ecran chiar și la aplicațiile care nu-l arată accesibilității (Netflix, Peacock).
 *
 * Deocamdată doar notăm textul în jurnal (și în [SpokenText]), ca să vedem ce spune fiecare aplicație.
 */
class SilentTtsService : TextToSpeechService() {

    private var language = arrayOf("eng", "USA", "")

    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        DiagLog.add("Motor de voce AK (mut): folosit de o aplicație")
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int =
        if (country.isNullOrBlank()) TextToSpeech.LANG_AVAILABLE else TextToSpeech.LANG_COUNTRY_AVAILABLE

    override fun onGetLanguage(): Array<String> = language

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        language = arrayOf(lang.orEmpty(), country.orEmpty(), variant.orEmpty())
        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onStop() = Unit

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val text = (request.charSequenceText ?: request.text)?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) {
            val pkg = runCatching { packageManager.getNameForUid(request.callerUid) }.getOrNull()
                ?.substringBefore(':') ?: "?"
            SpokenText.add(pkg, text)
            if (SpokenText.shouldLog(pkg, text)) {
                DiagLog.add("Voce ${appLabel(pkg)}: „${text.take(MAX_LOG_CHARS)}”")
            }
        }
        // Liniște: câteva milisecunde de zero, ca aplicația să primească „gata” și să continue.
        if (callback.start(SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) return
        val silence = ByteArray(minOf(SILENCE_BYTES, callback.maxBufferSize))
        callback.audioAvailable(silence, 0, silence.size)
        callback.done()
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val SILENCE_BYTES = 320 // 10 ms
        const val MAX_LOG_CHARS = 200
    }
}

/** Ultimele texte „rostite” de aplicații prin motorul nostru mut. */
object SpokenText {
    data class Line(val pkg: String, val text: String, val at: Long)

    private const val MAX = 50
    private const val REPEAT_LOG_MS = 60_000L
    private val lines = ArrayDeque<Line>()
    private val lastLogged = HashMap<String, Pair<String, Long>>()

    @Synchronized
    fun add(pkg: String, text: String) {
        lines.addLast(Line(pkg, text, SystemClock.elapsedRealtime()))
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized
    fun recent(pkg: String, withinMs: Long): List<Line> {
        val now = SystemClock.elapsedRealtime()
        return lines.filter { it.pkg == pkg && now - it.at <= withinMs }
    }

    /** Același text repetat (ex. focus pe același buton) îl notăm cel mult o dată pe minut. */
    @Synchronized
    fun shouldLog(pkg: String, text: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        val last = lastLogged[pkg]
        if (last != null && last.first == text && now - last.second < REPEAT_LOG_MS) return false
        lastLogged[pkg] = text to now
        return true
    }
}
