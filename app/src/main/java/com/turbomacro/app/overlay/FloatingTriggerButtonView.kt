package com.turbomacro.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * 小米 Game Turbo 风格悬浮触发键（幽灵微透圆环）
 *
 * 【极其关键的底层防冲突机制】：
 * - 当开启了 **Shizuku** 时：系统支持多窗口并发注入，手指 **按住触发键 (ACTION_DOWN) 立即连发，松手 (ACTION_UP) 立停**。
 * - 当仅开启 **无障碍服务（未授权 Shizuku）** 时：
 *   由于 Android 无障碍底层限制，只要你的物理手指还按在悬浮按钮上（占用着触控通道），
 *   系统就会拒绝 `dispatchGesture` 向底下的游戏窗口注入点击（这就是为什么之前有水波纹特效但游戏内没反应）！
 *   因此，在纯无障碍模式下，我们升级为 **「松手瞬间/轻点切换极速连发 + 按住期间智能短暂释放焦点」**：
 *   - 轻点一下触发键（手指离开屏幕）-> 立即全速连点游戏！再碰一下触发键 -> 0ms 立即停止！
 */
@SuppressLint("ViewConstructor")
class FloatingTriggerButtonView(
    context: Context,
    private val isShizukuModeProvider: () -> Boolean,
    private val isMacroRunningProvider: () -> Boolean,
    private val onHoldStart: () -> Unit,
    private val onHoldEnd: () -> Unit,
    private val onDragMoved: (newX: Int, newY: Int) -> Unit,
    private val onOpenMenuClicked: () -> Unit
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
