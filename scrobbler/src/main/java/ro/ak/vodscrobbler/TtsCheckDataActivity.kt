package ro.ak.vodscrobbler

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/** Răspunsul standard „voci instalate” pentru [SilentTtsService] (cerut de Setări și unele aplicații). */
class TtsCheckDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val voices = arrayListOf("eng-USA", "eng-GBR", "ron-ROU")
        setResult(
            TextToSpeech.Engine.CHECK_VOICE_DATA_PASS,
            Intent()
                .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, voices)
                .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, arrayListOf()),
        )
        finish()
    }
}
