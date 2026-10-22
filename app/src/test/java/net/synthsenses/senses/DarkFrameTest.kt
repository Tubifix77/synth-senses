package net.synthsenses.senses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a frame is too dark to show anything.
 *
 * The measured case: all night the P20's camera faced a dark ceiling at
 * brightness 0.00, and the ML Kit labeller returned "Sky, Monochrome, Space"
 * for every one of 749 such frames. The narration then said "I can see sky,
 * monochrome, space" in a pitch-dark room. Below 0.10 those three words are
 * the labeller's answer to any black image, on both phones; above 0.20 its
 * labels vary like a real room.
 */
class DarkFrameTest {

    @Test
    fun `a black frame shows nothing`() {
        assertTrue(tooDarkToSee(0f))
    }

    @Test
    fun `the band where the labeller only ever answered sky monochrome space`() {
        // 0.00: 749 of 749 frames. Below 0.05: 248 of 248. Below 0.10: Sky on
        // 116 of 116.
        for (b in listOf(0f, 0.01f, 0.049f, 0.05f, 0.09f, 0.099f)) {
            assertTrue("brightness $b must count as too dark", tooDarkToSee(b))
        }
    }

    @Test
    fun `anything at or above the cut is looked at`() {
        // Deliberately conservative: 0.10-0.20 was mostly the black-frame
        // signature too, but not entirely, and hiding a real scene is worse
        // than publishing a doubtful label.
        for (b in listOf(0.10f, 0.15f, 0.20f, 0.5f, 1f)) {
            assertFalse("brightness $b must still be labelled", tooDarkToSee(b))
        }
    }
}
