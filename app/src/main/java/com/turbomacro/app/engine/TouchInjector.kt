package com.turbomacro.app.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 触控注入：
 *
 * 按住连发（要与拖枪/走位共存）→ **只能**走 TouchMux：
 *   抢占物理触控屏，把玩家手指 + 开火额外槽合并进同一虚拟设备。
 *   任何 `input tap` / 合成 MotionEvent / 无障碍手势都会让 Android 丢掉正在滑动的那根手指。
 *
 * 编辑态单次测试仍可走无障碍（此时不要求多指共存）。
 */
class TouchInjector(context: Context) {

    companion object {
        private const val TAG = "TouchInjector"
    }

    enum class ActiveBackend {
        TOUCH_MUX,
        NONE
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    val mux = TouchMuxClient(context.applicationContext)

    fun isShizukuReady(): Boolean = mux.isShizukuReady()

    fun isMuxMerging(): Boolean = mux.isMerging()

    fun getActiveBackend(): ActiveBackend {
        return if (mux.isMerging()) ActiveBackend.TOUCH_MUX else ActiveBackend.NONE
    }

    /** 启用多指共存通道。需短暂抬起所有手指。 */
    fun ensureMuxReady(): Boolean = mux.ensureMerging()

    fun releaseMux() {
        mux.release()
    }

    fun muxError(): String? = mux.lastError()

    /**
     * 单次开火点按。按住连发时 allowAccessibilityFallback 必须为 false，
     * 且必须已 ENGAGE TouchMux。
     */
    suspend fun injectTap(
        service: AccessibilityService?,
        x: Float,
        y: Float,
        holdMs: Long,
        isStillHoldingTrigger: () -> Boolean,
        allowAccessibilityFallback: Boolean = false
    ): Boolean {
        if (!isStillHoldingTrigger()) return false

        val pressMs = holdMs.coerceIn(8L, 48L).toInt()
        val ix = x.toInt().coerceAtLeast(0)
        val iy = y.toInt().coerceAtLeast(0)

        if (mux.isMerging()) {
            val ok = mux.tap(ix, iy, pressMs)
            // 留给 helper 完成 DOWN→UP；间隔由引擎控制，这里只做极短让步避免打满 CPU
            if (ok) delay(pressMs.toLong().coerceAtMost(12L))
            return ok
        }

        if (allowAccessibilityFallback && service != null) {
            return dispatchAccessibilityTap(service, x, y, pressMs.toLong())
        }
        Log.w(TAG, "injectTap blocked: mux not merging (${mux.lastError()})")
        return false
    }

    suspend fun injectSwipe(
        service: AccessibilityService?,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
        isStillHoldingTrigger: () -> Boolean,
        allowAccessibilityFallback: Boolean = false
    ): Boolean {
        if (!isStillHoldingTrigger()) return false
        // 开火宏不需要滑屏；避免在合并通道上发复杂轨迹，直接拒绝
        if (mux.isMerging()) {
            Log.w(TAG, "swipe skipped on mux path")
            return false
        }
        if (allowAccessibilityFallback && service != null) {
            return dispatchAccessibilitySwipe(
                service, startX, startY, endX, endY, durationMs.coerceAtLeast(32L)
            )
        }
        return false
    }

    private suspend fun dispatchAccessibilityTap(
        service: AccessibilityService,
        x: Float,
        y: Float,
        durationMs: Long
    ): Boolean = withTimeoutOrNull(durationMs + 350L) {
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x + 0.4f, y + 0.4f)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(10L, 60L)))
                .build()
            val ok = service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        if (done.compareAndSet(false, true) && cont.isActive) cont.resume(true)
                    }
                    override fun onCancelled(g: GestureDescription?) {
                        if (done.compareAndSet(false, true) && cont.isActive) cont.resume(false)
                    }
                },
                mainHandler
            )
            if (!ok && done.compareAndSet(false, true) && cont.isActive) cont.resume(false)
        }
    } ?: false

    private suspend fun dispatchAccessibilitySwipe(
        service: AccessibilityService,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean = withTimeoutOrNull(durationMs + 400L) {
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(32L, 2000L)))
                .build()
            val ok = service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        if (done.compareAndSet(false, true) && cont.isActive) cont.resume(true)
                    }
                    override fun onCancelled(g: GestureDescription?) {
                        if (done.compareAndSet(false, true) && cont.isActive) cont.resume(false)
                    }
                },
                mainHandler
            )
            if (!ok && done.compareAndSet(false, true) && cont.isActive) cont.resume(false)
        }
    } ?: false
}
