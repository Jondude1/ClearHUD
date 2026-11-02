package com.jondude1.clearhud.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.jondude1.clearhud.HudBus

/** GPS speed + heading, and feeds positions to the speed-limit lookup. */
@SuppressLint("MissingPermission")
class GpsTracker(ctx: Context, private val onFix: (Location) -> Unit) : LocationListener {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun start() {
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        }.onFailure { HudBus.log("GPS unavailable: ${it.message}") }
    }

    fun stop() {
        runCatching { lm.removeUpdates(this) }
        HudBus.gpsSpeedMps.value = null
    }

    override fun onLocationChanged(loc: Location) {
        HudBus.gpsSpeedMps.value = if (loc.hasSpeed()) loc.speed else null
        if (loc.hasBearing() && loc.speed > 1.5f) HudBus.heading.value = loc.bearing
        onFix(loc)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) { HudBus.gpsSpeedMps.value = null }
}
