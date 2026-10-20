package net.synthsenses.senses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ordinal contract.
 *
 * A consumer that cannot carry strings carries the index instead — Potluck
 * values are at most 8 bytes, so `body.motion` crosses as a number and the
 * label is an enum on the far side. That makes the *order* of these lists part
 * of the wire format, even though nothing in the JSON ever shows it.
 *
 * This is the kind of break that announces itself nowhere. Insert a state in
 * the middle of `MOTION` and every ordinal after it shifts: the phone keeps
 * sending, the receiver keeps decoding, and from that moment it reports
 * "walking" when the phone is in a car. No exception, no malformed percept,
 * no other test in this repo goes red.
 *
 * So these assertions are literal on purpose. They are not checking that the
 * code works; they are checking that nobody changed the order without meaning
 * to. If one fails, the fix is almost never to update the expected list —
 * it is to append instead of insert, and to tell the consumers.
 */
class VocabularyTest {

    @Test
    fun `motion ordinals are fixed`() {
        assertEquals(
            listOf("still", "handled", "walking", "vehicle"),
            Vocabulary.MOTION
        )
    }

    @Test
    fun `posture ordinals are fixed`() {
        assertEquals(
            listOf("face_up", "face_down", "upright", "tilted", "pocket", "unknown"),
            Vocabulary.POSTURE
        )
    }

    @Test
    fun `the thermal ladder is ordered by severity, least first`() {
        assertEquals(
            listOf("NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN"),
            Vocabulary.THERMAL
        )
    }

    @Test
    fun `thermal UNKNOWN is absence, not a severity`() {
        // Putting it at index 7 would make "the platform would not say" sort as
        // worse than SHUTDOWN, and would make a comparison on the ordinal lie.
        assertFalse(
            "UNKNOWN must stay out of the ladder: it is absence, and a consumer " +
                "should express it the way it expresses any missing sensor",
            Vocabulary.THERMAL.contains(Vocabulary.THERMAL_UNKNOWN)
        )
    }

    @Test
    fun `motion has no unknown, because the classifier always commits`() {
        // PROTOCOL.md used to list one. BodySensor.classifyMotion has an
        // exhaustive else branch and cannot return it, and documenting a value
        // that never arrives is the same class of error as omitting one.
        assertFalse(Vocabulary.MOTION.contains("unknown"))
    }

    @Test
    fun `posture does have an unknown, because a phone can lack gravity`() {
        // Degrade, never fabricate: with no gravity sensor there is no honest
        // posture to report.
        assertTrue(Vocabulary.POSTURE.contains(Vocabulary.POSTURE_UNKNOWN))
    }

    @Test
    fun `no vocabulary repeats a value`() {
        for ((name, v) in listOf(
            "MOTION" to Vocabulary.MOTION,
            "POSTURE" to Vocabulary.POSTURE,
            "THERMAL" to Vocabulary.THERMAL
        )) {
            assertEquals("$name has a duplicate: $v", v.size, v.distinct().size)
        }
    }

    @Test
    fun `every percept carries only values from these vocabularies`() {
        // The tie between the lists and what actually ships, including the
        // degraded phone: `bare()` is the one with no gravity sensor and a
        // platform that will not report thermal status, so it exercises both
        // unknowns.
        for ((name, p) in listOf("full" to Fixtures.full(), "bare" to Fixtures.bare())) {
            assertTrue(
                "$name: body.motion was '${p.body.motion}', not in MOTION",
                Vocabulary.MOTION.contains(p.body.motion)
            )
            assertTrue(
                "$name: body.posture was '${p.body.posture}', not in POSTURE",
                Vocabulary.POSTURE.contains(p.body.posture)
            )
            assertTrue(
                "$name: self.thermal was '${p.self.thermal}', which is neither a " +
                    "rung on the ladder nor UNKNOWN",
                Vocabulary.THERMAL.contains(p.self.thermal) ||
                    p.self.thermal == Vocabulary.THERMAL_UNKNOWN
            )
        }
    }
}
