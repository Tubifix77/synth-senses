package net.synthsenses.senses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rules for the Endpoint field.
 *
 * These are not generic coverage either. Every case here is something that
 * happened, or something that would have, during the first setup on a real
 * phone on 2026-10-21:
 *
 * - The field was empty beneath a grey `ws://192.168.1.20:8077/link`, which
 *   reads as a value. Start was pressed with nothing in it, and all the panel
 *   showed was an offline link.
 * - The example was on a different subnet from the phone's, so it could not be
 *   copied, only retyped — thirty characters by thumb.
 * - A partly typed address must never be treated as an address. Prefilling the
 *   subnet puts `ws://192.168.0.` in the field on purpose, and that string is
 *   not somewhere to connect to.
 */
class EndpointTest {

    private val subnet = "192.168.0."

    @Test
    fun `a subnet is only taken from a complete address`() {
        assertEquals("192.168.0.", Endpoint.subnetPrefix("192.168.0.42"))
        assertEquals("10.0.0.", Endpoint.subnetPrefix(" 10.0.0.1 "))
        assertNull(Endpoint.subnetPrefix(null))
        assertNull(Endpoint.subnetPrefix(""))
        assertNull(Endpoint.subnetPrefix("192.168.0"))
        assertNull(Endpoint.subnetPrefix("192.168.0.999"))
        assertNull(Endpoint.subnetPrefix("fe80::1"))
    }

    @Test
    fun `one thumb is enough when the phone knows its own subnet`() {
        assertEquals(
            "ws://192.168.0.7:8077/link",
            Endpoint.normalize("7", subnet)
        )
        assertEquals(
            "ws://192.168.0.254:8077/link",
            Endpoint.normalize("254", subnet)
        )
    }

    @Test
    fun `a bare octet means nothing without a subnet to put it in`() {
        assertEquals("", Endpoint.normalize("7"))
        assertEquals("", Endpoint.normalize("256", subnet))
    }

    @Test
    fun `scheme, port and path are filled in around an address`() {
        assertEquals(
            "ws://192.168.0.7:8077/link",
            Endpoint.normalize("192.168.0.7", subnet)
        )
        assertEquals(
            "ws://192.168.0.7:9000/link",
            Endpoint.normalize("192.168.0.7:9000", subnet)
        )
        assertEquals(
            "ws://192.168.0.7:8077/link",
            Endpoint.normalize("ws://192.168.0.7", subnet)
        )
        assertEquals(
            "ws://box.local:8077/link",
            Endpoint.normalize("box.local")
        )
    }

    @Test
    fun `an address that is already complete comes back unchanged`() {
        val full = "ws://192.168.0.7:8077/link"
        assertEquals(full, Endpoint.normalize(full, subnet))
        assertEquals("wss://box.example:443/link", Endpoint.normalize("wss://box.example:443/link"))
    }

    @Test
    fun `an explicit scheme and path are obeyed, including POST mode`() {
        // The receiver's POST handler ignores the path, so an http address is
        // left pointing exactly where it was pointed.
        assertEquals(
            "http://192.168.0.7:8077/percept",
            Endpoint.normalize("http://192.168.0.7:8077/percept", subnet)
        )
        assertEquals("http://box:8077", Endpoint.normalize("http://box"))
        assertEquals("", Endpoint.normalize("ftp://box"))
    }

    @Test
    fun `a half-typed address is not an address`() {
        // This is the prefilled value. Start must stay disabled on it.
        assertEquals("", Endpoint.normalize("ws://192.168.0.", subnet))
        assertEquals("", Endpoint.normalize("192.168.0.", subnet))
        assertEquals("", Endpoint.normalize("192.168.0.7.5", subnet))
        assertEquals("", Endpoint.normalize("192.168.0.300", subnet))
        assertEquals("", Endpoint.normalize("", subnet))
        assertEquals("", Endpoint.normalize("   ", subnet))
        assertEquals("", Endpoint.normalize("ws://", subnet))
        assertEquals("", Endpoint.normalize("ws://box:70000"))
        assertEquals("", Endpoint.normalize("ws://box:notaport"))
    }

    @Test
    fun `whitespace around a pasted address is forgiven`() {
        assertEquals(
            "ws://192.168.0.7:8077/link",
            Endpoint.normalize("  ws://192.168.0.7:8077/link\n", subnet)
        )
    }

    @Test
    fun `a beacon is accepted only when it is ours`() {
        val good = """{"synth_senses":1,"ws":"ws://192.168.0.7:8077/link","host":"box"}"""
        assertEquals("ws://192.168.0.7:8077/link", Endpoint.parseBeacon(good))

        assertNull(Endpoint.parseBeacon("""{"ws":"ws://192.168.0.7:8077/link"}"""))
        assertNull(Endpoint.parseBeacon("""{"synth_senses":99,"ws":"ws://1.2.3.4:1/link"}"""))
        assertNull(Endpoint.parseBeacon("not json at all"))
        assertNull(Endpoint.parseBeacon(""))
    }

    @Test
    fun `a beacon that names only a port is answered from where it came from`() {
        assertEquals(
            "ws://192.168.0.7:8077/link",
            Endpoint.parseBeacon("""{"synth_senses":1,"port":8077}""", "192.168.0.7")
        )
        // Nothing to go on: no claimed address and no source.
        assertNull(Endpoint.parseBeacon("""{"synth_senses":1,"port":8077}"""))
    }

    @Test
    fun `a broadcast cannot put an arbitrary string in front of the user`() {
        // Anything on the LAN can send a UDP packet to that port, so whatever
        // it claims goes through the same rules as typed input.
        assertNull(
            Endpoint.parseBeacon("""{"synth_senses":1,"ws":"javascript:alert(1)"}""")
        )
        assertNull(
            Endpoint.parseBeacon("""{"synth_senses":1,"ws":"file:///etc/passwd"}""")
        )
        // A claimed address that is junk falls back to the packet's real source
        // rather than being offered as typed.
        assertEquals(
            "ws://192.168.0.7:8077/link",
            Endpoint.parseBeacon(
                """{"synth_senses":1,"ws":"ftp://evil","port":8077}""", "192.168.0.7"
            )
        )
    }
}
