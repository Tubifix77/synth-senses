package net.synthsenses.senses

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * The radio sense. Two very different channels:
 *
 *  BLE — short range, so a good proxy for "who is in this room right now".
 *  Counts devices and remembers named ones. MAC randomisation means you can't
 *  follow a stranger around, but you can count bodies and recognise fixed things
 *  (your headphones, a TV, a beacon).
 *
 *  WiFi — longer range and access points don't randomise their BSSIDs, which
 *  makes them the stable part of a place fingerprint.
 *
 * Both feed PlaceMemory, which is where the actual sense of place lives.
 *
 * Permission notes that will bite you:
 *  - BLE scanning needs BLUETOOTH_SCAN. We declare it with neverForLocation so
 *    it works WITHOUT any location permission.
 *  - WiFi scan results need ACCESS_FINE_LOCATION on Android 10+, and location
 *    services switched on device-wide. That's why WiFi is its own toggle.
 *  - startScan() is throttled to 4 calls per 2 minutes. We respect that and read
 *    the cached results in between, which is free.
 *  - Cell info also needs ACCESS_FINE_LOCATION.
 *  - **An unfiltered BLE scan returns nothing while the screen is off** (Android
 *    8.1+). Measured: the P20 saw BLE in 3 of 784 screen-off samples and the
 *    Xiaomi in 6 of 115, against 72% and 77% with the screen on -- and a
 *    phone left to run untouched spends nearly all its time screen-off. Up to
 *    Android 14, any non-empty filter list counts as filtered (ScanManager:
 *    `filters != null && !filters.isEmpty()`), so one empty ScanFilter keeps
 *    the scan running. Android 15 closed that (`isFilteredScan` wants a filter
 *    with a field set), and from 13 Bluetooth is a Play-updatable module, so
 *    above 12 the empty filter may or may not be honoured.
 *  - **More than five scan starts in thirty seconds are refused** with
 *    SCAN_FAILED_SCANNING_TOO_FREQUENTLY (AppScanStats: 5 per 30 000 ms). At
 *    the 5 s default that is roughly one sample in six.
 *
 * Both used to be published as `ble_count: 0` -- "nobody here" -- when the
 * truth was "could not look". Now they are null.
 */
class RadioSensor(private val context: Context) {

    companion object {
        private const val TAG = "RadioSensor"
        private const val BLE_WINDOW_MS = 1_800L
        private const val WIFI_SCAN_MIN_GAP_MS = 32_000L   // 4 per 2 min, with headroom
    }

    private val placeMemory = PlaceMemory(context)

    private val bluetooth: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private val wifi: WifiManager? by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    private var lastWifiScanAt = 0L

    private val power: PowerManager? by lazy {
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }

    val places get() = placeMemory

    suspend fun sample(wantBle: Boolean, wantWifi: Boolean, wantCell: Boolean): Radio? {
        if (!wantBle && !wantWifi && !wantCell) return null

        // null means the scan did not happen or cannot be trusted, which is not
        // the same as an empty room. See the class comment.
        val ble = if (wantBle) scanBle() else null
        val aps = if (wantWifi) scanWifi() else emptyList()
        val cell = if (wantCell) readCell() else null

        // Fingerprint the place from whatever is stable: APs first, then BLE
        // devices that look fixed (strong and named).
        val fingerprint = HashMap<String, Int>()
        aps.forEach { fingerprint["w:${it.id}"] = it.rssi }
        ble.orEmpty().filter { it.rssi > -75 }.forEach { fingerprint["b:${it.id}"] = it.rssi }

        val match = if (fingerprint.isEmpty()) null else placeMemory.recognise(fingerprint)

        return Radio(
            bleCount = ble?.size,
            bleNamed = ble?.filter { it.name != null }?.sortedByDescending { it.rssi }?.take(8),
            bleStrongestRssi = ble?.maxOfOrNull { it.rssi },
            wifiCount = aps.size,
            wifiConnected = connectedSsid(),
            wifiStrongestRssi = aps.maxOfOrNull { it.rssi },
            cell = cell,
            placeId = match?.id,
            placeName = match?.name,
            placeSimilarity = match?.similarity ?: 0f,
            placeIsNew = match?.isNew ?: false
        )
    }

    // ---- BLE ----

    @SuppressLint("MissingPermission")
    private suspend fun scanBle(): List<Beacon>? = withContext(Dispatchers.IO) {
        // Every early return is "could not look", so null rather than empty: a
        // denied permission or a switched-off radio is not an empty room.
        if (!hasPermission(bleScanPermission())) return@withContext null
        val adapter = bluetooth ?: return@withContext null
        if (!adapter.isEnabled) return@withContext null
        val scanner = adapter.bluetoothLeScanner ?: return@withContext null

        val screenOn = power?.isInteractive ?: true
        val found = LinkedHashMap<String, Beacon>()
        // Written from the scanner's callback thread, read after the window.
        // 0 is "no failure"; the platform's error codes start at 1.
        val failed = AtomicInteger(0)

        val callback = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                val addr = result.device?.address ?: return
                val id = shortHash(addr)
                val name = radioName(runCatching {
                    result.scanRecord?.deviceName ?: result.device?.name
                }.getOrNull())
                // keep the strongest sighting of each device in the window
                val existing = found[id]
                if (existing == null || result.rssi > existing.rssi) {
                    found[id] = Beacon(id, name ?: existing?.name, result.rssi)
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(0, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                // Including the platform refusing a sixth start inside 30 s,
                // which used to reach the wire as zero devices.
                Log.w(TAG, "BLE scan failed: $errorCode")
                failed.set(errorCode)
            }
        }

        val settings = ScanSettings.Builder()
            // LOW_POWER is the right default for a thing that runs for hours.
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        // One empty filter, so the scan counts as filtered and keeps running
        // with the screen off. It matches everything, so nothing is excluded.
        val filters = listOf(ScanFilter.Builder().build())

        try {
            scanner.startScan(filters, settings, callback)
            delay(BLE_WINDOW_MS)
        } catch (t: Throwable) {
            Log.w(TAG, "BLE scan start failed", t)
            failed.set(-1)
        } finally {
            runCatching { scanner.stopScan(callback) }
        }

        when {
            failed.get() != 0 -> null
            // Above Android 12 the empty filter may not be honoured with the
            // screen off, and then a blind scan and an empty room look the
            // same. Heard something: it ran. Heard nothing: cannot tell.
            !screenOn && Build.VERSION.SDK_INT >= 33 && found.isEmpty() -> null
            else -> found.values.toList()
        }
    }

