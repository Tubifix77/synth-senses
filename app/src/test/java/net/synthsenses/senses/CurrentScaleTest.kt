package net.synthsenses.senses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Battery current, against the two unit conventions real phones use.
 *
 * The measured case: a Huawei P20 Lite on a wall charger reported raw values
 * around −640 and +1010, which divided by 1000 became −0.64 and +1.01 mA —
 * a phone drawing half a milliamp while charging. The number was wrong by
 * three orders of magnitude and looked perfectly reasonable, which is the
 * combination worth a regression test.
 */
class CurrentScaleTest {

    @Test
    fun `a microamp device is recognised from one large reading`() {
        val s = CurrentScale()
        // 300 mA discharging, as microamps.
        assertEquals(-300f, s.milliamps(-300_000)!!, 1e-3f)
        assertEquals("microamp", s.unit)
        assertTrue(s.settled)
        // And keeps converting from then on, including small ones.
        assertEquals(-0.5f, s.milliamps(-500)!!, 1e-3f)
    }

    @Test
    fun `the Huawei's milliamps are believed once they stay small`() {
        val s = CurrentScale()
        // The real overnight range. None of it can be microamps from a phone
        // on a charger, but it takes a few samples to be sure of that.
        repeat(CurrentScale.MIN_SAMPLES - 1) { assertNull(s.milliamps(1010)) }
        assertNull("not settled until the evidence is in", s.unit)

        val mA = s.milliamps(1010)
        assertEquals("milliamp", s.unit)
        assertEquals("reported as given, not divided", 1010f, mA!!, 1e-3f)
        assertEquals(-640f, s.milliamps(-640)!!, 1e-3f)
    }

    @Test
    fun `null while undetermined, rather than a number that may be 1000x wrong`() {
        val s = CurrentScale()
        assertNull("the first reading cannot settle the milliamp case", s.milliamps(640))
        assertTrue(!s.settled)
    }

    @Test
    fun `an unsupported property is not evidence of anything`() {
        val s = CurrentScale()
        repeat(100) { assertNull(s.milliamps(Int.MIN_VALUE)) }
        repeat(100) { assertNull(s.milliamps(0)) }
        assertTrue("neither sentinel counts as a sample", !s.settled)
        assertNull(s.unit)
    }

    @Test
    fun `a device that starts idle and then draws properly is still microamps`() {
        val s = CurrentScale()
        // 5 mA in microamps looks small, and would be mistaken for milliamps
        // if a single reading decided it. Nineteen of them still must not.
        repeat(CurrentScale.MIN_SAMPLES - 1) { s.milliamps(5_000) }
        assertNull("still undecided", s.unit)
        // Then the phone wakes up and draws 400 mA.
        assertEquals(-400f, s.milliamps(-400_000)!!, 1e-3f)
        assertEquals("one honest reading settles it the right way", "microamp", s.unit)
    }

    @Test
    fun `the floor is far above anything a milliamp device can report`() {
        // 20 mA in microamps. A milliamp device would have to draw 20 amps to
        // reach it, so the two ranges cannot be confused.
        assertEquals(20_000, CurrentScale.MICRO_FLOOR)
        val s = CurrentScale()
        repeat(CurrentScale.MIN_SAMPLES) { s.milliamps(3_000) }
        assertEquals("3 A is a lot, and still not 20 A", "milliamp", s.unit)
        assertEquals(3_000f, s.milliamps(3_000)!!, 1e-3f)
    }
}
