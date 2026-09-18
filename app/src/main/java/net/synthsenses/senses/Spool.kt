package net.synthsenses.senses

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Bounded disk queue for percepts that couldn't be delivered. Oldest are dropped
 * once it's full, on the grounds that a synthetic life catching up on two
 * thousand percepts is more useful than one catching up on fifty thousand.
 *
 * Worth knowing: transcripts sit in here in plaintext until they're delivered.
 */
class Spool(private val file: File) {

    /**
     * Production entry point. The File-based primary constructor is the same
     * arrangement Habituation and PlaceMemory use, and for the same reason:
     * the bound is the interesting part and it should be testable on a plain
     * JVM rather than needing an emulator.
     */
    constructor(context: Context) : this(File(context.filesDir, "spool.jsonl"))

    companion object {
        private const val TAG = "Spool"
        internal const val MAX_LINES = 2_000
    }

    private val lock = Any()

    fun append(o: JSONObject) = synchronized(lock) {
        runCatching {
            file.appendText(o.toString().replace("\n", " ") + "\n")
            val lines = file.readLines()
            if (lines.size > MAX_LINES) {
                file.writeText(lines.takeLast(MAX_LINES).joinToString("\n") + "\n")
            }
        }.onFailure { Log.w(TAG, "append failed", it) }
        Unit
    }

    fun read(): List<JSONObject> = synchronized(lock) {
        runCatching {
            if (!file.exists()) return emptyList()
            file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        }.getOrElse { emptyList() }
    }

    fun clear() = synchronized(lock) {
        runCatching { if (file.exists()) file.delete() }
        Unit
    }

    fun size(): Int = synchronized(lock) {
        runCatching { if (file.exists()) file.readLines().count { it.isNotBlank() } else 0 }
            .getOrDefault(0)
    }
}
