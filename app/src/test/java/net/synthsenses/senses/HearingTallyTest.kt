package net.synthsenses.senses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tally of everything heard between two percepts.
 *
 * The measured case: an evening with the TV on, sent as a heartbeat every two
 * minutes, each percept carrying one one-second window. The companion reading
 * it was told "quiet" or "other" nearly every time, while the classifier's top
 * sound across the same samples was Speech 18 of 60 and Music 4.
 */
class HearingTallyTest {

    private fun heard(name: String?, level: Float = -48f, classifier: String? = "ok") =
        Fixtures.hearing(
            levelDb = level,
            events = if (name == null) emptyList() else listOf(Scored(name, 0.6f)),
            classifier = classifier
        )

    @Test
    fun `nothing sampled is nothing to say`() {
        assertNull(HearingTally().summary())
    }

    @Test
    fun `the TV evening's evidence survives to the percept`() {
        val t = HearingTally()
        repeat(18) { t.add(heard("Speech")) }
        repeat(16) { t.add(heard(null)) }
        repeat(7) { t.add(heard("Silence")) }
        repeat(5) { t.add(heard("Child speech, kid speaking")) }
        repeat(4) { t.add(heard("Music")) }
        val r = t.summary()!!
        assertEquals(50, r.samples)
        assertEquals(50, r.classified)
        assertEquals("a sample with no event is counted, not discarded", 16, r.unnamed)
        assertEquals(18, r.top["Speech"])
        assertEquals("ordered by how often it was the top sound",
            "Speech", r.top.keys.first())
    }

    @Test
    fun `the most confident event is the sample's vote`() {
        val t = HearingTally()
        t.add(Fixtures.hearing(events = listOf(Scored("Music", 0.4f), Scored("Speech", 0.7f))))
        assertEquals(mapOf("Speech" to 1), t.summary()!!.top)
    }

    @Test
    fun `a sample with no working classifier is not counted as heard`() {
        val t = HearingTally()
        t.add(heard(null, classifier = "absent"))
        t.add(heard(null, classifier = "failed"))
        t.add(heard("Speech"))
        val r = t.summary()!!
        assertEquals("level still counts", 3, r.samples)
        assertEquals("but only one sample was actually classified", 1, r.classified)
        assertEquals("and an empty list from a missing model is not silence", 0, r.unnamed)
    }

    @Test
    fun `loudness is averaged as energy, not as decibels`() {
        val t = HearingTally()
        t.add(heard("Speech", level = -40f))
        t.add(heard("Silence", level = -60f))
        val mean = t.summary()!!.levelDbMean
        // The arithmetic mean of the logarithms would say -50.
        assertEquals(-43.0f, mean, 0.05f)
    }

    @Test
    fun `reset starts the next interval empty`() {
        val t = HearingTally()
        repeat(5) { t.add(heard("Speech")) }
        t.reset()
        assertNull(t.summary())
        t.add(heard("Music"))
        assertEquals(mapOf("Music" to 1), t.summary()!!.top)
    }

    @Test
    fun `a long interval cannot grow the tally without bound`() {
        val t = HearingTally()
        repeat(40) { i -> t.add(heard("sound $i")) }
        assertTrue(t.summary()!!.top.size <= HearingTally.MAX_NAMES)
    }
}
