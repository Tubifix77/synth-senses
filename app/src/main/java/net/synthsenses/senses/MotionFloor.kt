package net.synthsenses.senses

import kotlin.math.max

/**
 * How still "still" is, on *this* phone.
 *
 * `classifyMotion` used to ask whether `accel_rms` was under a constant 0.08.
 * That constant was reasoned from magnitudes rather than measured, and the
 * first overnight run showed what it costs: a Huawei P20 Lite lying face up on
 * a charger, untouched from 23:02 to 07:27, reported `accel_rms` in a very
 * tight band around **0.17–0.18** and was therefore called `handled` for 290
 * of 303 samples. A Xiaomi 14T Pro idles at 0.04–0.05 and is called `still`
 * correctly. Same code, same situation, opposite answer — and `motion` is a
 * published value, so Potluck showed the phone being handled all night.
 *
 * Retuning 0.08 to suit the Huawei would overfit the second device exactly as
 * it currently overfits the first. What ports is the *shape* of the question:
 * not "is this number small" but "is this phone quieter than it usually is".
 *
 * So the floor is learned. It is the **median** of recent readings, which
 * matters: the minimum is the wrong statistic, because the P20 dips to 0.01
 * occasionally while its typical resting value is 0.17, and a minimum-tracker
 * latches onto that outlier and calls the resting state `handled` forever.
 * Measured against the recorded logs, a minimum-tracker scored 3.3% where the
 * median scored 100%.
 *
 * Two guards:
 *
 *  - **The learned ceiling never goes below [STILL_ABS].** A device quieter
 *    than the old constant keeps the old behaviour, so this change can only
 *    ever make `still` easier to reach, never harder. Nothing that was
 *    correctly still before becomes `handled` now.
 *  - **The floor is capped at [REST_CAP].** A phone carried in a pocket all
 *    day would otherwise raise its own median until walking looked like
 *    resting. Above the cap the ceiling stops following.
 *
 * Rotation is still judged absolutely, and deliberately: a gyroscope reads
 * near zero at rest on *every* device, because it measures rotation rather
 * than the noise of a spring. Across 299 untouched P20 samples `gyro_rms` had
 * median, 90th percentile and maximum all equal to 0.010 — perfectly flat —
 * while the same phone in a hand reached 2.1. It needs no calibration, and it
 * is what separates a phone resting on a desk from a phone held loosely at
 * the same acceleration.
 *
 * Validated in [MotionFloorTest] against the real distributions of both
 * phones. See `docs/VALIDATION.md`.
 */
internal class MotionFloor(private val window: Int = REST_WINDOW) {

    companion object {
        /** Readings kept. Insensitive: 60, 120 and 240 scored identically. */
        const val REST_WINDOW = 120

        /**
         * The old absolute threshold, kept as the ceiling's lower bound so a
         * quiet device behaves exactly as it did before.
         */
        const val STILL_ABS = 0.08f

        /** Headroom over the learned floor, measured rather than guessed. */
        const val STILL_FACTOR = 1.4f
        const val STILL_MARGIN = 0.02f

        /** A phone that is never put down must not learn that motion is rest. */
        const val REST_CAP = 0.25f

        /** Rotation at rest is near zero on every device, so this one is absolute. */
        const val STILL_GYRO = 0.04f
    }

    private val recent = ArrayDeque<Float>()

    fun observe(accelRms: Float) {
        synchronized(recent) {
            recent.addLast(accelRms)
            while (recent.size > window) recent.removeFirst()
        }
    }

    /** How many readings the floor is currently based on. */
    fun samples(): Int = synchronized(recent) { recent.size }

    /**
     * The `accel_rms` at or below which this phone counts as resting.
     *
     * With nothing observed yet this is [STILL_ABS], so a fresh start behaves
     * exactly as the old code did and then adapts within a few samples.
     */
    fun stillCeiling(): Float {
        val sorted = synchronized(recent) { if (recent.isEmpty()) null else recent.sorted() }
            ?: return STILL_ABS
        val median = sorted[sorted.size / 2].coerceAtMost(REST_CAP)
        return max(STILL_ABS, median * STILL_FACTOR + STILL_MARGIN)
    }
}

/**
 * Pure so it can be tested without a `SensorManager`, the same reason
 * `requiredPermissions` was pulled out of `MainActivity`.
 *
 * Every value returned is a member of `Vocabulary.MOTION`, and a new one has
 * to be appended there before it can be returned here. Consumers that cannot
 * carry strings send the index into that list instead.
 */
internal fun classifyMotion(
    accelRms: Float,
    gyroRms: Float,
    stillCeiling: Float
): String = when {
    accelRms <= stillCeiling && gyroRms < MotionFloor.STILL_GYRO -> Vocabulary.STILL
    // rotation without much translation is a hand turning the phone over
    gyroRms > 0.5f && accelRms < 0.9f -> Vocabulary.HANDLED
    accelRms < 0.55f -> Vocabulary.HANDLED
    accelRms < 3.0f -> Vocabulary.WALKING
    // a vehicle is sustained acceleration with little rotation: a walk
    // swings the phone, a car does not
    gyroRms < 0.35f -> Vocabulary.VEHICLE
    else -> Vocabulary.WALKING
}
