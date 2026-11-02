package com.jondude1.clearhud.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.jondude1.clearhud.*
import com.jondude1.clearhud.hud.Maneuver
import com.jondude1.clearhud.hud.WyHudProtocol
import com.jondude1.clearhud.nav.NavListenerService
import com.jondude1.clearhud.update.Updater
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ClearTheme { AppRoot() } }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    DRIVE("Drive", Icons.Outlined.Speed),
    DEVICES("Devices", Icons.Outlined.Bluetooth),
    SETTINGS("Settings", Icons.Outlined.Tune),
}

@Composable
private fun AppRoot() {
    var tab by remember { mutableStateOf(Tab.DRIVE) }
    Scaffold(
        containerColor = C.bg,
        bottomBar = {
            NavigationBar(containerColor = C.card, tonalElevation = 0.dp) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t, onClick = { tab = t },
                        icon = { Icon(t.icon, null) }, label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = C.teal, selectedTextColor = C.teal,
                            indicatorColor = C.teal.copy(alpha = 0.12f),
                            unselectedIconColor = C.dim, unselectedTextColor = C.dim,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            UpdateBanner()
            when (tab) {
                Tab.DRIVE -> DriveTab()
                Tab.DEVICES -> DevicesTab()
                Tab.SETTINGS -> SettingsTab()
            }
        }
    }
}

// ───────────────────────── Drive ─────────────────────────

@Composable
private fun DriveTab() {
    val ctx = LocalContext.current
    val running by HudBus.driveRunning.collectAsState()
    val link by HudBus.hudLink.collectAsState()
    val linkDetail by HudBus.hudLinkDetail.collectAsState()
    val nav by HudBus.nav.collectAsState()
    val obd by HudBus.obd.collectAsState()
    var perms by remember { mutableStateOf(Perms.check(ctx)) }
    LifecycleResumeEffect(Unit) { perms = Perms.check(ctx); onPauseOrDispose { } }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        perms = Perms.check(ctx)
    }
    val accent = C.accents.getOrElse(Prefs.accent(ctx)) { C.teal }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Header("ClearHUD", "Heads-up display")

        // Live preview of the phone HUD
        Box(Modifier.fillMaxWidth().aspectRatio(2.1f).clip(RoundedCornerShape(20.dp)).border(1.dp, C.line, RoundedCornerShape(20.dp))) {
            HudScreen(accent, Prefs.overspeedMargin(ctx), Modifier.fillMaxSize(), compact = true)
        }

        if (!perms.allGood) {
            SetupCard(perms,
                onRuntime = { launcher.launch(Perms.runtime()) },
                onNotif = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton(
                if (running) "Stop drive" else "Start drive",
                if (running) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                primary = !running, modifier = Modifier.weight(1f),
            ) {
                if (running) DriveService.stop(ctx) else {
                    if (!perms.runtimeOk) launcher.launch(Perms.runtime()) else DriveService.start(ctx)
                }
            }
            BigButton("Phone HUD", Icons.Outlined.Fullscreen, primary = false, modifier = Modifier.weight(1f)) {
                if (!running && perms.runtimeOk) DriveService.start(ctx)
                ctx.startActivity(Intent(ctx, HudActivity::class.java))
            }
        }

        StatusRow(Icons.Outlined.Monitor, "WYHUD", when (link) {
            LinkStatus.CONNECTED -> "Connected"; LinkStatus.OFF -> if (Prefs.useWyHud(ctx)) "Starts with drive" else "Disabled"
            else -> linkDetail
        }, link == LinkStatus.CONNECTED)
        StatusRow(Icons.Outlined.Navigation, "Google Maps", nav?.let { "${it.maneuver.label} · ${it.road}".trim(' ', '·') }
            ?: if (perms.notifOk) "Waiting for navigation" else "Needs notification access", nav != null)
        StatusRow(Icons.Outlined.DirectionsCar, "OBD", if (Prefs.useObd(ctx)) obd.status else "Off — enable in Settings", obd.connected)
    }
}

// ───────────────────────── Devices ─────────────────────────

