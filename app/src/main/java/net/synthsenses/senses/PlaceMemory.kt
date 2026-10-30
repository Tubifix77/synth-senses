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
 * A place has to be bootstrapped into that scheme, and getting this wrong is
 * not a degradation, it is total failure. Persistence needs several visits
 * before it means anything, and an anchor cannot have been seen twice when the
 * place has been visited once — so applying the mature filter to a brand-new
 * place empties its denominator, makes its coverage zero against every scan,
 * and it is never recognised again, so it never gets a second observation.
 * Every scan then mints a new place, forever. So a place is LEARNING for its
 * first few visits: no persistence filter, a lower recognition bar to offset
 * the passers-by still sitting in its denominator at full weight, and every
 * recognition teaches it. Nothing about a settled place changes.
 *
 * Coverage answers how much of the place is visible, and that is only half of
 * the question. It cannot tell a scan of a *different* room from a scan showing
 * a thin *subset* of this one, because both score low the same way. On hardware
 * that gap cost a duplicate: one sample saw 10 of its usual 18 access points,
 * coverage fell to 0.49 against a bar of 0.50, and a second id was minted for
 * the room the phone was already lying still in. So minting also consults the
 * other direction:
 *
 *     containment = Σ signal weight (as heard now) over this place's visible
 *                   anchors, divided by the same over everything visible
 *
 * High containment means most of what I can see belongs to somewhere I already
 * know, which is a degraded scan of that place rather than a new one — so no
 * new place is minted, and the scan teaches nothing, because counting it as a
 * visit would dilute every anchor's persistence. Stray devices share nothing
 * with the room, so they are still correctly new. See `docs/VALIDATION.md`
 * for the measured separation between the three cases.
 *
 * Measured behaviour, 12 revisits after 6 training visits, over 200 seeds of
 * the simulation driven through this class's real entry point:
 *
 *    quiet room, light drift                 mean 0.94   recognised 12/12
 *    dense environment, 60% of anchors drop  mean 0.52   recognised  8/12
 *    BLE only, 3 fixed + 4 random per visit              recognised 12/12
 *    BLE only, 2 fixed + 6 random per visit              recognised 12/12
 *    street, and unrelated 40-device scans        0.00   always new
 *    adjacent room sharing 4 access points        0.45   new on 96.5% of seeds
 *
 * That last line is the honest one: separating a room from the room next door
 * is a 96.5% property of this metric, not a guarantee, and the failures are
 * settled places claiming the neighbour rather than anything to do with the
 * learning window.
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

        /**
         * How many visits a place spends learning before the two filters above
         * are applied to it. Until then it has no usable persistence statistics
         * and the filters would reject all of its own anchors. Six is what the
         * estimates need to mean anything, and it is the training period the
         * simulation in docs/VALIDATION.md assumes.
         *
         * Do not raise this past 8 without re-running that simulation: a place
         * that is still learning is matched leniently, and leaving somewhere
         * lenient for longer than it takes to settle is how the room next door
         * gets absorbed.
         */
        private const val LEARNING_VISITS = 6

        /**
         * The bar a learning place is recognised at. Lower than RECOGNISE
         * because a young place's denominator still holds the passers-by of its
         * first visit at full persistence, which biases its coverage down by
         * about W(anchors) / (W(anchors) + W(transients)) — measured at ~0.66
         * where a settled place scores ~0.94, and spread widely enough that
         * roughly one visit in ten would fall under RECOGNISE and split the
         * room in two permanently. Adopting a learning place too readily costs
         * little, since it has barely any identity yet. Rejecting one is
         * forever.
         */
        private const val LEARNING_RECOGNISE = 0.35f

        /** Guards against a one-anchor place matching everything. */
        private const val MIN_SHARED = 2

        /**
         * How much of what is visible must belong to a known place before a
         * scan is treated as that place seen badly, rather than as somewhere
         * new. Measured against the three cases that have to stay apart: a
         * degraded scan of the current room sits near 1.0, an adjacent room
         * sharing four weakened access points sits near 0.2, and a single
         * stray device sits at 0. Half is the gap.
         */
        private const val CONTAINED = 0.5f

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

        /** Too new for its own persistence statistics to be worth anything. */
        fun isLearning(): Boolean = visits < LEARNING_VISITS

        fun recogniseAt(): Float = if (isLearning()) LEARNING_RECOGNISE else RECOGNISE
    }

    private val places = LinkedHashMap<String, Known>()
    private var nextId = 1

    init {
        load()
    }

    fun recognise(fingerprint: Map<String, Int>): Match? {
        // Fewer entries than MIN_SHARED can never score against any place, so a
        // place minted from them is one that can never be recognised again.
        // Seen on the P20, 2026-10-29: lying still, its WiFi scans kept coming
        // back as the home network alone, and every such sample with no BLE in
        // range minted a fresh place -- 17 dead ids in 90 minutes. Too little
        // to say where the phone is, so say nothing.
        if (fingerprint.size < MIN_SHARED) return null
        val now = System.currentTimeMillis()

        // The weight of everything visible right now: the denominator for
        // containment below. Computed once, because it does not vary by place.
        var seenWeight = 0f
        for (rssi in fingerprint.values) seenWeight += signalWeight(rssi)

        var bestId: String? = null
        var best = Score(0f, 0, 0f, 0f)
        for ((id, known) in places) {
            val s = score(known, fingerprint, seenWeight)
            // Better coverage wins; an exact tie is broken by the weight of
            // corroborating evidence, never by insertion order. The cap in
            // `score` makes ties at 1.00 common once two places cover one room,
            // and taking the first-inserted made the reported id flip between
            // them with the phone lying still.
            if (s.coverage > best.coverage ||
                (s.coverage == best.coverage && s.achieved > best.achieved)
            ) {
                best = s
                bestId = id
            }
        }
        val bestCoverage = best.coverage

        if (bestId != null && bestCoverage >= places.getValue(bestId).recogniseAt()) {
            val k = places.getValue(bestId)
            k.lastMs = now
            // Only a confident match teaches a settled place. A borderline one is
            // reported but not learned from, so a doorway can't slowly blend two
            // rooms into one. A place that is still learning always learns, which
            // is the only way it ever accumulates the evidence to settle.
            if (bestCoverage >= MERGE || k.isLearning()) observe(k, fingerprint)
            else k.visits += 1
            save()
            return Match(k.id, k.name, bestCoverage, isNew = false)
        }

        // A scan can be too thin to judge. If most of what is visible belongs
        // to a place already known, we are standing in that place under a
        // degraded scan rather than standing somewhere new -- and minting here
        // leaves a permanent duplicate of the room we are already in. Measured
        // on hardware: one sample that saw 10 of its usual 18 access points
        // dropped the room to 0.49 and minted a second id for it, after which
        // both covered the same room and the reported place flapped.
        //
        // Coverage cannot see this on its own. It asks how much of the place is
        // visible; this asks how much of what is visible is the place. A few
        // stray devices share nothing with the room and still mint, as they must.
        if (bestId != null && best.containment >= CONTAINED && best.shared >= MIN_SHARED) {
            val k = places.getValue(bestId)
            k.lastMs = now
            // Neither observed nor counted as a visit, deliberately. A scan we
            // do not trust enough to act on must not teach, and bumping visits
            // on its own would dilute every anchor's persistence.
            save()
            Log.i(TAG, "thin scan over ${k.id} (coverage ${"%.2f".format(bestCoverage)}," +
                " contained ${"%.2f".format(best.containment)}) - not a new place")
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
     * How one place scores against one scan, in both directions.
     *
     * `coverage` is achieved / expected, capped at 1 -- see the class comment
     * for why it is this shape rather than a symmetric similarity. `achieved`
     * is kept unrounded so an exact tie at the cap can be broken on evidence.
     * `containment` is the other direction: the share of what is visible that
     * this place accounts for.
     */
    private data class Score(
        val coverage: Float,
        val shared: Int,
        val achieved: Float,
        val containment: Float
    )

    private fun score(k: Known, fingerprint: Map<String, Int>, seenWeight: Float): Score {
        val none = Score(0f, 0, 0f, 0f)
        if (k.anchors.isEmpty()) return none

        var expected = 0f
        var achieved = 0f
        var seenHere = 0f
        var shared = 0

        // A learning place is scored on everything it has ever seen, weighted by
        // how often it has seen it. Transients still cost it almost nothing,
        // because persistence is seen/visits and theirs is 1/visits.
        val learning = k.isLearning()

        for ((id, anchor) in k.anchors) {
            val p = k.persistence(anchor)
            if (!learning && (p < MIN_PERSISTENCE || anchor.seen < MIN_SEEN)) continue

            val w = signalWeight(anchor.rssi)
            expected += w * p
            val rssiNow = fingerprint[id]
            if (rssiNow != null) {
                achieved += w
                shared += 1
                // Weighted as heard now, matching the denominator: an anchor
                // heard faintly through a wall must not count as strongly as
                // the same anchor heard in the room.
                seenHere += signalWeight(rssiNow)
            }
        }

        if (expected <= 0f || shared < MIN_SHARED) return none
        return Score(
            coverage = min(1f, achieved / expected),
            shared = shared,
            achieved = achieved,
            containment = if (seenWeight <= 0f) 0f else min(1f, seenHere / seenWeight)
        )
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
                    // Android's JSONObject.optString returns the four-character
                    // string "null" for a JSONObject.NULL value, rather than the
                    // fallback -- so an unnamed place came back literally named
                    // "null" after one save/load cycle, and the narration said
                    // "I am in null." Unnamed is the normal case, since nothing
                    // names a place unless someone sends name_place.
                    //
                    // The JVM tests cannot catch this: they run against the real
                    // org.json, where optString honours the fallback. Only the
                    // instrumented tests see Android's own implementation.
                    // Link.kt already guarded this correctly with isNull; this
                    // did not.
                    //
                    // The `!= "null"` also cleans up data already on disk: a
                    // place loaded as "null" was re-saved under that name by the
                    // next observe(), so the string is really there now.
                    name = (if (o.isNull("name")) null else o.optString("name", ""))
                        ?.takeIf { it.isNotBlank() && it != "null" },
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
