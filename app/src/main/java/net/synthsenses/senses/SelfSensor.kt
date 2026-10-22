package net.synthsenses.senses

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Interoception — the phone's sense of its own body.
 *
 * This is the block I'd feed an AI first if I only got one. Battery temperature
 * is a body temperature. Thermal status is literally a fever scale, and the OS
 * publishes it as an enum from NONE to SHUTDOWN. Charging is being fed. Memory
 * pressure is something close to fatigue. None of it needs a permission and all
 * of it costs nothing to read.
 */
/**
 * Whether the phone is being fed, which is what `self.charging` has always been
 * documented to mean.
 *
 * It used to be `BatteryManager.isCharging`, which answers a different
 * question: whether the system currently considers the battery to be filling.
 * Vendor battery protection pauses that while the phone stays plugged in. A
 * Huawei P20 Lite on a wall charger all night held itself at 81-82% in a
 * regular ~88-minute cycle -- top up, stop, drift, top up -- and `isCharging`
 * followed the cycle, so `charging` read false for 182 of 299 samples taken on
 * the charger. It did not even track the current: it agreed with the direction
 * of `current_ma` 55% of the time.
 *
 * `EXTRA_PLUGGED` is stable across all of that, because the plug does not move.
 * Whether current is actually flowing in right now is still published, as the
 * sign of `current_ma`, so nothing is lost by this. [plugged] below zero means
 * the extra was missing, and only then is the old answer used.
 */
internal fun onExternalPower(plugged: Int, isCharging: Boolean): Boolean =
    if (plugged < 0) isCharging else plugged > 0

class SelfSensor(private val context: Context) {

    companion object {
        private const val TAG = "SelfSensor"
    }

    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val batteryManager =
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val connectivity =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** Which unit this phone reports battery current in. See [CurrentScale]. */
    private val currentScale = CurrentScale()

    /** Exposed so `status` can say whether the unit has been established yet. */
    val currentUnit: String? get() = currentScale.unit

    fun sample(): SelfState {
        val sticky: Intent? = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

        val tempC = sticky
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
            ?.let { it / 10f }            // reported in tenths of a degree

        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1

        val voltageV = sticky
            ?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            ?.takeIf { it > 0 }
            ?.let { it / 1000f }          // reported in mV

        // Not simply `/ 1000`. Android documents this as microamps and
        // Huawei reports milliamps, which made the published figure 1000x wrong
        // on the P20 -- and reasonable-looking, which is worse. CurrentScale
        // works the unit out from the readings and reports null until it knows.
        val currentMa = runCatching {
            currentScale.milliamps(
                batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            )
        }.getOrNull()

        val level = batteryManager
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .let { if (it in 0..100) it / 100f else -1f }

        val mem = ActivityManager.MemoryInfo().also {
            runCatching { activityManager.getMemoryInfo(it) }
        }
        val memFreePct =
            if (mem.totalMem > 0) mem.availMem.toFloat() / mem.totalMem else -1f

        val storageFreePct = runCatching {
            val f = File(context.filesDir.absolutePath)
            val total = f.totalSpace
            if (total > 0) f.usableSpace.toFloat() / total else null
        }.getOrNull()

        return SelfState(
            batteryPct = level,
            charging = onExternalPower(plugged, batteryManager.isCharging),
            batteryTempC = tempC,
            currentMa = currentMa,
            voltageV = voltageV,
            thermal = thermalName(),
            thermalHeadroom = thermalHeadroom(),
            memFreePct = memFreePct,
            memLow = mem.lowMemory,
            storageFreePct = storageFreePct,
            screenOn = power.isInteractive,
            net = netKind(),
            uptimeS = SystemClock.elapsedRealtime() / 1000
        )
    }

    /**
     * NONE < LIGHT < MODERATE < SEVERE < CRITICAL < EMERGENCY < SHUTDOWN, which
     * is the order of `Vocabulary.THERMAL`. Written out as an explicit `when`
     * rather than indexing that list by the platform constant: the ladder is
     * only usefully ordered if each name is tied to the status it means, not to
     * a number that happens to line up today.
     */
    private fun thermalName(): String {
        if (Build.VERSION.SDK_INT < 29) return Vocabulary.THERMAL_UNKNOWN
        return when (runCatching { power.currentThermalStatus }.getOrNull()) {
            PowerManager.THERMAL_STATUS_NONE -> Vocabulary.THERMAL_NONE
            PowerManager.THERMAL_STATUS_LIGHT -> Vocabulary.THERMAL_LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> Vocabulary.THERMAL_MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> Vocabulary.THERMAL_SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL -> Vocabulary.THERMAL_CRITICAL
            PowerManager.THERMAL_STATUS_EMERGENCY -> Vocabulary.THERMAL_EMERGENCY
            PowerManager.THERMAL_STATUS_SHUTDOWN -> Vocabulary.THERMAL_SHUTDOWN
            else -> Vocabulary.THERMAL_UNKNOWN
        }
    }

    /**
     * Forecast of thermal throttling: 0 = fine, 1 = about to throttle.
     * Android 30+. Documented to be called no more than once every 10 s, so if
     * you drop the sample interval below that expect nulls here.
     */
    private fun thermalHeadroom(): Float? {
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            power.getThermalHeadroom(10).takeIf { !it.isNaN() }
        }.onFailure { Log.d(TAG, "headroom unavailable: ${it.message}") }.getOrNull()
    }

    private fun netKind(): String {
        val caps = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
            ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }
}