@SuppressLint("MissingPermission")
@Composable
private fun DevicesTab() {
    val ctx = LocalContext.current
    val log by HudBus.log.collectAsState()
    val link by HudBus.hudLink.collectAsState()
    var obdAddr by remember { mutableStateOf(Prefs.obdAddress(ctx)) }
    val bonded = remember {
        runCatching {
            (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.bondedDevices
                .map { (it.name ?: it.address) to it.address }.sortedBy { it.first }
        }.getOrDefault(emptyList())
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Header("Devices", "HUD, OBD and test tools")

        Panel("WYHUD") {
            Text("Saved: ${Prefs.hudAddress(ctx) ?: "none — will scan for \"WYHUD\""}", color = C.dim, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { Prefs.setHudAddress(ctx, null) }) { Text("Forget & rescan") }
        }

        Panel("Send test to WYHUD") {
            if (link != LinkStatus.CONNECTED) Text("Start a drive first so the HUD is connected.", color = C.dim, fontSize = 14.sp)
            val tests = listOf(
                "Left 250 ft" to { WyHudProtocol.turn(Maneuver.LEFT, 76) },
                "Right 0.5 mi" to { WyHudProtocol.turn(Maneuver.RIGHT, 805) },
                "Slight right" to { WyHudProtocol.turn(Maneuver.SLIGHT_RIGHT, 300) },
                "U-turn" to { WyHudProtocol.turn(Maneuver.UTURN_LEFT, 150) },
                "Roundabout" to { WyHudProtocol.turn(Maneuver.ROUNDABOUT, 200) },
                "Arrive" to { WyHudProtocol.turn(Maneuver.ARRIVE, 30) },
                "ETA 1:05 / 26 mi" to { WyHudProtocol.trip(65 * 60, 41_843) },
                "Road: Main St" to { WyHudProtocol.road("Main St") },
            )
            FlowRowCompat(tests.map { (label, f) -> label to { DriveService.hud?.send(f()) ?: Unit } })
            Spacer(Modifier.height(8.dp))
            Text("Phone HUD demo", color = C.dim, fontSize = 13.sp)
            FlowRowCompat(listOf(
                "Demo turn" to { HudBus.nav.value = NavState(Maneuver.RIGHT, 180, "Erie St", "Turn right onto Erie St", 14 * 60, 9_800, "2:14 PM") },
                "Demo alert" to { HudBus.alert.value = Alert("Messages", "Jenna", "On my way home") },
                "Clear" to { HudBus.nav.value = null },
            ))
        }

        Panel("Veepeak OBD adapter") {
            Text("Pair it in Android Bluetooth settings first (Classic models), then pick it here. BLE models show up once seen nearby.",
                color = C.dim, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            if (bonded.isEmpty()) Text("No paired devices found.", color = C.dim, fontSize = 14.sp)
            bonded.forEach { (name, addr) ->
                val sel = addr == obdAddr
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                    obdAddr = addr; Prefs.setObdAddress(ctx, addr); Prefs.setUseObd(ctx, true)
                }.background(if (sel) C.teal.copy(alpha = 0.10f) else Color.Transparent).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = sel, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = C.teal))
                    Spacer(Modifier.width(10.dp))
                    Column { Text(name, color = C.text); Text(addr, color = C.dim, fontSize = 12.sp) }
                }
            }
        }

        Panel("Activity log") {
            if (log.isEmpty()) Text("Nothing yet.", color = C.dim, fontSize = 14.sp)
            log.take(30).forEach { Text(it, color = C.dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
        }
    }
}

// ───────────────────────── Settings ─────────────────────────

