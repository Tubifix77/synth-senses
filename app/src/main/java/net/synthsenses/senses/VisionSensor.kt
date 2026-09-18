package net.synthsenses.senses

import android.content.Context
import android.graphics.ImageFormat
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.objects.defaults.PredefinedCategory
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/**
 * The eyes. Grabs one frame on demand, runs three bundled ML Kit models over it,
 * and returns a Vision. All models are on-device and ship inside the APK, so
 * this works with the radio off.
 */
class VisionSensor(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    companion object {
        private const val TAG = "VisionSensor"

        /**
         * true  = keep the camera bound between samples. Fast (no warm-up) but the
         *         sensor stays powered, which is most of this app's battery cost.
         * false = unbind after each sample. Saves real power, adds ~400 ms latency.
         */
        const val KEEP_CAMERA_WARM = true
    }

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.55f).build()
    )

    private val objectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE) // gives us stable track ids
            .enableMultipleObjects()
            .enableClassification()
            .build()
    )

    private val textRecognizer = TextRecognition.getClient(
        TextRecognizerOptions.DEFAULT_OPTIONS
    )

    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var pending: CompletableDeferred<InputImageWithBrightness>? = null
    private var boundLens: String? = null

    private class InputImageWithBrightness(val image: InputImage, val brightness: Float)

    /** Grabs a frame and interprets it. Returns null if the camera never delivered one. */
    suspend fun sample(lens: String, wantText: Boolean): Vision? {
        val frame = withTimeoutOrNull(6_000) { grabFrame(lens) } ?: run {
            Log.w(TAG, "no frame within 6s")
            return null
        }

        val labels = runCatching {
            labeler.process(frame.image).await()
                .map { Scored(it.text, it.confidence) }
                .sortedByDescending { it.conf }
                .take(6)
        }.getOrElse {
            Log.w(TAG, "labeling failed", it); emptyList()
        }

        val objects = runCatching {
            val w = frame.image.width.toFloat().coerceAtLeast(1f)
            val h = frame.image.height.toFloat().coerceAtLeast(1f)
            objectDetector.process(frame.image).await().map { obj ->
                val b = obj.boundingBox
                Seen(
                    name = obj.labels.maxByOrNull { it.confidence }?.text
                        ?: categoryName(obj.labels.firstOrNull()?.text),
                    box = listOf(b.left / w, b.top / h, b.right / w, b.bottom / h),
                    track = obj.trackingId
                )
            }
        }.getOrElse {
            Log.w(TAG, "object detection failed", it); emptyList()
        }

        val text = if (!wantText) null else runCatching {
            textRecognizer.process(frame.image).await().text
                .takeIf { it.isNotBlank() }
                ?.lines()
                ?.filter { it.isNotBlank() }
                ?.take(6)
                ?.joinToString(" / ")
        }.getOrElse {
            Log.w(TAG, "OCR failed", it); null
        }

        if (!KEEP_CAMERA_WARM) unbind()

        return Vision(
            lens = lens,
            brightness = frame.brightness,
            labels = labels,
            objects = objects,
            text = text
        )
    }

    private fun categoryName(raw: String?): String = when (raw) {
        PredefinedCategory.FASHION_GOOD -> "Clothing"
        PredefinedCategory.FOOD -> "Food"
        PredefinedCategory.HOME_GOOD -> "Household object"
        PredefinedCategory.PLACE -> "Place"
        PredefinedCategory.PLANT -> "Plant"
        else -> raw ?: "Object"
    }

    private suspend fun grabFrame(lens: String): InputImageWithBrightness =
        withContext(Dispatchers.Main) {
            bind(lens)
            val deferred = CompletableDeferred<InputImageWithBrightness>()
            pending = deferred
            deferred.await()
        }

    private suspend fun bind(lens: String) {
        if (provider != null && boundLens == lens) return

        val p = provider ?: ProcessCameraProvider.getInstance(context).await().also {
            provider = it
        }
        p.unbindAll()

        val ia = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        ia.setAnalyzer(analysisExecutor) { proxy -> onFrame(proxy) }

        val selector =
            if (lens == "front") CameraSelector.DEFAULT_FRONT_CAMERA
            else CameraSelector.DEFAULT_BACK_CAMERA

        p.bindToLifecycle(lifecycleOwner, selector, ia)
        analysis = ia
        boundLens = lens
    }

    // ImageProxy.getImage() is @ExperimentalGetImage in CameraX. Opting in here rather
    // than propagating the marker, which would force every caller to opt in too.
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun onFrame(proxy: ImageProxy) {
        val waiting = pending
        if (waiting == null || waiting.isCompleted) {
            // nobody asked for a frame — drop it, this is the duty cycle
            proxy.close()
            return
        }

        try {
            val media = proxy.image
            if (media == null) {
                proxy.close()
                return
            }
            val brightness = meanLuma(proxy)
            // InputImage.fromMediaImage copies what it needs, but ML Kit docs require
            // the proxy stay open until processing completes. We build from bitmap-free
            // YUV and immediately hand off a retained InputImage.
            val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
            pending = null
            // Deliberately complete before closing: ML Kit reads the planes synchronously
            // on process() call, and we await() that on the caller's coroutine. To stay
            // safe we hold the proxy until the deferred consumer is done.
            waiting.complete(InputImageWithBrightness(input, brightness))
            // Give ML Kit a window to read the planes. 1.5 s is generous for three models
            // on an NPU; if you see "ImageProxy closed" warnings, raise it.
            analysisExecutor.execute {
                Thread.sleep(1_500)
                runCatching { proxy.close() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "frame handling failed", t)
            runCatching { proxy.close() }
        }
    }

    /** Mean luma from the Y plane — cheap ambient-brightness estimate, 0..1. */
    private fun meanLuma(proxy: ImageProxy): Float {
        return try {
            if (proxy.format != ImageFormat.YUV_420_888) return 0.5f
            val y = proxy.planes[0]
            val buf = y.buffer
            val rowStride = y.rowStride
            val h = proxy.height
            var sum = 0L
            var n = 0
            // sample a sparse grid rather than every pixel
            var row = 0
            while (row < h) {
                var col = 0
                while (col < proxy.width) {
                    val idx = row * rowStride + col
                    if (idx < buf.limit()) {
                        sum += (buf.get(idx).toInt() and 0xFF)
                        n++
                    }
                    col += 16
                }
                row += 16
            }
            buf.rewind()
            if (n == 0) 0.5f else (sum.toFloat() / n / 255f)
        } catch (t: Throwable) {
            0.5f
        }
    }

    fun unbind() {
        provider?.unbindAll()
        boundLens = null
        analysis = null
    }

    fun close() {
        unbind()
        runCatching { labeler.close() }
        runCatching { objectDetector.close() }
        runCatching { textRecognizer.close() }
        analysisExecutor.shutdown()
    }
}
