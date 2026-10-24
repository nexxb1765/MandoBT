package com.mandobt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Convierte el teléfono en un dispositivo HID Bluetooth compuesto:
 *  ID 1 = teclado, ID 2 = mouse, ID 3 = gamepad.
 */
@SuppressLint("MissingPermission")
object Hid {

    private val DESC: ByteArray = intArrayOf(
        // ---------- Teclado (ID 1) ----------
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x01,
        0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
        0x95, 0x05, 0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x91, 0x02,
        0x95, 0x01, 0x75, 0x03, 0x91, 0x01,
        0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
        0xC0,
        // ---------- Mouse (ID 2) ----------
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, 0x02, 0x09, 0x01, 0xA1, 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x05, 0x81, 0x01,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
        0xC0, 0xC0,
        // ---------- Gamepad (ID 3): 16 botones, hat, 4 ejes, 2 gatillos ----------
        0x05, 0x01, 0x09, 0x05, 0xA1, 0x01, 0x85, 0x03,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x10, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x10, 0x81, 0x02,
        0x05, 0x01, 0x09, 0x39, 0x15, 0x00, 0x25, 0x07, 0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14,
        0x75, 0x04, 0x95, 0x01, 0x81, 0x42,
        0x65, 0x00, 0x35, 0x00, 0x45, 0x00,
        0x75, 0x04, 0x95, 0x01, 0x81, 0x03,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x04, 0x81, 0x02,
        0x09, 0x33, 0x09, 0x34, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x02, 0x81, 0x02,
        0xC0
    ).map { it.toByte() }.toByteArray()

    private lateinit var ctx: Context
    private var adapter: BluetoothAdapter? = null
    private var proxy: BluetoothHidDevice? = null
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()

    private var started = false
    private var unsupported = false
    private var wantConnect = true
    private var attempts = 0
    private var lastPad = ByteArray(0)

    var host: BluetoothDevice? = null
        private set
    var registered = false
        private set
    var onState: (() -> Unit)? = null
    var mouseButtons = 0

    private val retry = Runnable { autoReconnect() }

    fun init(c: Context) {
        if (started) return
        ctx = c.applicationContext
        adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter?.isEnabled != true) { notifyState(); return }
        started = true
        adapter!!.getProfileProxy(ctx, listener, BluetoothProfile.HID_DEVICE)
        notifyState()
    }

    fun status(): String = when {
        adapter == null && !started -> "Iniciando…"
        adapter?.isEnabled != true -> "Bluetooth apagado: enciéndelo"
        unsupported -> "Tu teléfono no soporta modo mando Bluetooth (HID)"
        proxy == null || !registered -> "Iniciando perfil HID…"
        host != null -> "● Conectado: ${host?.name ?: host?.address}"
        else -> "○ Listo, sin conexión"
    }

    fun bonded(): List<BluetoothDevice> = adapter?.bondedDevices?.toList() ?: emptyList()

    fun connect(dev: BluetoothDevice) {
        wantConnect = true
        attempts = 0
        Prefs.lastMac = dev.address
        host?.let { if (it.address != dev.address) proxy?.disconnect(it) }
        proxy?.connect(dev)
    }

    fun disconnect() {
        wantConnect = false
        main.removeCallbacks(retry)
        host?.let { proxy?.disconnect(it) }
    }

    // ---------- Reconexión automática ----------
    private fun autoReconnect() {
        if (!wantConnect || host != null || !registered) return
        val mac = Prefs.lastMac ?: return
        val dev = try { adapter?.getRemoteDevice(mac) } catch (e: Exception) { null } ?: return
        attempts++
        proxy?.connect(dev)
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        main.removeCallbacks(retry)
        if (wantConnect && host == null && attempts < 20) main.postDelayed(retry, 4000)
    }

    private fun notifyState() { main.post { onState?.invoke() } }

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            val hp = p as BluetoothHidDevice
            proxy = hp
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "Mando BT", "Mando, teclado y mouse", "MandoBT",
                BluetoothHidDevice.SUBCLASS1_COMBO, DESC
            )
            unsupported = !hp.registerApp(sdp, null, null, exec, cb)
            notifyState()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            proxy = null
            registered = false
            host = null
            started = false
            notifyState()
            main.postDelayed({ init(ctx) }, 2000)
        }
    }

    private val cb = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@Hid.registered = registered
            if (registered) main.post { attempts = 0; autoReconnect() }
            notifyState()
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    attempts = 0
                    lastPad = ByteArray(0)
                    main.removeCallbacks(retry)
                    Prefs.lastMac = device.address
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host?.address == device.address) host = null
                    if (wantConnect) main.post { scheduleReconnect() }
                }
            }
            notifyState()
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            proxy?.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray?) {
            proxy?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }
    }

    // ---------- Envío de reportes ----------
    private fun send(id: Int, data: ByteArray) {
        val h = host ?: return
        proxy?.sendReport(h, id, data)
    }

    fun keyboard(mods: Int, keys: List<Int>) {
        val d = ByteArray(8)
        d[0] = mods.toByte()
        keys.take(6).forEachIndexed { i, k -> d[2 + i] = k.toByte() }
        send(1, d)
    }

    fun mouse(dx: Int, dy: Int, wheel: Int = 0) {
        var x = dx; var y = dy; var w = wheel
        do {
            val cx = x.coerceIn(-127, 127)
            val cy = y.coerceIn(-127, 127)
            val cw = w.coerceIn(-127, 127)
            send(2, byteArrayOf(mouseButtons.toByte(), cx.toByte(), cy.toByte(), cw.toByte()))
            x -= cx; y -= cy; w -= cw
        } while (x != 0 || y != 0 || w != 0)
    }

    fun mouseButton(bit: Int, down: Boolean) {
        mouseButtons = if (down) mouseButtons or bit else mouseButtons and bit.inv()
        mouse(0, 0, 0)
    }

    fun pad(buttons: Int, hat: Int, lx: Int, ly: Int, rx: Int, ry: Int, lt: Int, rt: Int) {
        val d = byteArrayOf(
            buttons.toByte(), (buttons shr 8).toByte(), hat.toByte(),
            lx.toByte(), ly.toByte(), rx.toByte(), ry.toByte(), lt.toByte(), rt.toByte()
        )
        if (d.contentEquals(lastPad)) return
        lastPad = d
        send(3, d)
    }
}
