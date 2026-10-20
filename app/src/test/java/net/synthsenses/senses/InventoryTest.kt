package net.synthsenses.senses

import android.hardware.Sensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The inventory a phone reports about itself.
 *
 * This exists because "does this phone have a barometer" has so far been
 * answerable only from a spec sheet, and this project has a rule against
 * trusting those. The app degrades silently on missing hardware — correct at
 * runtime, useless when the hardware is what you are trying to establish.
 *
 * Testable at all only because `inventoryJson` takes a lookup function rather
 * than a `SensorManager`, the same trick that keeps `Habituation` off
 * Robolectric.
 */
class InventoryTest {

    /** A phone with everything, naming each part the way real hardware does. */
    private val wellEquipped: (Int) -> String? = { "sensor-$it v1" }

    /** A phone with nothing at all. Legal, and must not throw. */
    private val barren: (Int) -> String? = { null }

    @Test
    fun `a fully equipped phone reports every sensor present, by name`() {
        val json = inventoryJson(wellEquipped)
        for (spec in BODY_SENSORS) {
            val entry = json.getJSONObject(spec.key)
            assertTrue("${spec.key} should be present", entry.getBoolean("present"))
            assertEquals("sensor-${spec.type} v1", entry.getString("name"))
        }
        assertTrue(missingSensors(wellEquipped).isEmpty())
    }

    @Test
    fun `a phone with no sensors reports them absent rather than failing`() {
        val json = inventoryJson(barren)
        for (spec in BODY_SENSORS) {
            val entry = json.getJSONObject(spec.key)
            assertFalse("${spec.key} should be absent", entry.getBoolean("present"))
            assertFalse(
                "an absent sensor must not carry a name",
                entry.has("name")
            )
        }
        assertEquals(BODY_SENSORS.map { it.key }, missingSensors(barren))
    }

    @Test
    fun `the barometer is reported absent without disturbing anything else`() {
        // The concrete prediction for the phone this will first run on: its
        // spec sheets list no barometer. If `status` says otherwise, the sheets
        // were wrong, which is the entire point of asking the phone.
        val noBarometer: (Int) -> String? =
            { if (it == Sensor.TYPE_PRESSURE) null else "sensor-$it v1" }
        val json = inventoryJson(noBarometer)
        assertFalse(json.getJSONObject("pressure").getBoolean("present"))
        assertTrue(json.getJSONObject("light").getBoolean("present"))
        assertEquals(listOf("pressure"), missingSensors(noBarometer))
    }

    @Test
    fun `every entry says what it feeds, so a reader need not know the code`() {
        val json = inventoryJson(barren)
        for (spec in BODY_SENSORS) {
            val feeds = json.getJSONObject(spec.key).getString("feeds")
            assertTrue("${spec.key} should say what it feeds", feeds.isNotEmpty())
        }
    }

    @Test
    fun `the sensor list has no duplicate keys or types`() {
        val keys = BODY_SENSORS.map { it.key }
        val types = BODY_SENSORS.map { it.type }
        assertEquals("duplicate key in BODY_SENSORS: $keys", keys.size, keys.distinct().size)
        assertEquals("duplicate type in BODY_SENSORS: $types", types.size, types.distinct().size)
    }

    @Test
    fun `the sensors the body sense actually reads are all listed`() {
        // BodySensor.start() iterates BODY_SENSORS, so registration cannot
        // drift from the inventory. This pins the other half: the types its
        // onSensorChanged branches on.
        val handled = listOf(
            Sensor.TYPE_LIGHT, Sensor.TYPE_PROXIMITY, Sensor.TYPE_PRESSURE,
            Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GRAVITY,
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_STEP_COUNTER
        )
        val listed = BODY_SENSORS.map { it.type }.toSet()
        for (t in handled) {
            assertTrue(
                "sensor type $t is read by BodySensor but missing from " +
                    "BODY_SENSORS, so status would call it absent",
                listed.contains(t)
            )
        }
    }
}
