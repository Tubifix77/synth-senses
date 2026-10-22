package net.synthsenses.senses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `still` boundary, against the two accelerometers it has to work on.
 *
 * These are not invented numbers. They are the measured resting bands from
 * the first overnight run and the evening before it:
 *
 *   Huawei P20 Lite  untouched on a charger, 23:02-07:27, 303 samples
 *                    accel_rms peaked sharply at 0.17-0.18, gyro_rms flat at
 *                    0.010 (median, p90 and max all equal)
 *   Xiaomi 14T Pro   idling, accel_rms 0.04-0.05
 *
 * The old absolute threshold of 0.08 called the P20 `handled` for 290 of 303
 * of those samples. That is the failure these tests exist to prevent coming
 * back.
 */
class MotionFloorTest {

    /** The P20's measured resting band. */
    private val huaweiRest = listOf(0.17f, 0.18f, 0.17f, 0.18f, 0.19f, 0.17f,
                                    0.18f, 0.16f, 0.18f, 0.17f, 0.18f, 0.17f)

    /** The Xiaomi's measured resting band. */
    private val xiaomiRest = listOf(0.04f, 0.05f, 0.04f, 0.05f, 0.03f, 0.04f,
                                    0.05f, 0.04f, 0.04f, 0.05f, 0.04f, 0.05f)

    private fun settled(readings: List<Float>, times: Int = 12): MotionFloor {
        val f = MotionFloor()
        repeat(times) { readings.forEach { f.observe(it) } }
        return f
    }

    @Test
    fun `a Huawei resting at 0_17 is still, which the old constant denied`() {
        val f = settled(huaweiRest)
        val ceiling = f.stillCeiling()
        assertTrue("the learned ceiling must clear the measured resting band",
            ceiling > 0.19f)
        for (r in huaweiRest) {
            assertEquals(
                "resting at $r must read still (ceiling $ceiling)",
                Vocabulary.STILL, classifyMotion(r, 0.01f, ceiling)
            )
        }
        // The regression itself: under the old rule every one of these was
        // `handled`, all night, on a phone nobody touched.
        assertEquals(
            "this is what shipped before, and it was wrong",
            Vocabulary.HANDLED, classifyMotion(0.17f, 0.01f, MotionFloor.STILL_ABS)
        )
    }

    @Test
    fun `a Xiaomi resting at 0_04 is still, as it already was`() {
        val f = settled(xiaomiRest)
        for (r in xiaomiRest) {
            assertEquals(
                "the quiet phone must not regress",
                Vocabulary.STILL, classifyMotion(r, 0.01f, f.stillCeiling())
            )
        }
    }

    @Test
    fun `the change can only widen still, never narrow it`() {
        // A device quieter than the old constant keeps the old behaviour
        // exactly, so nothing that was correctly still becomes handled.
        val f = settled(listOf(0.001f, 0.002f, 0.001f))
        assertEquals(
            "the ceiling never drops below the old absolute threshold",
            MotionFloor.STILL_ABS, f.stillCeiling(), 1e-6f
        )
        assertEquals(MotionFloor.STILL_ABS, MotionFloor().stillCeiling(), 1e-6f)
    }

    @Test
    fun `a fresh floor behaves exactly as the old code did`() {
        val f = MotionFloor()
        assertEquals("nothing learned yet means the old constant",
            MotionFloor.STILL_ABS, f.stillCeiling(), 1e-6f)
        assertEquals(0, f.samples())
        assertEquals(Vocabulary.HANDLED, classifyMotion(0.17f, 0.01f, f.stillCeiling()))
    }

    @Test
    fun `one dip to near zero must not poison the floor`() {
        // The minimum is the wrong statistic. The P20 reports 0.01 now and
        // then while its typical resting value is 0.17, and a minimum-tracker
        // latches onto the outlier: measured against the real logs it scored
        // 3.3% where the median scored 100%.
        val f = MotionFloor()
        repeat(12) { huaweiRest.forEach { f.observe(it) } }
        f.observe(0.01f)
        f.observe(0.02f)
        assertTrue(
            "a couple of outliers must not drag the ceiling under the band",
            f.stillCeiling() > 0.19f
        )
        assertEquals(Vocabulary.STILL, classifyMotion(0.18f, 0.01f, f.stillCeiling()))
    }

    @Test
    fun `a phone never put down does not learn that motion is rest`() {
        // Without the cap, a phone carried all day raises its own median until
        // walking reads as resting.
        val f = MotionFloor()
        repeat(200) { f.observe(2.0f) }
        val ceiling = f.stillCeiling()
        assertTrue("the floor is capped", ceiling <= MotionFloor.REST_CAP * MotionFloor.STILL_FACTOR + MotionFloor.STILL_MARGIN + 1e-6f)
        assertTrue("walking must never read still", classifyMotion(2.0f, 0.1f, ceiling) != Vocabulary.STILL)
        assertTrue("nor must being handled", classifyMotion(0.5f, 0.1f, ceiling) != Vocabulary.STILL)
    }

    @Test
    fun `rotation still decides it, at any acceleration`() {
        val f = settled(huaweiRest)
        val ceiling = f.stillCeiling()
        // Same acceleration as resting, but the phone is turning: across 299
        // untouched P20 samples gyro_rms never exceeded 0.010, while the same
        // phone in a hand reached 2.1. It needs no calibration.
        assertEquals(
            "a turning phone is not still however quiet its accelerometer",
            Vocabulary.HANDLED, classifyMotion(0.18f, 0.6f, ceiling)
        )
        assertTrue(classifyMotion(0.18f, MotionFloor.STILL_GYRO, ceiling) != Vocabulary.STILL)
        assertEquals(Vocabulary.STILL,
            classifyMotion(0.18f, MotionFloor.STILL_GYRO - 0.001f, ceiling))
    }

    @Test
    fun `the bands above still are untouched`() {
        // Only the `still` boundary has been measured. The rest are still
        // reasoned from magnitudes, and this pins them so a later measurement
        // is a deliberate change rather than a side effect.
        val c = MotionFloor.STILL_ABS
        assertEquals(Vocabulary.HANDLED, classifyMotion(0.3f, 0.6f, c))
        assertEquals(Vocabulary.HANDLED, classifyMotion(0.4f, 0.1f, c))
        assertEquals(Vocabulary.WALKING, classifyMotion(1.5f, 0.1f, c))
        assertEquals(Vocabulary.VEHICLE, classifyMotion(3.5f, 0.1f, c))
        assertEquals(Vocabulary.WALKING, classifyMotion(3.5f, 0.5f, c))
    }

    @Test
    fun `every verdict is a member of the wire vocabulary`() {
        val c = MotionFloor.STILL_ABS
        for (a in listOf(0f, 0.05f, 0.2f, 0.6f, 1.5f, 3.5f, 20f)) {
            for (g in listOf(0f, 0.02f, 0.2f, 0.6f, 3f)) {
                assertTrue(
                    "classifyMotion($a, $g) escaped Vocabulary.MOTION",
                    classifyMotion(a, g, c) in Vocabulary.MOTION
                )
            }
        }
    }

    @Test
    fun `the window is bounded`() {
        val f = MotionFloor()
        repeat(MotionFloor.REST_WINDOW * 3) { f.observe(0.17f) }
        assertEquals(MotionFloor.REST_WINDOW, f.samples())
    }
}
