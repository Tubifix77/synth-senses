package net.synthsenses.senses

import org.json.JSONObject

/**
 * Turning what a person can bear to type on a phone keyboard into a URL the
 * [Link] can dial.
 *
 * This exists because of the first real setup on a phone. The field was empty
 * with a grey example above it — `ws://192.168.1.20:8077/link`, on a subnet
 * that was not the one the phone was on — and the example reads as a value.
 * Start was pressed with nothing in the field, `Link.connect` returned at once
 * with `OFFLINE` / "no endpoint set", and from the panel that looked like an
 * ordinary offline link rather than like nothing having been attempted. Then
 * all thirty characters had to be typed by thumb, from a different subnet.
 *
 * So: fill in every part the phone can work out for itself, and ask for the one
 * part it cannot. The phone knows its own /24 and it knows the port the
 * receiver listens on; what it does not know is which machine on the subnet is
 * running it. That is one to three digits.
 *
 * Everything here is deliberately free of Android types so the rules are unit
 * tested on a plain JVM, the same reason `Habituation` takes a `File`.
 */
object Endpoint {

    /** What `tools/receiver.py` binds by default. */
    const val DEFAULT_PORT = 8077

    /** The WebSocket path the receiver serves. */
    const val DEFAULT_PATH = "/link"

    /** UDP port `tools/receiver.py` broadcasts its address on. */
    const val BEACON_PORT = 8078

    /** The key a beacon must carry to be one of ours, and its value. */
    const val BEACON_MAGIC = "synth_senses"
    const val BEACON_VERSION = 1

    private val OCTET = Regex("""\d{1,3}""")
    private val IPV4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")
    private val SCHEMES = setOf("ws", "wss", "http", "https")
    private const val HOST_CHARS =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-"

    /**
     * `"192.168.0.42"` -> `"192.168.0."`, anything that is not a complete IPv4
     * address -> null. The trailing dot is kept so the result can simply be
     * prepended to a final octet.
     */
    fun subnetPrefix(ipv4: String?): String? {
        val ip = ipv4?.trim().orEmpty()
        if (!isIpv4(ip)) return null
        return ip.substringBeforeLast('.') + "."
    }

    /**
     * What was typed, turned into something to dial, or `""` if it is not an
     * address yet. An empty return is how the panel knows to keep Start
     * disabled and say why, instead of handing a half-typed string to okhttp.
     *
     * ```
     * ""                      -> ""                              nothing yet
     * "7"            + subnet -> "ws://192.168.0.7:8077/link"    one thumb
     * "192.168.0."            -> ""                              half an address
     * "192.168.0.7"           -> "ws://192.168.0.7:8077/link"
     * "192.168.0.7:9000"      -> "ws://192.168.0.7:9000/link"
     * "box.local"             -> "ws://box.local:8077/link"
     * "http://box:8077/in"    -> unchanged — an explicit scheme is obeyed
     * ```
     *
     * The default path is only added for the WebSocket schemes. The receiver's
     * POST handler ignores the path entirely, so an `http://` address is left
     * pointing wherever it was pointed.
     */
    fun normalize(raw: String, subnet: String? = null): String {
        var rest = raw.trim()
        if (rest.isEmpty()) return ""

        // A bare final octet only means anything if we know which /24 to put
        // it in, which is the whole point of prefilling the subnet.
        if (OCTET.matches(rest)) {
            if (subnet == null || rest.toInt() > 255) return ""
            rest = subnet + rest.toInt()
        }

        val scheme: String
        if (rest.contains("://")) {
            scheme = rest.substringBefore("://").lowercase()
            if (scheme !in SCHEMES) return ""
            rest = rest.substringAfter("://")
        } else {
            scheme = "ws"
        }
        if (rest.isEmpty()) return ""

        var authority = rest.substringBefore('/')
        val path = rest.substring(authority.length)

        val host: String
        val port: String
        if (authority.startsWith("[")) {                 // literal IPv6
            val close = authority.indexOf(']')
            if (close < 0) return ""
            host = authority.substring(0, close + 1)
            val after = authority.substring(close + 1)
            if (after.isNotEmpty() && !after.startsWith(":")) return ""
            port = after.removePrefix(":")
        } else {
            host = authority.substringBefore(':')
            port = authority.substringAfter(':', "")
        }
        if (!validHost(host)) return ""
        if (port.isNotEmpty() && (port.toIntOrNull() ?: 0) !in 1..65535) return ""

        authority = if (port.isEmpty()) "$host:$DEFAULT_PORT" else "$host:$port"
        val tail = when {
            path.isNotEmpty() -> path
            scheme.startsWith("ws") -> DEFAULT_PATH
            else -> ""
        }
        return "$scheme://$authority$tail"
    }

    /**
     * One UDP beacon from `tools/receiver.py`, turned into an address to offer,
     * or null if the packet is not ours or makes no sense.
     *
     * Everything the packet claims goes back through [normalize], so a garbled
     * or hostile broadcast on the LAN cannot put an arbitrary string in front
     * of the user as something to connect to. [sourceIp] — where the packet
     * actually came from — is used when the beacon names only a port, which is
     * the more trustworthy of the two anyway.
     */
    fun parseBeacon(payload: String, sourceIp: String? = null): String? {
        val o = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        if (o.optInt(BEACON_MAGIC, 0) != BEACON_VERSION) return null

        val claimed = o.optString("ws", "")
        if (claimed.isNotBlank()) {
            val url = normalize(claimed)
            if (url.isNotEmpty()) return url
        }
        if (sourceIp.isNullOrBlank()) return null
        val port = o.optInt("port", DEFAULT_PORT)
        if (port !in 1..65535) return null
        return normalize("ws://$sourceIp:$port$DEFAULT_PATH")
    }

    private fun isIpv4(s: String): Boolean {
        val m = IPV4.matchEntire(s) ?: return false
        return m.groupValues.drop(1).all { it.toInt() <= 255 }
    }

    private fun validHost(h: String): Boolean {
        if (h.isEmpty()) return false
        if (h.startsWith("[")) return h.endsWith("]") && h.length > 2
        if (h.any { it !in HOST_CHARS }) return false
        if (h.first() in ".-" || h.last() in ".-") return false
        // Digits and dots but not four whole octets is a half-typed address,
        // not a hostname. "192.168.0." must not be accepted as something to
        // dial just because prefilling the subnet put it there.
        if (h.all { it.isDigit() || it == '.' }) return isIpv4(h)
        return true
    }
}
