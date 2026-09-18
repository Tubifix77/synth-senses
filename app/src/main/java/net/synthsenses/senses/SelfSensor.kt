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

    fun sample(): SelfState {
        val sticky: Intent? = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

        val tempC = sticky
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
            ?.let { it / 10f }            // reported in tenths of a degree

        val voltageV = sticky
            ?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            ?.takeIf { it > 0 }
            ?.let { it / 1000f }          // reported in mV

        val currentMa = runCatching {
            batteryManager
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                .takeIf { it != Int.MIN_VALUE && it != 0 }
                ?.let { it / 1000f }      // µA -> mA; negative means discharging
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
            charging = batteryManager.isCharging,
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

    /** NONE < LIGHT < MODERATE < SEVERE < CRITICAL < EMERGENCY < SHUTDOWN. */
    private fun thermalName(): String {
        if (Build.VERSION.SDK_INT < 29) return "UNKNOWN"
        return when (runCatching { power.currentThermalStatus }.getOrNull()) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "UNKNOWN"
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
