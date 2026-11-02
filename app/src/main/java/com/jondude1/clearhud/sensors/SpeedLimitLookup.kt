package com.jondude1.clearhud.sensors

import android.location.Location
import com.jondude1.clearhud.HudBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Posted speed limits from OpenStreetMap (Overpass API). Looks up the roads
 * within ~30 m, prefers the one whose direction matches travel, and only
 * re-queries after moving ~250 m or 20 s, so it stays light on data.
 */
class SpeedLimitLookup(private val scope: CoroutineScope) {
    private var last: Location? = null
    private var lastAt = 0L
    private var job: Job? = null

    fun onFix(loc: Location) {
        val prev = last
        val now = System.currentTimeMillis()
        val moved = prev?.distanceTo(loc) ?: Float.MAX_VALUE
        if (job?.isActive == true) return
        if (moved < 250f && now - lastAt < 20_000) return
        if (prev != null && moved < 25f) return // parked
        last = loc; lastAt = now
        job = scope.launch(Dispatchers.IO) {
            runCatching { query(loc) }
                .onSuccess { HudBus.speedLimitMph.value = it }
                .onFailure { HudBus.log("Speed limit lookup failed: ${it.message}") }
        }
    }

    private fun query(loc: Location): Int? {
        val q = "[out:json][timeout:8];way(around:30,${loc.latitude},${loc.longitude})[highway][maxspeed];out tags geom;"
        val url = URL("https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(q, "UTF-8"))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 10000
            setRequestProperty("User-Agent", "ClearHUD/1.0 (github.com/Jondude1/clearhud)")
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val ways = JSONObject(body).optJSONArray("elements") ?: return null
        var best: Pair<Int, Double>? = null
        for (i in 0 until ways.length()) {
            val w = ways.getJSONObject(i)
            val mph = parseMaxspeed(w.optJSONObject("tags")?.optString("maxspeed") ?: continue) ?: continue
            val score = if (loc.hasBearing()) bearingMismatch(w, loc) else 0.0
            if (best == null || score < best.second) best = mph to score
        }
        return best?.first
    }

    /** Angle (deg) between travel direction and the road's nearest segment. */
    private fun bearingMismatch(way: JSONObject, loc: Location): Double {
        val g = way.optJSONArray("geometry") ?: return 90.0
        var bestDist = Double.MAX_VALUE; var bestDiff = 90.0
        for (i in 0 until g.length() - 1) {
            val a = g.getJSONObject(i); val b = g.getJSONObject(i + 1)
            val la = Location("").apply { latitude = a.getDouble("lat"); longitude = a.getDouble("lon") }
            val lb = Location("").apply { latitude = b.getDouble("lat"); longitude = b.getDouble("lon") }
            val d = (loc.distanceTo(la) + loc.distanceTo(lb)).toDouble()
            if (d < bestDist) {
                bestDist = d
                val diff = abs(((la.bearingTo(lb) - loc.bearing + 540) % 360) - 180).toDouble()
                bestDiff = minOf(diff, 180 - diff) // roads are two-way
            }
        }
        return bestDiff
    }

    private fun parseMaxspeed(v: String): Int? {
        val n = Regex("""\d+""").find(v)?.value?.toIntOrNull() ?: return null
        return if (v.contains("mph", true)) n else (n * 0.621371).roundToInt()
    }
}
