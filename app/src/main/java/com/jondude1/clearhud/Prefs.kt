package com.jondude1.clearhud

import android.content.Context

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("clearhud", Context.MODE_PRIVATE)

    fun hudAddress(c: Context) = p(c).getString("hud_addr", null)
    fun setHudAddress(c: Context, v: String?) = p(c).edit().putString("hud_addr", v).apply()

    fun obdAddress(c: Context) = p(c).getString("obd_addr", null)
    fun setObdAddress(c: Context, v: String?) = p(c).edit().putString("obd_addr", v).apply()

    /** 0 = normal, 1 = flip top/bottom (phone flat, top toward windshield), 2 = flip left/right. */
    fun mirror(c: Context) = p(c).getInt("mirror_mode", 1)
    fun setMirror(c: Context, v: Int) = p(c).edit().putInt("mirror_mode", v).apply()

    fun overspeedMargin(c: Context) = p(c).getInt("over_margin", 5)
    fun setOverspeedMargin(c: Context, v: Int) = p(c).edit().putInt("over_margin", v).apply()

    fun forwardAlerts(c: Context) = p(c).getBoolean("alerts", true)
    fun setForwardAlerts(c: Context, v: Boolean) = p(c).edit().putBoolean("alerts", v).apply()

    fun alertsToWyHud(c: Context) = p(c).getBoolean("alerts_wy", true)
    fun setAlertsToWyHud(c: Context, v: Boolean) = p(c).edit().putBoolean("alerts_wy", v).apply()

    fun useWyHud(c: Context) = p(c).getBoolean("use_wy", true)
    fun setUseWyHud(c: Context, v: Boolean) = p(c).edit().putBoolean("use_wy", v).apply()

    fun useObd(c: Context) = p(c).getBoolean("use_obd", false)
    fun setUseObd(c: Context, v: Boolean) = p(c).edit().putBoolean("use_obd", v).apply()

    fun accent(c: Context) = p(c).getInt("accent", 0)
    fun setAccent(c: Context, v: Int) = p(c).edit().putInt("accent", v).apply()
}
