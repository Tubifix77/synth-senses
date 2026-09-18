package net.synthsenses.senses

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

const val SCHEMA_VERSION = 2

data class Scored(val name: String, val conf: Float)

data class Seen(val name: String, val box: List<Float>, val track: Int?)

data class Vision(
    val lens: String,
    val brightness: Float,
    val labels: List<Scored>,
    val objects: List<Seen>,
    val text: String?
)

data class Hearing(
    val levelDb: Float,
    val peakDb: Float,
    val events: List<Scored>,
    val transcript: String?
)

// ---- radio: the sense of place ----

data class Beacon(val id: String, val name: String?, val rssi: Int)

data class Cell(val kind: String, val id: String?, val dbm: Int?)

data class Radio(
    val bleCount: Int,
    val bleNamed: List<Beacon>,
    val bleStrongestRssi: Int?,
    val wifiCount: Int,
    val wifiConnected: String?,
    val wifiStrongestRssi: Int?,
    val cell: Cell?,
    val placeId: String?,
    val placeName: String?,
    val placeSimilarity: Float,
    val placeIsNew: Boolean
)

// ---- body: proprioception ----

data class Body(
    val motion: String,
    val posture: String,
    val accelRms: Float,
    val gyroRms: Float,
    val headingDeg: Int?,
    val steps: Long?,
    val stepsDelta: Long?,
    val lux: Float?,
    val covered: Boolean?,
    val magneticUt: Float?,
    val magneticAnomaly: Float?,
    val pressureHpa: Float?,
    val pressureDeltaPerMin: Float?
)

// ---- self: interoception ----

data class SelfState(
    val batteryPct: Float,
    val charging: Boolean,
    val batteryTempC: Float?,
    val currentMa: Float?,
    val voltageV: Float?,
    val thermal: String,
    val thermalHeadroom: Float?,
    val memFreePct: Float,
    val memLow: Boolean,
    val storageFreePct: Float?,
    val screenOn: Boolean,
    val net: String,
    val uptimeS: Long
)

// ---- tempo: circadian sense ----

data class Tempo(
    val localTime: String,
    val tzOffsetMin: Int,
    val dayOfWeek: String,
    val partOfDay: String,
    val solarElevationDeg: Float?,
    val isDaylight: Boolean?,
    val minutesToSunset: Int?,
    val minutesSinceSunrise: Int?,
    val dayLengthMin: Int?
)

data class Place(val lat: Double, val lon: Double, val accM: Float)

data class Attention(
    val salience: Float,
    val novelTokens: List<String>,
    val knownTokens: Int,
    val dishabituated: Boolean,
    val floorReason: String?
)

