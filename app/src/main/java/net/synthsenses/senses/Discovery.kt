package net.synthsenses.senses

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketTimeoutException

/**
 * Finding the box, instead of typing its address.
 *
 * Two separate things, both of which the phone can do for itself and neither of
 * which it was doing:
 *
 * 1. [localIPv4] — the phone's own address. Its /24 is almost certainly the
 *    receiver's /24 too, which turns thirty characters of typing into one to
 *    three digits. Read from `ConnectivityManager`'s `LinkProperties` rather
 *    than `WifiManager.getConnectionInfo`, which is deprecated, needs location
 *    permission on recent Android, and knows nothing about ethernet or USB
 *    tethering.
 * 2. [listen] — `tools/receiver.py` broadcasts a small UDP packet saying where
 *    it is. Nothing listens for it unless the user presses Find, because a
 *    socket opened at launch is a thing running that nobody asked for.
 *
 * Both fail to null rather than throwing. A phone with no network, a locked-
 * down hotspot that drops broadcasts, an emulator with no LAN — all of those
 * mean the field stays as it was and the user types an address, which is
 * exactly where this started.
 */
object Discovery {

    private const val TAG = "Discovery"

    /** How long [listen] waits before giving up. The beacon goes out every 2 s. */
    const val LISTEN_MS = 6_000L

    /**
     * This phone's own IPv4 address on the active network, or null.
     *
     * Needs ACCESS_NETWORK_STATE, which the manifest has already had since v0.1
     * for the uplink. No runtime permission is involved, so this cannot put a
     * dialog in front of anyone.
     */
    fun localIPv4(context: Context): String? {
        return runCatching {
            val cm = context.applicationContext
                .getSystemService(ConnectivityManager::class.java)
            val network = cm?.activeNetwork
            val props = network?.let { cm.getLinkProperties(it) }
            props?.linkAddresses
                ?.map { it.address }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull {
                    !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress
                }
                ?.hostAddress
        }.getOrNull()
    }

    /**
     * Wait for a receiver to announce itself, and return an address to offer.
     *
     * Blocks, so call it off the main thread. Returns a URL already through
     * [Endpoint.normalize], or null if nothing answered in [timeoutMs].
     *
     * The multicast lock is the part that is easy to miss: Android's WiFi
     * driver filters broadcast and multicast traffic while the radio is in
     * power save, so without it a packet that reaches the phone can still never
     * reach this socket. It needs CHANGE_WIFI_MULTICAST_STATE, which is a
     * normal permission — granted at install, no prompt.
     */
    fun listen(context: Context, timeoutMs: Long = LISTEN_MS): String? {
        val wifi = runCatching {
            context.applicationContext.getSystemService(WifiManager::class.java)
        }.getOrNull()
        val lock = runCatching { wifi?.createMulticastLock("synth-senses-discovery") }.getOrNull()
        runCatching { lock?.acquire() }

        try {
            // The SocketAddress overload, explicitly: null means "unbound", so
            // reuseAddress can be set before the bind rather than after it.
            DatagramSocket(null as SocketAddress?).use { sock ->
                sock.reuseAddress = true
                sock.broadcast = true
                sock.soTimeout = 1_000
                sock.bind(InetSocketAddress(Endpoint.BEACON_PORT))

                val buffer = ByteArray(2048)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        sock.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    val found = Endpoint.parseBeacon(text, packet.address?.hostAddress)
                    if (found != null) {
                        Log.i(TAG, "beacon from ${packet.address?.hostAddress}")
                        return found
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "discovery gave up", t)
        } finally {
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
        return null
    }
}
