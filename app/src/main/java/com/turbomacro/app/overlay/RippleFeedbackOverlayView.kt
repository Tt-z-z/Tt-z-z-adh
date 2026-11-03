package com.turbomacro.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 小米 Game Turbo 风格「完全穿透、零触控阻挡」高刷视觉反馈层
 *
 * WindowManager 挂载此 View 时带有：
 * `FLAG_NOT_TOUCHABLE or FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN`
 * 因此它 100% 不拦截任何手指点击，只负责在每次宏点击坐标处渲染一圈 180ms 极速扩散消隐的青蓝色光环。
 */
class RippleFeedbackOverlayView(context: Context) : View(context) {

    private data class RippleParticle(
        val x: Float,
        val y: Float,
        val endX: Float?,
        val endY: Float?,
        val birthTimeMs: Long,
        val durationMs: Long = 180L
    )

    private val particles = CopyOnWriteArrayList<RippleParticle>()

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        color = Color.parseColor("#00E5FF") // 小米 Game Turbo 标志性电光青
    }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#8000E5FF")
    }

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#00E5FF")
    }

    /**
     * 触发一次水波纹动画（可在任意线程调用）
     */
    fun spawnRipple(x: Float, y: Float, endX: Float? = null, endY: Float? = null) {
        // 限制同屏粒子上限，保证 120Hz 满帧不掉帧
        if (particles.size > 24) {
            particles.removeAt(0)
        }
        particles.add(
            RippleParticle(
                x = x,
                y = y,
                endX = endX,
                endY = endY,
                birthTimeMs = SystemClock.uptimeMillis()
            )
        )
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (particles.isEmpty()) return

        val now = SystemClock.uptimeMillis()
        var hasAlive = false

        for (p in particles) {
            val elapsed = now - p.birthTimeMs
            if (elapsed >= p.durationMs) {
                particles.remove(p)
                continue
            }
            hasAlive = true
            val progress = elapsed.toFloat() / p.durationMs
            // 非线性缓动扩散 (Ease-Out Quad)
            val eased = 1f - (1f - progress) * (1f - progress)
            val alpha = ((1f - eased) * 235).toInt().coerceIn(0, 255)

            // 绘制中心实心亮核与外扩波纹环
            val maxRadius = 38f
            val currentRadius = 8f + maxRadius * eased

            ringPaint.alpha = alpha
            corePaint.alpha = (alpha * 0.65f).toInt()

            // 因 Window 是全屏状态栏下或全屏坐标系，将屏幕绝对坐标转换为本 View 局部坐标
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val localX = p.x - loc[0]
            val localY = p.y - loc[1]

            canvas.drawCircle(localX, localY, 7f * (1f - progress * 0.5f), corePaint)
            canvas.drawCircle(localX, localY, currentRadius, ringPaint)

            // 若是滑动步骤，绘制流光轨迹线
            if (p.endX != null && p.endY != null) {
                val localEndX = p.endX - loc[0]
                val localEndY = p.endY - loc[1]
                trailPaint.alpha = alpha
                val curTrailX = localX + (localEndX - localX) * eased
                val curTrailY = localY + (localEndY - localY) * eased
                canvas.drawLine(localX, localY, curTrailX, curTrailY, trailPaint)
            }
        }

        if (hasAlive) {
            postInvalidateOnAnimation()
        }
    }
}
