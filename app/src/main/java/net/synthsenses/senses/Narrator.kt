package net.synthsenses.senses

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns a percept into prose, on-device and deterministically. The receiving AI
 * can read this directly or ignore it and use the structured fields.
 *
 * Order matters: place, then what's visible, then audible, then body, then
 * internal state, then time. It reads like a creature reporting in.
 */
object Narrator {

    fun describe(p: Percept): String {
        val parts = mutableListOf<String>()

        placeSentence(p)?.let { parts += it }
        visionSentences(p).forEach { parts += it }
        hearingSentence(p)?.let { parts += it }
        parts += bodySentence(p)
        interoceptionSentence(p)?.let { parts += it }
        parts += tempoSentence(p)

        return parts.joinToString(" ")
    }

    private fun placeSentence(p: Percept): String? {
        val r = p.radio ?: return null
        val sb = StringBuilder()

        when {
            r.placeName != null && !r.placeIsNew ->
                sb.append("I am in ${r.placeName}.")
            r.placeIsNew && r.placeId != null ->
                sb.append("This is somewhere I have not been before (${r.placeId}).")
            r.placeId != null ->
                sb.append("I recognise this place (${r.placeId}).")
            else -> return null
        }

        val crowd = when {
            r.bleCount == 0 -> null
            r.bleCount <= 2 -> "almost nothing else is broadcasting nearby"
            r.bleCount <= 8 -> "a handful of devices are nearby"
            r.bleCount <= 20 -> "many devices are nearby"
            else -> "this is a crowded radio environment"
        }
        crowd?.let { sb.append(" ${it.replaceFirstChar { c -> c.uppercase() }}.") }

        val known = r.bleNamed.mapNotNull { it.name }.distinct().take(3)
        if (known.isNotEmpty()) {
            sb.append(" I can see ${known.joinToString(", ")}.")
        }
        return sb.toString()
    }

    private fun visionSentences(p: Percept): List<String> {
        val v = p.vision ?: return emptyList()
        val out = mutableListOf<String>()

        val light = lightPhrase(v, p.body.lux)
        val things = v.labels.take(3).joinToString(", ") { it.name.lowercase(Locale.US) }
        val counted = countObjects(v.objects)

        val sb = StringBuilder(light)
        if (things.isNotEmpty() || counted.isNotEmpty()) {
            sb.append(" I can see ")
            sb.append(listOf(things, counted).filter { it.isNotEmpty() }.joinToString("; "))
            sb.append(".")
        }
        out += sb.toString()

        v.text?.takeIf { it.isNotBlank() }?.let {
            out += "Text reads \"${it.replace('\n', ' ').trim()}\"."
        }
        return out
    }

    private fun lightPhrase(v: Vision, lux: Float?): String = when {
        lux == null -> when {
            v.brightness < 0.15f -> "In near-darkness."
            v.brightness < 0.35f -> "In dim light."
            v.brightness > 0.8f -> "In bright light."
            else -> "In even light."
        }
        lux < 5f -> "In darkness."
        lux < 60f -> "In dim light."
        lux < 400f -> "In ordinary indoor light."
        lux < 5_000f -> "In bright light, likely near a window."
        else -> "In full daylight."
    }

    private fun countObjects(objs: List<Seen>): String {
        if (objs.isEmpty()) return ""
        return objs.groupBy { it.name }
            .entries
            .sortedByDescending { it.value.size }
            .take(3)
            .joinToString(", ") { (name, list) ->
                val n = list.size
                val noun = name.lowercase(Locale.US)
                if (n == 1) "one $noun" else "$n ${plural(noun)}"
            }
    }

    private fun plural(n: String) = when {
        n == "person" -> "people"
        n.endsWith("s") || n.endsWith("x") || n.endsWith("ch") -> "${n}es"
        else -> "${n}s"
    }

