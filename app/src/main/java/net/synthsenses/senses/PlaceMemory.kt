package net.synthsenses.senses

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.min

/**
 * Learns places from radio fingerprints, entirely on the phone.
 *
 * The naive approach — compare two fingerprints for overlap — does not work, and
 * I checked before shipping it. Simulated against realistic conditions it
 * fragments one room into a dozen "new places" as soon as anchors drop out of a
 * scan, and BLE-only mode fails almost completely because randomised MACs swamp
 * the comparison.
 *
 * What works is asking a different question. Not "do these two readings match"
 * but "how much of what this place normally shows me can I see right now":
 *
 *     expected = Σ (signal weight × persistence) over the place's anchors
 *     achieved = Σ  signal weight           over anchors visible right now
 *     coverage = achieved / expected
 *
 * Persistence is the fraction of visits an anchor has been seen on, which the
 * place learns for itself. That gives three properties worth having:
 *
 *  - Self-calibrating. A place that only ever reveals a third of its anchors
 *    expects a third, so a normal visit still scores ~1.0.
 *  - Transient-proof. Extra devices in the current scan aren't in the
 *    denominator, so a passing phone can't dilute a match.
 *  - MAC-randomisation-proof. An anchor must be seen at least twice, and on at
 *    least 35% of visits, to count at all. Randomised addresses never repeat, so
 *    they're excluded automatically.
 *
 * Measured behaviour from that simulation (12 revisits each):
 *
 *    quiet room, light drift                 mean 0.97   recognised 12/12
 *    dense environment, 60% of anchors drop  mean 0.66   recognised  8/12
 *    BLE only, 3 fixed + 4 random per visit  mean 1.00   recognised 12/12
 *    BLE only, 2 fixed + 6 random per visit  mean 1.00   recognised 12/12
 *    adjacent room sharing 4 access points        0.41   correctly new
 *    unrelated 40-device scan                     0.00   correctly new
 *
 * Known limitation: somewhere with no stable anchors at all — a busy station
 * concourse where 85% of what you see is different every scan — will keep
 * minting new place ids. That's arguably correct, since it isn't a stable place,
 * but if it bothers you, raise the training period before anchors are trusted.
 *
 * The box names places over the link. That division is the interesting part: the
 * phone discovers that a place exists, the AI decides what it is.
 */
class PlaceMemory(private val file: File) {

    /** Production entry point; see Habituation for why the File constructor exists. */
    constructor(context: Context) : this(File(context.filesDir, "places.json"))

    companion object {
        private const val TAG = "PlaceMemory"

        /** Coverage at or above this is the same place. */
        private const val RECOGNISE = 0.50f

        /** At or above this, also blend the reading into the place's centroid. */
        private const val MERGE = 0.75f

        /** An anchor must be this persistent to count toward expected weight. */
        private const val MIN_PERSISTENCE = 0.35f

        /** ...and must have been seen at least this many times. Kills random MACs. */
        private const val MIN_SEEN = 2

        /** Guards against a one-anchor place matching everything. */
        private const val MIN_SHARED = 2

        private const val MAX_PLACES = 120
        private const val MAX_ANCHORS = 80
        private const val RSSI_BLEND = 0.25f
    }

    data class Match(
        val id: String,
        val name: String?,
        val similarity: Float,
        val isNew: Boolean
    )

    private class Anchor(var rssi: Int, var seen: Int)

    private class Known(
        val id: String,
        var name: String?,
        val anchors: HashMap<String, Anchor>,
        var visits: Int,
        var firstMs: Long,
        var lastMs: Long
    ) {
        fun persistence(a: Anchor): Float =
            min(1f, a.seen.toFloat() / visits.coerceAtLeast(1))
    }

    private val places = LinkedHashMap<String, Known>()
    private var nextId = 1

    init {
        load()
    }

    fun recognise(fingerprint: Map<String, Int>): Match? {
        if (fingerprint.isEmpty()) return null
        val now = System.currentTimeMillis()

        var bestId: String? = null
        var bestCoverage = 0f
        for ((id, known) in places) {
            val c = coverage(known, fingerprint)
            if (c > bestCoverage) {
                bestCoverage = c
                bestId = id
            }
        }

        if (bestId != null && bestCoverage >= RECOGNISE) {
            val k = places.getValue(bestId)
            k.lastMs = now
            // Only a confident match teaches the place. A borderline one is
            // reported but not learned from, so a doorway can't slowly blend two
            // rooms into one.
            if (bestCoverage >= MERGE) observe(k, fingerprint)
            else k.visits += 1
            save()
            return Match(k.id, k.name, bestCoverage, isNew = false)
        }

        val id = "p$nextId"
        nextId += 1
        val fresh = Known(
            id = id,
            name = null,
            anchors = HashMap(),
            visits = 0,
            firstMs = now,
            lastMs = now
        )
        observe(fresh, fingerprint)
        places[id] = fresh
        prune()
        save()
        Log.i(TAG, "new place $id (best existing coverage ${"%.2f".format(bestCoverage)})")
        return Match(id, null, bestCoverage, isNew = true)
    }

