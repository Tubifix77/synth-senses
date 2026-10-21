package net.synthsenses.senses

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.core.RunningMode
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.BaseOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
 *
 * It also stands aside for the telephone. One of the phones this runs on is a
 * household's emergency line — fixed in place, always on, sound always up —
 * and a sense that costs that phone a call is not worth having at any quality.
 * See [micIsBusy].
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

    /** Where the model came from: "asset", "file", or null if there is none. */
    var modelSource: String? = null
        private set

    /** The last classification failure, for `status`. logcat is not reachable. */
    var lastClassifyError: String? = null
        private set

    /**
     * The model, from the APK if it is there and from a file if it is not.
     *
     * `yamnet.tflite` is 4 MB and gitignored. CI fetches it during the
     * build and verifies it against the published hash, so a CI-built APK
     * ships able to hear. That was not always so: every APK this project
     * built before 2026-10-21 was permanently deaf, with no fix short of
     * a local Android toolchain this machine does not have.
     *
     * The file path stays as a fallback, because it costs nothing and
     * covers a locally built APK and anyone who would rather the build
     * not reach the network. Confirmed against tasks-core 1.0.0's own
     * bytecode rather than assumed: `BaseOptions` exposes
     * `setModelAssetPath`, `setModelAssetBuffer` and
     * `setModelAssetFileDescriptor`.
     *
     * So: the asset first, for a build that shipped with one, then a file
     * dropped into the app's own external files directory, which needs no
     * permission and no rebuild. `adb push`, a USB cable or any file manager
     * will do it; the control panel shows which source was used.
     */
    private fun baseOptions(): BaseOptions? {
        val asset = runCatching {
            context.assets.open(MODEL_ASSET).close()
            BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build()
        }.getOrNull()
        if (asset != null) {
            modelSource = "asset"
            return asset
        }

        val file = modelFile()
        if (file == null) {
            Log.i(TAG, "no $MODEL_ASSET in assets, and none in "
                + modelDirs().joinToString(", ") { it.absolutePath })
            return null
        }
        return runCatching {
            // MediaPipe wants a direct buffer in native order; a heap buffer
            // is rejected at the JNI boundary rather than at build time.
            val bytes = file.readBytes()
            val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            buf.put(bytes)
            buf.rewind()
            BaseOptions.builder().setModelAssetBuffer(buf).build()
        }.onSuccess {
            modelSource = "file"
            Log.i(TAG, "sound model loaded from ${file.absolutePath}")
        }.onFailure {
            Log.w(TAG, "could not read $MODEL_ASSET from ${file.absolutePath}", it)
        }.getOrNull()
    }

    /**
     * Where a model file may be dropped, in the order they are tried.
     *
     * `getExternalFilesDir` is the obvious one and is useless on a modern
     * phone: Android 11 restricted `Android/data`, so a file manager cannot
     * see into it and MTP over a cable cannot either. HyperOS does not even
     * list the folder. Creating one by hand does not help, because the
     * directory exists and is access-controlled rather than missing.
     *
     * `getExternalMediaDirs` is the accessible sibling. `Android/media` was
     * deliberately left browsable, so a file manager can write there, and
     * Android still gives each package its own directory inside it. The app
     * creates it on start so there is somewhere to drop the file into.
     */
    private fun modelDirs(): List<File> = buildList {
        runCatching { context.externalMediaDirs.filterNotNull() }
            .getOrDefault(emptyList()).forEach { add(it) }
        context.getExternalFilesDir(null)?.let { add(it) }
    }

    private fun modelFile(): File? =
        modelDirs().map { File(it, MODEL_ASSET) }.firstOrNull { it.isFile }

    /** Make somewhere for the model to be put, so the folder exists to find. */
    fun prepareModelDir() {
        runCatching { modelDirs().firstOrNull()?.mkdirs() }
    }

    private fun ensureClassifier(): AudioClassifier? {
        if (classifierTried) return classifier
        classifierTried = true
        val base = baseOptions()
        classifier = if (base == null) null else runCatching {
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
        if (!modelAvailable) modelSource = null
        return classifier
    }

    /**
     * Records a window and interprets it. Returns null if the mic couldn't be
     * opened, or if the phone is ringing or on a call.
     */
    @SuppressLint("MissingPermission")
    suspend fun sample(): Hearing? = withContext(Dispatchers.IO) {
        val mode = runCatching {
            context.getSystemService(AudioManager::class.java)?.mode
        }.getOrNull()
        if (micIsBusy(mode)) {
            Log.i(TAG, "phone is ringing or on a call — not touching the mic")
            return@withContext null
        }

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

        val cls = ensureClassifier()
        var state = if (cls == null) "absent" else "ok"
        val events = cls?.let { c ->
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
                // Recorded as well as logged. This failure was invisible from
                // the percept stream for an entire evening: the mic reported
                // healthy levels, the model had loaded, and events simply
                // stopped arriving with the reason going only to logcat --
                // which this project has no adb to read.
                Log.w(TAG, "classification failed", it)
                state = "failed"
                lastClassifyError = "${it.javaClass.simpleName}: ${it.message}"
                emptyList()
            }
        } ?: emptyList()

        Hearing(
            levelDb = levelDb,
            peakDb = peakDb,
            events = events,
            transcript = null, // filled in later by SpeechSensor if it fires
            classifier = state
        )
    }

    private fun dbfs(amplitude: Float): Float =
        if (amplitude <= 1e-7f) -90f else (20f * log10(amplitude)).coerceIn(-90f, 0f)

    fun close() {
        runCatching { classifier?.close() }
        classifier = null
    }
}

/**
 * Whether the phone's own telephony is using the audio path, given
 * `AudioManager.getMode()`.
 *
 * One of the devices this runs on is a household's emergency phone: fixed in
 * place, permanently charging, sound always up, and the only number some of the
 * family have. The ears already open the mic in short bursts and release it in
 * a `finally` rather than holding it, which makes a collision unlikely — but
 * unlikely is the wrong standard for a phone whose entire job is to be
 * answerable. This makes it impossible instead.
 *
 * Anything other than `MODE_NORMAL` counts as busy. That is deliberately
 * broader than testing for the three call modes by name: it already covers
 * `MODE_RINGTONE`, so the mic is left alone from the first ring rather than
 * from the moment someone answers, and it covers call screening and the
 * redirect modes added later without this code having heard of them. A mode
 * added in some future Android is far more likely to be about a call than not,
 * and the cost of being wrong in that direction is one missing sound label.
 *
 * A negative value is `MODE_INVALID` or `MODE_CURRENT`, which `getMode()` does
 * not return in practice; a null means the service could not be reached. Both
 * mean "could not tell", and neither is treated as busy — a phone that cannot
 * report its mode is not thereby on a call, and failing closed would silently
 * cost the sense entirely. `AudioRecord` still fails gracefully underneath.
 */
internal fun micIsBusy(mode: Int?): Boolean =
    mode != null && mode > AudioManager.MODE_NORMAL
