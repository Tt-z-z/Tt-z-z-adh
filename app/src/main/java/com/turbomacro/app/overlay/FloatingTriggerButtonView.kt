package com.turbomacro.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * 小米 Game Turbo 风格悬浮触发键（幽灵微透圆环）
 *
 * - 支持「按住触发 / 轻触触发」
 * - 支持「快速连点 3 下触发键」直接进入/退出编辑模式（作为【音量+】物理键的双保险兜底）
 */
@SuppressLint("ViewConstructor")
class FloatingTriggerButtonView(
    context: Context,
    private val isShizukuModeProvider: () -> Boolean,
    private val isMacroRunningProvider: () -> Boolean,
    private val onHoldStart: () -> Unit,
    private val onHoldEnd: () -> Unit,
    private val onDragMoved: (newX: Int, newY: Int) -> Unit,
    private val onOpenMenuClicked: () -> Unit,
    private val onQuickEditGesture: () -> Unit = {}
) : View(context) {

    var isLockedForCombat: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var idleAlpha: Float = 0.38f
        set(value) {
            field = value.coerceIn(0.15f, 1.0f)
            if (!isPressing && !isMacroRunningProvider()) alpha = field
        }

    private var isPressing = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var hasDragged = false

    // 快速连击 3 下进入编辑态计数器
    private var tapStreakCount = 0
    private var lastTapDownUptimeMs = 0L

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC141923")
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5.5f
        color = Color.parseColor("#00E5FF")
    }

    private val innerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00E5FF")
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    init {
        alpha = idleAlpha
    }

    fun refreshVisualState() {
        val active = isPressing || isMacroRunningProvider()
        alpha = if (active) 0.92f else if (isLockedForCombat) idleAlpha else 0.85f
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) / 2f) - 6f

        val active = isPressing || isMacroRunningProvider()
        val scale = if (active) 0.91f else 1.0f
        canvas.save()
        canvas.scale(scale, scale, cx, cy)

        canvas.drawCircle(cx, cy, radius, bgPaint)

        when {
            active -> {
                ringPaint.color = Color.parseColor("#00FFAA")
                ringPaint.strokeWidth = 7.5f
            }
            !isLockedForCombat -> {
                ringPaint.color = Color.parseColor("#FFB300")
                ringPaint.strokeWidth = 5.5f
            }
            else -> {
                ringPaint.color = Color.parseColor("#00E5FF")
                ringPaint.strokeWidth = 4.5f
            }
        }
        canvas.drawCircle(cx, cy, radius, ringPaint)

        if (isLockedForCombat) {
            innerDotPaint.color = if (active) Color.parseColor("#00FFAA") else Color.parseColor("#00E5FF")
            canvas.drawCircle(cx, cy, if (active) radius * 0.42f else radius * 0.26f, innerDotPaint)
        } else {
            val fontMetrics = textPaint.fontMetrics
            val baseline = cy - (fontMetrics.ascent + fontMetrics.descent) / 2f
            canvas.drawText("移动", cx, baseline, textPaint)
        }

        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isLockedForCombat) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val now = SystemClock.uptimeMillis()
                if (now - lastTapDownUptimeMs <= 320L) {
                    tapStreakCount++
                } else {
                    tapStreakCount = 1
                }
                lastTapDownUptimeMs = now
                if (tapStreakCount >= 3) {
                    tapStreakCount = 0
                    isPressing = false
                    onHoldEnd()
                    onQuickEditGesture()
                    return true
                }
            }

            val shizukuMode = isShizukuModeProvider()
            if (shizukuMode) {
                // Shizuku 原生通道：真·按住连发，松开即停（多指零冲突）
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        isPressing = true
                        refreshVisualState()
                        onHoldStart()
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isPressing) {
                            isPressing = false
                            onHoldEnd()
                            refreshVisualState()
                        }
                        return true
                    }
                }
            } else {
                // 纯无障碍模式：
                // 若正在运行宏，手指一碰触发键 (ACTION_DOWN) 0ms 立即停止！
                // 若未运行宏，手指松开瞬间 (ACTION_UP) 立即无阻挡注入游戏点击！
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        isPressing = true
                        if (isMacroRunningProvider()) {
                            onHoldEnd()
                        }
                        refreshVisualState()
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val wasRunning = isMacroRunningProvider()
                        isPressing = false
                        if (!wasRunning && event.actionMasked == MotionEvent.ACTION_UP) {
                            onHoldStart()
                        }
                        refreshVisualState()
                        return true
                    }
                }
            }
            return true
        } else {
            // 编辑解锁态：允许拖动触发键位置，或点击打开调参菜单
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isPressing = true
                    hasDragged = false
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    alpha = 0.95f
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - lastRawX).toInt()
                    val dy = (event.rawY - lastRawY).toInt()
                    if (abs(event.rawX - downRawX) > 10f || abs(event.rawY - downRawY) > 10f) {
                        hasDragged = true
                    }
                    if (hasDragged) {
                        onDragMoved(dx, dy)
                    }
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isPressing = false
                    alpha = 0.85f
                    invalidate()
                    if (!hasDragged && event.actionMasked == MotionEvent.ACTION_UP) {
                        onOpenMenuClicked()
                    }
                    return true
                }
            }
            return true
        }
    }
}
