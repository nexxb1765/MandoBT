package com.mandobt

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private val BG = Color.parseColor("#101216")
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var status: TextView
    private lateinit var panels: List<View>
    private lateinit var pad: GamepadView
    private lateinit var presetBtn: Button
    private lateinit var editBtn: Button
    private lateinit var editTools: LinearLayout

    private val pressedKeys = LinkedHashSet<Int>()
    private var mods = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Prefs.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        Hid.onState = { status.text = Hid.status() }
        status.text = Hid.status()
        ensurePerms()
    }

    override fun onResume() {
        super.onResume()
        if (hasBtPerm()) Hid.init(this)
        status.text = Hid.status()
    }

    override fun onPause() {
        super.onPause()
        pressedKeys.clear(); mods = 0
        Hid.keyboard(0, emptyList())
        Hid.mouseButtons = 0
        Hid.mouse(0, 0, 0)
        pad.releaseAll()
    }

    override fun onDestroy() {
        Hid.onState = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(f: Boolean) {
        super.onWindowFocusChanged(f)
        if (f) window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    // ---------- Permisos ----------
    private fun hasBtPerm() = Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun ensurePerms() {
        if (Build.VERSION.SDK_INT >= 31) {
            val need = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (need.isNotEmpty()) { requestPermissions(need.toTypedArray(), 1); return }
        }
        Hid.init(this)
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (hasBtPerm()) Hid.init(this)
        status.text = Hid.status()
    }

    // ---------- UI ----------
    private fun small(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 12f
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(12), dp(2), dp(12), dp(2))
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; maxLines = 1 }
        bar.addView(status, lp(0, WRAP, 1f))
        bar.addView(small("Conectar") { connectDialog() })
        bar.addView(small("Visible") {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
        })
        listOf("Mando", "Mouse", "Teclado", "Ajustes").forEachIndexed { i, n -> bar.addView(small(n) { show(i) }) }

        val content = FrameLayout(this)
        panels = listOf(gamepadPanel(), mousePanel(), keyboardPanel(), settingsPanel())
        panels.forEach { content.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }

        root.addView(bar, lp(MATCH, dp(40)))
        root.addView(content, lp(MATCH, 0, 1f))
        setContentView(root)
        show(0)
    }

    private fun show(i: Int) {
        panels.forEachIndexed { j, v -> v.visibility = if (i == j) View.VISIBLE else View.GONE }
    }

    // ---------- Conexión ----------
    private fun connectDialog() {
        val list = Hid.bonded()
        val b = AlertDialog.Builder(this).setTitle("Conectar a PC o teléfono")
        if (list.isEmpty()) {
            b.setMessage("No hay dispositivos emparejados.\n\n1) Toca «Visible».\n2) En tu PC/teléfono busca dispositivos Bluetooth y empareja con este teléfono.\n3) Vuelve aquí y toca «Conectar».")
        } else {
            val items = (list.map { it.name ?: it.address } + "Desconectar").toTypedArray()
            b.setItems(items) { _, i -> if (i < list.size) Hid.connect(list[i]) else Hid.disconnect() }
        }
        b.setPositiveButton("Ajustes Bluetooth") { _, _ ->
            startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        b.show()
    }

    // ---------- Panel Mando ----------
    private fun gamepadPanel(): View {
        pad = GamepadView(this)
        pad.preset = Prefs.preset
        pad.controls = Presets.load(pad.preset)

        presetBtn = small(pad.preset) { presetDialog() }
        editBtn = small("Editar botones") { toggleEdit() }
        editTools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        editTools.addView(small("Tamaño −") { pad.resize(-0.02f) })
        editTools.addView(small("Tamaño +") { pad.resize(0.02f) })
        editTools.addView(small("Restablecer") {
            Presets.reset(pad.preset)
            pad.controls = Presets.build(pad.preset)
        })

        val tb = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tb.addView(presetBtn); tb.addView(editBtn); tb.addView(editTools)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tb, lp(MATCH, dp(36)))
        col.addView(pad, lp(MATCH, 0, 1f))
        return col
    }

    private fun toggleEdit() {
        pad.editMode = !pad.editMode
        editBtn.text = if (pad.editMode) "Listo" else "Editar botones"
        editTools.visibility = if (pad.editMode) View.VISIBLE else View.GONE
        if (!pad.editMode) Presets.save(pad.preset, pad.controls)
    }

    private fun presetDialog() {
        if (pad.editMode) toggleEdit()
        AlertDialog.Builder(this).setTitle("Estilo de mando")
            .setItems(Presets.names.toTypedArray()) { _, i ->
                val n = Presets.names[i]
                Prefs.preset = n
                pad.preset = n
                pad.controls = Presets.load(n)
                presetBtn.text = n
            }.show()
    }

    // ---------- Panel Mouse ----------
    private fun mousePanel(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TouchpadView(this), lp(MATCH, 0, 1f))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Clic izquierdo" to 1, "Clic medio" to 4, "Clic derecho" to 2).forEach { (t, bit) ->
            val btn = small(t) { }
            btn.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> Hid.mouseButton(bit, true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Hid.mouseButton(bit, false)
                }
                true
            }
            row.addView(btn, lp(0, dp(44), 1f))
        }
        col.addView(row, lp(MATCH, WRAP))
        return col
    }

    // ---------- Panel Teclado ----------
    private class K(val label: String, val code: Int, val w: Float = 1f, val mod: Boolean = false)

    private fun kbReport() = Hid.keyboard(mods, pressedKeys.toList())

    private fun keyBg(on: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(6).toFloat()
        setColor(Color.parseColor(if (on) "#2F6FD6" else "#3A3F47"))
    }

    private fun keyboardPanel(): View {
        val rows = mutableListOf<List<K>>()
        rows += listOf(K("Esc", 0x29)) + (1..12).map { K("F$it", 0x39 + it) } + K("Supr", 0x4C)
        rows += listOf(K("`", 0x35)) +
                "1234567890".map { K("$it", if (it == '0') 0x27 else 0x1E + (it - '1')) } +
                listOf(K("-", 0x2D), K("=", 0x2E), K("⌫", 0x2A, 1.8f))
        rows += listOf(K("Tab", 0x2B, 1.5f)) +
                "qwertyuiop".map { K("$it", 0x04 + (it - 'a')) } +
                listOf(K("[", 0x2F), K("]", 0x30), K("\\", 0x31))
        rows += listOf(K("Bloq", 0x39, 1.8f)) +
                "asdfghjkl".map { K("$it", 0x04 + (it - 'a')) } +
                listOf(K(";", 0x33), K("'", 0x34), K("Enter", 0x28, 2f))
        rows += listOf(K("Shift", 0x02, 2.3f, true)) +
                "zxcvbnm".map { K("$it", 0x04 + (it - 'a')) } +
                listOf(K(",", 0x36), K(".", 0x37), K("/", 0x38), K("↑", 0x52))
        rows += listOf(K("Ctrl", 0x01, 1.4f, true), K("Win", 0x08, 1.4f, true), K("Alt", 0x04, 1.4f, true),
            K("Espacio", 0x2C, 6f), K("←", 0x50), K("↓", 0x51), K("→", 0x4F))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(2), 0, dp(2)) }
        for (r in rows) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in r) {
                val b = Button(this).apply {
                    text = k.label
                    isAllCaps = false
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                    setPadding(0, 0, 0, 0)
                    background = keyBg(false)
                }
                b.setOnTouchListener { v, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            if (Prefs.haptics) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            if (k.mod) {
                                mods = mods xor k.code
                                v.background = keyBg((mods and k.code) != 0)
                            } else {
                                pressedKeys.add(k.code)
                                v.background = keyBg(true)
                            }
                            kbReport()
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!k.mod) {
                            pressedKeys.remove(k.code)
                            v.background = keyBg(false)
                            kbReport()
                        }
                    }
                    true
                }
                val p = lp(0, MATCH, k.w).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
                row.addView(b, p)
            }
            col.addView(row, lp(MATCH, 0, 1f))
        }
        return col
    }

    // ---------- Panel Ajustes ----------
    private fun seek(label: String, get: () -> Int, set: (Int) -> Unit): View {
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(6), dp(16), dp(6)) }
        val t = TextView(this).apply { text = "$label: ${get()}"; setTextColor(Color.WHITE) }
        val s = SeekBar(this).apply {
            max = 100
            progress = get()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) { set(p); t.text = "$label: $p" }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        l.addView(t); l.addView(s)
        return l
    }

    private fun settingsPanel(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(seek("Sensibilidad del mouse", { Prefs.mouseSens }, { Prefs.mouseSens = it }))
        col.addView(seek("Velocidad de scroll", { Prefs.scrollSpeed }, { Prefs.scrollSpeed = it }))
        col.addView(seek("Sensibilidad de los sticks", { Prefs.stickSens }, { Prefs.stickSens = it }))
        col.addView(seek("Zona muerta de los sticks", { Prefs.deadzone }, { Prefs.deadzone = it }))
        col.addView(Switch(this).apply {
            text = "Vibración al tocar"
            setTextColor(Color.WHITE)
            isChecked = Prefs.haptics
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnCheckedChangeListener { _, c -> Prefs.haptics = c }
        })
        col.addView(TextView(this).apply {
            setTextColor(Color.parseColor("#9AA0A8"))
            textSize = 12f
            setPadding(dp(16), dp(8), dp(16), dp(8))
            text = "Consejo: deja el teléfono con la pantalla encendida y desactiva el ahorro de batería para esta app. " +
                    "Si se corta la conexión, la app intenta reconectar sola con el último dispositivo."
        })
        return ScrollView(this).apply { addView(col) }
    }
}
