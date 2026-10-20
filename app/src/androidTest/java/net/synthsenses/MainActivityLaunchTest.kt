package net.synthsenses

import android.Manifest
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
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
     * system dialog in front of the test. These are exactly the permissions
     * `askPermissions()` requests with the default sense settings; location is
     * absent because wifi, cell and location all default to off.
     */
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_SCAN)
            if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
        }.toTypedArray()
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
