package net.synthsenses.senses

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The offline backlog.
 *
 * The bound is the point: a synthetic life catching up on two thousand percepts
 * is useful, one catching up on fifty thousand is not, and an unbounded queue
 * on a phone eventually fills the disk. None of that was tested, because Spool
 * took a Context. It takes a File now, the same arrangement Habituation and
 * PlaceMemory use and for the same reason.
 */
class SpoolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun spool() = Spool(File(tmp.newFolder(), "spool.jsonl"))

    private fun percept(n: Int) = JSONObject().apply {
        put("seq", n)
        put("device_id", "test")
    }

    @Test
    fun `what goes in comes back out, in order`() {
        val s = spool()
        repeat(5) { s.append(percept(it)) }

        val back = s.read()
        assertEquals(5, back.size)
        assertEquals(listOf(0, 1, 2, 3, 4), back.map { it.getInt("seq") })
        assertEquals(5, s.size())
    }

    @Test
    fun `an empty spool reads as empty rather than blowing up`() {
        val s = spool()
        assertEquals(emptyList<JSONObject>(), s.read())
        assertEquals(0, s.size())
    }

    @Test
    fun `the queue is bounded, and it is the oldest that go`() {
        val s = spool()
        val overflow = Spool.MAX_LINES + 10
        repeat(overflow) { s.append(percept(it)) }

        assertEquals("the bound must hold", Spool.MAX_LINES, s.size())

        val back = s.read()
        assertEquals(Spool.MAX_LINES, back.size)
        assertEquals(
            "the newest percept must survive", overflow - 1,
            back.last().getInt("seq")
        )
        assertEquals(
            "and the oldest must be the one dropped", overflow - Spool.MAX_LINES,
            back.first().getInt("seq")
        )
    }

    @Test
    fun `a percept containing newlines cannot corrupt the queue`() {
        val file = File(tmp.newFolder(), "spool.jsonl")
        val s = Spool(file)
        // A transcript is free text off a speech recogniser and can contain
        // anything, while this file format is one JSON object per line.
        val multiline = "one" + "\n" + "two" + "\n" + "three"
        s.append(JSONObject().apply { put("seq", 1); put("transcript", multiline) })
        s.append(percept(2))

        assertEquals("two percepts in, two percepts out", 2, s.size())
        assertEquals(
            "and two physical lines in the file", 2,
            file.readLines().count { it.isNotBlank() }
        )

        val back = s.read()
        assertEquals(2, back.size)
        // JSON escaping already keeps the newlines off the line boundary, so the
        // transcript survives exactly rather than being flattened into spaces.
        assertEquals(multiline, back[0].getString("transcript"))
        assertEquals(2, back[1].getInt("seq"))
    }

    @Test
    fun `clearing empties it and leaves it usable`() {
        val s = spool()
        repeat(3) { s.append(percept(it)) }
        s.clear()

        assertEquals(0, s.size())
        assertEquals(emptyList<JSONObject>(), s.read())

        s.append(percept(99))
        assertEquals(1, s.size())
        assertEquals(99, s.read().single().getInt("seq"))
    }

    @Test
    fun `a backlog survives the process dying`() {
        val file = File(tmp.newFolder(), "spool.jsonl")
        val first = Spool(file)
        repeat(4) { first.append(percept(it)) }

        val reopened = Spool(file)
        assertEquals("the whole point of spooling to disk", 4, reopened.size())
        assertEquals(listOf(0, 1, 2, 3), reopened.read().map { it.getInt("seq") })
    }

    @Test
    fun `a corrupt line is skipped rather than taking the backlog with it`() {
        val file = File(tmp.newFolder(), "spool.jsonl")
        val s = Spool(file)
        s.append(percept(1))
        file.appendText("this is not json" + System.lineSeparator())
        s.append(percept(2))

        val back = s.read()
        assertEquals("the readable percepts still come back", 2, back.size)
        assertEquals(listOf(1, 2), back.map { it.getInt("seq") })
    }

    @Test
    fun `size counts percepts, not blank lines`() {
        val file = File(tmp.newFolder(), "spool.jsonl")
        val s = Spool(file)
        s.append(percept(1))
        file.appendText(System.lineSeparator() + System.lineSeparator())

        assertEquals(1, s.size())
        assertFalse(s.read().isEmpty())
        assertTrue(s.read().all { it.has("seq") })
    }
}
