package com.mandobt

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

const val T_BTN = 0
const val T_STICK_L = 1
const val T_STICK_R = 2
const val T_DPAD = 3
const val T_TRIG_L = 4
const val T_TRIG_R = 5

/** Un control en pantalla. x,y = centro (fracción del ancho/alto). size = diámetro (fracción del alto). */
class Ctl(
    val id: String, val type: Int, val label: String,
    var x: Float, var y: Float, var size: Float,
    val idx: Int = -1, val rect: Boolean = false, val color: Int = Color.parseColor("#4A4F57")
)

object Presets {
    val names = listOf("Xbox", "PlayStation", "Switch")

    fun build(name: String): MutableList<Ctl> {
        val ps = name == "PlayStation"
        val sw = name == "Switch"
        val g = Color.parseColor("#4A4F57")
        fun lab(xb: String, pl: String, ns: String) = if (ps) pl else if (sw) ns else xb
        val l = mutableListOf<Ctl>()

        l += Ctl("L2", T_TRIG_L, lab("LT", "L2", "ZL"), 0.07f, 0.12f, 0.14f, -1, true, g)
        l += Ctl("L1", T_BTN, lab("LB", "L1", "L"), 0.19f, 0.12f, 0.14f, 6, true, g)
        l += Ctl("R1", T_BTN, lab("RB", "R1", "R"), 0.81f, 0.12f, 0.14f, 7, true, g)
        l += Ctl("R2", T_TRIG_R, lab("RT", "R2", "ZR"), 0.93f, 0.12f, 0.14f, -1, true, g)
        l += Ctl("SEL", T_BTN, lab("Back", "Share", "−"), 0.43f, 0.18f, 0.11f, 10, true, g)
        l += Ctl("STA", T_BTN, lab("Start", "Options", "+"), 0.57f, 0.18f, 0.11f, 11, true, g)
        l += Ctl("HOME", T_BTN, lab("Home", "PS", "⌂"), 0.50f, 0.34f, 0.11f, 12, false, g)
        l += Ctl("L3", T_BTN, "L3", 0.43f, 0.50f, 0.10f, 13, false, g)
        l += Ctl("R3", T_BTN, "R3", 0.57f, 0.50f, 0.10f, 14, false, g)

        if (ps) {
            l += Ctl("DPAD", T_DPAD, "", 0.15f, 0.46f, 0.38f, -1, false, g)
            l += Ctl("SL", T_STICK_L, "", 0.38f, 0.76f, 0.38f)
        } else {
            l += Ctl("SL", T_STICK_L, "", 0.15f, 0.46f, 0.40f)
            l += Ctl("DPAD", T_DPAD, "", 0.36f, 0.76f, 0.36f, -1, false, g)
        }
        l += Ctl("SR", T_STICK_R, "", 0.62f, 0.76f, 0.40f)

        val fx = 0.85f; val fy = 0.52f; val dx = 0.08f; val dy = 0.18f; val fs = 0.16f
        val c = when {
            ps -> listOf("#4C7BD9", "#D9534F", "#D16BA5", "#3FB59D")
            sw -> listOf("#6B7078", "#6B7078", "#6B7078", "#6B7078")
            else -> listOf("#2E9E44", "#D0353B", "#2F6FD6", "#D6A92B")
        }.map { Color.parseColor(it) }
        // abajo, derecha, izquierda, arriba
        val t = when {
            ps -> listOf("✕", "○", "□", "△")
            sw -> listOf("B", "A", "Y", "X")
            else -> listOf("A", "B", "X", "Y")
        }
        l += Ctl("F_S", T_BTN, t[0], fx, fy + dy, fs, 0, false, c[0])
        l += Ctl("F_E", T_BTN, t[1], fx + dx, fy, fs, 1, false, c[1])
        l += Ctl("F_W", T_BTN, t[2], fx - dx, fy, fs, 3, false, c[2])
        l += Ctl("F_N", T_BTN, t[3], fx, fy - dy, fs, 4, false, c[3])
        return l
    }

