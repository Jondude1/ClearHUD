package com.jondude1.clearhud

import com.jondude1.clearhud.hud.LaneHint
import com.jondude1.clearhud.hud.Maneuver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Current turn-by-turn guidance, parsed from Google Maps. */
data class NavState(
    val maneuver: Maneuver,
    val distanceMeters: Int,
    val road: String,
    val instruction: String,
    val etaSeconds: Int? = null,
    val remainingMeters: Int? = null,
    val arrivalClock: String? = null,
    val lanes: List<LaneHint> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class ObdState(
    val connected: Boolean = false,
    val deviceName: String? = null,
    val speedKmh: Int? = null,
    val rpm: Int? = null,
    val coolantC: Int? = null,
    val voltage: Double? = null,
    val status: String = "Not connected",
)

data class Alert(val app: String, val title: String, val text: String, val at: Long = System.currentTimeMillis())

enum class LinkStatus { OFF, SCANNING, CONNECTING, CONNECTED, FAILED }

/** Single place every data source writes to and every display reads from. */
object HudBus {
    val nav = MutableStateFlow<NavState?>(null)
    val gpsSpeedMps = MutableStateFlow<Float?>(null)
    val heading = MutableStateFlow<Float?>(null)
    val speedLimitMph = MutableStateFlow<Int?>(null)
    val obd = MutableStateFlow(ObdState())
    val alert = MutableStateFlow<Alert?>(null)
    val hudLink = MutableStateFlow(LinkStatus.OFF)
    val hudLinkDetail = MutableStateFlow("")
    val driveRunning = MutableStateFlow(false)
    val log = MutableStateFlow<List<String>>(emptyList())

    fun log(msg: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        log.update { (listOf("$stamp  $msg") + it).take(80) }
    }

    /** Best available speed: OBD (exact) beats GPS. */
    fun speedMph(): Int? {
        obd.value.speedKmh?.let { return (it * 0.621371).toInt() }
        return gpsSpeedMps.value?.let { (it * 2.23694f).toInt() }
    }
}
