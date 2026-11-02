package com.jondude1.clearhud.hud

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.jondude1.clearhud.HudBus
import com.jondude1.clearhud.LinkStatus
import com.jondude1.clearhud.Prefs
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.UUID

/**
 * Bluetooth LE link to the WYHUD. Finds it by name or saved address, raises the
 * MTU, and pushes frames through a single queue so writes never overlap.
 * Reconnects on its own while [start]ed.
 */
@SuppressLint("MissingPermission")
class WyHudLink(private val ctx: Context, private val scope: CoroutineScope) {

    companion object {
        val SERVICE: UUID = UUID.fromString("0000fee9-0000-1000-8000-00805f9b34fb")
        val WRITE: UUID = UUID.fromString("d44bc439-abfd-45a2-b575-925416129600")
        const val NAME = "WYHUD"
    }

    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var running = false
    private val queue = Channel<ByteArray>(capacity = 64)
    private var writeAck: CompletableDeferred<Unit>? = null
    private var pumpJob: Job? = null
    private var retryJob: Job? = null

    val isConnected get() = HudBus.hudLink.value == LinkStatus.CONNECTED

    fun start() {
        if (running) return
        running = true
        pumpJob = scope.launch { pump() }
        connectOrScan()
    }

    fun stop() {
        running = false
        retryJob?.cancel()
        pumpJob?.cancel()
        stopScan()
        gatt?.close(); gatt = null; writeChar = null
        HudBus.hudLink.value = LinkStatus.OFF
    }

    fun send(frame: ByteArray?) {
        if (frame != null && isConnected) queue.trySend(frame)
    }

    private fun status(s: LinkStatus, detail: String) {
        HudBus.hudLink.value = s
        HudBus.hudLinkDetail.value = detail
        HudBus.log("HUD: $detail")
    }

    private fun connectOrScan() {
        val a = adapter ?: return status(LinkStatus.FAILED, "No Bluetooth on this phone")
        if (!a.isEnabled) return status(LinkStatus.FAILED, "Bluetooth is off").also { scheduleRetry(5000) }
        val saved = Prefs.hudAddress(ctx)
        if (saved != null) connect(a.getRemoteDevice(saved)) else scan()
    }

    // ---- scanning ----
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            val name = r.device.name ?: r.scanRecord?.deviceName ?: return
            if (!name.startsWith(NAME, ignoreCase = true)) return
            stopScan()
            Prefs.setHudAddress(ctx, r.device.address)
            connect(r.device)
        }
        override fun onScanFailed(errorCode: Int) {
            status(LinkStatus.FAILED, "Scan failed ($errorCode)"); scheduleRetry(8000)
        }
    }
    private var scanning = false

    private fun scan() {
        val sc = adapter?.bluetoothLeScanner ?: return scheduleRetry(5000)
        status(LinkStatus.SCANNING, "Looking for $NAME…")
        scanning = true
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        // Name filter is unreliable on some phones, so scan broadly and match in the callback.
        runCatching { sc.startScan(null as List<ScanFilter>?, settings, scanCb) }
        scope.launch {
            delay(15_000)
            if (scanning) { stopScan(); status(LinkStatus.FAILED, "$NAME not found — is it powered?"); scheduleRetry(10_000) }
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCb) }
    }

    private fun scheduleRetry(ms: Long) {
        if (!running) return
        retryJob?.cancel()
        retryJob = scope.launch { delay(ms); if (running && !isConnected) connectOrScan() }
    }

    // ---- connection ----
    private fun connect(device: BluetoothDevice) {
        status(LinkStatus.CONNECTING, "Connecting to ${device.address}…")
        gatt?.close()
        gatt = if (Build.VERSION.SDK_INT >= 23)
            device.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        else device.connectGatt(ctx, false, gattCb)
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestMtu(80)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                writeChar = null
                g.close(); if (gatt == g) gatt = null
                if (running) { status(LinkStatus.CONNECTING, "Lost HUD, reconnecting…"); scheduleRetry(2000) }
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, st: Int) {
            mtu = if (st == BluetoothGatt.GATT_SUCCESS) newMtu else 23
            g.discoverServices()
        }
        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            val c = g.getService(SERVICE)?.getCharacteristic(WRITE)
            if (c == null) {
                status(LinkStatus.FAILED, "Connected device isn't a WYHUD")
                Prefs.setHudAddress(ctx, null); g.disconnect(); return
            }
            writeChar = c
            status(LinkStatus.CONNECTED, "Connected (MTU $mtu)")
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            writeAck?.complete(Unit)
        }
    }

    // ---- writing ----
    private suspend fun pump() {
        for (frame in queue) {
            val c = writeChar ?: continue
            val g = gatt ?: continue
            val chunk = (mtu - 3).coerceIn(20, 244)
            var i = 0
            while (i < frame.size) {
                val part = frame.copyOfRange(i, minOf(frame.size, i + chunk))
                writeOne(g, c, part)
                i += chunk
                if (i < frame.size) delay(50)
            }
            delay(15)
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun writeOne(g: BluetoothGatt, c: BluetoothGattCharacteristic, data: ByteArray) {
        val ack = CompletableDeferred<Unit>().also { writeAck = it }
        val type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, data, type) == BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = type; c.value = data; g.writeCharacteristic(c)
        }
        if (ok) withTimeoutOrNull(400) { ack.await() }
    }
}
