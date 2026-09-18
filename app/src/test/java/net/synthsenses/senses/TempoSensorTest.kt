package net.synthsenses.senses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * Solar position, against the real Kotlin.
 *
 * The original implementation double-counted time of day and returned the same
 * elevation for midnight and noon. The night assertions below are precisely the
 * ones that bug would have failed, so they are the regression guard.
 *
 * Reference values are almanac figures for Copenhagen, 55.68 N 12.57 E.
 */
class TempoSensorTest {

    private val LAT = 55.68
    private val LON = 12.57

    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(y, mo - 1, d, h, mi, 0)
        }.timeInMillis

    private fun elevationAt(y: Int, mo: Int, d: Int, h: Int, mi: Int): Double =
        TempoSensor.solarElevation(
            LAT, LON, TempoSensor.daysSinceJ2000(utc(y, mo, d, h, mi))
        )

    @Test
    fun `midnight and noon are not the same — the original bug`() {
        val noon = elevationAt(2026, 6, 21, 10, 0)
        val midnight = elevationAt(2026, 6, 21, 0, 0)

        assertTrue("summer local noon should be high: $noon", noon > 50.0)
        assertTrue("summer midnight must be below the horizon: $midnight", midnight < 0.0)
        assertTrue(
            "midnight and noon must differ by a lot, got $noon vs $midnight",
            noon - midnight > 50.0
        )
    }

    @Test
    fun `elevation matches almanac within a degree or two`() {
        assertEquals("summer noon", 55.2, elevationAt(2026, 6, 21, 10, 0), 2.0)
        assertEquals("summer night", -10.2, elevationAt(2026, 6, 21, 0, 0), 2.0)
        assertEquals("winter noon", 10.9, elevationAt(2026, 12, 21, 11, 0), 2.0)
        assertEquals("winter night", -57.7, elevationAt(2026, 12, 21, 23, 0), 2.0)
    }

    @Test
    fun `the subsolar point at equinox noon is near overhead`() {
        val e = TempoSensor.solarElevation(
            0.0, 0.0, TempoSensor.daysSinceJ2000(utc(2026, 3, 20, 12, 0))
        )
        assertEquals("equator, equinox, noon UTC", 90.0, e, 3.0)
    }

    @Test
    fun `sunrise and sunset match the almanac to within a few minutes`() {
        val tz = TimeZone.getTimeZone("Europe/Copenhagen")
        val midday = utc(2026, 6, 21, 12, 0)
        val events = TempoSensor.sunEvents(LAT, LON, midday, tz)

        assertNotNull("solstice has both a sunrise and a sunset", events)
        val rise = events!!.sunriseMs!!
        val set = events.sunsetMs!!

        fun localHhMm(ms: Long): Pair<Int, Int> {
            val c = Calendar.getInstance(tz).apply { timeInMillis = ms }
            return c.get(Calendar.HOUR_OF_DAY) to c.get(Calendar.MINUTE)
        }

        val (rh, rm) = localHhMm(rise)
        val (sh, sm) = localHhMm(set)

        // almanac: 04:26 and 21:58 local (CEST)
        val riseMin = rh * 60 + rm
        val setMin = sh * 60 + sm
        assertEquals("sunrise ${rh}:${rm}", 4 * 60 + 26, riseMin.toDouble(), 5.0)
        assertEquals("sunset ${sh}:${sm}", 21 * 60 + 58, setMin.toDouble(), 5.0)

        val lengthMin = (set - rise) / 60_000
        assertTrue("solstice day length near 17.5 h, got $lengthMin min", lengthMin in 1020..1080)
    }

    @Test
    fun `winter days are much shorter than summer days`() {
        val tz = TimeZone.getTimeZone("Europe/Copenhagen")
        val summer = TempoSensor.sunEvents(LAT, LON, utc(2026, 6, 21, 12, 0), tz)!!
        val winter = TempoSensor.sunEvents(LAT, LON, utc(2026, 12, 21, 12, 0), tz)!!

        val s = (summer.sunsetMs!! - summer.sunriseMs!!) / 60_000
        val w = (winter.sunsetMs!! - winter.sunriseMs!!) / 60_000

        assertTrue("summer $s min should dwarf winter $w min", s > w * 2)
        assertTrue("winter day is around 7 h, got $w min", w in 380..460)
    }

    @Test
    fun `polar summer has no sunset`() {
        val tz = TimeZone.getTimeZone("UTC")
        // Longyearbyen, well inside the arctic circle, at the solstice
        val events = TempoSensor.sunEvents(78.22, 15.63, utc(2026, 6, 21, 12, 0), tz)
        assertNotNull(events)
        assertTrue(
            "the midnight sun genuinely has no rise or set",
            events!!.sunriseMs == null && events.sunsetMs == null
        )
    }

    @Test
    fun `without a location it degrades to clock only`() {
        val t = TempoSensor.sample(utc(2026, 6, 21, 12, 0), at = null)
        assertEquals(null, t.solarElevationDeg)
        assertEquals(null, t.isDaylight)
        assertTrue("still names a part of day", t.partOfDay.isNotBlank())
        assertTrue("still knows the weekday", t.dayOfWeek.isNotBlank())
    }

    @Test
    fun `with a location it fills in the solar fields`() {
        val t = TempoSensor.sample(
            utc(2026, 6, 21, 10, 0), at = Place(LAT, LON, 50f)
        )
        assertNotNull(t.solarElevationDeg)
        assertEquals(true, t.isDaylight)
        assertTrue("daytime at the solstice", t.solarElevationDeg!! > 40f)
        assertNotNull("day length known", t.dayLengthMin)
    }
}
