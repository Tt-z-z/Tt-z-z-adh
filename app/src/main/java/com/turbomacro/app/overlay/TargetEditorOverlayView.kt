package com.turbomacro.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.turbomacro.app.model.ActionType
import com.turbomacro.app.model.MacroStep
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 靶点可视化编排 + 实时手势录制层
 *
 * 仅在「编辑模式」或「手势录制模式」下挂载可触摸；
 * 当切回「战斗锁定模式」时自动卸下或隐藏，做到游戏画面 **0 靶点遮挡**！
 */
@SuppressLint("ViewConstructor")
class TargetEditorOverlayView(
    context: Context,
    private val steps: MutableList<MacroStep>,
    private val onStepSelected: (MacroStep) -> Unit,
    private val onStepsChanged: () -> Unit
) : View(context) {

    var isRecordingGesture: Boolean = false
        set(value) {
            field = value
            if (value) {
                lastRecordUpTimeMs = 0L
            }
            invalidate()
        }

    private var draggingStep: MacroStep? = null
    private var draggingEndPoint: Boolean = false
    private val screenLoc = IntArray(2)

    // 手势录制状态
    private var recordDownTimeMs = 0L
    private var lastRecordUpTimeMs = 0L
    private var recordStartX = 0f
    private var recordStartY = 0f
    private var recordCurrentX = 0f
    private var recordCurrentY = 0f
    private var isFingerDownInRecord = false

    private val targetCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#B3101824")
    }

    private val targetBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        color = Color.parseColor("#00E5FF")
    }

    private val swipeEndPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#FFAB00")
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        color = Color.parseColor("#CC00E5FF")
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }

    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val hintBannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#D9161E2E")
    }

    private val hintTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        textSize = 32f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        getLocationOnScreen(screenLoc)
        val offsetX = screenLoc[0].toFloat()
        val offsetY = screenLoc[1].toFloat()

        // 顶部轻提示条
        val bannerText = if (isRecordingGesture) {
            "● 手势录制中：直接在屏幕点击或滑动，自动记录坐标与间隔"
        } else {
            "编辑模式：拖拽准星调整位置 · 轻点准星微调毫秒间隔 · 锁定后隐身"
        }
        canvas.drawRoundRect(
            width * 0.08f,
            36f,
            width * 0.92f,
            116f,
            24f,
            24f,
            hintBannerPaint
        )
        canvas.drawText(bannerText, width / 2f, 86f, hintTextPaint)

        // 绘制所有步骤靶点与滑动连线
        val radius = 42f
        for (step in steps) {
            val sx = step.x - offsetX
            val sy = step.y - offsetY

            if (step.type == ActionType.SWIPE) {
                val ex = step.endX - offsetX
                val ey = step.endY - offsetY
                canvas.drawLine(sx, sy, ex, ey, linePaint)
                canvas.drawCircle(ex, ey, radius * 0.65f, targetCirclePaint)
                canvas.drawCircle(ex, ey, radius * 0.65f, swipeEndPaint)
                canvas.drawText("${step.id}终", ex, ey + 10f, numberPaint)
            }

            canvas.drawCircle(sx, sy, radius, targetCirclePaint)
            canvas.drawCircle(sx, sy, radius, targetBorderPaint)
            // 十字准星刻度线
            canvas.drawLine(sx - radius - 8f, sy, sx - radius + 8f, sy, targetBorderPaint)
            canvas.drawLine(sx + radius - 8f, sy, sx + radius + 8f, sy, targetBorderPaint)
            canvas.drawLine(sx, sy - radius - 8f, sx, sy - radius + 8f, targetBorderPaint)
            canvas.drawLine(sx, sy + radius - 8f, sx, sy + radius + 8f, targetBorderPaint)

            val fm = numberPaint.fontMetrics
            val base = sy - (fm.ascent + fm.descent) / 2f
            canvas.drawText(step.id.toString(), sx, base, numberPaint)
        }

        // 若正在录制手势，实时绘制当前手指轨迹
        if (isRecordingGesture && isFingerDownInRecord) {
            val sx = recordStartX - offsetX
            val sy = recordStartY - offsetY
            val cx = recordCurrentX - offsetX
            val cy = recordCurrentY - offsetY
            canvas.drawLine(sx, sy, cx, cy, linePaint)
            canvas.drawCircle(cx, cy, 36f, swipeEndPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rawX = event.rawX
        val rawY = event.rawY

        if (isRecordingGesture) {
            return handleGestureRecordingTouch(event, rawX, rawY)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 优先命中滑动终点或起点靶心
                val hitRadius = 64f
                for (step in steps.asReversed()) {
                    if (step.type == ActionType.SWIPE &&
                        hypot(rawX - step.endX, rawY - step.endY) <= hitRadius
                    ) {
                        draggingStep = step
                        draggingEndPoint = true
                        return true
                    }
                    if (hypot(rawX - step.x, rawY - step.y) <= hitRadius) {
                        draggingStep = step
                        draggingEndPoint = false
                        return true
                    }
                }
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                val target = draggingStep ?: return false
                if (draggingEndPoint) {
                    target.endX = rawX
                    target.endY = rawY
                } else {
                    val dx = rawX - target.x
                    val dy = rawY - target.y
                    target.x = rawX
                    target.y = rawY
                    if (target.type == ActionType.SWIPE) {
                        target.endX += dx
                        target.endY += dy
                    }
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val target = draggingStep
                draggingStep = null
                if (target != null) {
                    onStepsChanged()
                    onStepSelected(target)
                    return true
                }
                return false
            }
        }
        return false
    }

    private fun handleGestureRecordingTouch(event: MotionEvent, rawX: Float, rawY: Float): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val now = SystemClock.uptimeMillis()
                // 若存在上一步，自动将两步之间的真实停顿写入上一步的 intervalAfterMs
                if (lastRecordUpTimeMs > 0L && steps.isNotEmpty()) {
                    val realGap = (now - lastRecordUpTimeMs).coerceIn(8L, 3000L)
                    steps.last().intervalAfterMs = realGap
                }
                recordDownTimeMs = now
                recordStartX = rawX
                recordStartY = rawY
                recordCurrentX = rawX
                recordCurrentY = rawY
                isFingerDownInRecord = true
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                recordCurrentX = rawX
                recordCurrentY = rawY
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                isFingerDownInRecord = false
                val now = SystemClock.uptimeMillis()
                val holdMs = (now - recordDownTimeMs).coerceIn(10L, 2000L)
                lastRecordUpTimeMs = now

                val dist = hypot(rawX - recordStartX, rawY - recordStartY)
                val isSwipe = dist > 38f
                val newStep = MacroStep(
                    id = steps.size + 1,
                    type = if (isSwipe) ActionType.SWIPE else ActionType.TAP,
                    x = recordStartX,
                    y = recordStartY,
                    endX = rawX,
                    endY = rawY,
                    holdDurationMs = holdMs,
                    intervalAfterMs = 35L
                )
                steps.add(newStep)
                onStepsChanged()
                invalidate()
                return true
            }
        }
        return false
    }
}
