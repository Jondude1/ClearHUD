package com.jondude1.clearhud.hud

/**
 * Turn types ClearHUD understands. [wyCode] is the icon number the WYHUD
 * firmware draws for that maneuver (learned from its Bluetooth protocol).
 */
enum class Maneuver(val wyCode: Int, val label: String) {
    STRAIGHT(9, "Continue"),
    LEFT(2, "Turn left"),
    RIGHT(3, "Turn right"),
    SLIGHT_LEFT(4, "Slight left"),
    SLIGHT_RIGHT(5, "Slight right"),
    SHARP_LEFT(6, "Sharp left"),
    SHARP_RIGHT(7, "Sharp right"),
    UTURN_LEFT(8, "U-turn"),
    UTURN_RIGHT(19, "U-turn"),
    ROUNDABOUT(11, "Roundabout"),
    ROUNDABOUT_EXIT(12, "Exit roundabout"),
    ARRIVE(15, "Arrive"),
    UNKNOWN(13, "");

    val isLeft get() = this in setOf(LEFT, SLIGHT_LEFT, SHARP_LEFT, UTURN_LEFT)
}

/** One lane in a lane-guidance strip. */
data class LaneHint(val kind: Kind, val recommended: Boolean) {
    enum class Kind(val wyBits: Int) { STRAIGHT(0), LEFT(1), RIGHT(3), UTURN(5), SLIGHT_RIGHT(8) }
}