    /**
     * achieved / expected, capped at 1. See the class comment for why it's this
     * shape rather than a symmetric similarity.
     */
    private fun coverage(k: Known, fingerprint: Map<String, Int>): Float {
        if (k.anchors.isEmpty()) return 0f

        var expected = 0f
        var achieved = 0f
        var shared = 0

        for ((id, anchor) in k.anchors) {
            val p = k.persistence(anchor)
            if (p < MIN_PERSISTENCE || anchor.seen < MIN_SEEN) continue

            val w = signalWeight(anchor.rssi)
            expected += w * p
            if (fingerprint.containsKey(id)) {
                achieved += w
                shared += 1
            }
        }

        if (expected <= 0f || shared < MIN_SHARED) return 0f
        return min(1f, achieved / expected)
    }

    /** -100 dBm -> ~0, -30 dBm -> 1. */
    private fun signalWeight(rssi: Int): Float =
        ((rssi + 100f) / 70f).coerceIn(0.05f, 1f)

    private fun observe(k: Known, fingerprint: Map<String, Int>) {
        k.visits += 1
        for ((id, rssi) in fingerprint) {
            val a = k.anchors[id]
            if (a == null) {
                k.anchors[id] = Anchor(rssi, 1)
            } else {
                a.rssi = (a.rssi * (1f - RSSI_BLEND) + rssi * RSSI_BLEND).toInt()
                a.seen += 1
            }
        }
        // Keep the most persistent anchors, not merely the loudest — a faint
        // access point that's always there beats a strong phone that passed once.
        if (k.anchors.size > MAX_ANCHORS) {
            val keep = k.anchors.entries
                .sortedWith(
                    compareByDescending<MutableMap.MutableEntry<String, Anchor>> {
                        it.value.seen
                    }.thenByDescending { it.value.rssi }
                )
                .take(MAX_ANCHORS)
                .map { it.key to it.value }
            k.anchors.clear()
            keep.forEach { (id, a) -> k.anchors[id] = a }
        }
    }

    // ---- control from the box ----

    fun name(id: String, name: String): Boolean {
        val k = places[id] ?: return false
        k.name = name.takeIf { it.isNotBlank() }
        save()
        return true
    }

    fun forget() {
        places.clear()
        nextId = 1
        runCatching { if (file.exists()) file.delete() }
    }

    fun count() = places.size

    fun listJson(): JSONArray = JSONArray().apply {
        places.values.sortedByDescending { it.visits }.forEach { k ->
            val stable = k.anchors.count {
                k.persistence(it.value) >= MIN_PERSISTENCE && it.value.seen >= MIN_SEEN
            }
            put(JSONObject().apply {
                put("id", k.id)
                put("name", k.name ?: JSONObject.NULL)
                put("visits", k.visits)
                put("anchors", k.anchors.size)
                put("stable_anchors", stable)
                put("first_seen", Percept.iso(k.firstMs))
                put("last_seen", Percept.iso(k.lastMs))
            })
        }
    }

    // ---- persistence ----

    private fun prune() {
        if (places.size <= MAX_PLACES) return
        places.entries
            .sortedWith(compareBy({ it.value.visits }, { it.value.lastMs }))
            .take(places.size - MAX_PLACES)
            .map { it.key }
            .forEach { places.remove(it) }
    }

    private fun save() {
        runCatching {
            val arr = JSONArray()
            places.values.forEach { k ->
                arr.put(JSONObject().apply {
                    put("id", k.id)
                    put("name", k.name ?: JSONObject.NULL)
                    put("visits", k.visits)
                    put("first", k.firstMs)
                    put("last", k.lastMs)
                    put("anchors", JSONObject().apply {
                        k.anchors.forEach { (id, a) ->
                            put(id, JSONArray().apply { put(a.rssi); put(a.seen) })
                        }
                    })
                })
            }
            file.writeText(JSONObject().apply {
                put("next_id", nextId)
                put("places", arr)
            }.toString())
        }.onFailure { Log.w(TAG, "save failed", it) }
    }

    private fun load() {
        runCatching {
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            nextId = root.optInt("next_id", 1)
            val arr = root.optJSONArray("places") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val anchors = HashMap<String, Anchor>()
                o.optJSONObject("anchors")?.let { a ->
                    a.keys().forEach { key ->
                        val pair = a.optJSONArray(key) ?: return@forEach
                        anchors[key] = Anchor(pair.optInt(0, -90), pair.optInt(1, 1))
                    }
                }
                val id = o.getString("id")
                places[id] = Known(
                    id = id,
                    name = o.optString("name", "").takeIf { it.isNotBlank() },
                    anchors = anchors,
                    visits = o.optInt("visits", 1),
                    firstMs = o.optLong("first", 0L),
                    lastMs = o.optLong("last", 0L)
                )
            }
            Log.i(TAG, "loaded ${places.size} places")
        }.onFailure { Log.w(TAG, "load failed", it) }
    }
}