    fun load(name: String): MutableList<Ctl> {
        val l = build(name)
        val js = Prefs.layout(name) ?: return l
        try {
            val arr = JSONArray(js)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                l.find { it.id == o.getString("id") }?.apply {
                    x = o.getDouble("x").toFloat()
                    y = o.getDouble("y").toFloat()
                    size = o.getDouble("size").toFloat()
                }
            }
        } catch (_: Exception) { }
        return l
    }

    fun save(name: String, l: List<Ctl>) {
        val arr = JSONArray()
        l.forEach {
            arr.put(JSONObject().put("id", it.id).put("x", it.x.toDouble()).put("y", it.y.toDouble()).put("size", it.size.toDouble()))
        }
        Prefs.saveLayout(name, arr.toString())
    }

    fun reset(name: String) = Prefs.saveLayout(name, null)
}

class GamepadView(ctx: Context) : View(ctx) {
    private val owner = HashMap<Int, Ctl>()
    private val grab = HashMap<Int, FloatArray>()
    private val pressed = HashSet<Ctl>()
    private val vec = HashMap<Ctl, FloatArray>()
    private var hat = 8
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    var preset = "Xbox"
    var controls: MutableList<Ctl> = mutableListOf()
        set(v) {
            field = v
            selected = null
            owner.clear(); pressed.clear(); vec.clear(); hat = 8
            invalidate(); push()
        }
    var editMode = false
        set(v) { field = v; releaseAll(); invalidate() }
    var selected: Ctl? = null

    fun resize(delta: Float) {
        selected?.let { it.size = (it.size + delta).coerceIn(0.08f, 0.6f) }
        invalidate()
    }

    private fun cx(k: Ctl) = k.x * width
    private fun cy(k: Ctl) = k.y * height
    private fun dia(k: Ctl) = k.size * height

    private fun hit(k: Ctl, x: Float, y: Float): Boolean {
        val d = dia(k); val dx = x - cx(k); val dy = y - cy(k)
        return if (k.rect) abs(dx) <= d * 0.85f && abs(dy) <= d * 0.5f
        else hypot(dx, dy) <= d / 2 * (if (k.type == T_STICK_L || k.type == T_STICK_R) 1.2f else 1.05f)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        invalidate()
        push()
        return true
    }

    private fun down(id: Int, x: Float, y: Float) {
        val k = controls.lastOrNull { hit(it, x, y) } ?: return
        owner[id] = k
        if (editMode) { selected = k; grab[id] = floatArrayOf(x - cx(k), y - cy(k)); return }
        when (k.type) {
            T_BTN, T_TRIG_L, T_TRIG_R -> { pressed.add(k); buzz() }
            else -> move(id, x, y)
        }
    }

    private fun move(id: Int, x: Float, y: Float) {
        val k = owner[id] ?: return
        if (editMode) {
            val g = grab[id] ?: return
            k.x = ((x - g[0]) / width).coerceIn(0.04f, 0.96f)
            k.y = ((y - g[1]) / height).coerceIn(0.06f, 0.94f)
            return
        }
        val d = dia(k)
        when (k.type) {
            T_STICK_L, T_STICK_R -> {
                var vx = (x - cx(k)) / (d / 2)
                var vy = (y - cy(k)) / (d / 2)
                val m = hypot(vx, vy)
                if (m > 1f) { vx /= m; vy /= m }
                vec[k] = floatArrayOf(vx, vy)
            }
            T_DPAD -> hat = hatFrom(x - cx(k), y - cy(k), d)
        }
    }

    private fun up(id: Int) {
        val k = owner.remove(id)
        grab.remove(id)
        if (k == null || editMode) return
        pressed.remove(k)
        vec.remove(k)
        if (k.type == T_DPAD) hat = 8
    }

