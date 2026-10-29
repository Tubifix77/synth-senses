package net.synthsenses.senses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A WiFi scan that lists nothing while the phone is connected is blind.
 *
 * From 2026-10-24 the P20 sat on its home network for five days with every
 * scan empty, and place learning, left with BLE alone, minted a new place
 * every few samples on a phone that never moved.
 */
class WifiBlindTest {

    @Test
    fun `connected but seeing no network is a blind scan`() {
        assertTrue(wifiScanBlind(wantWifi = true, apCount = 0, connectedSsid = "Aura_Fiber_64164"))
    }

    @Test
    fun `a scan that sees networks is believed`() {
        assertFalse(wifiScanBlind(wantWifi = true, apCount = 16, connectedSsid = "Aura_Fiber_64164"))
    }

    @Test
    fun `not connected, an empty scan can be a real empty street`() {
        assertFalse(wifiScanBlind(wantWifi = true, apCount = 0, connectedSsid = null))
    }

    @Test
    fun `wifi switched off in the app is not a blind scan, just no scan`() {
        assertFalse(wifiScanBlind(wantWifi = false, apCount = 0, connectedSsid = "Aura_Fiber_64164"))
    }
}
