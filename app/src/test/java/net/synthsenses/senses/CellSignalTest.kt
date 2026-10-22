package net.synthsenses.senses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A cell signal that is a measurement, against one that is a placeholder.
 *
 * The measured case: 124 of the Xiaomi's 323 cell readings were exactly
 * -44 dBm, on the same two cells that otherwise read -102 to -112. A phone
 * lying still does not swing 60 dB between samples. -44 is the top of the
 * LTE RSRP range, and that modem returns it when it has no measurement. The
 * P20's modem never did: zero readings at -44 out of 896.
 */
class CellSignalTest {

    @Test
    fun `the top of the LTE range is a placeholder, not a signal`() {
        assertNull(cellDbm("lte", -44))
        assertNull(cellDbm("nr", -44))
        assertNull("above the ceiling is not a reading either", cellDbm("lte", -30))
    }

    @Test
    fun `real readings pass through`() {
        // The Xiaomi's own readings of those same cells, and the P20's range.
        for (v in listOf(-102, -104, -111, -112, -97, -109)) {
            assertEquals(v, cellDbm("lte", v))
        }
        assertEquals(-45, cellDbm("lte", -45))
    }

    @Test
    fun `the documented unavailable sentinel never reaches the wire`() {
        // CellInfo.UNAVAILABLE. Unfiltered, it would publish 2147483647 dBm.
        for (kind in listOf("lte", "nr", "wcdma", "gsm")) {
            assertNull(kind, cellDbm(kind, Int.MAX_VALUE))
        }
    }

    @Test
    fun `other radio types are left alone until measured`() {
        // Their ranges differ and no placeholder has been observed for them, so
        // nothing is assumed: only the documented sentinel is removed.
        assertEquals(-51, cellDbm("gsm", -51))
        assertEquals(-24, cellDbm("wcdma", -24))
    }
}
