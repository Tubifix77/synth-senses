package net.synthsenses.senses

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Where the phone is, sensed rather than borrowed.
 *
 * Location used to come from `getLastKnownLocation` alone -- whatever another
 * app had last asked the system for -- and the fix's timestamp was thrown away.
 * On a phone nobody uses, nothing else asks. The P20 published one fix,
 * unchanged, for 20.75 hours across 895 percepts, Potluck included. It was only
 * still right because the phone had not moved; carried to another room it would
 * have gone on reporting the first one.
 *
 * So this asks for updates itself while `location` is on -- from the network
 * provider, which uses Wi-Fi and cell and works indoors, and from GPS, which is
 * better outdoors and searches in vain indoors -- and every fix it hands out
 * carries its age. A stationary phone keeping one fix for hours is fine; a large
 * age on a phone that has moved is a stale position, and now that is visible.
 */
internal class LocationFeed(private val context: Context) {

    companion object {
        private const val TAG = "LocationFeed"

        /** A minute between fixes: plenty for a phone that mostly sits still. */
        private const val INTERVAL_MS = 60_000L

        private val PROVIDERS =
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
    }

    private val manager: LocationManager? by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }

    @Volatile
    private var newest: Location? = null
    private var requesting = false

    // All four overridden on purpose. From API 30 the last three are default
    // methods, but on Android 9 they are abstract in the framework, and a
    // listener relying on the default throws AbstractMethodError the first time
    // a provider is switched on or off.
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            newest = location
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit
    }

    private fun permitted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Idempotent, and quietly retried next time if permission is not there yet. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (requesting || !permitted()) return
        val lm = manager ?: return
        for (p in PROVIDERS) {
            runCatching {
                lm.requestLocationUpdates(p, INTERVAL_MS, 0f, listener, Looper.getMainLooper())
            }.onFailure { Log.w(TAG, "no updates from $p", it) }
        }
        requesting = true
    }

    fun stop() {
        if (!requesting) return
        runCatching { manager?.removeUpdates(listener) }
        requesting = false
    }

    /** The newest fix known from any source, with its age, or null if there is none. */
    @SuppressLint("MissingPermission")
    fun latest(): Place? {
        if (!permitted()) return null
        val lm = manager
        val known = PROVIDERS.mapNotNull { p ->
            runCatching { lm?.getLastKnownLocation(p) }.getOrNull()
        }
        val fix = (known + listOfNotNull(newest)).maxByOrNull { it.elapsedRealtimeNanos }
            ?: return null
        // Monotonic clock on both sides, so a wall-clock change cannot make a
        // fix look fresh or ancient.
        val ageS = ((SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) /
            1_000_000_000L).coerceAtLeast(0L)
        return Place(fix.latitude, fix.longitude, fix.accuracy, ageS)
    }
}
