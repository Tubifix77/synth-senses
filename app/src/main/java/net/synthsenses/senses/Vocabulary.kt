package net.synthsenses.senses

/**
 * The closed string vocabularies a percept uses, in a fixed order.
 *
 * These exist because a consumer that cannot carry strings has to carry
 * ordinals instead, and an ordinal is only meaningful if the order is a
 * promise. Potluck is the first such consumer: its values are at most 8 bytes,
 * so `body.motion` crosses as `0..3` and the label lives in an enum on the far
 * side. See `docs/ROADMAP.md`.
 *
 * The failure this guards against is silent in both repos at once. Add a fifth
 * motion state in the middle of [MOTION] and every ordinal after it shifts; a
 * receiver keeps decoding, keeps reporting, and is simply wrong from then on.
 * Nothing throws, and no test anywhere would have gone red before this file.
 *
 * So: **append, never insert, and never reorder.** `VocabularyTest` pins the
 * exact lists, and a change to one of them is meant to fail that test loudly
 * enough that the wire consequence gets considered rather than discovered.
 * Appending is the cheap case — an unknown-to-them ordinal is a receiver
 * problem on one value, not a silent relabelling of all of them.
 */
internal object Vocabulary {

    const val STILL = "still"
    const val HANDLED = "handled"
    const val WALKING = "walking"
    const val VEHICLE = "vehicle"

    /** Every value `BodySensor.classifyMotion` can return. */
    val MOTION = listOf(STILL, HANDLED, WALKING, VEHICLE)

    const val FACE_UP = "face_up"
    const val FACE_DOWN = "face_down"
    const val UPRIGHT = "upright"
    const val TILTED = "tilted"
    const val POCKET = "pocket"
    const val POSTURE_UNKNOWN = "unknown"

    /**
     * Every value `BodySensor.posture` can return. Unlike [MOTION] this one
     * really does have an unknown: a phone with no gravity sensor cannot be
     * guessed at, and saying so is the degrade-never-fabricate invariant.
     */
    val POSTURE = listOf(FACE_UP, FACE_DOWN, UPRIGHT, TILTED, POCKET, POSTURE_UNKNOWN)

    /**
     * Android's thermal ladder, least to most severe, as `SelfSensor` reports
     * it. Ordered by severity rather than by name on purpose — an ordinal from
     * this list can be compared, not just equated.
     *
     * `UNKNOWN` is deliberately absent. It is not a rung on the ladder; it
     * means the platform would not say, which is absence and belongs in
     * whatever a consumer uses for absence rather than at severity 7.
     */
    const val THERMAL_NONE = "NONE"
    const val THERMAL_LIGHT = "LIGHT"
    const val THERMAL_MODERATE = "MODERATE"
    const val THERMAL_SEVERE = "SEVERE"
    const val THERMAL_CRITICAL = "CRITICAL"
    const val THERMAL_EMERGENCY = "EMERGENCY"
    const val THERMAL_SHUTDOWN = "SHUTDOWN"

    val THERMAL = listOf(
        THERMAL_NONE, THERMAL_LIGHT, THERMAL_MODERATE, THERMAL_SEVERE,
        THERMAL_CRITICAL, THERMAL_EMERGENCY, THERMAL_SHUTDOWN
    )

    const val THERMAL_UNKNOWN = "UNKNOWN"
}
