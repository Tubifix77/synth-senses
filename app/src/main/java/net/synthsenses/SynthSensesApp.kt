package net.synthsenses

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Writes a crash somewhere a person can actually read it.
 *
 * This project has no local Android toolchain and therefore no `adb`, so an
 * uncaught exception produces a stack trace in logcat that nobody here can
 * reach. The phone says "forced to close due to internal error" and that is
 * the entire diagnosis available. It happened on the first evening on
 * hardware, and there was nothing to look at.
 *
 * So the handler writes the trace to the app's external media directory,
 * which is the one place a file manager can still browse on a modern phone —
 * `Android/data` is restricted and MTP cannot see into it either. The same
 * directory the sound model is dropped into, for the same reason.
 *
 * It then hands the exception to whatever handler was already installed, so
 * the system still does its normal thing. This observes; it does not swallow.
 */
class SynthSensesApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { writeCrash(thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun writeCrash(thread: Thread, error: Throwable) {
        val dir = externalMediaDirs.filterNotNull().firstOrNull()
            ?: getExternalFilesDir(null)
            ?: return
        dir.mkdirs()

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .apply { timeZone = TimeZone.getDefault() }
            .format(Date())
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }

        File(dir, "crash-$stamp.txt").writeText(
            buildString {
                appendLine("synth-senses crash")
                appendLine("when    ${Date()}")
                appendLine("thread  ${thread.name}")
                appendLine("device  ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("android ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
                appendLine()
                append(trace.toString())
            }
        )
        Log.e("SynthSenses", "crash written to $dir", error)
        prune(dir)
    }

    /** Keep the newest few. A crash loop must not fill the phone. */
    private fun prune(dir: File, keep: Int = 5) {
        val crashes = dir.listFiles { f -> f.name.startsWith("crash-") }
            ?.sortedByDescending { it.lastModified() } ?: return
        crashes.drop(keep).forEach { runCatching { it.delete() } }
    }
}
