package net.synthsenses.senses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * The wire schema and the stimulus vocabulary.
 *
 * Two of this project's stated invariants live in this one file and neither had
 * a test:
 *
 *  - "Continuous quantities are bucketed in `Percept.tokens()`. A room at 310
 *    lux and the same room at 340 lux must produce the same token, or nothing
 *    ever habituates." Habituation is keyed entirely on these strings, so a
 *    token that tracks a raw sensor reading would silently defeat the whole
 *    attention model — no test would fail, the app would just never go quiet.
 *  - "Sensors degrade, never fabricate." A receiver has to be able to tell
 *    "no barometer" from "1013 hPa", which means absent blocks must serialise
 *    as explicit nulls rather than vanish or default.
 */
class PerceptTest {

    // ---- bucketing: the invariant habituation depends on ----

    @Test
    fun `the same room at a slightly different brightness is the same stimulus`() {
        val dim = Fixtures.full(body = Fixtures.body(lux = 310f)).tokens()
        val brighter = Fixtures.full(body = Fixtures.body(lux = 340f)).tokens()

        assertEquals("310 lux and 340 lux must be one token", dim, brighter)
        assertTrue("and it should be the indoor bucket", dim.contains("lx:indoor"))
    }

    @Test
    fun `lux buckets break where the names say they do, and nowhere else`() {
        fun bucket(lux: Float) =
            Fixtures.full(body = Fixtures.body(lux = lux)).tokens().first { it.startsWith("lx:") }

        assertEquals("lx:dark", bucket(0f))
        assertEquals("lx:dark", bucket(4.9f))
        assertEquals("lx:dim", bucket(5f))
        assertEquals("lx:dim", bucket(59f))
        assertEquals("lx:indoor", bucket(60f))
        assertEquals("lx:indoor", bucket(399f))
        assertEquals("lx:bright", bucket(400f))
        assertEquals("lx:bright", bucket(4_999f))
        assertEquals("lx:sunlit", bucket(5_000f))
        assertEquals("lx:sunlit", bucket(120_000f))
    }

    @Test
    fun `loudness is bucketed too, so ordinary room noise does not churn`() {
        fun db(level: Float) =
            Fixtures.full(hearing = Fixtures.hearing(levelDb = level))
                .tokens().first { it.startsWith("db:") }

        assertEquals("small drift must not produce a new token", db(-44f), db(-40f))
        assertTrue("a big change must", db(-40f) != db(-20f))
    }

    @Test
    fun `object counts are bucketed, not counted`() {
        fun oc(n: Int) = Fixtures.full(
            vision = Fixtures.vision(
                objects = (0 until n).map { Seen("Person", listOf(0f, 0f, 1f, 1f), it) })
        ).tokens().first { it.startsWith("oc:") }

        assertEquals("oc:0", oc(0))
        assertEquals("oc:1", oc(1))
        assertEquals(oc(2), oc(3))
        assertEquals(oc(4), oc(8))
        assertEquals(oc(9), oc(20))
        assertEquals("oc:20+", oc(21))
        assertTrue("the buckets must actually differ", oc(3) != oc(4))
    }

    @Test
    fun `a percept with nothing but a body still yields usable tokens`() {
        val t = Fixtures.bare().tokens()
        assertTrue("motion is always known", t.any { it.startsWith("m:") })
        assertTrue("posture is always known", t.any { it.startsWith("po:") })
        assertTrue("thermal is always known", t.any { it.startsWith("th:") })
        assertTrue("part of day is always known", t.any { it.startsWith("tod:") })
        assertFalse("but nothing may be invented for a missing light sensor",
            t.any { it.startsWith("lx:") })
        assertFalse("nor for a camera that isn't there", t.any { it.startsWith("v:") })
        assertFalse("nor for a mic", t.any { it.startsWith("s:") })
    }

    @Test
    fun `a magnetic anomaly only registers once it is actually anomalous`() {
        fun has(anomaly: Float?) =
            Fixtures.full(body = Fixtures.body(magneticAnomaly = anomaly))
                .tokens().contains("mag:anomaly")

        assertFalse(has(null))
        assertFalse(has(5f))
        assertFalse(has(15f))
        assertTrue(has(15.1f))
        assertTrue(has(60f))
    }

    @Test
    fun `a flat battery is a stimulus in its own right`() {
        assertFalse(Fixtures.full(self = Fixtures.self(batteryPct = 0.5f)).tokens().contains("bat:low"))
        assertTrue(Fixtures.full(self = Fixtures.self(batteryPct = 0.10f)).tokens().contains("bat:low"))
    }

    // ---- the wire format ----

    @Test
    fun `absent senses serialise as explicit nulls, never as absent keys`() {
        val o = Fixtures.bare().toJson()

        for (block in listOf("vision", "hearing", "radio", "gps", "attention")) {
            assertTrue("$block must be present as a key", o.has(block))
            assertTrue("$block must be null, not missing or faked", o.isNull(block))
        }
        // and within a block, a sensor the phone lacks is null rather than zero
        val body = o.getJSONObject("body")
        for (field in listOf("lux", "pressure_hpa", "magnetic_ut", "heading_deg", "steps")) {
            assertTrue("body.$field must exist", body.has(field))
            assertTrue("body.$field must be null when unmeasured", body.isNull(field))
        }
        assertEquals("a receiver must still be told the motion state", "still",
            body.getString("motion"))
    }

