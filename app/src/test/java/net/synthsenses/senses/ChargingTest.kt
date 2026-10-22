package net.synthsenses.senses

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `self.charging` is "being fed", not "the OS thinks the battery is filling".
 *
 * The measured case: a Huawei P20 Lite on a wall charger all night held itself
 * at 81-82% in a regular top-up cycle, and `BatteryManager.isCharging` followed
 * the cycle, so the phone reported `charging: false` for 182 of 299 samples
 * taken while plugged in.
 */
class ChargingTest {

    // The plug values Android reports: 0 is on battery, AC is 1, USB 2,
    // wireless 4. -1 stands for the extra being missing altogether.
    private val onBattery = 0
    private val ac = 1
    private val usb = 2
    private val wireless = 4
    private val missing = -1

    @Test
    fun `plugged in but holding its charge still counts as charging`() {
        // The overnight case: on the charger, battery protection has paused
        // the top-up, and isCharging says false.
        assertEquals(true, onExternalPower(ac, isCharging = false))
    }

    @Test
    fun `every power source counts`() {
        for (source in listOf(ac, usb, wireless)) {
            assertEquals("plugged=$source", true, onExternalPower(source, isCharging = false))
        }
    }

    @Test
    fun `on battery is not charging, whatever isCharging claims`() {
        assertEquals(false, onExternalPower(onBattery, isCharging = true))
        assertEquals(false, onExternalPower(onBattery, isCharging = false))
    }

    @Test
    fun `without the extra, the old answer is the only one there is`() {
        assertEquals(true, onExternalPower(missing, isCharging = true))
        assertEquals(false, onExternalPower(missing, isCharging = false))
    }
}
