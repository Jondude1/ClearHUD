package com.jondude1.clearhud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.jondude1.clearhud.hud.WyHudLink
import com.jondude1.clearhud.hud.WyHudProtocol
import com.jondude1.clearhud.sensors.GpsTracker
import com.jondude1.clearhud.sensors.ObdClient
import com.jondude1.clearhud.sensors.SpeedLimitLookup
import com.jondude1.clearhud.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Runs while driving: keeps the WYHUD link, GPS, speed-limit lookup and OBD
 * alive, and streams the current navigation to the WYHUD.
 */
class DriveService : Service() {

    companion object {
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "drive"
        @Volatile var hud: WyHudLink? = null

        fun start(c: Context) = androidx.core.content.ContextCompat.startForegroundService(c, Intent(c, DriveService::class.java))
        fun stop(c: Context) = c.startService(Intent(c, DriveService::class.java).setAction(ACTION_STOP))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var link: WyHudLink
    private lateinit var gps: GpsTracker
    private lateinit var limits: SpeedLimitLookup
    private lateinit var obd: ObdClient
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        goForeground()
        if (!started) { started = true; begin() }
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Driving", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, DriveService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("ClearHUD is running")
            .setContentText("Sending directions and speed to your HUD")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        runCatching { ServiceCompat.startForeground(this, 7, n, type) }
            .onFailure { HudBus.log("Couldn't start in background — open ClearHUD once: ${it.message}"); stopSelf() }
    }

    private fun begin() {
        HudBus.driveRunning.value = true
        HudBus.log("Drive started")
        link = WyHudLink(this, scope).also { hud = it }
        limits = SpeedLimitLookup(scope)
        gps = GpsTracker(this) { limits.onFix(it) }
        obd = ObdClient(this, scope)

        if (Prefs.useWyHud(this)) link.start()
        gps.start()
        if (Prefs.useObd(this)) obd.start()

        // Push navigation whenever it changes...
        scope.launch {
            HudBus.nav.collectLatest { sendNav(force = true) }
        }
        // ...and refresh every 2 s so the HUD never goes stale after a reconnect.
        scope.launch {
            while (isActive) { delay(2000); sendNav(force = false) }
        }
        // Show calls/messages on the WYHUD's text line for a few seconds.
        scope.launch {
            HudBus.alert.collectLatest { a ->
                if (a == null || !Prefs.alertsToWyHud(this@DriveService)) return@collectLatest
                val text = if (a.app == "Call") "CALL ${a.title}" else "MSG ${a.title}"
                repeat(3) { link.send(WyHudProtocol.road(text)); delay(2000) }
                sendNav(force = true)
            }
        }
    }

    private var lastRoad: String? = null

    private fun sendNav(force: Boolean) {
        if (!link.isConnected) return
        val n = HudBus.nav.value ?: return
        link.send(WyHudProtocol.turn(n.maneuver, n.distanceMeters))
        if (n.etaSeconds != null || n.remainingMeters != null)
            link.send(WyHudProtocol.trip(n.etaSeconds ?: 0, n.remainingMeters ?: 0))
        if (force || n.road != lastRoad) { link.send(WyHudProtocol.road(n.road)); lastRoad = n.road }
        WyHudProtocol.lanes(n.lanes)?.let(link::send)
    }

    override fun onDestroy() {
        if (started) {
            link.stop(); gps.stop(); obd.stop()
        }
        hud = null
        scope.cancel()
        HudBus.driveRunning.value = false
        HudBus.log("Drive stopped")
        super.onDestroy()
    }
}
