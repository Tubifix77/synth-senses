package net.synthsenses.senses

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.exp

/**
 * Attention as habituation rather than a fixed threshold.
 *
 * Every stimulus token carries a trace. Each exposure moves that trace toward
 * fully habituated; time away lets it decay back. Salience is how un-habituated
 * the current stimulus set is, so:
 *
 *   - something never seen before is maximally salient
 *   - the same room, sampled every five seconds, goes quiet within ~2 minutes
 *   - that same room becomes interesting again after a couple of hours away
 *   - a startling stimulus in one channel partially restores response in all of
 *     them (dishabituation — it happens in real nervous systems and it's the
 *     behaviour you want: a bang should make it look around at everything)
 *
 * Traces persist to disk, which is the point — habituation *is* memory, and it
 * shouldn't reset because the service restarted.
 */
class Habituation(private val file: File) {

    /**
     * Production entry point. The File-based primary constructor exists so the
     * model can be exercised by plain JVM unit tests — the Context dependency
     * was the only thing forcing Robolectric, and the maths is the part worth
     * testing.
     */
    constructor(context: Context) : this(File(context.filesDir, "habituation.json"))

    companion object {
        private const val TAG = "Habituation"

        /** How much of the remaining response one exposure consumes. */
        private const val RATE = 0.18f

        /** Recovery time constant. At 45 min, ~2 h away restores most response. */
        private const val TAU_MS = 45f * 60f * 1000f

        /** How much a startling event un-habituates everything. */
        private const val DISHABITUATION = 0.5f

        /** Max traces kept. Least-recently-seen are dropped past this. */
        private const val MAX_TRACES = 4_000
    }

    private class Trace(
        var h: Float = 0f,
        var exposures: Int = 0,
        var lastMs: Long = 0L,
        var firstMs: Long = 0L
    )

    private val traces = HashMap<String, Trace>()

    /** Per-prefix gain, settable by the box: "attend to sound, ignore the walls." */
    private val gains = HashMap<String, Float>()

    private var lastPostMs = 0L
    private var dishabituatedThisTick = false

    init {
        load()
    }

    // ---- the model ----

    private fun decayed(t: Trace, now: Long): Float {
        if (t.exposures == 0) return 0f
        val dt = (now - t.lastMs).coerceAtLeast(0L).toFloat()
        return t.h * exp(-dt / TAU_MS)
    }

    private fun gainFor(token: String): Float {
        val prefix = token.substringBefore(':')
        return gains[prefix] ?: 1f
    }

    /** How much response this one token still commands, 0..1 (before gain). */
    private fun tokenSalience(token: String, now: Long): Float {
        val t = traces[token] ?: return 1f
        return 1f - decayed(t, now)
    }

    /**
     * Aggregate salience. Weighted between the single most-novel token and the
     * mean: the max term lets one genuinely new thing break through a familiar
     * scene, the mean term keeps a wholly familiar scene quiet.
     */
    fun salience(tokens: Set<String>, now: Long): Float {
        if (tokens.isEmpty()) return 0f
        var max = 0f
        var sum = 0f
        for (tok in tokens) {
            val s = (tokenSalience(tok, now) * gainFor(tok)).coerceIn(0f, 1f)
            if (s > max) max = s
            sum += s
        }
        val mean = sum / tokens.size
        return (0.6f * max + 0.4f * mean).coerceIn(0f, 1f)
    }

    fun novelTokens(tokens: Set<String>, now: Long, limit: Int = 8): List<String> =
        tokens.filter { tokenSalience(it, now) > 0.85f }
            .sorted()
            .take(limit)

    /** Commit these tokens as experienced. Call once per sample, posted or not. */
    fun expose(tokens: Set<String>, now: Long) {
        for (tok in tokens) {
            val t = traces.getOrPut(tok) { Trace(firstMs = now) }
            val d = decayed(t, now)
            t.h = d + (1f - d) * RATE
            t.exposures += 1
            t.lastMs = now
        }
        prune()
    }

    /**
     * A startling event. Knocks every trace back toward responsive, so the next
     * sample reports the whole scene again rather than just the bang.
     */
    fun dishabituate() {
        traces.values.forEach { it.h *= DISHABITUATION }
        dishabituatedThisTick = true
    }

