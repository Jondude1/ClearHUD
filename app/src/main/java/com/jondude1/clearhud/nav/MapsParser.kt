package com.jondude1.clearhud.nav

import com.jondude1.clearhud.NavState
import com.jondude1.clearhud.hud.Maneuver
import kotlin.math.roundToInt

/**
 * Turns the text of a Google Maps navigation notification into a [NavState].
 * Maps typically shows: title = "500 ft", text = "Turn left onto Elm St",
 * subtext = "12 min · 4.3 mi · 1:42 PM ETA". Field order varies, so every
 * field is searched.
 */
object MapsParser {
    private val distanceRx = Regex("""(\d+(?:[.,]\d+)?)\s*(mi|ft|yd|km|m)\b""", RegexOption.IGNORE_CASE)
    private val hoursRx = Regex("""(\d+)\s*(?:hr|h|hour)s?""", RegexOption.IGNORE_CASE)
    private val minsRx = Regex("""(\d+)\s*(?:min|m)\b""", RegexOption.IGNORE_CASE)
    private val clockRx = Regex("""\b(\d{1,2}:\d{2}\s*(?:AM|PM)?)""", RegexOption.IGNORE_CASE)
    private val roadRx = Regex("""\b(?:onto|on|toward|towards|to|via|for)\s+(.+)$""", RegexOption.IGNORE_CASE)

    fun parse(title: String?, text: String?, sub: String?, big: String?): NavState? {
        val fields = listOfNotNull(title, text, big).map { it.trim() }.filter { it.isNotEmpty() }
        if (fields.isEmpty()) return null

        // The distance-to-turn is the field that is (mostly) just a distance.
        val distField = fields.firstOrNull { f -> distanceRx.find(f)?.let { it.value.length >= f.length - 6 } == true }
        val distanceMeters = distField?.let { toMeters(distanceRx.find(it)!!) } ?: 0

        // Instruction = the longest non-distance field.
        val instruction = fields.filter { it != distField }.maxByOrNull { it.length }
            ?: distField ?: return null

        val maneuver = classify(instruction)
        val road = roadRx.find(instruction)?.groupValues?.get(1)?.trim()
            ?: if (maneuver == Maneuver.UNKNOWN || maneuver == Maneuver.STRAIGHT) instruction else ""

        var eta: Int? = null
        var remaining: Int? = null
        var clock: String? = null
        sub?.split('·', '•', '-')?.map { it.trim() }?.forEach { part ->
            when {
                clockRx.containsMatchIn(part) && part.contains("ETA", true) || part.contains("arrival", true) ->
                    clock = clockRx.find(part)?.value
                distanceRx.containsMatchIn(part) && remaining == null -> remaining = toMeters(distanceRx.find(part)!!)
                (hoursRx.containsMatchIn(part) || minsRx.containsMatchIn(part)) && eta == null -> {
                    val h = hoursRx.find(part)?.groupValues?.get(1)?.toInt() ?: 0
                    val m = minsRx.find(part.replace(hoursRx, ""))?.groupValues?.get(1)?.toInt() ?: 0
                    eta = (h * 60 + m) * 60
                }
                clockRx.containsMatchIn(part) && clock == null -> clock = clockRx.find(part)?.value
            }
        }

        return NavState(
            maneuver = maneuver,
            distanceMeters = distanceMeters,
            road = road.take(40),
            instruction = instruction,
            etaSeconds = eta,
            remainingMeters = remaining,
            arrivalClock = clock,
        )
    }

    private fun toMeters(m: MatchResult): Int {
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 0
        return when (m.groupValues[2].lowercase()) {
            "mi" -> v * 1609.344
            "ft" -> v * 0.3048
            "yd" -> v * 0.9144
            "km" -> v * 1000
            else -> v
        }.roundToInt()
    }

    fun classify(s: String): Maneuver {
        val t = s.lowercase()
        fun has(vararg w: String) = w.any { t.contains(it) }
        return when {
            has("destination", "arrive", "you have arrived", "on the left is", "on the right is") -> Maneuver.ARRIVE
            has("u-turn", "u turn", "make a u") -> Maneuver.UTURN_LEFT
            has("roundabout", "traffic circle", "rotary") ->
                if (has("exit the", "leave the")) Maneuver.ROUNDABOUT_EXIT else Maneuver.ROUNDABOUT
            has("sharp left") -> Maneuver.SHARP_LEFT
            has("sharp right") -> Maneuver.SHARP_RIGHT
            has("slight left", "keep left", "bear left", "fork left", "ramp on the left", "exit on the left", "merge left") -> Maneuver.SLIGHT_LEFT
            has("slight right", "keep right", "bear right", "fork right", "take exit", "take the exit", "ramp", "merge") -> Maneuver.SLIGHT_RIGHT
            has("turn left", "left onto", "left at", "left toward", "left to") -> Maneuver.LEFT
            has("turn right", "right onto", "right at", "right toward", "right to") -> Maneuver.RIGHT
            has("continue", "head ", "straight", "stay on", "toward", "follow") -> Maneuver.STRAIGHT
            else -> Maneuver.STRAIGHT
        }
    }
}
