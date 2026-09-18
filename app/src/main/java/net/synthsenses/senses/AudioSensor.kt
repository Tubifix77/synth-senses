package net.synthsenses.senses

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.core.RunningMode
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.BaseOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The ears. Opens the mic for a short window, measures it, and asks YAMNet what
 * it was. The AudioRecord is opened and released per sample on purpose — that way
 * SpeechSensor can take the mic afterwards without fighting us for it.
 *
 * If assets/yamnet.tflite is missing, classification is skipped and everything
 * else still works.
 */
class AudioSensor(private val context: Context) {

    companion object {
        private const val TAG = "AudioSensor"
        private const val SAMPLE_RATE = 16_000
        private const val WINDOW_MS = 975          // YAMNet's native window
        private const val MODEL_ASSET = "yamnet.tflite"

        /** Sound classes that mean "a human is talking" — used to trigger transcription. */
        val SPEECHY = setOf("Speech", "Conversation", "Narration, monologue", "Whispering", "Shout")
    }

    private var classifier: AudioClassifier? = null
    private var classifierTried = false

    /** True once we know whether the model loaded. Exposed so the UI can warn. */
    var modelAvailable: Boolean = false
        private set

    private fun ensureClassifier(): AudioClassifier? {
        if (classifierTried) return classifier
        classifierTried = true
        classifier = runCatching {
            val base = BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build()
            val opts = AudioClassifier.AudioClassifierOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.AUDIO_CLIPS)
                .setScoreThreshold(0.30f)
                .setMaxResults(5)
                .build()
            AudioClassifier.createFromOptions(context, opts)
        }.onFailure {
            Log.i(TAG, "no sound classifier (${it.message}) — running loudness-only")
        }.getOrNull()
        modelAvailable = classifier != null
        return classifier
    }

    /** Records a window and interprets it. Returns null if the mic couldn't be opened. */
    @SuppressLint("MissingPermission")
    suspend fun sample(): Hearing? = withContext(Dispatchers.IO) {
        val wanted = SAMPLE_RATE * WINDOW_MS / 1000
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        if (minBuf <= 0) {
            Log.w(TAG, "float PCM unsupported on this device")
            return@withContext null
        }

        val record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBuf, wanted * 4))
                .build()
        } catch (t: Throwable) {
            // UNPROCESSED isn't guaranteed; fall back to the ordinary mic
            runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT,
                    max(minBuf, wanted * 4)
                )
            }.getOrElse {
                Log.w(TAG, "cannot open mic", it)
                return@withContext null
            }
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return@withContext null
        }

        val samples = FloatArray(wanted)
        try {
            record.startRecording()
            var read = 0
            val deadline = System.currentTimeMillis() + 3_000
            while (read < wanted && System.currentTimeMillis() < deadline) {
                val n = record.read(
                    samples, read, wanted - read, AudioRecord.READ_BLOCKING
                )
                if (n <= 0) break
                read += n
            }
            if (read < wanted / 2) {
                Log.w(TAG, "short read: $read of $wanted")
                return@withContext null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "recording failed", t)
            return@withContext null
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
        }

        var sumSq = 0.0
        var peak = 0f
        for (s in samples) {
            sumSq += s.toDouble() * s
            val a = if (s < 0) -s else s
            if (a > peak) peak = a
        }
        val rms = sqrt(sumSq / samples.size).toFloat()
        val levelDb = dbfs(rms)
        val peakDb = dbfs(peak)

        val events = ensureClassifier()?.let { c ->
            runCatching {
                val fmt = AudioData.AudioDataFormat.builder()
                    .setNumOfChannels(1)
                    .setSampleRate(SAMPLE_RATE.toFloat())
                    .build()
                val data = AudioData.create(fmt, samples.size)
                data.load(samples)
                c.classify(data)
                    .classificationResults()
                    .firstOrNull()
                    ?.classifications()
                    ?.firstOrNull()
                    ?.categories()
                    ?.map { Scored(it.categoryName(), it.score()) }
                    ?.sortedByDescending { it.conf }
                    ?.take(4)
                    ?: emptyList()
            }.getOrElse {
                Log.w(TAG, "classification failed", it); emptyList()
            }
        } ?: emptyList()

        Hearing(
            levelDb = levelDb,
            peakDb = peakDb,
            events = events,
            transcript = null // filled in later by SpeechSensor if it fires
        )
    }

    private fun dbfs(amplitude: Float): Float =
        if (amplitude <= 1e-7f) -90f else (20f * log10(amplitude)).coerceIn(-90f, 0f)

    fun close() {
        runCatching { classifier?.close() }
        classifier = null
    }
}