    fun releaseAll() {
        owner.clear(); grab.clear(); pressed.clear(); vec.clear(); hat = 8
        push()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) releaseAll()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) releaseAll()
    }

    private fun buzz() {
        if (Prefs.haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun hatFrom(dx: Float, dy: Float, d: Float): Int {
        if (hypot(dx, dy) < d * 0.10f) return 8
        var a = Math.toDegrees(atan2(dx.toDouble(), (-dy).toDouble()))
        if (a < 0) a += 360.0
        return ((a + 22.5) / 45).toInt() % 8
    }

    /** Aplica zona muerta y sensibilidad. */
    private fun axis(v: FloatArray): IntArray {
        val m = hypot(v[0], v[1])
        val dz = Prefs.deadzone / 100f * 0.5f
        if (m <= dz) return intArrayOf(0, 0)
        val s = (((m - dz) / (1f - dz)) * (0.5f + Prefs.stickSens / 100f)).coerceAtMost(1f)
        val k = s / m
        return intArrayOf((v[0] * k * 127).roundToInt(), (v[1] * k * 127).roundToInt())
    }

    private fun push() {
        if (editMode) { Hid.pad(0, 8, 0, 0, 0, 0, 0, 0); return }
        var btn = 0; var lt = 0; var rt = 0
        for (c in pressed) when (c.type) {
            T_BTN -> btn = btn or (1 shl c.idx)
            T_TRIG_L -> { lt = 255; btn = btn or (1 shl 8) }
            T_TRIG_R -> { rt = 255; btn = btn or (1 shl 9) }
        }
        var l = intArrayOf(0, 0); var r = intArrayOf(0, 0)
        for ((c, v) in vec) {
            if (c.type == T_STICK_L) l = axis(v) else if (c.type == T_STICK_R) r = axis(v)
        }
        Hid.pad(btn, hat, l[0], l[1], r[0], r[1], lt, rt)
    }

    // ---------- Dibujo ----------
    override fun onDraw(c: Canvas) {
        c.drawColor(Color.parseColor("#101216"))
        for (k in controls) drawCtl(c, k)
    }

    private fun shape(c: Canvas, k: Ctl, x: Float, y: Float, d: Float) {
        if (k.rect) c.drawRoundRect(x - d * 0.8f, y - d * 0.4f, x + d * 0.8f, y + d * 0.4f, d * 0.2f, d * 0.2f, p)
        else c.drawCircle(x, y, d / 2, p)
    }

    private fun drawCtl(c: Canvas, k: Ctl) {
        val x = cx(k); val y = cy(k); val d = dia(k)
        val on = k in pressed || (k.type == T_DPAD && hat != 8 && owner.containsValue(k))
        p.style = Paint.Style.FILL
        when (k.type) {
            T_STICK_L, T_STICK_R -> {
                p.color = Color.parseColor("#1D2127"); c.drawCircle(x, y, d / 2, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 4f
                p.color = Color.parseColor("#3A3F47"); c.drawCircle(x, y, d / 2, p)
                p.style = Paint.Style.FILL
                val v = vec[k]; val r = d * 0.24f
                val kx = x + (v?.get(0) ?: 0f) * (d / 2 - r)
                val ky = y + (v?.get(1) ?: 0f) * (d / 2 - r)
                p.color = Color.parseColor(if (v != null) "#8A929C" else "#5A606A")
                c.drawCircle(kx, ky, r, p)
            }
            T_DPAD -> {
                p.color = k.color
                c.drawRoundRect(x - d / 2, y - d * 0.17f, x + d / 2, y + d * 0.17f, d * 0.08f, d * 0.08f, p)
                c.drawRoundRect(x - d * 0.17f, y - d / 2, x + d * 0.17f, y + d / 2, d * 0.08f, d * 0.08f, p)
                if (on) {
                    val a = Math.toRadians(hat * 45.0)
                    p.color = Color.parseColor("#CCFFFFFF")
                    c.drawCircle(x + (sin(a) * d * 0.34).toFloat(), y - (cos(a) * d * 0.34).toFloat(), d * 0.09f, p)
                }
            }
            else -> {
                p.color = k.color
                shape(c, k, x, y, d)
                if (on) { p.color = Color.argb(110, 255, 255, 255); shape(c, k, x, y, d) }
                p.color = Color.WHITE
                p.textAlign = Paint.Align.CENTER
                p.textSize = if (k.rect) d * 0.34f else d * 0.40f
                c.drawText(k.label, x, y - (p.ascent() + p.descent()) / 2, p)
            }
        }
        if (editMode) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = if (k === selected) 6f else 2f
            p.color = if (k === selected) Color.YELLOW else Color.argb(120, 255, 255, 255)
            shape(c, k, x, y, d * 1.04f)
            p.style = Paint.Style.FILL
        }
    }
}
