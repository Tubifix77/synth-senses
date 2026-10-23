package net.synthsenses.senses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A device's broadcast name, as text and nothing else.
 *
 * Seen on the P20 on 2026-10-23: a headset advertising "smartSILENO free"
 * with a trailing NUL, carried into `radio.ble_named` and the narration.
 */
class RadioNameTest {

    @Test
    fun `padding a device put in its advertisement is dropped`() {
        assertEquals("smartSILENO free", radioName("smartSILENO free\u0000"))
        assertEquals("Ultra Slim Soundbar", radioName("Ultra Slim Soundbar\u0000\u0000\u0000"))
    }

    @Test
    fun `a name made only of padding is no name`() {
        assertNull(radioName("\u0000\u0000\u0000\u0000"))
        assertNull(radioName("   "))
        assertNull(radioName(""))
        assertNull(radioName(null))
    }

    @Test
    fun `ordinary names pass through, including non-Latin ones`() {
        assertEquals("LE-Bose QC45", radioName("LE-Bose QC45"))
        assertEquals("Aura_Fiber_64164", radioName("Aura_Fiber_64164"))
        assertEquals("Søren's ørestykker", radioName("Søren's ørestykker"))
    }
}