    private fun bleScanPermission() =
        if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN
        else Manifest.permission.ACCESS_FINE_LOCATION

    // ---- WiFi ----

    @SuppressLint("MissingPermission")
    private fun scanWifi(): List<Beacon> {
        val w = wifi ?: return emptyList()
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return emptyList()
        if (!w.isWifiEnabled) return emptyList()

        val now = System.currentTimeMillis()
        if (now - lastWifiScanAt > WIFI_SCAN_MIN_GAP_MS) {
            // throttled by the OS to 4 per 2 min; failing here is fine, the cache
            // still has the last results
            runCatching { @Suppress("DEPRECATION") w.startScan() }
            lastWifiScanAt = now
        }

        return runCatching {
            w.scanResults.map { r ->
                Beacon(
                    id = shortHash(r.BSSID),
                    name = radioName(r.SSID),
                    rssi = r.level
                )
            }
        }.getOrElse {
            Log.w(TAG, "wifi scanResults failed", it); emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectedSsid(): String? {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return null
        return runCatching {
            @Suppress("DEPRECATION")
            wifi?.connectionInfo?.ssid
                ?.trim('"')
                ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        }.getOrNull()
    }

    // ---- cell ----

    @SuppressLint("MissingPermission")
    private fun readCell(): Cell? {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return null
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return null

        return runCatching {
            val best: CellInfo = tm.allCellInfo
                ?.filter { it.isRegistered }
                ?.firstOrNull()
                ?: return null

            // CellInfoNr and CellIdentityNr are API 29, and minSdk is 28, so the
            // 5G branch is tested for separately rather than sitting in the
            // `when`. A phone old enough to lack the class is old enough to
            // lack the radio, so nothing is lost by reporting "other" there.
            if (Build.VERSION.SDK_INT >= 29 && best is CellInfoNr) {
                return@runCatching Cell(
                    "nr",
                    (best.cellIdentity as? CellIdentityNr)?.nci?.toString(),
                    cellDbm("nr", best.cellSignalStrength.dbm)
                )
            }

            when (best) {
                is CellInfoLte -> Cell(
                    "lte",
                    (best.cellIdentity as? CellIdentityLte)?.ci?.toString(),
                    cellDbm("lte", best.cellSignalStrength.dbm)
                )

                is CellInfoWcdma -> Cell(
                    "wcdma",
                    (best.cellIdentity as? CellIdentityWcdma)?.cid?.toString(),
                    cellDbm("wcdma", best.cellSignalStrength.dbm)
                )

                is CellInfoGsm -> Cell(
                    "gsm",
                    (best.cellIdentity as? CellIdentityGsm)?.cid?.toString(),
                    cellDbm("gsm", best.cellSignalStrength.dbm)
                )

                else -> Cell("other", null, null)
            }
        }.getOrNull()
    }

    // ---- helpers ----

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

}

/**
 * MACs and BSSIDs never leave the device in raw form — they're hashed to a
 * short stable id. Your box gets identity without getting addresses.
 *
 * FNV-1a, truncated to 10 hex characters. Top-level and internal rather than a
 * private method, because "raw identifiers never leave the device" is one of
 * this project's load-bearing invariants and an invariant with no test is a
 * hope. Constructing a RadioSensor needs a Context; this does not.
 */
internal fun shortHash(s: String): String {
    var h = 0xcbf29ce484222325uL
    for (b in s.lowercase().toByteArray()) {
        h = h xor b.toUByte().toULong()
        h *= 0x100000001b3uL
    }
    return h.toString(16).padStart(16, '0').take(10)
}

/**
 * A cell signal in dBm, or null where the modem gave no measurement.
 *
 * Two things are not readings. Android's `CellInfo.UNAVAILABLE`, which is
 * `Int.MAX_VALUE` and used to pass straight through. And, for LTE and NR,
 * exactly -44 dBm: the top of the RSRP range, which a Xiaomi 14T Pro returned
 * for 124 of 323 readings on cells that otherwise read -102 to -112 -- its
 * placeholder for "nothing measured". GSM and WCDMA have other ranges and no
 * observed placeholder, so only the sentinel is removed for them.
 */
/**
 * A broadcast name as text a person could read, or null.
 *
 * Names are whatever a device's firmware put in its advertisement, padding
 * included: a headset on the P20's first evening online advertised
 * "smartSILENO free" with a trailing NUL, which went into the percept and the
 * narration as `\u0000`. Control characters are dropped and the rest trimmed,
 * so a name made only of padding is no name at all.
 */
internal fun radioName(raw: String?): String? =
    raw?.filterNot { it.isISOControl() }?.trim()?.takeIf { it.isNotEmpty() }

internal fun cellDbm(kind: String, raw: Int): Int? = when {
    raw == Int.MAX_VALUE -> null
    (kind == "lte" || kind == "nr") && raw >= -44 -> null
    else -> raw
}
