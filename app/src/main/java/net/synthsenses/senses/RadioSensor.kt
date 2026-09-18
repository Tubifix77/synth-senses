package net.synthsenses.senses

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
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

    val places get() = placeMemory

    suspend fun sample(wantBle: Boolean, wantWifi: Boolean, wantCell: Boolean): Radio? {
        if (!wantBle && !wantWifi && !wantCell) return null

        val ble = if (wantBle) scanBle() else emptyList()
        val aps = if (wantWifi) scanWifi() else emptyList()
        val cell = if (wantCell) readCell() else null

        // Fingerprint the place from whatever is stable: APs first, then BLE
        // devices that look fixed (strong and named).
        val fingerprint = HashMap<String, Int>()
        aps.forEach { fingerprint["w:${it.id}"] = it.rssi }
        ble.filter { it.rssi > -75 }.forEach { fingerprint["b:${it.id}"] = it.rssi }

        val match = if (fingerprint.isEmpty()) null else placeMemory.recognise(fingerprint)

        return Radio(
            bleCount = ble.size,
            bleNamed = ble.filter { it.name != null }.sortedByDescending { it.rssi }.take(8),
            bleStrongestRssi = ble.maxOfOrNull { it.rssi },
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
    private suspend fun scanBle(): List<Beacon> = withContext(Dispatchers.IO) {
        if (!hasPermission(bleScanPermission())) return@withContext emptyList()
        val adapter = bluetooth ?: return@withContext emptyList()
        if (!adapter.isEnabled) return@withContext emptyList()
        val scanner = adapter.bluetoothLeScanner ?: return@withContext emptyList()

        val found = LinkedHashMap<String, Beacon>()

        val callback = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                val addr = result.device?.address ?: return
                val id = shortHash(addr)
                val name = runCatching {
                    result.scanRecord?.deviceName ?: result.device?.name
                }.getOrNull()?.takeIf { it.isNotBlank() }
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
                Log.w(TAG, "BLE scan failed: $errorCode")
            }
        }

        val settings = ScanSettings.Builder()
            // LOW_POWER is the right default for a thing that runs for hours.
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        try {
            scanner.startScan(null, settings, callback)
            delay(BLE_WINDOW_MS)
        } catch (t: Throwable) {
            Log.w(TAG, "BLE scan start failed", t)
        } finally {
            runCatching { scanner.stopScan(callback) }
        }

        found.values.toList()
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
                    name = r.SSID?.takeIf { it.isNotBlank() },
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

            when (best) {
                is CellInfoLte -> Cell(
                    "lte",
                    (best.cellIdentity as? CellIdentityLte)?.ci?.toString(),
                    best.cellSignalStrength.dbm
                )

                is CellInfoNr -> Cell(
                    "nr",
                    (best.cellIdentity as? CellIdentityNr)?.nci?.toString(),
                    best.cellSignalStrength.dbm
                )

                is CellInfoWcdma -> Cell(
                    "wcdma",
                    (best.cellIdentity as? CellIdentityWcdma)?.cid?.toString(),
                    best.cellSignalStrength.dbm
                )

                is CellInfoGsm -> Cell(
                    "gsm",
                    (best.cellIdentity as? CellIdentityGsm)?.cid?.toString(),
                    best.cellSignalStrength.dbm
                )

                else -> Cell("other", null, null)
            }
        }.getOrNull()
    }

    // ---- helpers ----

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    /**
     * MACs and BSSIDs never leave the device in raw form — they're hashed to a
     * short stable id. Your box gets identity without getting addresses.
     */
    private fun shortHash(s: String): String {
        var h = 0xcbf29ce484222325uL
        for (b in s.lowercase().toByteArray()) {
            h = h xor b.toUByte().toULong()
            h *= 0x100000001b3uL
        }
        return h.toString(16).padStart(16, '0').take(10)
    }
}
