package net.synthsenses.senses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The habituation curve, exercised against the real Kotlin.
 *
 * These assertions were first written as a throwaway Python model. That model
 * validated the *design* but never touched the shipped code. The numbers below
 * are the model's outputs, now used as expectations for the implementation —
 * which is the whole point: if Habituation.kt drifts from the design, this
 * fails.
 */
class HabituationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fresh(): Habituation = Habituation(File(tmp.newFolder(), "hab.json"))

    private val scene = setOf("v:Room", "v:Furniture", "m:still", "lx:indoor")

    @Test
    fun `a static scene goes quiet within about two minutes`() {
        val h = fresh()
        var now = 0L
        val readings = mutableListOf<Float>()

        repeat(60) {
            readings += h.salience(scene, now)
            h.expose(scene, now)
            now += 5_000
        }

        assertEquals("first sighting is maximally salient", 1.0f, readings[0], 0.001f)
        assertTrue("decays fast early: ${readings[2]}", readings[2] in 0.55f..0.75f)
        assertTrue("near silent by exposure 20: ${readings[19]}", readings[19] < 0.10f)
        assertTrue("still quiet at 60: ${readings[59]}", readings[59] < 0.05f)

        // strictly decreasing while nothing changes
        for (i in 1 until readings.size) {
            assertTrue("salience rose at $i", readings[i] <= readings[i - 1] + 1e-4f)
        }
    }

    @Test
    fun `response recovers with time away`() {
        val h = fresh()
        var now = 0L
        repeat(40) { h.expose(scene, now); now += 5_000 }

        val minute = 60_000L
        val atZero = h.salience(scene, now)
        val at20 = h.salience(scene, now + 20 * minute)
        val at45 = h.salience(scene, now + 45 * minute)
        val at120 = h.salience(scene, now + 120 * minute)
        val at360 = h.salience(scene, now + 360 * minute)

        assertTrue("habituated on arrival: $atZero", atZero < 0.05f)
        assertTrue("20 min restores something: $at20", at20 in 0.25f..0.50f)
        assertTrue("45 min is roughly half back: $at45", at45 in 0.55f..0.75f)
        assertTrue("2 h is nearly full: $at120", at120 > 0.85f)
        assertTrue("6 h is full: $at360", at360 > 0.97f)
        assertTrue("monotonic recovery", atZero < at20 && at20 < at45 && at45 < at120)
    }

    @Test
    fun `a novel token breaks through a habituated scene`() {
        val h = fresh()
        var now = 0L
        repeat(40) { h.expose(scene, now); now += 5_000 }
        assertTrue(h.salience(scene, now) < 0.05f)

        val withPerson = scene + setOf("o:Person", "s:Speech")
        val jump = h.salience(withPerson, now)

        assertTrue("a person walking in must register: $jump", jump > 0.60f)
        assertEquals(
            listOf("o:Person", "s:Speech"),
            h.novelTokens(withPerson, now).filter { it.startsWith("o:") || it.startsWith("s:") }
        )
    }

    @Test
    fun `dishabituation partially restores everything`() {
        val h = fresh()
        var now = 0L
        repeat(40) { h.expose(scene, now); now += 5_000 }

        val before = h.salience(scene, now)
        h.dishabituate()
        val after = h.salience(scene, now)

        assertTrue("before was quiet: $before", before < 0.05f)
        assertTrue("a bang makes it look around again: $after", after > 0.40f)
    }

    @Test
    fun `attention gain weights a token prefix`() {
        val h = fresh()
        var now = 0L
        val sounds = setOf("s:Speech", "s:Typing")
        repeat(30) { h.expose(sounds, now); now += 5_000 }

        val plain = h.salience(sounds, now)
        h.setGain("s", 5f)
        val amplified = h.salience(sounds, now)
        h.setGain("s", 1f)
        val restored = h.salience(sounds, now)

        assertTrue("gain raises salience: $plain -> $amplified", amplified > plain)
        assertEquals("gain 1.0 is a no-op", plain, restored, 1e-4f)
    }

    @Test
    fun `traces survive a restart`() {
        val dir = tmp.newFolder()
        val file = File(dir, "hab.json")

        val first = Habituation(file)
        var now = 1_000_000L
        repeat(40) { first.expose(scene, now); now += 5_000 }
        // evaluate() is what persists; drive one through the gate
        first.evaluate(perceptWith(scene, now), 0.35f, 120_000L)
        val quietBefore = first.salience(scene, now)

        val reloaded = Habituation(file)
        val quietAfter = reloaded.salience(scene, now)

        assertTrue("file was written", file.exists())
        assertEquals(
            "habituation is memory and must not reset on restart",
            quietBefore, quietAfter, 0.02f
        )
    }

    @Test
    fun `intensity floors override habituation`() {
        val h = fresh()
        // Real wall-clock timestamps, and one percept actually gated before the
        // scene is worn in. The model labels the first percept it ever gates
        // "first" whatever its salience, and it recognises "ever" by lastPostMs
        // still being zero — so starting at t=0 and only calling expose() would
        // leave this asserting on that branch instead of on the floor.
        var now = 1_600_000_000_000L
        h.evaluate(perceptWith(scene, now), 0.35f, 999_999_999L)
        now += 5_000
        repeat(60) { h.expose(scene, now); now += 5_000 }

        // thoroughly habituated, but the phone is cooking
        val hot = perceptWith(scene, now, thermal = "SEVERE")
        val verdict = h.evaluate(hot, 0.35f, 999_999_999L)

        assertTrue("overheating must not be habituated away", verdict.attention.salience >= 0.95f)
        assertEquals("thermal severe", verdict.attention.floorReason)
        assertEquals("salient", verdict.trigger)
    }

    @Test
    fun `heartbeat fires when nothing is salient`() {
        val h = fresh()
        var now = 0L
        // first posts, then goes quiet
        assertEquals("first", h.evaluate(perceptWith(scene, now), 0.35f, 60_000L).trigger)
        now += 5_000
        repeat(30) {
            h.evaluate(perceptWith(scene, now), 0.35f, 60_000L)
            now += 1_000
        }
        val quiet = h.evaluate(perceptWith(scene, now), 0.35f, 60_000L)
        assertEquals("should have gone quiet", null, quiet.trigger)

        now += 120_000
        assertEquals(
            "heartbeat", h.evaluate(perceptWith(scene, now), 0.35f, 60_000L).trigger
        )
    }

    // ---- helpers ----

    private fun perceptWith(
        tokens: Set<String>,
        nowMs: Long,
        thermal: String = "NONE"
    ): Percept = Percept(
        deviceId = "test",
        seq = 0,
        tsMs = nowMs,
        trigger = "pending",
        vision = Vision(
            lens = "back",
            brightness = 0.4f,
            labels = tokens.filter { it.startsWith("v:") }
                .map { Scored(it.removePrefix("v:"), 0.9f) },
            objects = emptyList(),
            text = null
        ),
        hearing = Hearing(-40f, -30f, emptyList(), null),
        radio = null,
        body = Body(
            motion = "still", posture = "face_up", accelRms = 0f, gyroRms = 0f,
            headingDeg = null, steps = null, stepsDelta = null, lux = 200f,
            covered = null, magneticUt = null, magneticAnomaly = null,
            pressureHpa = null, pressureDeltaPerMin = null
        ),
        self = SelfState(
            batteryPct = 0.8f, charging = false, batteryTempC = 30f, currentMa = null,
            voltageV = null, thermal = thermal, thermalHeadroom = null,
            memFreePct = 0.5f, memLow = false, storageFreePct = null,
            screenOn = false, net = "wifi", uptimeS = 1
        ),
        tempo = Tempo(
            localTime = "12:00", tzOffsetMin = 0, dayOfWeek = "Friday",
            partOfDay = "midday", solarElevationDeg = null, isDaylight = null,
            minutesToSunset = null, minutesSinceSunrise = null, dayLengthMin = null
        ),
        gps = null
    )
}
