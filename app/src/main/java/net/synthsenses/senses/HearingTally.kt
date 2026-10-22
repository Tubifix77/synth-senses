package net.synthsenses.senses

import kotlin.math.log10
import kotlin.math.pow

/**
 * What the room sounded like since the last percept went out, rather than in
 * the one second the last sample happened to catch.
 *
 * The phone classifies sound every sample, but in a steady scene habituation
 * sends a percept only on the two-minute heartbeat, and each percept carried
 * the classification of its own single 975 ms window. Every other sample's
 * result was thrown away. A companion reading this through Potluck therefore
 * saw one random second every two minutes: with the TV on it was told "quiet"
 * or "other" almost every time, while across the same evening the classifier's
 * top sound was Speech 18 times in 60 and Music 4 -- it heard the TV, and the
 * percept discarded the evidence.
 *
 * So every sample is tallied, and each sent percept carries the tally as
 * `hearing.recent`. The current sample's own `events` are unchanged, and so is
 * habituation: tokens still come from the current sample only.
 *
 * Two details that matter:
 *
 *  - Only samples where the classifier actually ran are counted as heard. With
 *    no model, or a model that threw, an empty event list says nothing about
 *    the room, the same distinction `Hearing.classifier` exists to make.
 *  - Loudness is averaged as **energy**, not as decibels. The plain mean of
 *    -40 and -60 dB is -50, but a room that was loud half the time and silent
 *    the rest carried about -43 dB of sound on average; averaging the
 *    logarithms understates anything intermittent.
 */
internal class HearingTally {

    companion object {
        /** Distinct sounds kept per interval; a long heartbeat cannot grow it unbounded. */
        const val MAX_NAMES = 10
    }

    private val tops = HashMap<String, Int>()
    private var samples = 0
    private var classified = 0
    private var unnamed = 0
    private var powerSum = 0.0

    fun add(h: Hearing) {
        samples += 1
        powerSum += 10.0.pow(h.levelDb / 10.0)
        // A sample with no working classifier is level-only evidence.
        if (h.classifier != null && h.classifier != "ok") return
        classified += 1
        val top = h.events.maxByOrNull { it.conf }?.name
        if (top == null) unnamed += 1 else tops[top] = (tops[top] ?: 0) + 1
    }

    /** Null until something has been sampled since the last reset. */
    fun summary(): Recent? {
        if (samples == 0) return null
        val kept = tops.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(MAX_NAMES)
            .associate { it.key to it.value }
        return Recent(
            samples = samples,
            classified = classified,
            unnamed = unnamed,
            top = kept,
            levelDbMean = (10.0 * log10(powerSum / samples)).toFloat()
        )
    }

    fun reset() {
        tops.clear()
        samples = 0
        classified = 0
        unnamed = 0
        powerSum = 0.0
    }
}
