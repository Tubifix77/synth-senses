package net.synthsenses.senses

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("synth_senses", Context.MODE_PRIVATE)

    private fun str(k: String, d: String) = sp.getString(k, d) ?: d
    private fun put(k: String, v: String) = sp.edit().putString(k, v.trim()).apply()
    private fun put(k: String, v: Long) = sp.edit().putLong(k, v).apply()
    private fun put(k: String, v: Float) = sp.edit().putFloat(k, v).apply()
    private fun put(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    /** ws:// or wss:// for the two-way link, http(s):// for POST-only. */
    var endpoint: String
        get() = str("endpoint", "")
        set(v) = put("endpoint", v)

    var token: String
        get() = str("token", "")
        set(v) = put("token", v)

    var intervalMs: Long
        get() = sp.getLong("interval_ms", 5_000L)
        set(v) = put("interval_ms", v)

    var heartbeatMs: Long
        get() = sp.getLong("heartbeat_ms", 120_000L)
        set(v) = put("heartbeat_ms", v)

    /** Salience needed to speak unprompted. */
    var threshold: Float
        get() = sp.getFloat("threshold", 0.35f)
        set(v) = put("threshold", v)

    var vision: Boolean
        get() = sp.getBoolean("vision", true)
        set(v) = put("vision", v)

    var ocr: Boolean
        get() = sp.getBoolean("ocr", true)
        set(v) = put("ocr", v)

    var hearing: Boolean
        get() = sp.getBoolean("hearing", true)
        set(v) = put("hearing", v)

    var speech: Boolean
        get() = sp.getBoolean("speech", false)
        set(v) = put("speech", v)

    /** BLE works without location permission thanks to neverForLocation. */
    var ble: Boolean
        get() = sp.getBoolean("ble", true)
        set(v) = put("ble", v)

    /** WiFi scanning needs ACCESS_FINE_LOCATION and location services enabled. */
    var wifi: Boolean
        get() = sp.getBoolean("wifi", false)
        set(v) = put("wifi", v)

    var cell: Boolean
        get() = sp.getBoolean("cell", false)
        set(v) = put("cell", v)

    var location: Boolean
        get() = sp.getBoolean("location", false)
        set(v) = put("location", v)

    var voice: Boolean
        get() = sp.getBoolean("voice", false)
        set(v) = put("voice", v)

    var lens: String
        get() = str("lens", "back")
        set(v) = put("lens", v)

    /** Stable per-install id, so your box can tell two sensor nodes apart. */
    val deviceId: String
        get() = sp.getString("device_id", null) ?: run {
            val fresh = UUID.randomUUID().toString().replace("-", "").take(12)
            sp.edit().putString("device_id", fresh).apply()
            fresh
        }
}

/** Live state the service publishes and the control panel reads. */
object Live {
    data class Status(
        val running: Boolean = false,
        val samples: Long = 0,
        val posted: Long = 0,
        val spooled: Int = 0,
        val salience: Float = 0f,
        val lastTrigger: String = "—",
        val lastNarration: String = "",
        val lastUplink: String = "—",
        val linkState: String = "OFFLINE",
        val soundModelLoaded: Boolean = false,
        val knownTokens: Int = 0,
        val quietTokens: Int = 0,
        val meanHabituation: Float = 0f,
        val places: Int = 0,
        val placeId: String? = null,
        val placeName: String? = null,
        val novel: List<String> = emptyList()
    )

    val status = MutableStateFlow(Status())

    fun update(block: (Status) -> Status) {
        status.value = block(status.value)
    }
}
