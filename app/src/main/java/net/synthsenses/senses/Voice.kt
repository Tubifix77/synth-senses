package net.synthsenses.senses

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.QUEUE_ADD
import android.util.Log
import java.util.Locale

/**
 * A mouth, so the box can answer in the room rather than only on the wire.
 * Entirely optional, and off unless a speak command arrives.
 *
 * Note that anything spoken here will be picked up by the next hearing sample and
 * transcribed back. That feedback loop is either a bug or the most interesting
 * thing in the project, depending on what your box does with it. If you want it
 * suppressed, gate AudioSensor for a couple of seconds after speaking.
 */
class Voice(context: Context) {

    companion object {
        private const val TAG = "Voice"
    }

    private var engine: TextToSpeech? = null

    @Volatile
    var ready = false
        private set

    @Volatile
    var lastSpokenAtMs = 0L
        private set

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                runCatching { engine?.language = Locale.getDefault() }
            } else {
                Log.w(TAG, "TTS init failed ($status)")
            }
        }
    }

    fun speak(text: String): Boolean {
        val e = engine ?: return false
        if (!ready || text.isBlank()) return false
        lastSpokenAtMs = System.currentTimeMillis()
        return runCatching {
            e.speak(text.take(600), QUEUE_ADD, null, "synth-${System.nanoTime()}")
        }.getOrDefault(TextToSpeech.ERROR) == TextToSpeech.SUCCESS
    }

    fun close() {
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
    }
}
