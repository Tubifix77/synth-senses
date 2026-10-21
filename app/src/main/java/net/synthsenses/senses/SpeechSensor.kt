package net.synthsenses.senses

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turns nearby speech into text, offline, using whatever recognizer the phone ships.
 * Called only when AudioSensor's classifier says it heard a voice, and at most
 * once every [MIN_GAP_MS] — this is the most privacy-loaded sense here and also
 * the one that costs the most power.
 *
 * EXTRA_PREFER_OFFLINE is a request, not a guarantee. On some builds the
 * recognizer will still go to the network. If that matters to you, check
 * `SpeechRecognizer.createOnDeviceSpeechRecognizer` availability, or leave the
 * speech sense off.
 */
class SpeechSensor(private val context: Context) {

    companion object {
        private const val TAG = "SpeechSensor"
        const val MIN_GAP_MS = 20_000L
        private const val TIMEOUT_MS = 8_000L
    }

    private var lastRunAt = 0L

    /**
     * Why the last attempt produced nothing, or null if the last one worked.
     *
     * `transcript: null` meant three different things -- speech switched off,
     * nobody spoke, and the recogniser failed -- and `onError` threw the code
     * away, so the third was invisible. An evening on the P20 produced ten
     * voice detections and zero transcripts with nothing anywhere saying why.
     * This is the same hole `sound_last_error` was added to close for the
     * classifier, and it shows up in `status` beside it.
     */
    @Volatile
    var lastError: String? = null
        private set

    /** The constants, by name, because a bare "error 12" helps nobody. */
    private fun errorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
        SpeechRecognizer.ERROR_NETWORK -> "network error"
        SpeechRecognizer.ERROR_AUDIO -> "audio recording error"
        SpeechRecognizer.ERROR_SERVER -> "server error"
        SpeechRecognizer.ERROR_CLIENT -> "client error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech input"
        SpeechRecognizer.ERROR_NO_MATCH -> "no match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recogniser busy"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "insufficient permissions"
        else -> "error $code"
    }

    fun dueFor(nowMs: Long) = nowMs - lastRunAt >= MIN_GAP_MS

    suspend fun transcribeOnce(): String? = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.i(TAG, "no recognizer on this device")
            lastError = "no recogniser installed"
            return@withContext null
        }
        lastRunAt = System.currentTimeMillis()

        val result = CompletableDeferred<String?>()

        val recognizer = if (Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(bundle: Bundle) {
                val best = bundle
                    .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                // An empty result is not a failure: it is the recogniser
                // saying it heard nothing it could transcribe. Distinguish it
                // from the error path rather than reporting both as silence.
                lastError = if (best == null) "returned no text" else null
                if (!result.isCompleted) result.complete(best)
            }

            override fun onError(code: Int) {
                lastError = errorName(code)
                Log.i(TAG, "recogniser failed: ${errorName(code)}")
                if (!result.isCompleted) result.complete(null)
            }

            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200)
        }

        runCatching { recognizer.startListening(intent) }.onFailure {
            Log.w(TAG, "startListening failed", it)
            if (!result.isCompleted) result.complete(null)
        }

        val text = withTimeoutOrNull(TIMEOUT_MS) { result.await() }
        runCatching { recognizer.cancel() }
        runCatching { recognizer.destroy() }
        text
    }
}
