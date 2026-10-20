package net.synthsenses.senses

import android.media.AudioManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ears must never cost the phone a call.
 *
 * One of the devices this runs on is a household's emergency line: fixed in
 * place, always charging, sound always up, and the only number some of the
 * family have. A sense that interferes with that is not worth having at any
 * quality, so this is the one place in the project where the correct behaviour
 * is to give up the measurement without hesitating.
 */
class AudioCallStateTest {

    @Test
    fun `a ringing phone keeps its microphone`() {
        // MODE_RINGTONE, not MODE_IN_CALL: the mic is released from the first
        // ring, not from the moment someone manages to answer.
        assertTrue(micIsBusy(AudioManager.MODE_RINGTONE))
    }

    @Test
    fun `a call in progress keeps its microphone`() {
        assertTrue(micIsBusy(AudioManager.MODE_IN_CALL))
        assertTrue(micIsBusy(AudioManager.MODE_IN_COMMUNICATION))
    }

    @Test
    fun `an idle phone lets the ears listen`() {
        assertFalse(micIsBusy(AudioManager.MODE_NORMAL))
    }

    @Test
    fun `modes this code has never heard of are treated as calls`() {
        // MODE_CALL_SCREENING (30), MODE_CALL_REDIRECT and
        // MODE_COMMUNICATION_REDIRECT (31) are all above MODE_NORMAL, and so
        // is whatever gets added next. Erring this way costs one sound label;
        // erring the other way costs a call.
        for (mode in 4..12) {
            assertTrue("mode $mode should be treated as a call", micIsBusy(mode))
        }
    }

    @Test
    fun `an unreadable mode does not disable the sense`() {
        // null means the service could not be reached; negatives are
        // MODE_INVALID and MODE_CURRENT, which getMode() does not return.
        // "Cannot tell" is not "on a call", and failing closed here would
        // silently cost hearing altogether on some phone we have not seen.
        assertFalse(micIsBusy(null))
        assertFalse(micIsBusy(AudioManager.MODE_INVALID))
        assertFalse(micIsBusy(AudioManager.MODE_CURRENT))
    }
}
