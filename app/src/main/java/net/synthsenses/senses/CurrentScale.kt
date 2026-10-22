package net.synthsenses.senses

import kotlin.math.abs
import kotlin.math.max

/**
 * Which unit this phone reports its battery current in.
 *
 * Android documents `BATTERY_PROPERTY_CURRENT_NOW` as **microamps**, and
 * `SelfSensor` divided by 1000 accordingly. Huawei reports **milliamps**, so on
 * the P20 Lite the published figure was out by three orders of magnitude:
 * across a night on a wall charger `current_ma` ranged −0.64 to +1.01, which
 * would mean a phone drawing half a milliamp while charging — impossible, and
 * plausible-looking enough to be believed. The true figures were about −640 and
 * +1010 mA.
 *
 * There is no list of which vendors do which, and guessing per model would rot.
 * But the two unit scales do not overlap anywhere near a real phone:
 *
 *  - a microamp-reporting device awake and sampling sensors reports tens to
 *    hundreds of **thousands** (300 mA is 300 000 µA)
 *  - a milliamp-reporting device reports tens to hundreds
 *
 * So one observation above [MICRO_FLOOR] settles it as microamps, and nothing
 * else can reach there: a milliamp device would have to be drawing 20 amps.
 * Until either answer is established the reading is **null**, because reporting
 * a number that might be 1000× wrong is worse than reporting nothing — the
 * receiver cannot tell, and this one looked entirely reasonable.
 *
 * Mirrors [MotionFloor]: a device-dependent constant that cannot be assumed,
 * so it is measured on the device instead.
 */
internal class CurrentScale {

    companion object {
        /**
         * 20 mA expressed in microamps. An awake phone exceeds this within a
         * sample or two; nothing reporting milliamps can reach it.
         */
        const val MICRO_FLOOR = 20_000

        /**
         * How many readings must stay small before "this device reports
         * milliamps" is believed rather than "this phone happens to be idle".
         */
        const val MIN_SAMPLES = 20
    }

    private var maxSeen = 0
    private var samples = 0

    /** True once the unit is known either way. */
    val settled: Boolean get() = maxSeen >= MICRO_FLOOR || samples >= MIN_SAMPLES

    /** `"microamp"`, `"milliamp"`, or null while undetermined. Exposed by `status`. */
    val unit: String?
        get() = when {
            maxSeen >= MICRO_FLOOR -> "microamp"
            samples >= MIN_SAMPLES -> "milliamp"
            else -> null
        }

    /**
     * Take one raw reading and return it in milliamps, or null if the unit is
     * not yet established.
     *
     * `Int.MIN_VALUE` means the property is unsupported and 0 means no reading;
     * neither is evidence about the unit, so neither is counted.
     */
    fun milliamps(raw: Int): Float? {
        if (raw == Int.MIN_VALUE || raw == 0) return null
        maxSeen = max(maxSeen, abs(raw))
        samples += 1
        return when {
            maxSeen >= MICRO_FLOOR -> raw / 1000f
            samples >= MIN_SAMPLES -> raw.toFloat()
            else -> null
        }
    }
}
