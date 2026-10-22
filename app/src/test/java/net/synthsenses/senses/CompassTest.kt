package net.synthsenses.senses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a heading is a compass bearing and when it is pointing at a magnet.
 *
 * The measured case: the P20 rests by its charger in a 351 uT field, about
 * seven times Earth's. For 802 of its 901 samples the published heading came
 * from a magnetometer dominated by that local source, and the narration said
 * "facing south-west" 579 times. In the user's hand the same phone read 43 uT,
 * and the Xiaomi reads 48.
 */
class CompassTest {

    @Test
    fun `the charger spot is not Earth's field`() {
        // The overnight range, 347-352, and the highest reading, 464.
        for (ut in listOf(347.9f, 351f, 352.3f, 464f)) {
            assertFalse("$ut uT must not yield a bearing", compassUsable(ut))
        }
    }

    @Test
    fun `ordinary readings anywhere on Earth are`() {
        // The P20 in a hand, the Xiaomi's median, and both ends of Earth's
        // own 22-67 uT range.
        for (ut in listOf(22f, 43f, 48f, 52f, 67f)) {
            assertTrue("$ut uT is Earth's field", compassUsable(ut))
        }
    }

    @Test
    fun `the band's edges`() {
        assertTrue(compassUsable(EARTH_FIELD_MIN_UT))
        assertTrue(compassUsable(EARTH_FIELD_MAX_UT))
        assertFalse("shielded, inside metal", compassUsable(5f))
        assertFalse("the Xiaomi's one stray reading", compassUsable(79f))
    }

    @Test
    fun `with no magnetometer reading there is nothing to check`() {
        assertTrue(compassUsable(null))
    }
}
