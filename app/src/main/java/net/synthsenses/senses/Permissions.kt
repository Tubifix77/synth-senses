package net.synthsenses.senses

import android.Manifest

/**
 * Which runtime permissions the enabled senses actually need, on a given
 * Android version.
 *
 * This lived inline in `MainActivity` and was wrong below API 31, in a way that
 * silently disabled a whole sense and one of the three validated algorithms.
 *
 * `BLUETOOTH_SCAN` only exists from API 31, and this app declares it with
 * `neverForLocation` precisely so a BLE scan costs no location permission. On
 * API 29 and 30 there is no such permission and no such escape hatch: a BLE
 * scan returns an empty list unless `ACCESS_FINE_LOCATION` is held. The old
 * code asked for fine location only when wifi, cell or GPS were switched on —
 * all off by default — while BLE ships on. So on an Android 10 or 11 phone with
 * stock settings the scan always came back empty, `ble_count` was a permanent
 * zero, the place fingerprint was never non-empty, and `PlaceMemory.recognise`
 * was therefore never called even once. No place could ever be learned. Nothing
 * failed; it just quietly did nothing.
 *
 * Pulled out as a pure function so the decision can be tested at every API
 * level without an emulator, the same reason `Habituation` and `PlaceMemory`
 * take a `File`.
 */
internal fun requiredPermissions(
    sdkInt: Int,
    ble: Boolean,
    wifi: Boolean,
    cell: Boolean,
    location: Boolean
): List<String> {
    val wanted = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )
    if (sdkInt >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
    if (sdkInt >= 31 && ble) wanted += Manifest.permission.BLUETOOTH_SCAN
    if (sdkInt >= 29) wanted += Manifest.permission.ACTIVITY_RECOGNITION

    // Wifi scan results and cell info need fine location on Android 10+. So
    // does a BLE scan, but only below 31, where BLUETOOTH_SCAN does not exist.
    // Asking for it there is the only way the BLE switch can mean what it says.
    if (wifi || cell || location || (ble && sdkInt < 31)) {
        wanted += Manifest.permission.ACCESS_FINE_LOCATION
    }
    return wanted.distinct()
}

/**
 * True when the enabled senses will make this build ask for fine location, so
 * the control panel can say so before the dialog appears rather than after.
 */
internal fun willAskForLocation(
    sdkInt: Int,
    ble: Boolean,
    wifi: Boolean,
    cell: Boolean,
    location: Boolean
): Boolean = wifi || cell || location || (ble && sdkInt < 31)
