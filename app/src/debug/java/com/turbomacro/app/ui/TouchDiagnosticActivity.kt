package com.turbomacro.app.ui

import android.app.Activity
import android.os.Bundle
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/** 只观察触摸事件；不注入事件、不创建悬浮窗、不连接 TouchMux。 */
class TouchDiagnosticActivity : Activity() {
    private lateinit var surface: DiagnosticView
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surface = DiagnosticView()
        setContentView(surface)
    }
    override fun onPause() {
        if (::surface.isInitialized) surface.releaseContacts()
        super.onPause()
    }

    private inner class DiagnosticView : View(this@TouchDiagnosticActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val target = RectF()
        private val clear = RectF()
        private val drag = RectF()
        private val location = IntArray(2)
        private val contacts = mutableMapOf<Int, Contact>()
        private val trail = java.util.ArrayDeque<PointF>()
        private var taps = 0
        private var moves = 0
        private var cancels = 0
        private var anomalies = 0
        private var dragStarts = 0
        private var dragEnds = 0
        private val density = resources.displayMetrics.density
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES; isClickable = true }

        fun releaseContacts() {
            if (contacts.isNotEmpty()) cancels++
            contacts.clear()
            updateSummary()
            invalidate()
        }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            releaseContacts()
            trail.clear()
            target.set(w * .60f, h * .36f, w * .94f, h * .54f)
            clear.set(w * .06f, h * .36f, w * .45f, h * .45f)
            drag.set(w * .06f, h * .60f, w * .94f, h * .94f)
            updateSummary()
        }
        private fun updateSummary() {
            getLocationOnScreen(location)
            contentDescription = "touch-diagnostic taps=$taps moves=$moves cancels=$cancels anomalies=$anomalies " +
                "dragStarts=$dragStarts dragEnds=$dragEnds active=${contacts.size} " +
                "targetX=${(location[0] + target.centerX()).toInt()} targetY=${(location[1] + target.centerY()).toInt()} " +
                "width=$width height=$height rotation=${display?.rotation ?: 0}"
        }
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.rgb(16, 20, 28))
            paint.color = Color.WHITE
            paint.textSize = 16 * density
            val lines = listOf("触控诊断 · 本页不自动点击", "点击 $taps   MOVE $moves   CANCEL $cancels",
                "拖动开始 $dragStarts / 结束 $dragEnds", "活跃触点 ${contacts.size}   异常 $anomalies",
                "请在下方区域持续拖动", "右侧为独立点击目标")
            lines.forEachIndexed { i, text -> canvas.drawText(text, 16 * density, (30 + i * 25) * density, paint) }
            paint.color = Color.rgb(220, 110, 20); canvas.drawRoundRect(target, 16f, 16f, paint)
            paint.color = Color.rgb(55, 65, 80); canvas.drawRoundRect(clear, 16f, 16f, paint)
            paint.color = Color.rgb(25, 65, 80); canvas.drawRoundRect(drag, 16f, 16f, paint)
            paint.color = Color.WHITE
            canvas.drawText("点击区", target.left + 10 * density, target.centerY(), paint)
            canvas.drawText("清空统计", clear.left + 10 * density, clear.centerY(), paint)
            paint.color = Color.CYAN
            for (point in trail) canvas.drawCircle(point.x, point.y, 3 * density, paint)
            getLocationOnScreen(location)
            paint.textSize = 12 * density
            canvas.drawText("目标屏幕坐标 ${(target.centerX() + location[0]).toInt()}, ${(target.centerY() + location[1]).toInt()}",
                16 * density, height - 8 * density, paint)
        }
        override fun performClick(): Boolean { super.performClick(); return true }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val index = event.actionIndex
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN && contacts.isNotEmpty()) {
                        anomalies++; contacts.clear()
                    }
                    val id = event.getPointerId(index)
                    val x = event.getX(index); val y = event.getY(index)
                    val role = when { target.contains(x, y) -> 1; drag.contains(x, y) -> 2; clear.contains(x, y) -> 3; else -> 0 }
                    if (contacts.put(id, Contact(role)) != null) anomalies++
                    if (role == 2) dragStarts++
                }
                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until event.pointerCount) {
                        val contact = contacts[event.getPointerId(i)]
                        if (contact == null) { anomalies++; continue }
                        val x = event.getX(i); val y = event.getY(i)
                        if (contact.role == 1 && !target.contains(x, y)) contact.validTap = false
                        if (contact.role == 2) {
                            moves++
                            if (trail.size >= 600) trail.removeFirst()
                            trail.addLast(PointF(x, y))
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val contact = contacts.remove(event.getPointerId(index))
                    if (contact == null) anomalies++ else when (contact.role) {
                        1 -> if (contact.validTap && target.contains(event.getX(index), event.getY(index))) taps++
                        2 -> dragEnds++
                        3 -> if (clear.contains(event.getX(index), event.getY(index)) && contacts.isEmpty()) {
                            taps = 0; moves = 0; cancels = 0; anomalies = 0; dragStarts = 0; dragEnds = 0; trail.clear()
                        }
                    }
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        if (contacts.isNotEmpty()) { anomalies++; contacts.clear() }
                        performClick()
                    }
                }
                MotionEvent.ACTION_CANCEL -> { cancels++; contacts.clear() }
            }
            updateSummary()
            invalidate()
            return true
        }
    }
    private data class Contact(val role: Int, var validTap: Boolean = true)
}
