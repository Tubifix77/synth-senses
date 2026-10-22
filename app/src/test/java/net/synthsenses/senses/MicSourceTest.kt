package net.synthsenses.senses

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The ears hear the room, not a mouth at the mic.
 *
 * UNPROCESSED was requested unconditionally, and on a phone without it Android
 * hands back the ordinary processed input without an error -- so the fallback
 * written for those phones never ran. The choice is now made from the phone's
 * own declaration.
 */
class MicSourceTest {

    @Test
    fun `a phone that declares unprocessed capture gets it`() {
        assertEquals(MediaRecorder.AudioSource.UNPROCESSED, micSourceFor(true))
    }

    @Test
    fun `without it, the input the CDD keeps free of noise reduction and gain control`() {
        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, micSourceFor(false))
    }

    @Test
    fun `never the ordinary or the speakerphone input`() {
        // DEFAULT and MIC are processed for a close talker; VOICE_COMMUNICATION
        // suppresses background on purpose, and a television is background.
        for (supported in listOf(true, false)) {
            val s = micSourceFor(supported)
            assertNotEquals(MediaRecorder.AudioSource.DEFAULT, s)
            assertNotEquals(MediaRecorder.AudioSource.MIC, s)
            assertNotEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, s)
        }
    }
}
