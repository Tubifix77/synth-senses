package net.synthsenses.senses

import android.hardware.Sensor
import org.json.JSONObject

/**
 * What this particular phone actually has.
 *
 * The app already degrades rather than fabricates, so a missing sensor costs
 * nothing at runtime — it just yields `null` forever. The problem is that it
 * does so *silently*. The only trace is a `Log.d` per absent sensor, and
 * reading logcat needs a cable and an Android SDK, which is exactly what this
 * project does not have. So "does this phone have a barometer" has until now
 * been answerable only from a spec sheet, which is the kind of source this repo
 * has a rule against trusting.
 *
 * This makes the question answerable over the wire instead: `status` reports
 * what was found, by name, straight from `SensorManager`. The hardware evening
 * should begin by typing `status` and reading the answer rather than by
 * assuming the sheet was right.
 *
 * It matters more now that the target is a modern phone. The design brief is to
 * use whatever a 2025-era device offers and degrade on an old one, and that is
 * only checkable if the app will say which it found.
 */

/** One sensor the body sense would use, and why it bothers. */
internal data class SensorSpec(val key: String, val type: Int, val feeds: String)

/**
 * Every sensor `BodySensor.start()` registers, in the same order.
 *
 * Keep this in step with that method: a sensor registered but not listed here
 * is one the inventory will claim is absent, which is a worse failure than not
 * reporting it at all. `InventoryTest` holds the two together.
 */
internal val BODY_SENSORS = listOf(
    SensorSpec("light", Sensor.TYPE_LIGHT, "body.lux, and the dark half of pocket"),
    SensorSpec("proximity", Sensor.TYPE_PROXIMITY, "body.covered, and posture=pocket"),
    SensorSpec("pressure", Sensor.TYPE_PRESSURE, "body.pressure_hpa and its drift"),
    SensorSpec("magnetometer", Sensor.TYPE_MAGNETIC_FIELD, "body.magnetic_ut and anomaly"),
    SensorSpec("accelerometer", Sensor.TYPE_ACCELEROMETER, "body.accel_rms, so motion"),
    SensorSpec("gyroscope", Sensor.TYPE_GYROSCOPE, "body.gyro_rms, so motion"),
    SensorSpec("gravity", Sensor.TYPE_GRAVITY, "body.posture"),
    SensorSpec("rotation_vector", Sensor.TYPE_ROTATION_VECTOR, "body.heading_deg"),
    SensorSpec("step_counter", Sensor.TYPE_STEP_COUNTER, "body.steps and steps_delta")
)

/**
 * The inventory as JSON, given a way to look a sensor up by type.
 *
 * Takes the lookup as a function rather than a `SensorManager` for the same
 * reason `Habituation` takes a `File`: it is the only way to test this without
 * an emulator. A present sensor reports the hardware's own name — "LSM6DSO
 * Accelerometer" says more about what is really in the phone than `true` does.
 */
internal fun inventoryJson(name: (Int) -> String?): JSONObject = JSONObject().apply {
    for (s in BODY_SENSORS) {
        put(s.key, JSONObject().apply {
            val found = name(s.type)
            put("present", found != null)
            if (found != null) put("name", found)
            put("feeds", s.feeds)
        })
    }
}

/** The keys of everything the lookup says is missing, for a one-line summary. */
internal fun missingSensors(name: (Int) -> String?): List<String> =
    BODY_SENSORS.filter { name(it.type) == null }.map { it.key }
