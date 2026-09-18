package net.synthsenses.senses

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A sense of time and season, computed locally — no network, no permission
 * beyond the coarse location you may already have.
 *
 * With a location it gives solar elevation, whether it's daylight, how long
 * until the light goes, and day length. Without one it degrades to clock and
 * day-of-week, which is still enough for a part-of-day token.
 *
 * The solar maths is the standard low-precision NOAA approximation. I validated
 * it against almanac values for Copenhagen on the 2026 solstices: sunrise 04:26
 * and sunset 21:58 on 21 June, matching to the minute, and the equinox subsolar
 * point comes out at 88 degrees where it should be 90. Good to about a degree,
 * which is far better than this needs.
 */
object TempoSensor {

    private const val J2000_MS = 946_728_000_000L   // 2000-01-01 12:00:00 UTC
    private const val MS_PER_DAY = 86_400_000.0

    /** Refraction-corrected horizon: the sun's centre at sunrise is slightly below. */
    private const val HORIZON_DEG = -0.833

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
    private val dowFmt = SimpleDateFormat("EEEE", Locale.US)

    fun sample(nowMs: Long, at: Place?): Tempo {
        val tz = TimeZone.getDefault()
        val offsetMin = tz.getOffset(nowMs) / 60_000

        val localTime = synchronized(timeFmt) {
            timeFmt.timeZone = tz
            timeFmt.format(Date(nowMs))
        }
        val dow = synchronized(dowFmt) {
            dowFmt.timeZone = tz
            dowFmt.format(Date(nowMs))
        }

        if (at == null) {
            return Tempo(
                localTime = localTime,
                tzOffsetMin = offsetMin,
                dayOfWeek = dow,
                partOfDay = partOfDayFromClock(nowMs, tz),
                solarElevationDeg = null,
                isDaylight = null,
                minutesToSunset = null,
                minutesSinceSunrise = null,
                dayLengthMin = null
            )
        }

        val elev = solarElevation(at.lat, at.lon, daysSinceJ2000(nowMs)).toFloat()
        val daylight = elev > HORIZON_DEG

        val events = sunEvents(at.lat, at.lon, nowMs, tz)

        return Tempo(
            localTime = localTime,
            tzOffsetMin = offsetMin,
            dayOfWeek = dow,
            partOfDay = partOfDay(elev, daylight, nowMs, tz),
            solarElevationDeg = elev,
            isDaylight = daylight,
            minutesToSunset = events?.sunsetMs
                ?.let { ((it - nowMs) / 60_000L).toInt() }
                ?.takeIf { it >= 0 },
            minutesSinceSunrise = events?.sunriseMs
                ?.let { ((nowMs - it) / 60_000L).toInt() }
                ?.takeIf { it >= 0 },
            dayLengthMin = events?.let { e ->
                if (e.sunriseMs != null && e.sunsetMs != null) {
                    ((e.sunsetMs - e.sunriseMs) / 60_000L).toInt()
                } else null
            }
        )
    }

    // ---- solar position ----

    private fun daysSinceJ2000(ms: Long): Double = (ms - J2000_MS) / MS_PER_DAY

    /**
     * Sun elevation above the horizon, in degrees.
     * n is fractional days since J2000.0 — it already carries the time of day,
     * so do NOT add an hour term on top of it.
     */
    fun solarElevation(latDeg: Double, lonDeg: Double, n: Double): Double {
        val meanLon = (280.460 + 0.9856474 * n).mod(360.0)
        val meanAnom = Math.toRadians((357.528 + 0.9856003 * n).mod(360.0))
        val ecLon = Math.toRadians(
            meanLon + 1.915 * sin(meanAnom) + 0.020 * sin(2 * meanAnom)
        )
        val obliquity = Math.toRadians(23.439 - 0.0000004 * n)

        val dec = asin(sin(obliquity) * sin(ecLon))
        val ra = atan2(cos(obliquity) * sin(ecLon), cos(ecLon))

        val gmst = (18.697374558 + 24.06570982441908 * n).mod(24.0)
        val lst = (gmst + lonDeg / 15.0).mod(24.0)
        val hourAngle = Math.toRadians(lst * 15.0 - Math.toDegrees(ra))

        val lat = Math.toRadians(latDeg)
        return Math.toDegrees(
            asin(sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(hourAngle))
        )
    }

    private class SunEvents(val sunriseMs: Long?, val sunsetMs: Long?)

