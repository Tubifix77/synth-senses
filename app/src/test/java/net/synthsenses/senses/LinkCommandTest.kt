package net.synthsenses.senses

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Inbound command parsing.
 *
 * This is the one surface an outside process drives directly: whatever your box
 * sends arrives here as JSON and is coerced into arguments that
 * `SenseService.handle()` acts on. Malformed or missing arguments have to fall
 * back rather than throw, because a command that crashes the loop takes the
 * whole sensory apparatus down with it.
 */
class LinkCommandTest {

    private fun cmd(name: String, json: String) = Link.Command(name, JSONObject(json))

    @Test
    fun `arguments are read with their declared types`() {
        val c = cmd("set", """{"cmd":"set","interval_ms":15000,"threshold":0.42,"vision":true,"lens":"front"}""")

        assertEquals(15_000L, c.long("interval_ms", 0L))
        assertEquals(0.42f, c.float("threshold", 0f), 1e-6f)
        assertEquals(true, c.bool("vision", false))
        assertEquals("front", c.str("lens"))
    }

    @Test
    fun `a missing argument falls back instead of throwing`() {
        val c = cmd("set", """{"cmd":"set"}""")

        assertEquals(5_000L, c.long("interval_ms", 5_000L))
        assertEquals(0.35f, c.float("threshold", 0.35f), 1e-6f)
        assertEquals(true, c.bool("vision", true))
        assertEquals(7, c.int("nope", 7))
        assertNull(c.str("lens"))
        assertEquals("back", c.str("lens", "back"))
    }

    @Test
    fun `an explicit JSON null is treated as absent, not as the string null`() {
        val c = cmd("look", """{"cmd":"look","lens":null}""")

        assertNull("a null lens must not become \"null\"", c.str("lens"))
        assertEquals("back", c.str("lens", "back"))
    }

    @Test
    fun `numbers arriving as strings are still usable`() {
        // hand-typed JSON at a console is a first-class way to drive this
        val c = cmd("set", """{"cmd":"set","interval_ms":"20000","threshold":"0.5"}""")

        assertEquals(20_000L, c.long("interval_ms", 0L))
        assertEquals(0.5f, c.float("threshold", 0f), 1e-6f)
    }

    @Test
    fun `a nonsense value falls back rather than propagating garbage`() {
        val c = cmd("set", """{"cmd":"set","interval_ms":"soon","threshold":"quite high"}""")

        assertEquals(5_000L, c.long("interval_ms", 5_000L))
        assertEquals(0.35f, c.float("threshold", 0.35f), 1e-6f)
    }

    @Test
    fun `the advertised command vocabulary is exactly what the service handles`() {
        val advertised = Commands.NAMES.toSet()

        assertEquals("no duplicates in the hello frame", Commands.NAMES.size, advertised.size)
        assertEquals(
            setOf("sample", "look", "read", "listen", "speak", "attend", "set",
                  "places", "name_place", "forget", "status"),
            advertised
        )
        assertTrue("every name must be lowercase, since inbound names are lowercased",
            advertised.all { it == it.lowercase() })
    }

    @Test
    fun `the constants and the advertised list cannot drift apart`() {
        val constants = listOf(
            Commands.SAMPLE, Commands.LOOK, Commands.READ, Commands.LISTEN, Commands.SPEAK,
            Commands.ATTEND, Commands.SET, Commands.PLACES, Commands.NAME_PLACE,
            Commands.FORGET, Commands.STATUS
        )
        assertEquals(constants.toSet(), Commands.NAMES.toSet())
    }

    @Test
    fun `speak carries its text through untouched`() {
        val c = cmd("speak", """{"cmd":"speak","text":"hello there, it is 12:00 & all is well"}""")
        assertEquals("hello there, it is 12:00 & all is well", c.str("text"))
    }

    @Test
    fun `attend reads a modality and a gain`() {
        val c = cmd("attend", """{"cmd":"attend","modality":"s","gain":2.5}""")
        assertEquals("s", c.str("modality"))
        assertEquals(2.5f, c.float("gain", 1f), 1e-6f)
    }
}
