package net.synthsenses.senses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/**
 * Place recognition, against the real Kotlin.
 *
 * The Python prototype of this metric FAILED twice before the third design
 * worked — symmetric similarity fragmented rooms, and the first asymmetric
 * version capped out in dense environments. These tests encode the scenarios
 * that exposed those failures, so a future "simplification" cannot quietly
 * reintroduce them.
 */
class PlaceMemoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fresh() = PlaceMemory(File(tmp.newFolder(), "places.json"))

    private val rng = Random(7)

    private fun room(n: Int, shift: Int = 0): Map<String, Int> =
        (0 until n).associate { "w:ap${it + shift}" to rng.nextInt(-80, -40) }

    /** A revisit: some anchors missing, RSSI drifted, a few devices passing through. */
    private fun revisit(
        base: Map<String, Int>, drop: Double, drift: Int, transient: Int
    ): Map<String, Int> {
        val out = HashMap<String, Int>()
        for ((k, v) in base) {
            if (rng.nextDouble() < drop) continue
            out[k] = (v + rng.nextInt(-drift, drift + 1)).coerceIn(-95, -30)
        }
        repeat(transient) { out["b:x${rng.nextInt(0, 1_000_000)}"] = rng.nextInt(-90, -70) }
        return out
    }

    @Test
    fun `a quiet room is recognised across revisits, not fragmented`() {
        val pm = fresh()
        val desk = room(14)

        pm.recognise(revisit(desk, 0.25, 8, 3))          // first sighting
        repeat(5) { pm.recognise(revisit(desk, 0.25, 8, 3)) }   // learn persistence

        var recognised = 0
        repeat(12) {
            val m = pm.recognise(revisit(desk, 0.25, 8, 3))!!
            if (!m.isNew) recognised++
        }

        assertEquals("every revisit should be the same place", 12, recognised)
        assertEquals("exactly one place should exist", 1, pm.count())
    }

    @Test
    fun `BLE only with randomised MACs still recognises the room`() {
        // The case that broke the original metric completely: 3 fixed devices,
        // plus 4 freshly randomised addresses every single visit.
        val pm = fresh()
        val fixed = (0 until 3).associate { "b:fix$it" to rng.nextInt(-70, -50) }

        fun bleVisit(): Map<String, Int> {
            val d = HashMap<String, Int>()
            fixed.forEach { (k, v) -> d[k] = v + rng.nextInt(-6, 7) }
            repeat(4) { d["b:r${rng.nextInt(0, 1_000_000)}"] = rng.nextInt(-90, -70) }
            return d
        }

        repeat(6) { pm.recognise(bleVisit()) }

        var recognised = 0
        repeat(12) { if (!pm.recognise(bleVisit())!!.isNew) recognised++ }

        assertEquals("randomised MACs must not defeat recognition", 12, recognised)
        assertEquals(1, pm.count())
    }

    @Test
    fun `an adjacent room sharing access points stays a separate place`() {
        val pm = fresh()
        val desk = room(14)
        repeat(8) { pm.recognise(revisit(desk, 0.25, 8, 3)) }

        // thin walls: the kitchen sees four of the desk's APs, weakly
        val kitchen = HashMap(room(9, shift = 100))
        desk.keys.take(4).forEach { kitchen[it] = desk.getValue(it) - 18 }

        val m = pm.recognise(kitchen)!!
        assertTrue("rooms must not collapse into one another", m.isNew)
        assertEquals(2, pm.count())
    }

    @Test
    fun `unrelated environments are always new`() {
        val pm = fresh()
        val desk = room(14)
        repeat(8) { pm.recognise(revisit(desk, 0.25, 8, 3)) }

        assertTrue("a street is not the desk", pm.recognise(room(25, 900))!!.isNew)
        assertTrue("a big unrelated scan is not the desk", pm.recognise(room(40, 2000))!!.isNew)
    }

    @Test
    fun `an empty scan yields no match at all`() {
        val pm = fresh()
        repeat(5) { pm.recognise(room(10)) }
        assertNull("nothing seen means nothing claimed", pm.recognise(emptyMap()))
    }

    @Test
    fun `a single stray device cannot match a known place`() {
        val pm = fresh()
        val desk = room(14)
        repeat(8) { pm.recognise(revisit(desk, 0.25, 8, 3)) }

        val before = pm.count()
        assertNull("one anchor is not enough evidence", pm.recognise(mapOf("b:stray" to -70)))
        assertEquals("nor enough to found a place on", before, pm.count())
    }

    @Test
    fun `a scan that sees only the home network mints nothing`() {
        // The P20 on 2026-10-29, lying still: scans shrank to the one network it
        // was connected to, and each one with no BLE in range minted a place no
        // later scan could ever match. 17 of them in 90 minutes.
        val pm = fresh()
        val desk = room(14)
        repeat(8) { pm.recognise(revisit(desk, 0.25, 8, 3)) }
        val home = desk.keys.first()

        val before = pm.count()
        repeat(30) { assertNull(pm.recognise(mapOf(home to -42))) }
        assertEquals("no place minted from a one-entry scan", before, pm.count())
        assertTrue("and the room is still itself", !pm.recognise(revisit(desk, 0.25, 8, 3))!!.isNew)
    }

    @Test
    fun `a degraded scan of a known room does not mint a duplicate of it`() {
        val pm = fresh()
        val desk = room(18)
        repeat(10) { pm.recognise(revisit(desk, 0.1, 6, 2)) }
        assertEquals("the room settled as a single place", 1, pm.count())

        // The hardware case, 2026-10-21: one sample saw 10 of the 18 access
        // points it usually sees, coverage fell to 0.49 against a RECOGNISE of
        // 0.50, and a second id was minted for the room the phone was already
        // lying still in. Both then covered the same room and the reported
        // place flapped between them for the rest of the evening.
        //
        // Six of eighteen is further under the threshold than the real sample
        // was, so this fails loudly if the guard goes away.
        val thin = desk.entries.take(6).associate { it.key to it.value }
        val m = pm.recognise(thin)!!

        assertTrue("a thin scan is not somewhere new", !m.isNew)
        assertEquals("the room keeps its own id", "p1", m.id)
        assertEquals("and no duplicate is left behind", 1, pm.count())
        assertTrue(
            "the real coverage is reported, not a fabricated certainty",
            m.similarity < 0.5f
        )
    }

    @Test
    fun `a thin scan teaches the place nothing`() {
        val pm = fresh()
        val desk = room(18)
        repeat(10) { pm.recognise(revisit(desk, 0.1, 6, 2)) }
        val before = pm.listJson().getJSONObject(0)

        val thin = desk.entries.take(6).associate { it.key to it.value }
        repeat(5) { pm.recognise(thin) }
        val after = pm.listJson().getJSONObject(0)

        // Counting a scan we would not act on as a visit divides every anchor's
        // persistence by a larger number, so a run of bad scans would slowly
        // dissolve the very room it had failed to recognise.
        assertEquals(
            "a scan too thin to judge is not a visit",
            before.getInt("visits"), after.getInt("visits")
        )
        assertEquals(
            "and teaches it no anchors",
            before.getInt("anchors"), after.getInt("anchors")
        )
        assertEquals(
            "so the stable set is untouched",
            before.getInt("stable_anchors"), after.getInt("stable_anchors")
        )
    }

    @Test
    fun `a tie at the cap is broken by evidence, not by which was learned first`() {
        val pm = fresh()
        val cupboard = room(3)
        repeat(8) { pm.recognise(cupboard) }
        val hall = room(20, shift = 500)
        repeat(8) { pm.recognise(hall) }
        assertEquals("two genuinely separate places", 2, pm.count())

        // Both score exactly 1.00 here: every anchor either place knows is
        // visible, and `min(1f, ...)` hides the difference between three
        // anchors and twenty. The cupboard was learned first, so a strict `>`
        // over insertion order returns it -- which is how one still phone
        // reported two different rooms in alternation.
        val both = HashMap<String, Int>(cupboard).apply { putAll(hall) }
        val m = pm.recognise(both)!!

        assertEquals("the better-corroborated place wins the tie", "p2", m.id)
        assertEquals("and the answer does not oscillate", "p2", pm.recognise(both)!!.id)
    }

    @Test
    fun `naming persists and survives a reload`() {
        val file = File(tmp.newFolder(), "places.json")
        val pm = PlaceMemory(file)
        val desk = room(12)
        val first = pm.recognise(revisit(desk, 0.2, 6, 2))!!
        repeat(4) { pm.recognise(revisit(desk, 0.2, 6, 2)) }

        assertTrue(pm.name(first.id, "desk"))
        assertTrue("unknown ids are rejected", !pm.name("p999", "nowhere"))

        val reloaded = PlaceMemory(file)
        val again = reloaded.recognise(revisit(desk, 0.2, 6, 2))!!
        assertEquals("desk", again.name)
        assertTrue("and it is the same place", !again.isNew)
    }

    @Test
    fun `forget clears everything`() {
        val pm = fresh()
        repeat(5) { pm.recognise(room(10)) }
        assertTrue(pm.count() > 0)
        pm.forget()
        assertEquals(0, pm.count())
    }

    @Test
    fun `listJson reports stable anchors separately from total`() {
        val pm = fresh()
        val desk = room(14)
        repeat(6) { pm.recognise(revisit(desk, 0.25, 8, 5)) }

        val arr = pm.listJson()
        assertTrue(arr.length() >= 1)
        val o = arr.getJSONObject(0)
        assertNotNull(o.get("stable_anchors"))
        assertTrue(
            "transients inflate the total but not the stable count",
            o.getInt("stable_anchors") <= o.getInt("anchors")
        )
    }
}
