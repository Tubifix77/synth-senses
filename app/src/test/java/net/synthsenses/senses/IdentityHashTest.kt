package net.synthsenses.senses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Raw identifiers never leave the device."
 *
 * That is one of this project's load-bearing invariants and the only thing
 * enforcing it is `shortHash`, which every MAC and BSSID passes through before
 * it can enter a percept. An invariant with no test is a hope, so: the hash has
 * to be stable across runs and restarts or place recognition silently breaks,
 * and it must not be reversible into or contain the address it came from.
 */
class IdentityHashTest {

    private val mac = "A4:83:E7:2B:19:0C"

    @Test
    fun `the same address always hashes the same way`() {
        assertEquals(shortHash(mac), shortHash(mac))
        assertEquals(
            "a restart must not renumber every anchor",
            shortHash("de:ad:be:ef:00:01"), shortHash("de:ad:be:ef:00:01")
        )
    }

    @Test
    fun `hashing is case insensitive, because vendors are not consistent`() {
        assertEquals(shortHash(mac), shortHash(mac.lowercase()))
        assertEquals(shortHash(mac), shortHash(mac.uppercase()))
    }

    @Test
    fun `the output is a fixed-width hex id and nothing else`() {
        for (addr in listOf(mac, "00:00:00:00:00:00", "ff:ff:ff:ff:ff:ff", "", "x")) {
            val h = shortHash(addr)
            assertEquals("always 10 characters, for '$addr'", 10, h.length)
            assertTrue("hex only, got '$h'", Regex("^[0-9a-f]{10}$").matches(h))
        }
    }

    @Test
    fun `the address itself never survives into the id`() {
        val h = shortHash(mac)
        assertFalse("the raw MAC must not appear", h.contains("a483e7", ignoreCase = true))
        assertFalse(h.contains(mac, ignoreCase = true))
        assertFalse(h.contains(mac.replace(":", ""), ignoreCase = true))
        // an SSID is free text and must not leak either
        assertFalse(shortHash("MyHouse-5G").contains("MyHouse", ignoreCase = true))
    }

    @Test
    fun `different addresses get different ids, including near-identical ones`() {
        val seen = mutableSetOf<String>()
        val addresses = buildList {
            for (i in 0 until 2000) add("00:11:22:33:%02x:%02x".format(i / 256, i % 256))
        }
        addresses.forEach { seen += shortHash(it) }

        assertEquals("2000 distinct addresses should give 2000 distinct ids",
            addresses.size, seen.size)
        assertNotEquals(shortHash("a4:83:e7:2b:19:0c"), shortHash("a4:83:e7:2b:19:0d"))
    }

    @Test
    fun `it is FNV-1a, pinned to known values so it cannot drift`() {
        // Changing the hash would renumber every anchor in every stored place,
        // silently emptying PlaceMemory on upgrade with no error anywhere.
        // These are 64-bit FNV-1a of the lowercased input, hex, first 10 chars,
        // computed independently rather than by re-running the same Kotlin.
        val golden = mapOf(
            "A4:83:E7:2B:19:0C" to "d64c92e34a",
            "" to "cbf29ce484",
            "a" to "af63dc4c86",
            "MyHouse-5G" to "1e1bff274f",
            "00:11:22:33:44:55" to "b996c58828"
        )
        for ((input, expected) in golden) {
            assertEquals("hash changed for '$input'", expected, shortHash(input))
        }
    }
}
