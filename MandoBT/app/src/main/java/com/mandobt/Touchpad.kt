package com.mandobt

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot
import kotlin.math.max

/**
 * Touchpad: 1 dedo = mover, toque = clic izq, 2 dedos = scroll,
 * toque con 2 dedos = clic der, doble toque y arrastrar = arrastrar/seleccionar.
 */
class TouchpadView(ctx: Context) : View(ctx) {
    private var lx = 0f; private var ly = 0f; private var sx = 0f; private var sy = 0f
    private var downT = 0L; private var moved = false; private var maxP = 0
    private var remX = 0f; private var remY = 0f; private var scr = 0f
    private var lastTapUp = 0L; private var dragging = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val main = Handler(Looper.getMainLooper())
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun avgX(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getX(i); return s / e.pointerCount }
    private fun avgY(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getY(i); return s / e.pointerCount }

    private fun click(bit: Int) {
        Hid.mouseButton(bit, true)
        main.postDelayed({ Hid.mouseButton(bit, false) }, 40)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lx = e.x; ly = e.y; sx = lx; sy = ly
                downT = e.eventTime; moved = false; maxP = 1
                remX = 0f; remY = 0f; scr = 0f
                if (downT - lastTapUp < 300) { dragging = true; Hid.mouseButton(1, true) }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxP = max(maxP, e.pointerCount)
                lx = avgX(e); ly = avgY(e)
            }
            MotionEvent.ACTION_MOVE -> {
                val ax = avgX(e); val ay = avgY(e)
                val dx = ax - lx; val dy = ay - ly
                lx = ax; ly = ay
                if (hypot(ax - sx, ay - sy) > slop) moved = true
                if (e.pointerCount >= 2) {
                    scr += dy * (0.02f + Prefs.scrollSpeed / 100f * 0.15f)
                    val w = scr.toInt()
                    if (w != 0) { scr -= w; Hid.mouse(0, 0, w) }
                } else {
                    val f = 0.3f + Prefs.mouseSens / 100f * 3.7f
                    remX += dx * f; remY += dy * f
                    val ix = remX.toInt(); val iy = remY.toInt()
                    if (ix != 0 || iy != 0) { remX -= ix; remY -= iy; Hid.mouse(ix, iy) }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val skip = e.actionIndex
                var a = 0f; var b = 0f; var n = 0
                for (i in 0 until e.pointerCount) if (i != skip) { a += e.getX(i); b += e.getY(i); n++ }
                if (n > 0) { lx = a / n; ly = b / n }
            }
            MotionEvent.ACTION_UP -> {
                val now = e.eventTime
                if (dragging) {
                    Hid.mouseButton(1, false); dragging = false; lastTapUp = 0
                } else if (!moved && now - downT < 250) {
                    click(if (maxP >= 2) 2 else 1)
                    lastTapUp = if (maxP == 1) now else 0
                }
                maxP = 0
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) { Hid.mouseButton(1, false); dragging = false }
                maxP = 0
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.parseColor("#15181D"))
        p.color = Color.parseColor("#6B7078")
        p.textAlign = Paint.Align.CENTER
        p.textSize = height * 0.07f
        val y = height / 2f
        c.drawText("TOUCHPAD", width / 2f, y - p.textSize, p)
        p.textSize = height * 0.055f
        c.drawText("1 dedo: mover · toque: clic · 2 dedos: scroll", width / 2f, y + p.textSize * 0.5f, p)
        c.drawText("toque con 2 dedos: clic derecho · doble toque y arrastra: seleccionar", width / 2f, y + p.textSize * 1.9f, p)
    }
}
