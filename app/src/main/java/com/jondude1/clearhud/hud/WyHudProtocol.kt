package com.jondude1.clearhud.hud

import android.util.Base64
import java.nio.charset.Charset
import kotlin.math.roundToInt

/**
 * Packet builder for the WYHUD (Wiiyii) head-up display.
 *
 * Frame on the wire: ASCII  "^" + base64(frame) + "$"
 * frame = 0x31 0x30 | checksum hi | checksum lo | 0x07 | command | payload...
 * checksum = 16-bit sum of every frame byte except the two checksum bytes.
 */
object WyHudProtocol {
    const val CMD_TURN = 1
    const val CMD_CAMERA = 3
    const val CMD_LANES = 4
    const val CMD_ROAD = 6
    const val CMD_TRIP = 9

    private val textCharset: Charset = runCatching { Charset.forName("GB2312") }.getOrDefault(Charsets.US_ASCII)

    fun frame(command: Int, payload: ByteArray): ByteArray {
        val body = byteArrayOf(0x07, command.toByte()) + payload
        var sum = 0x31 + 0x30
        for (b in body) sum += b.toInt() and 0xFF
        sum = sum and 0xFFFF
        val raw = byteArrayOf(0x31, 0x30, (sum shr 8).toByte(), sum.toByte()) + body
        val b64 = Base64.encodeToString(raw, Base64.NO_WRAP)
        return "^$b64$".toByteArray(Charsets.US_ASCII)
    }

    private fun u16(v: Int) = byteArrayOf((v shr 8 and 0xFF).toByte(), (v and 0xFF).toByte())

    /** Next maneuver and distance to it in meters; [then] is the maneuver after it, if known. */
    fun turn(maneuver: Maneuver, meters: Int, then: Maneuver? = null): ByteArray {
        val m = meters.coerceAtLeast(0)
        val payload = u16(maneuver.wyCode) + u16(m and 0xFFFF) + byteArrayOf(1) +
            u16((m ushr 16) and 0xFFFF) + u16((then ?: maneuver).wyCode)
        return frame(CMD_TURN, payload)
    }

    /** Remaining trip time and distance. */
    fun trip(secondsLeft: Int, metersLeft: Int): ByteArray {
        val minutes = (secondsLeft / 60.0).roundToInt().coerceIn(0, 255 * 60 + 59)
        val tenths = (metersLeft / 100.0).roundToInt().coerceIn(0, 0xFFFF)
        return frame(CMD_TRIP, byteArrayOf((minutes / 60).toByte(), (minutes % 60).toByte()) + u16(tenths))
    }

    /** Street name / short text line. Long names are trimmed to keep packets small. */
    fun road(text: String): ByteArray? {
        val t = text.trim().take(24)
        if (t.isEmpty()) return null
        val bytes = t.toByteArray(textCharset)
        return frame(CMD_ROAD, byteArrayOf(bytes.size.toByte()) + bytes)
    }

    /** Speed camera ahead (meters), or null/<=0 to clear. */
    fun camera(meters: Int?): ByteArray {
        val p = if (meters == null || meters <= 0) byteArrayOf(0, 0, 0)
        else byteArrayOf(1) + u16(meters.coerceAtMost(0xFFFF))
        return frame(CMD_CAMERA, p)
    }

    /** Up to 12 lanes; unused slots are blank. */
    fun lanes(lanes: List<LaneHint>): ByteArray? {
        if (lanes.isEmpty()) return null
        val p = ByteArray(12) { i ->
            val l = lanes.getOrNull(i)
            if (l == null) 15.toByte() else (l.kind.wyBits or if (l.recommended) 0x80 else 0).toByte()
        }
        return frame(CMD_LANES, p)
    }
}
