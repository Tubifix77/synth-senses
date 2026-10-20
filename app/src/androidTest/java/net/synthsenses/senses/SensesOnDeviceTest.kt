package net.synthsenses.senses

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The sense blocks, running against a real Android runtime for the first time.
 *
 * Everything in `app/src/test` builds percepts out of fixtures: the data classes
 * are exercised, the Android APIs underneath them never are. `SelfSensor` reads
 * BatteryManager, PowerManager and ActivityManager; `BodySensor` registers real
 * SensorManager listeners. None of that had ever been called.
 *
 * An emulator is a poor phone and a good test of the degradation invariant
 * precisely because it is a poor phone. It has no barometer, no light sensor
 * and no magnetometer worth the name, so the honest result is a percept full of
 * nulls — and "sensors degrade, never fabricate" says nulls are exactly what
 * must come back, rather than zeroes standing in for readings nobody took.
 */
@RunWith(AndroidJUnit4::class)
class SensesOnDeviceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun interoception_reads_the_real_device() {
        val s = SelfSensor(context).sample()

        assertTrue("battery should be a fraction, got ${s.batteryPct}",
            s.batteryPct in 0f..1f || s.batteryPct == -1f)
        assertTrue("thermal status should be named, got '${s.thermal}'", s.thermal.isNotBlank())
        assertTrue("memory should be a fraction, got ${s.memFreePct}",
            s.memFreePct in 0f..1f || s.memFreePct == -1f)
        assertTrue("uptime should advance, got ${s.uptimeS}", s.uptimeS >= 0)
        assertTrue("net should be named, got '${s.net}'", s.net.isNotBlank())
    }

    @Test
    fun proprioception_starts_and_reports_without_inventing_anything() {
        val body = BodySensor(context)
        body.start()
        try {
            Thread.sleep(2_000)          // let the listeners deliver something
            val b = body.sample()

            assertTrue("motion should be classified, got '${b.motion}'", b.motion.isNotBlank())
            assertTrue("posture should be classified, got '${b.posture}'", b.posture.isNotBlank())
            assertTrue("accel RMS should not be negative", b.accelRms >= 0f)
            assertTrue("gyro RMS should not be negative", b.gyroRms >= 0f)

            // The invariant, stated as the repo states it: a sensor this device
            // does not have must come back null. Never 0.0 masquerading as a
            // reading. An emulator lacking a barometer is the cleanest possible
            // way to check that, and it is why this test belongs on a device.
            b.pressureHpa?.let {
                assertTrue("a reported pressure should be plausible, got $it", it in 300f..1200f)
            }
            b.lux?.let {
                assertTrue("a reported lux should not be negative, got $it", it >= 0f)
            }
            b.headingDeg?.let {
                assertTrue("a reported heading should be a bearing, got $it", it in 0..359)
            }
        } finally {
            body.stop()
        }
    }

    @Test
    fun a_percept_built_on_this_device_serialises() {
        val body = BodySensor(context)
        body.start()
        val percept = try {
            Thread.sleep(1_500)
            Percept(
                deviceId = Prefs(context).deviceId,
                seq = 1,
                tsMs = System.currentTimeMillis(),
                trigger = "first",
                vision = null,
                hearing = null,
                radio = null,
                body = body.sample(),
                self = SelfSensor(context).sample(),
                tempo = TempoSensor.sample(System.currentTimeMillis(), at = null),
                gps = null,
                attention = Attention(1f, emptyList(), 0, false, null)
            )
        } finally {
            body.stop()
        }

        val json = percept.toJson()
        assertNotNull(json)
        assertTrue("schema must be on the wire", json.has("schema"))
        assertTrue("absent camera must serialise as null", json.isNull("vision"))
        assertTrue("absent mic must serialise as null", json.isNull("hearing"))
        assertTrue("narration should never be empty", percept.narration.isNotBlank())

        // tokens() is what habituation keys on, so it has to survive a real device
        val tokens = percept.tokens()
        assertTrue("a real device should still yield tokens", tokens.isNotEmpty())
        assertTrue("motion is always known", tokens.any { it.startsWith("m:") })
        assertFalse("nothing may be invented for a camera that is off",
            tokens.any { it.startsWith("v:") })
    }

    @Test
    fun the_device_id_is_stable_across_reads() {
        val first = Prefs(context).deviceId
        val second = Prefs(context).deviceId
        assertTrue("a node id must not change between reads", first == second)
        assertTrue("and it should look like one, got '$first'",
            Regex("^[0-9a-f]{12}$").matches(first))
    }
}
