package net.synthsenses.senses

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A place with no name keeps having no name, across a save and a reload.
 *
 * This test has to run on a device, and that is the entire point of it.
 * `JSONObject.optString` on Android returns the four-character string
 * `"null"` for a `JSONObject.NULL` value rather than the fallback it is
 * given. The JVM unit tests shadow `android.jar`'s stub with the real
 * `org.json`, where `optString` honours the fallback — so the bug this
 * guards against is **invisible to every JVM test in this project** and shows
 * up only on hardware.
 *
 * It did show up on hardware, on the first evening: unnamed is the normal
 * case, since nothing names a place unless someone sends `name_place`, so
 * every place came back named `"null"` after one restart and the narration
 * announced "I am in null."
 *
 * `Link.kt` had already guarded the same call correctly with `isNull`, which
 * is what makes this an oversight rather than a surprise.
 */
@RunWith(AndroidJUnit4::class)
class PlaceNameRoundTripTest {

    private fun tempStore(name: String): File {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        return File(dir, name).also { it.delete() }
    }

    /** Two scans of the same imaginary room, so a place forms and persists. */
    private val fingerprint = mapOf(
        "aa11bb22cc" to -55, "dd33ee44ff" to -61, "1122334455" to -70,
        "6677889900" to -48, "abcdef0123" to -66
    )

    @Test
    fun an_unnamed_place_is_still_unnamed_after_a_reload() {
        val file = tempStore("places-roundtrip.json")

        val first = PlaceMemory(file)
        val made = first.recognise(fingerprint)
        assertEquals("a fingerprint should produce a place", true, made != null)
        assertNull("a fresh place has no name", made!!.name)
        // observe it again so the store is written with the place settled
        first.recognise(fingerprint)

        val reloaded = PlaceMemory(file)
        val seen = reloaded.recognise(fingerprint)
        assertEquals("the same place should come back", made.id, seen?.id)
        assertNull(
            "an unnamed place must not come back named \"null\": Android's " +
                "optString returns that string for JSONObject.NULL, and the " +
                "JVM tests cannot see it because they use the real org.json",
            seen?.name
        )
    }

    @Test
    fun a_real_name_survives_a_reload() {
        val file = tempStore("places-named.json")

        val first = PlaceMemory(file)
        val made = first.recognise(fingerprint)!!
        first.recognise(fingerprint)
        assertEquals(true, first.name(made.id, "kitchen"))

        val reloaded = PlaceMemory(file)
        assertEquals("kitchen", reloaded.recognise(fingerprint)?.name)
    }

    @Test
    fun the_listing_does_not_report_a_name_of_null_either() {
        // `places` is what a person reads, and it was showing `null` beside the
        // oldest places while newer ones correctly showed no name at all --
        // which is how the difference became visible in the first place.
        val file = tempStore("places-listing.json")
        val first = PlaceMemory(file)
        first.recognise(fingerprint)
        first.recognise(fingerprint)

        val listed = PlaceMemory(file).listJson()
        assertEquals("one place expected", 1, listed.length())
        val entry = listed.getJSONObject(0)
        assertEquals(
            "an unnamed place must serialise as JSON null, not the string",
            true, entry.isNull("name")
        )
    }
}
