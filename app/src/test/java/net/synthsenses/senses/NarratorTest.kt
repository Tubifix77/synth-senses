package net.synthsenses.senses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On-device prose.
 *
 * The narration is what a receiving AI reads when it does not want to parse the
 * structured blocks, so it has to be deterministic and it has to stay honest
 * about what the phone could not sense. A narrator that invents a detail is
 * worse than one that says nothing.
 */
class NarratorTest {

    @Test
    fun `the same percept always produces the same sentence`() {
        val p = Fixtures.full()
        val once = Narrator.describe(p)
        val again = Narrator.describe(p)
        val fresh = Narrator.describe(Fixtures.full())

        assertEquals(once, again)
        assertEquals("two identical percepts must narrate identically", once, fresh)
    }

    @Test
    fun `a known place is named, a new one is flagged as unfamiliar`() {
        val known = Narrator.describe(Fixtures.full(radio = Fixtures.radio(placeName = "desk")))
        assertTrue(known, known.startsWith("I am in desk."))

        val fresh = Narrator.describe(
            Fixtures.full(radio = Fixtures.radio(placeName = null, placeId = "p9", placeIsNew = true))
        )
        assertTrue(fresh, fresh.contains("not been before"))
        assertTrue("the id is worth quoting so the box can name it", fresh.contains("p9"))
    }

    @Test
    fun `what it can see and hear reaches the prose`() {
        val s = Narrator.describe(Fixtures.full())
        assertTrue(s, s.contains("I can see"))
        assertTrue(s, s.contains("room"))
        assertTrue(s, s.contains("one person"))
        assertTrue(s, s.contains("I hear speech"))
    }

    @Test
    fun `a transcript is quoted rather than paraphrased`() {
        val s = Narrator.describe(
            Fixtures.full(hearing = Fixtures.hearing(transcript = "put the kettle on"))
        )
        assertTrue(s, s.contains("\"put the kettle on\""))
    }

    @Test
    fun `text the camera read is quoted too`() {
        val s = Narrator.describe(Fixtures.full(vision = Fixtures.vision(text = "PLATFORM 9")))
        assertTrue(s, s.contains("Text reads \"PLATFORM 9\""))
    }

    @Test
    fun `posture and heading are described, and pocket suppresses the heading`() {
        val upright = Narrator.describe(
            Fixtures.full(body = Fixtures.body(posture = "upright", headingDeg = 90))
        )
        assertTrue(upright, upright.contains("held upright"))
        assertTrue("90 degrees is east", upright.contains("facing east"))

        val pocket = Narrator.describe(
            Fixtures.full(body = Fixtures.body(posture = "pocket", headingDeg = 90))
        )
        assertTrue(pocket, pocket.contains("pocket or a bag"))
        assertFalse("a phone in a pocket has no meaningful bearing to report",
            pocket.contains("facing"))
    }

    @Test
    fun `overheating is said plainly and early`() {
        val s = Narrator.describe(Fixtures.full(self = Fixtures.self(thermal = "SEVERE")))
        assertTrue(s, s.contains("overheating badly"))
    }

    @Test
    fun `a pressure spike is reported as a door or a lift, not as a number`() {
        val rising = Narrator.describe(
            Fixtures.full(body = Fixtures.body(pressureDeltaPerMin = 0.5f))
        )
        assertTrue(rising, rising.contains("Pressure is rising quickly"))

        val steady = Narrator.describe(
            Fixtures.full(body = Fixtures.body(pressureDeltaPerMin = 0.1f))
        )
        assertFalse("ordinary weather drift is not an event", steady.contains("Pressure is"))
    }

    @Test
    fun `a phone with no camera, mic or radio still says something true`() {
        val s = Narrator.describe(Fixtures.bare())

        assertTrue("it must still produce prose", s.isNotBlank())
        assertTrue("it still knows how it is being held", s.contains("I am still"))
        assertTrue("and what time it is", s.contains("deep night"))
        assertTrue("and that it cannot reach you", s.contains("no network"))

        assertFalse("it must not invent a view", s.contains("I can see"))
        assertFalse("nor a sound", s.contains("I hear"))
        assertFalse("nor a place", s.contains("I am in"))
    }

    @Test
    fun `narration is a single block of sentences, not a fragment`() {
        val s = Narrator.describe(Fixtures.full())
        assertTrue("should end in a full stop: $s", s.trimEnd().endsWith("."))
        assertFalse("no double spaces", s.contains("  "))
        assertFalse("no stray newlines on the wire", s.contains("\n"))
    }
}
