package net.synthsenses.senses

import android.Manifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The permission set, at every Android version this app supports.
 *
 * The first test here is a regression guard for a bug that broke a whole sense
 * without breaking anything: on Android 10 and 11 a BLE scan needs fine
 * location, the app never asked for it under stock settings, and so the scan
 * returned nothing, the place fingerprint was never non-empty, and
 * `PlaceMemory` never saw a single call. Place learning — redesigned three
 * times and validated across 2000 seeds — could not have worked at all on such
 * a phone, and no test anywhere would have gone red.
 *
 * minSdk is 28, so API 28, 29 and 30 are not hypothetical corners. They are
 * the floor this app claims to support.
 */
class PermissionsTest {

    private val defaults = Triple(true, false, false)   // ble on, wifi off, cell off

    private fun perms(sdk: Int, ble: Boolean = true, wifi: Boolean = false,
                      cell: Boolean = false, location: Boolean = false) =
        requiredPermissions(sdk, ble, wifi, cell, location)

    @Test
    fun `below 31 a BLE scan asks for fine location, or it scans nothing`() {
        for (sdk in listOf(28, 29, 30)) {
            assertTrue(
                "API $sdk with BLE on must request fine location: without it the " +
                    "scan is empty and no place is ever learned",
                perms(sdk, ble = true).contains(Manifest.permission.ACCESS_FINE_LOCATION)
            )
            assertFalse(
                "BLUETOOTH_SCAN does not exist at API $sdk",
                perms(sdk, ble = true).contains(Manifest.permission.BLUETOOTH_SCAN)
            )
        }
    }

    @Test
    fun `from 31 BLE costs no location permission at all`() {
        for (sdk in listOf(31, 33, 34, 36)) {
            val p = perms(sdk, ble = true)
            assertTrue("API $sdk should use BLUETOOTH_SCAN",
                p.contains(Manifest.permission.BLUETOOTH_SCAN))
            assertFalse(
                "API $sdk declares neverForLocation, so BLE alone must not drag " +
                    "fine location in",
                p.contains(Manifest.permission.ACCESS_FINE_LOCATION)
            )
        }
    }

    @Test
    fun `with every radio off, nothing asks for location`() {
        for (sdk in listOf(28, 29, 30, 31, 34, 36)) {
            assertFalse(
                "API $sdk with no radios should not ask for location",
                perms(sdk, ble = false).contains(Manifest.permission.ACCESS_FINE_LOCATION)
            )
        }
    }

    @Test
    fun `wifi or cell or gps asks for location at any version`() {
        for (sdk in listOf(29, 31, 36)) {
            assertTrue(perms(sdk, ble = false, wifi = true)
                .contains(Manifest.permission.ACCESS_FINE_LOCATION))
            assertTrue(perms(sdk, ble = false, cell = true)
                .contains(Manifest.permission.ACCESS_FINE_LOCATION))
            assertTrue(perms(sdk, ble = false, location = true)
                .contains(Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    @Test
    fun `the eyes and ears are always asked for`() {
        for (sdk in listOf(29, 31, 33, 36)) {
            val p = perms(sdk)
            assertTrue(p.contains(Manifest.permission.CAMERA))
            assertTrue(p.contains(Manifest.permission.RECORD_AUDIO))
        }
    }

    @Test
    fun `notifications are only asked for where they are a runtime permission`() {
        assertFalse(perms(29).contains(Manifest.permission.POST_NOTIFICATIONS))
        assertFalse(perms(32).contains(Manifest.permission.POST_NOTIFICATIONS))
        assertTrue(perms(33).contains(Manifest.permission.POST_NOTIFICATIONS))
        assertTrue(perms(36).contains(Manifest.permission.POST_NOTIFICATIONS))
    }

    @Test
    fun `the step counter permission is asked for only where it exists`() {
        for (sdk in listOf(29, 31, 36)) {
            assertTrue("API $sdk should request activity recognition",
                perms(sdk).contains(Manifest.permission.ACTIVITY_RECOGNITION))
        }
        // ACTIVITY_RECOGNITION is new in API 29. At 28 the step counter needs
        // no runtime permission at all, so asking would be asking for
        // something that does not exist.
        assertFalse(perms(28).contains(Manifest.permission.ACTIVITY_RECOGNITION))
    }

    @Test
    fun `nothing is requested twice`() {
        for (sdk in listOf(28, 29, 30, 31, 33, 36)) {
            val p = perms(sdk, ble = true, wifi = true, cell = true, location = true)
            assertTrue("API $sdk produced duplicates: $p", p.size == p.distinct().size)
        }
    }

    @Test
    fun `the panel can warn before the dialog appears, and agrees with the request`() {
        // The warning and the request must not disagree, or the user is told one
        // thing and asked another.
        for (sdk in listOf(28, 29, 30, 31, 34)) {
            for (ble in listOf(true, false)) {
                for (wifi in listOf(true, false)) {
                    val warns = willAskForLocation(sdk, ble, wifi, false, false)
                    val asks = requiredPermissions(sdk, ble, wifi, false, false)
                        .contains(Manifest.permission.ACCESS_FINE_LOCATION)
                    assertTrue(
                        "sdk=$sdk ble=$ble wifi=$wifi: warning said $warns, request said $asks",
                        warns == asks
                    )
                }
            }
        }
    }

    @Test
    fun `a default install at the minSdk floor asks for location`() {
        // Defaults are vision, hearing and BLE on; wifi, cell and GPS off. This
        // is the exact configuration that silently learned no places.
        //
        // It is worth being accurate about who this protects. The phone this
        // will first run on is an Android 14 device, which takes the API 31+
        // path and was never affected. This guards every phone between minSdk
        // 29 and API 30 — the range the project still claims to support, and
        // the only range where the bug could bite.
        val (ble, wifi, cell) = defaults
        assertTrue(
            requiredPermissions(28, ble, wifi, cell, location = false)
                .contains(Manifest.permission.ACCESS_FINE_LOCATION)
        )
    }
}