@Composable
private fun SettingsTab() {
    val ctx = LocalContext.current
    var useWy by remember { mutableStateOf(Prefs.useWyHud(ctx)) }
    var useObd by remember { mutableStateOf(Prefs.useObd(ctx)) }
    var alerts by remember { mutableStateOf(Prefs.forwardAlerts(ctx)) }
    var alertsWy by remember { mutableStateOf(Prefs.alertsToWyHud(ctx)) }
    var margin by remember { mutableFloatStateOf(Prefs.overspeedMargin(ctx).toFloat()) }
    var accent by remember { mutableIntStateOf(Prefs.accent(ctx)) }
    var mirror by remember { mutableIntStateOf(Prefs.mirror(ctx)) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Header("Settings", "Changes apply next drive")

        Panel("Displays") {
            Toggle("Drive the WYHUD", "Send turns, ETA and street names over Bluetooth", useWy) { useWy = it; Prefs.setUseWyHud(ctx, it) }
            Toggle("Read OBD data", "Speed, RPM, coolant and voltage from your Veepeak", useObd) { useObd = it; Prefs.setUseObd(ctx, it) }
        }

        Panel("Phone HUD look") {
            Text("Accent", color = C.text)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                C.accents.forEachIndexed { i, c ->
                    Box(Modifier.size(36.dp).clip(CircleShape).background(c)
                        .border(if (i == accent) 3.dp else 0.dp, Color.White, CircleShape)
                        .clickable { accent = i; Prefs.setAccent(ctx, i) })
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Reflection", color = C.text)
            Text("Pick how the phone sits on the dash. You can also tap the HUD to cycle.", color = C.dim, fontSize = 13.sp)
            listOf("Normal (no mirror)", "Flat, top toward windshield", "Flip left/right").forEachIndexed { i, l ->
                Row(Modifier.fillMaxWidth().clickable { mirror = i; Prefs.setMirror(ctx, i) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(mirror == i, null, colors = RadioButtonDefaults.colors(selectedColor = C.teal))
                    Spacer(Modifier.width(10.dp)); Text(l, color = C.text)
                }
            }
        }

        Panel("Speed") {
            Text("Warn when over the limit by ${margin.toInt()} mph", color = C.text)
            Slider(margin, { margin = it; Prefs.setOverspeedMargin(ctx, it.toInt()) }, valueRange = 0f..15f, steps = 14,
                colors = SliderDefaults.colors(thumbColor = C.teal, activeTrackColor = C.teal))
            Text("Speed limits come from OpenStreetMap and can be missing or out of date.", color = C.dim, fontSize = 13.sp)
        }

        Panel("Calls & messages") {
            Toggle("Show on phone HUD", "Caller or sender name for a few seconds", alerts) { alerts = it; Prefs.setForwardAlerts(ctx, it) }
            Toggle("Also show on WYHUD", "Uses the street-name line briefly", alertsWy) { alertsWy = it; Prefs.setAlertsToWyHud(ctx, it) }
        }

        UpdateCard()
    }
}

// ───────────────────────── Updates ─────────────────────────

private object UpdateState {
    var lastCheck = 0L
    val available = mutableStateOf<Updater.Release?>(null)
    val progress = mutableStateOf<Float?>(null)
    val message = mutableStateOf<String?>(null)
}

@Composable
private fun UpdateBanner() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var dismissed by remember { mutableStateOf(false) }
    val rel by UpdateState.available
    val prog by UpdateState.progress
    LifecycleResumeEffect(Unit) {
        if (System.currentTimeMillis() - UpdateState.lastCheck > 30 * 60_000) {
            UpdateState.lastCheck = System.currentTimeMillis()
            scope.launch { runCatching { Updater.check() }.getOrNull()?.let { UpdateState.available.value = it } }
        }
        onPauseOrDispose { }
    }
    val r = rel ?: return
    if (dismissed) return
    Row(Modifier.fillMaxWidth().background(C.teal.copy(alpha = 0.12f)).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(if (prog != null) "Downloading… ${((prog ?: 0f) * 100).toInt()}%" else "Update available — ${r.tag}",
            color = C.text, modifier = Modifier.weight(1f))
        if (prog == null) {
            TextButton({ dismissed = true }) { Text("Later", color = C.dim) }
            TextButton({ scope.launch { doUpdate(ctx, r) } }) { Text("Update", color = C.teal, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun UpdateCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val msg by UpdateState.message
    val prog by UpdateState.progress
    Panel("App") {
        Text("Version ${BuildConfig.VERSION_NAME}", color = C.text)
        msg?.let { Text(it, color = C.dim, fontSize = 13.sp) }
        prog?.let { LinearProgressIndicator({ it }, Modifier.fillMaxWidth().padding(top = 8.dp), color = C.teal) }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = {
            scope.launch {
                UpdateState.message.value = "Checking…"
                val r = runCatching { Updater.check() }.getOrNull()
                UpdateState.lastCheck = System.currentTimeMillis()
                if (r == null) UpdateState.message.value = "You're on the latest version."
                else { UpdateState.available.value = r; doUpdate(ctx, r) }
            }
        }) { Text("Check for updates") }
    }
}

private suspend fun doUpdate(ctx: Context, r: Updater.Release) {
    try {
        UpdateState.message.value = "Downloading ${r.tag}…"
        UpdateState.progress.value = 0f
        val f = Updater.download(ctx, r) { UpdateState.progress.value = it }
        UpdateState.progress.value = null
        if (!Updater.install(ctx, f)) UpdateState.message.value = "Allow installs from ClearHUD, then tap Update again."
        else UpdateState.message.value = null
    } catch (e: Exception) {
        UpdateState.progress.value = null
        UpdateState.message.value = "Update failed: ${e.message}"
    }
}

// ───────────────────────── Permissions ─────────────────────────

private data class PermState(val runtimeOk: Boolean, val notifOk: Boolean) {
    val allGood get() = runtimeOk && notifOk
}

private object Perms {
    fun runtime(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    fun check(c: Context) = PermState(
        runtimeOk = runtime().all { c.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED },
        notifOk = NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName),
    )
}

@Composable
private fun SetupCard(p: PermState, onRuntime: () -> Unit, onNotif: () -> Unit) {
    Panel("Finish setup", highlight = true) {
        if (!p.runtimeOk) SetupStep("Bluetooth & location", "To find your HUD and read speed", "Allow", onRuntime)
        if (!p.notifOk) SetupStep("Notification access", "To read Google Maps directions", "Open", onNotif)
    }
}

@Composable
private fun SetupStep(title: String, sub: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, color = C.text); Text(sub, color = C.dim, fontSize = 13.sp) }
        Button(onClick, colors = ButtonDefaults.buttonColors(containerColor = C.teal, contentColor = Color.Black)) { Text(action) }
    }
}

// ───────────────────────── Building blocks ─────────────────────────

@Composable
private fun Header(title: String, sub: String) {
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Text(title, color = C.text, fontSize = 30.sp, fontWeight = FontWeight.Light, letterSpacing = 0.5.sp)
        Text(sub.uppercase(), color = C.teal.copy(alpha = 0.8f), fontSize = 12.sp, letterSpacing = 3.sp)
    }
}

