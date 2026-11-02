package com.jondude1.clearhud.sensors

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import com.jondude1.clearhud.HudBus
import com.jondude1.clearhud.ObdState
import com.jondude1.clearhud.Prefs
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * ELM327-style OBD2 reader for Veepeak adapters. Works with both kinds:
 *  - Bluetooth Classic (paired in Android settings, serial profile)
 *  - Bluetooth LE ("OBDCheck BLE"-style, FFF0/FFE0 serial service)
 * Polls speed, RPM, coolant temp and battery voltage.
 */
@SuppressLint("MissingPermission")
class ObdClient(private val ctx: Context, private val scope: CoroutineScope) {

    private interface Transport {
        suspend fun write(s: String)
        suspend fun readUntilPrompt(timeoutMs: Long): String
        fun close()
    }

    private val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var job: Job? = null
    private var transport: Transport? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val addr = Prefs.obdAddress(ctx)
                if (addr == null) { set(ObdState(status = "Pick your Veepeak in Settings")); delay(5000); continue }
                runCatching { session(addr) }.onFailure {
                    set(ObdState(status = "OBD: ${it.message ?: "error"} — retrying"))
                }
                transport?.close(); transport = null
                delay(5000)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        transport?.close(); transport = null
        set(ObdState())
    }

    private fun set(s: ObdState) { HudBus.obd.value = s }

    private suspend fun session(addr: String) {
        val dev = adapter.getRemoteDevice(addr)
        val name = dev.name ?: addr
        set(ObdState(deviceName = name, status = "Connecting to $name…"))
        val t = if (dev.type == BluetoothDevice.DEVICE_TYPE_LE) openBle(dev) else openClassic(dev)
        transport = t
        for (cmd in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) {
            t.write(cmd); t.readUntilPrompt(if (cmd == "ATZ") 3000 else 1500)
        }
        set(ObdState(connected = true, deviceName = name, status = "Connected"))
        HudBus.log("OBD connected: $name")
        var tick = 0
        while (currentCoroutineContext().isActive) {
            val speed = pid(t, "010D")?.let { it[0] }
            val rpm = pid(t, "010C")?.let { (it[0] * 256 + it[1]) / 4 }
            val cur = HudBus.obd.value
            var coolant = cur.coolantC
            var volts = cur.voltage
            if (tick % 10 == 0) {
                coolant = pid(t, "0105")?.let { it[0] - 40 } ?: coolant
                volts = pid(t, "0142")?.let { (it[0] * 256 + it[1]) / 1000.0 } ?: run {
                    t.write("ATRV"); Regex("""(\d+\.\d)""").find(t.readUntilPrompt(1500))?.value?.toDoubleOrNull()
                } ?: volts
            }
            if (speed == null && rpm == null && tick > 3 && cur.speedKmh == null) {
                set(cur.copy(status = "Connected — engine off?"))
            } else {
                set(cur.copy(connected = true, speedKmh = speed, rpm = rpm, coolantC = coolant, voltage = volts, status = "Live"))
            }
            tick++
            delay(250)
        }
    }

    /** Sends a mode-01 PID and returns its data bytes, or null. */
    private suspend fun pid(t: Transport, cmd: String): IntArray? {
        t.write(cmd)
        val raw = t.readUntilPrompt(1500).uppercase().replace(Regex("[^0-9A-F]"), "")
        val key = "4" + cmd.substring(1)
        val i = raw.indexOf(key)
        if (i < 0) return null
        val hex = raw.substring(i + key.length)
        if (hex.length < 2) return null
        return IntArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16) }
    }

    // ---- Classic SPP ----
    private fun openClassic(dev: BluetoothDevice): Transport {
        val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        adapter.cancelDiscovery()
        val sock = runCatching { dev.createRfcommSocketToServiceRecord(spp).also { it.connect() } }
            .getOrElse { dev.createInsecureRfcommSocketToServiceRecord(spp).also { s -> s.connect() } }
        val inp: InputStream = sock.inputStream
        val out: OutputStream = sock.outputStream
        return object : Transport {
            override suspend fun write(s: String) { out.write("$s\r".toByteArray()); out.flush() }
            override suspend fun readUntilPrompt(timeoutMs: Long): String {
                val sb = StringBuilder()
                val end = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < end) {
                    if (inp.available() > 0) {
                        val c = inp.read().toChar()
                        if (c == '>') break
                        sb.append(c)
                    } else delay(10)
                }
                return sb.toString()
            }
            override fun close() { runCatching { sock.close() } }
        }
    }

    // ---- BLE serial ----
    private suspend fun openBle(dev: BluetoothDevice): Transport {
        val rx = Channel<ByteArray>(Channel.UNLIMITED)
        val ready = CompletableDeferred<Pair<BluetoothGatt, BluetoothGattCharacteristic>>()
        val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, st: Int, ns: Int) {
                if (ns == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
                else if (ns == BluetoothProfile.STATE_DISCONNECTED) {
                    ready.completeExceptionally(IllegalStateException("disconnected")); rx.close()
                }
            }
            @Suppress("DEPRECATION")
            override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
                val chars = g.services.flatMap { it.characteristics }
                val notify = chars.firstOrNull { it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 &&
                    it.service.uuid.toString().let { u -> u.startsWith("0000fff0") || u.startsWith("0000ffe0") || u.startsWith("e7810a71") } }
                    ?: chars.firstOrNull { it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 }
                val write = chars.firstOrNull { it.service == notify?.service &&
                    it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 }
                if (notify == null || write == null) { ready.completeExceptionally(IllegalStateException("no serial service")); return }
                g.setCharacteristicNotification(notify, true)
                notify.getDescriptor(cccd)?.let { d ->
                    if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    else { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
                }
                ready.complete(g to write)
            }
            @Deprecated("Deprecated in Java")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) { rx.trySend(c.value ?: return) }
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray) { rx.trySend(v) }
        }
        val gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
        val (g, w) = withTimeout(10_000) { ready.await() }
        delay(300)
        return object : Transport {
            @Suppress("DEPRECATION")
            override suspend fun write(s: String) {
                val data = "$s\r".toByteArray()
                if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(w, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                else { w.value = data; g.writeCharacteristic(w) }
            }
            override suspend fun readUntilPrompt(timeoutMs: Long): String {
                val sb = StringBuilder()
                withTimeoutOrNull(timeoutMs) {
                    while (true) {
                        val chunk = String(rx.receive())
                        val p = chunk.indexOf('>')
                        if (p >= 0) { sb.append(chunk, 0, p); break }
                        sb.append(chunk)
                    }
                }
                return sb.toString()
            }
            override fun close() { runCatching { gatt.disconnect(); gatt.close() } }
        }
    }
}