    @Test
    fun `a full percept carries every block the protocol documents`() {
        val o = Fixtures.full().toJson()
        for (key in listOf("schema", "device_id", "seq", "ts", "trigger", "attention",
                           "vision", "hearing", "radio", "body", "self", "tempo", "gps",
                           "narration")) {
            assertTrue("missing top-level key: $key", o.has(key))
        }
        assertEquals(SCHEMA_VERSION, o.getInt("schema"))
        assertEquals("testnode", o.getString("device_id"))
        assertEquals(42, o.getInt("seq"))
        assertTrue("narration must not be empty", o.getString("narration").isNotBlank())
    }

    @Test
    fun `the timestamp is ISO-8601 in UTC, because receivers parse it`() {
        val ts = Fixtures.full().toJson().getString("ts")
        assertTrue("unexpected timestamp format: $ts",
            Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$""").matches(ts))
        assertEquals("2020-09-13T12:26:40.000Z", ts)
    }

    @Test
    fun `floats on the wire are rounded, not dumped at full precision`() {
        val o = Fixtures.full(
            vision = Fixtures.vision(brightness = 0.123456f)
        ).toJson()
        assertEquals(0.12, o.getJSONObject("vision").getDouble("brightness"), 1e-9)
    }

    @Test
    fun `the schema version is 2 and changing it is a breaking change`() {
        // If this fails, docs/PROTOCOL.md and CHANGELOG.md need updating too --
        // that is the whole point of the assertion.
        assertEquals(2, SCHEMA_VERSION)
        assertEquals(2, JSONObject(Fixtures.full().toJson().toString()).getInt("schema"))
    }

    @Test
    fun `the wire format is exactly what docs PROTOCOL md documents`() {
        // Adding a nullable field is fine and needs no version bump. Removing or
        // renaming one, or changing a type, breaks every receiver -- so that has
        // to be a deliberate act: bump SCHEMA_VERSION, update docs/PROTOCOL.md,
        // note it in CHANGELOG.md. This test is what makes it deliberate instead
        // of accidental. The key sets below were checked against the sample
        // percept in docs/PROTOCOL.md and matched it block for block.
        val o = Fixtures.full().toJson()

        fun keysOf(obj: JSONObject): Set<String> =
            obj.keys().asSequence().toSet()

        assertEquals(
            setOf("schema", "device_id", "seq", "ts", "trigger", "attention", "vision",
                  "hearing", "radio", "body", "self", "tempo", "gps", "narration"),
            keysOf(o)
        )
        assertEquals(
            setOf("salience", "novel", "known_tokens", "dishabituated", "floor_reason"),
            keysOf(o.getJSONObject("attention"))
        )
        assertEquals(
            setOf("lens", "brightness", "labels", "objects", "text"),
            keysOf(o.getJSONObject("vision"))
        )
        assertEquals(
            setOf("name", "conf"),
            keysOf(o.getJSONObject("vision").getJSONArray("labels").getJSONObject(0))
        )
        assertEquals(
            setOf("name", "box", "track"),
            keysOf(o.getJSONObject("vision").getJSONArray("objects").getJSONObject(0))
        )
        assertEquals(
            setOf("level_db", "peak_db", "events", "transcript"),
            keysOf(o.getJSONObject("hearing"))
        )
        assertEquals(
            setOf("ble_count", "ble_named", "ble_strongest_rssi", "wifi_count",
                  "wifi_connected", "wifi_strongest_rssi", "cell", "place_id",
                  "place_name", "place_similarity", "place_is_new"),
            keysOf(o.getJSONObject("radio"))
        )
        assertEquals(
            setOf("id", "name", "rssi"),
            keysOf(o.getJSONObject("radio").getJSONArray("ble_named").getJSONObject(0))
        )
        assertEquals(
            setOf("kind", "id", "dbm"),
            keysOf(o.getJSONObject("radio").getJSONObject("cell"))
        )
        assertEquals(
            setOf("motion", "posture", "accel_rms", "gyro_rms", "heading_deg", "steps",
                  "steps_delta", "lux", "covered", "magnetic_ut", "magnetic_anomaly",
                  "pressure_hpa", "pressure_delta_per_min"),
            keysOf(o.getJSONObject("body"))
        )
        assertEquals(
            setOf("battery", "charging", "battery_temp_c", "current_ma", "voltage_v",
                  "thermal", "thermal_headroom", "mem_free_pct", "mem_low",
                  "storage_free_pct", "screen_on", "net", "uptime_s"),
            keysOf(o.getJSONObject("self"))
        )
        assertEquals(
            setOf("local_time", "tz_offset_min", "day_of_week", "part_of_day",
                  "solar_elevation_deg", "is_daylight", "minutes_to_sunset",
                  "minutes_since_sunrise", "day_length_min"),
            keysOf(o.getJSONObject("tempo"))
        )
        assertEquals(
            setOf("lat", "lon", "acc_m"),
            keysOf(o.getJSONObject("gps"))
        )
    }
}