@Composable
private fun Panel(title: String, highlight: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.card)
            .border(1.dp, if (highlight) C.teal.copy(alpha = 0.5f) else C.line, RoundedCornerShape(18.dp)).padding(18.dp),
    ) {
        Text(title.uppercase(), color = C.dim, fontSize = 12.sp, letterSpacing = 2.5.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun StatusRow(icon: ImageVector, title: String, detail: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.card).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (ok) C.teal else C.dim)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = C.text, fontWeight = FontWeight.Medium)
            Text(detail, color = C.dim, fontSize = 13.sp, maxLines = 1)
        }
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (ok) C.green else C.line))
    }
}

@Composable
private fun BigButton(label: String, icon: ImageVector, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg = if (primary) C.teal else C.cardHi
    val fg = if (primary) Color.Black else C.text
    Row(modifier.height(58.dp).clip(RoundedCornerShape(16.dp)).background(bg).clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = fg); Spacer(Modifier.width(8.dp))
        Text(label, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
private fun Toggle(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, color = C.text); Text(sub, color = C.dim, fontSize = 13.sp) }
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = C.teal))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowCompat(items: List<Pair<String, () -> Unit>>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (label, action) ->
            AssistChip(onClick = action, label = { Text(label) },
                colors = AssistChipDefaults.assistChipColors(labelColor = C.text), border = AssistChipDefaults.assistChipBorder(true, borderColor = C.line))
        }
    }
}