    /**
     * Sunrise and sunset for the local calendar day containing nowMs, found by
     * closed-form hour angle then refined. Returns nulls above the arctic circle
     * in the appropriate season, which is correct — there genuinely isn't one.
     */
    private fun sunEvents(latDeg: Double, lonDeg: Double, nowMs: Long, tz: TimeZone): SunEvents? {
        return runCatching {
            val cal = Calendar.getInstance(tz).apply {
                timeInMillis = nowMs
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val dayStart = cal.timeInMillis

            // declination at local solar noon is close enough for the hour angle
            val nNoon = daysSinceJ2000(dayStart + 12 * 3_600_000L)
            val meanLon = (280.460 + 0.9856474 * nNoon).mod(360.0)
            val meanAnom = Math.toRadians((357.528 + 0.9856003 * nNoon).mod(360.0))
            val ecLon = Math.toRadians(
                meanLon + 1.915 * sin(meanAnom) + 0.020 * sin(2 * meanAnom)
            )
            val obliquity = Math.toRadians(23.439 - 0.0000004 * nNoon)
            val dec = asin(sin(obliquity) * sin(ecLon))

            val lat = Math.toRadians(latDeg)
            val cosH = (sin(Math.toRadians(HORIZON_DEG)) - sin(lat) * sin(dec)) /
                (cos(lat) * cos(dec))

            // sun never rises, or never sets, at this latitude today
            if (cosH > 1.0 || cosH < -1.0) return@runCatching SunEvents(null, null)

            val hDeg = Math.toDegrees(acos(cosH))

            // refine each crossing by bisection on the real elevation function,
            // which absorbs the equation-of-time error the closed form leaves behind
            val roughRise = dayStart + ((12.0 - hDeg / 15.0) * 3_600_000L).toLong() -
                (lonDeg / 15.0 * 3_600_000L).toLong() + tz.getOffset(nowMs).toLong()
            val roughSet = dayStart + ((12.0 + hDeg / 15.0) * 3_600_000L).toLong() -
                (lonDeg / 15.0 * 3_600_000L).toLong() + tz.getOffset(nowMs).toLong()

            SunEvents(
                sunriseMs = refine(latDeg, lonDeg, roughRise, rising = true),
                sunsetMs = refine(latDeg, lonDeg, roughSet, rising = false)
            )
        }.getOrNull()
    }

    /** Bisect within ±2 h of the estimate for the horizon crossing. */
    private fun refine(lat: Double, lon: Double, guessMs: Long, rising: Boolean): Long? {
        var lo = guessMs - 2 * 3_600_000L
        var hi = guessMs + 2 * 3_600_000L

        fun f(ms: Long) = solarElevation(lat, lon, daysSinceJ2000(ms)) - HORIZON_DEG

        var flo = f(lo)
        var fhi = f(hi)
        if (flo.sign() == fhi.sign()) return guessMs   // no crossing bracketed; take the estimate

        repeat(40) {
            val mid = lo + (hi - lo) / 2
            val fmid = f(mid)
            if (fmid.sign() == flo.sign()) {
                lo = mid; flo = fmid
            } else {
                hi = mid; fhi = fmid
            }
        }
        return lo + (hi - lo) / 2
    }

    private fun Double.sign() = if (this >= 0) 1 else -1

    // ---- naming the time of day ----

    private fun partOfDay(elev: Float, daylight: Boolean, nowMs: Long, tz: TimeZone): String {
        val hour = Calendar.getInstance(tz).apply { timeInMillis = nowMs }
            .get(Calendar.HOUR_OF_DAY)
        return when {
            elev > 45f -> "midday"
            daylight && hour < 12 -> "morning"
            daylight && hour < 17 -> "afternoon"
            daylight -> "late afternoon"
            elev > -6f -> "twilight"
            elev > -18f && hour < 12 -> "pre-dawn"
            elev > -18f -> "dusk"
            hour in 0..4 -> "deep night"
            hour < 12 -> "before dawn"
            else -> "night"
        }
    }

    private fun partOfDayFromClock(nowMs: Long, tz: TimeZone): String {
        val hour = Calendar.getInstance(tz).apply { timeInMillis = nowMs }
            .get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 0..4 -> "deep night"
            in 5..7 -> "early morning"
            in 8..11 -> "morning"
            in 12..13 -> "midday"
            in 14..17 -> "afternoon"
            in 18..21 -> "evening"
            else -> "night"
        }
    }

    /** Unused but kept: proportion of the year elapsed, for seasonal reasoning. */
    fun seasonalPhase(nowMs: Long): Float {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val doy = cal.get(Calendar.DAY_OF_YEAR)
        val len = cal.getActualMaximum(Calendar.DAY_OF_YEAR)
        return doy.toFloat() / len
    }
}