data class Percept(
    val deviceId: String,
    val seq: Long,
    val tsMs: Long,
    val trigger: String,
    val vision: Vision?,
    val hearing: Hearing?,
    val radio: Radio?,
    val body: Body,
    val self: SelfState,
    val tempo: Tempo,
    val gps: Place?,
    val attention: Attention? = null
) {
    val narration: String by lazy { Narrator.describe(this) }

    /**
     * The stimulus vocabulary. Habituation operates on these strings, so what counts
     * as "the same thing twice" is decided entirely here. Continuous quantities are
     * bucketed on purpose — a room at 310 lux and the same room at 340 lux must
     * produce the same token, or nothing ever habituates to anything.
     */
    fun tokens(): Set<String> {
        val t = mutableSetOf<String>()

        vision?.let { v ->
            v.labels.forEach { t += "v:${it.name}" }
            v.objects.map { it.name }.distinct().forEach { t += "o:$it" }
            t += "oc:${bucketCount(v.objects.size)}"
            v.text?.takeIf { it.isNotBlank() }?.let { t += "tx:${it.hashCode()}" }
        }

        hearing?.let { h ->
            h.events.forEach { t += "s:${it.name}" }
            t += "db:${(h.levelDb / 10).toInt()}"
            if (!h.transcript.isNullOrBlank()) t += "sp:${h.transcript.hashCode()}"
        }

        radio?.let { r ->
            r.placeId?.let { t += "p:$it" }
            t += "bc:${bucketCount(r.bleCount)}"
            r.bleNamed.forEach { t += "bn:${it.name ?: it.id}" }
            r.wifiConnected?.let { t += "ap:$it" }
        }

        t += "m:${body.motion}"
        t += "po:${body.posture}"
        body.lux?.let { t += "lx:${luxBucket(it)}" }
        body.magneticAnomaly?.let { if (it > 15f) t += "mag:anomaly" }

        t += "th:${self.thermal}"
        t += "chg:${self.charging}"
        if (self.batteryPct in 0f..0.15f) t += "bat:low"

        t += "tod:${tempo.partOfDay}"

        return t
    }

    private fun bucketCount(n: Int) = when {
        n == 0 -> "0"
        n == 1 -> "1"
        n <= 3 -> "2-3"
        n <= 8 -> "4-8"
        n <= 20 -> "9-20"
        else -> "20+"
    }

    private fun luxBucket(lux: Float) = when {
        lux < 5f -> "dark"
        lux < 60f -> "dim"
        lux < 400f -> "indoor"
        lux < 5_000f -> "bright"
        else -> "sunlit"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", SCHEMA_VERSION)
        put("device_id", deviceId)
        put("seq", seq)
        put("ts", iso(tsMs))
        put("trigger", trigger)

        put("attention", attention?.let { a ->
            JSONObject().apply {
                put("salience", r2(a.salience))
                put("novel", JSONArray(a.novelTokens))
                put("known_tokens", a.knownTokens)
                put("dishabituated", a.dishabituated)
                put("floor_reason", a.floorReason ?: JSONObject.NULL)
            }
        } ?: JSONObject.NULL)

        put("vision", vision?.let { v ->
            JSONObject().apply {
                put("lens", v.lens)
                put("brightness", r2(v.brightness))
                put("labels", scoredArray(v.labels))
                put("objects", JSONArray().apply {
                    v.objects.forEach { o ->
                        put(JSONObject().apply {
                            put("name", o.name)
                            put("box", JSONArray().apply { o.box.forEach { put(r2(it)) } })
                            put("track", o.track ?: JSONObject.NULL)
                        })
                    }
                })
                put("text", v.text ?: JSONObject.NULL)
            }
        } ?: JSONObject.NULL)

        put("hearing", hearing?.let { h ->
            JSONObject().apply {
                put("level_db", r2(h.levelDb))
                put("peak_db", r2(h.peakDb))
                put("events", scoredArray(h.events))
                put("transcript", h.transcript ?: JSONObject.NULL)
            }
        } ?: JSONObject.NULL)

        put("radio", radio?.let { r ->
            JSONObject().apply {
                put("ble_count", r.bleCount)
                put("ble_named", JSONArray().apply {
                    r.bleNamed.forEach { b ->
                        put(JSONObject().apply {
                            put("id", b.id)
                            put("name", b.name ?: JSONObject.NULL)
                            put("rssi", b.rssi)
                        })
                    }
                })
                put("ble_strongest_rssi", r.bleStrongestRssi ?: JSONObject.NULL)
                put("wifi_count", r.wifiCount)
                put("wifi_connected", r.wifiConnected ?: JSONObject.NULL)
                put("wifi_strongest_rssi", r.wifiStrongestRssi ?: JSONObject.NULL)
                put("cell", r.cell?.let { c ->
                    JSONObject().apply {
                        put("kind", c.kind)
                        put("id", c.id ?: JSONObject.NULL)
                        put("dbm", c.dbm ?: JSONObject.NULL)
                    }
                } ?: JSONObject.NULL)
                put("place_id", r.placeId ?: JSONObject.NULL)
                put("place_name", r.placeName ?: JSONObject.NULL)
                put("place_similarity", r2(r.placeSimilarity))
                put("place_is_new", r.placeIsNew)
            }
        } ?: JSONObject.NULL)

        put("body", JSONObject().apply {
            put("motion", body.motion)
            put("posture", body.posture)
            put("accel_rms", r2(body.accelRms))
            put("gyro_rms", r2(body.gyroRms))
            put("heading_deg", body.headingDeg ?: JSONObject.NULL)
            put("steps", body.steps ?: JSONObject.NULL)
            put("steps_delta", body.stepsDelta ?: JSONObject.NULL)
            put("lux", body.lux?.let { r2(it) } ?: JSONObject.NULL)
            put("covered", body.covered ?: JSONObject.NULL)
            put("magnetic_ut", body.magneticUt?.let { r2(it) } ?: JSONObject.NULL)
            put("magnetic_anomaly", body.magneticAnomaly?.let { r2(it) } ?: JSONObject.NULL)
            put("pressure_hpa", body.pressureHpa?.let { r2(it) } ?: JSONObject.NULL)
            put(
                "pressure_delta_per_min",
                body.pressureDeltaPerMin?.let { r2(it) } ?: JSONObject.NULL
            )
        })

        put("self", JSONObject().apply {
            put("battery", r2(self.batteryPct))
            put("charging", self.charging)
            put("battery_temp_c", self.batteryTempC?.let { r2(it) } ?: JSONObject.NULL)
            put("current_ma", self.currentMa?.let { r2(it) } ?: JSONObject.NULL)
            put("voltage_v", self.voltageV?.let { r2(it) } ?: JSONObject.NULL)
            put("thermal", self.thermal)
            put("thermal_headroom", self.thermalHeadroom?.let { r2(it) } ?: JSONObject.NULL)
            put("mem_free_pct", r2(self.memFreePct))
            put("mem_low", self.memLow)
            put("storage_free_pct", self.storageFreePct?.let { r2(it) } ?: JSONObject.NULL)
            put("screen_on", self.screenOn)
            put("net", self.net)
            put("uptime_s", self.uptimeS)
        })

        put("tempo", JSONObject().apply {
            put("local_time", tempo.localTime)
            put("tz_offset_min", tempo.tzOffsetMin)
            put("day_of_week", tempo.dayOfWeek)
            put("part_of_day", tempo.partOfDay)
            put(
                "solar_elevation_deg",
                tempo.solarElevationDeg?.let { r2(it) } ?: JSONObject.NULL
            )
            put("is_daylight", tempo.isDaylight ?: JSONObject.NULL)
            put("minutes_to_sunset", tempo.minutesToSunset ?: JSONObject.NULL)
            put("minutes_since_sunrise", tempo.minutesSinceSunrise ?: JSONObject.NULL)
            put("day_length_min", tempo.dayLengthMin ?: JSONObject.NULL)
        })

        put("gps", gps?.let {
            JSONObject().apply {
                put("lat", it.lat); put("lon", it.lon); put("acc_m", r2(it.accM))
            }
        } ?: JSONObject.NULL)

        put("narration", narration)
    }

    private fun scoredArray(list: List<Scored>) = JSONArray().apply {
        list.forEach { put(JSONObject().apply { put("name", it.name); put("conf", r2(it.conf)) }) }
    }

    private fun r2(f: Float): Double = (f * 100f).roundToInt() / 100.0

    companion object {
        private val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }

        fun iso(ms: Long): String = synchronized(fmt) { fmt.format(Date(ms)) }
    }
}
