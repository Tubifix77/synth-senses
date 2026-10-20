package net.synthsenses

import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import net.synthsenses.senses.requiredPermissions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app opens.
 *
 * That sentence had never been verified. Until this test existed there were no
 * instrumented tests at all, CI only compiled and ran JVM tests, and so
 * `MainActivity.onCreate` had never executed anywhere — not on a phone, not on
 * an emulator, not once. "Untested on hardware" was the documented status; the
 * truer statement was that the thing had never started.
 *
 * An emulator cannot measure anything on the roadmap's hardware list. It can
 * answer this, which nothing else did, and answering it cheaply is what stops
 * a scarce session with a real phone being spent on a crash at launch.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityLaunchTest {

    /**
     * Granted up front so the launcher in `onCreate` returns without putting a
     * system dialog in front of the test. An ungranted permission leaves the
     * activity at STARTED behind the dialog, and the failure reads as "never
     * becomes RESUMED" — which looks like a crash and is not one.
     *
     * Derived from [requiredPermissions] rather than restated, because a
     * restated copy drifts. This one did: it was written to mirror the old
     * rule and said "location is absent because wifi, cell and location all
     * default to off", which stopped being true when a BLE scan below API 31
     * started requiring fine location. The copy was wrong for API 28, 29 and
     * 30 from that moment, and nothing noticed until the emulator matrix
     * gained an API 28 entry, because the only level ever run was 35 — where
     * BLE takes the `BLUETOOTH_SCAN` path and needs no location at all.
     *
     * Every switch is passed as on, so this grants the superset the build
     * could ask for at this API level. Granting more than the defaults need
     * costs the test nothing and means no dialog can appear whatever the
     * stored preferences happen to be.
     */
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *requiredPermissions(
            sdkInt = Build.VERSION.SDK_INT,
            ble = true, wifi = true, cell = true, location = true
        ).toTypedArray()
    )

    @Test
    fun the_control_panel_opens_and_stays_open() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(
                "MainActivity should reach RESUMED",
                Lifecycle.State.RESUMED, scenario.state
            )
        }
    }

    @Test
    fun it_survives_a_configuration_change() {
        // Compose state plus a Prefs-backed form is the classic place a rotation
        // takes an app down, and nothing had ever rotated this one.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
