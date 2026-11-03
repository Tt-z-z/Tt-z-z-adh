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
 * 小米 Game Turbo 风格悬浮触发键（幽灵微透胶囊 / 圆环）
 *
 * 交互设计：
 * - 【战斗锁定模式 (locked = true)】：
 *    - 闲置时半透明（默认 35% 透明度），体积小巧（52dp），不挡游戏视野。
 *    - 手指触碰瞬间 (ACTION_DOWN) **0ms 立即触发宏连发**，同时外圈亮起青色呼吸光环并伴随微缩按压动效。
 *    - 手指抬起瞬间 (ACTION_UP / ACTION_CANCEL) **0ms 立即停止宏**，恢复微透隐身态。
 *
 * - 【布局编辑模式 (locked = false)】：
 *    - 可自由拖拽触发键到屏幕顺手位置（如大拇指热区、侧边肩键位）。
 *    - 轻点触发键可弹出快捷参数/录制面板。
 */
@SuppressLint("ViewConstructor")
class FloatingTriggerButtonView(
    context: Context,
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
            if (!isPressing) alpha = field
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) / 2f) - 6f

        // 按下时微缩 92% 模拟真实物理按键下沉感
        val scale = if (isPressing) 0.91f else 1.0f
        canvas.save()
        canvas.scale(scale, scale, cx, cy)

        // 背景深色半透底盘
        canvas.drawCircle(cx, cy, radius, bgPaint)

        // 外环：战斗按住时高亮电光青，编辑模式为橙金提示色，待机为细青环
        when {
            isPressing -> {
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

        // 中心准星核或状态字
        if (isLockedForCombat) {
            innerDotPaint.color = if (isPressing) Color.parseColor("#00FFAA") else Color.parseColor("#00E5FF")
            canvas.drawCircle(cx, cy, if (isPressing) radius * 0.42f else radius * 0.26f, innerDotPaint)
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
            // 战斗锁定态：0ms 极速响应按住与松开，绝不判断拖拽以消除 TouchSlop 延迟
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isPressing = true
                    alpha = 0.95f
                    invalidate()
                    onHoldStart()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isPressing) {
                        isPressing = false
                        alpha = idleAlpha
                        invalidate()
                        onHoldEnd()
                    }
                    return true
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