    /**
     * Intensity override. Some things matter no matter how often they happen —
     * habituating to your own house fire would be a design flaw. Returns a
     * (floor, reason) pair, or null.
     */
    fun intensityFloor(p: Percept): Pair<Float, String>? {
        p.hearing?.let { h ->
            if (h.peakDb > -6f) return 0.9f to "sudden loud sound"
            if (!h.transcript.isNullOrBlank()) return 0.8f to "speech heard"
        }
        when (p.self.thermal) {
            "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN" ->
                return 1.0f to "thermal ${p.self.thermal.lowercase()}"
            "MODERATE" -> return 0.6f to "running hot"
        }
        if (!p.self.charging && p.self.batteryPct in 0f..0.08f) {
            return 0.95f to "battery critical"
        }
        if (p.radio?.placeIsNew == true) return 0.85f to "unfamiliar place"
        if (p.body.motion == "vehicle") return 0.5f to "in transit"
        p.body.pressureDeltaPerMin?.let {
            if (abs(it) > 0.6f) return 0.6f to "rapid pressure change"
        }
        if (p.self.net == "none") return 0.0f to "offline" // no floor, just annotated
        return null
    }

    // ---- gate ----

    data class Verdict(val trigger: String?, val attention: Attention)

    fun evaluate(p: Percept, threshold: Float, heartbeatMs: Long): Verdict {
        val now = p.tsMs
        val tokens = p.tokens()

        var s = salience(tokens, now)
        val floor = intensityFloor(p)
        if (floor != null && floor.first > s) s = floor.first

        val novel = novelTokens(tokens, now)

        // startling input dishabituates *after* this percept is scored, so the
        // bang itself reports normally and the next sample re-reports the scene
        val startling = floor != null && floor.first >= 0.85f

        val trigger = when {
            lastPostMs == 0L -> "first"
            s >= threshold -> "salient"
            now - lastPostMs >= heartbeatMs -> "heartbeat"
            else -> null
        }

        val attention = Attention(
            salience = s,
            novelTokens = novel,
            knownTokens = traces.size,
            dishabituated = dishabituatedThisTick,
            floorReason = floor?.second
        )
        dishabituatedThisTick = false

        expose(tokens, now)
        if (startling) dishabituate()
        if (trigger != null) {
            lastPostMs = now
            save()
        }

        return Verdict(trigger, attention)
    }

    // ---- control from the box ----

    fun setGain(prefix: String, gain: Float) {
        if (gain == 1f) gains.remove(prefix) else gains[prefix] = gain.coerceIn(0f, 5f)
    }

    fun clearGains() = gains.clear()

    fun gainSnapshot(): Map<String, Float> = gains.toMap()

    /** Forget everything. The box can ask for this to make the world new again. */
    fun forget() {
        traces.clear()
        lastPostMs = 0L
        runCatching { if (file.exists()) file.delete() }
    }

    fun stats(): Triple<Int, Int, Float> {
        val now = System.currentTimeMillis()
        val quiet = traces.count { decayed(it.value, now) > 0.7f }
        val mean = if (traces.isEmpty()) 0f
        else traces.values.map { decayed(it, now) }.average().toFloat()
        return Triple(traces.size, quiet, mean)
    }

    /** Top habituated tokens — what it has stopped noticing. Useful to inspect. */
    fun mostHabituated(limit: Int = 12): List<Pair<String, Int>> {
        val now = System.currentTimeMillis()
        return traces.entries
            .sortedByDescending { decayed(it.value, now) }
            .take(limit)
            .map { it.key to it.value.exposures }
    }

    // ---- persistence ----

    private fun prune() {
        if (traces.size <= MAX_TRACES) return
        traces.entries
            .sortedBy { it.value.lastMs }
            .take(traces.size - MAX_TRACES)
            .forEach { traces.remove(it.key) }
    }

    private fun save() {
        runCatching {
            val root = JSONObject()
            val t = JSONObject()
            traces.forEach { (k, v) ->
                t.put(k, JSONObject().apply {
                    put("h", v.h.toDouble())
                    put("n", v.exposures)
                    put("last", v.lastMs)
                    put("first", v.firstMs)
                })
            }
            root.put("traces", t)
            root.put("last_post", lastPostMs)
            file.writeText(root.toString())
        }.onFailure { Log.w(TAG, "save failed", it) }
    }

    private fun load() {
        runCatching {
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            lastPostMs = root.optLong("last_post", 0L)
            val t = root.optJSONObject("traces") ?: return
            t.keys().forEach { k ->
                val o = t.getJSONObject(k)
                traces[k] = Trace(
                    h = o.optDouble("h", 0.0).toFloat(),
                    exposures = o.optInt("n", 0),
                    lastMs = o.optLong("last", 0L),
                    firstMs = o.optLong("first", 0L)
                )
            }
            Log.i(TAG, "loaded ${traces.size} traces")
        }.onFailure { Log.w(TAG, "load failed", it) }
    }
}