    private fun hearingSentence(p: Percept): String? {
        val h = p.hearing ?: return null
        val bits = mutableListOf<String>()

        val events = h.events.take(3)
            .filter { !it.name.equals("Silence", true) }
            .joinToString(", ") { it.name.lowercase(Locale.US) }

        bits += if (events.isNotEmpty()) {
            "I hear $events"
        } else when {
            h.levelDb < -55f -> "It is near-silent"
            h.levelDb < -35f -> "It is quiet"
            h.levelDb < -18f -> "There is steady ambient noise"
            else -> "It is loud here"
        }

        h.transcript?.takeIf { it.isNotBlank() }?.let {
            bits += "someone said \"${it.trim()}\""
        }
        return bits.joinToString(" — ") + "."
    }

    private fun bodySentence(p: Percept): String {
        val b = p.body
        val sb = StringBuilder("I am ")
        sb.append(
            when (b.motion) {
                "still" -> "still"
                "handled" -> "being handled"
                "walking" -> "moving at walking pace"
                "vehicle" -> "travelling in a vehicle"
                else -> "in an unclear state of motion"
            }
        )
        sb.append(
            when (b.posture) {
                "face_up" -> ", lying face up"
                "face_down" -> ", lying face down"
                "upright" -> ", held upright"
                "tilted" -> ", tilted"
                "pocket" -> " and enclosed — a pocket or a bag, probably"
                else -> ""
            }
        )
        b.headingDeg?.let { if (b.posture != "pocket") sb.append(", facing ${compass(it)}") }
        sb.append(".")

        b.stepsDelta?.let { if (it > 0) sb.append(" $it steps since I last spoke.") }

        b.magneticAnomaly?.let {
            if (it > 25f) sb.append(" Something strongly magnetic is close by.")
            else if (it > 15f) sb.append(" The magnetic field here is disturbed.")
        }

        b.pressureDeltaPerMin?.let {
            if (abs(it) > 0.35f) {
                sb.append(
                    if (it > 0) " Pressure is rising quickly — descending, or a door just closed."
                    else " Pressure is falling quickly — ascending, or a door just opened."
                )
            }
        }
        return sb.toString()
    }

    private fun interoceptionSentence(p: Percept): String? {
        val s = p.self
        val bits = mutableListOf<String>()

        when (s.thermal) {
            "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN" ->
                bits += "I am overheating badly"
            "MODERATE" -> bits += "I am running hot"
            "LIGHT" -> bits += "I am a little warm"
        }

        s.batteryTempC?.let {
            if (it >= 42f && s.thermal == "NONE") bits += "my battery is at ${it.roundToInt()}°C"
        }

        when {
            s.charging -> bits += "I am charging"
            s.batteryPct in 0f..0.10f ->
                bits += "I have ${(s.batteryPct * 100).roundToInt()}% left and nothing feeding me"
            s.batteryPct in 0f..0.25f ->
                bits += "I am down to ${(s.batteryPct * 100).roundToInt()}%"
        }

        if (s.memLow) bits += "memory is tight"
        if (s.net == "none") bits += "I have no network — this will reach you late"

        if (bits.isEmpty()) return null
        return bits.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
    }

    private fun tempoSentence(p: Percept): String {
        val t = p.tempo
        val sb = StringBuilder("It is ${t.partOfDay} on ${t.dayOfWeek}, ${t.localTime}")

        t.minutesToSunset?.let { m ->
            if (t.isDaylight == true && m in 1..90) {
                sb.append("; the light goes in ${m} minutes")
            }
        }
        if (t.isDaylight == false && t.solarElevationDeg != null && t.solarElevationDeg < -18f) {
            sb.append("; full dark")
        }
        sb.append(".")
        return sb.toString()
    }

    private fun compass(deg: Int): String {
        val dirs = listOf(
            "north", "north-east", "east", "south-east",
            "south", "south-west", "west", "north-west"
        )
        val i = (((deg % 360) + 360) % 360 + 22) / 45
        return dirs[i % 8]
    }
}
