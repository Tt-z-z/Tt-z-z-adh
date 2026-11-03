package com.turbomacro.app.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.pm.PackageManager
import android.graphics.Path
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 三角洲 / FPS 适配：悬浮钮按住连点开火时，绝不干扰屏幕上其他手指的拖屏瞄准。
 *
 * Shizuku：独立虚拟触控设备 + 极短 DOWN→UP（不发 MOVE），避免和物理手指抢 pointer。
 * 无障碍：仅作兜底；按住悬浮钮期间若走无障碍，系统会打断拖屏——因此按住连发优先且仅用 Shizuku。
 */
class TouchInjector {

    companion object {
        private const val TAG = "TouchInjector"
        private const val INJECT_ASYNC = 0
        /** 与物理触控屏隔离的虚拟设备 ID */
        private const val VIRTUAL_DEVICE_ID = 0x54_52_42_4F // 'TRBO'
        private const val VIRTUAL_POINTER_ID = 9
    }

    enum class ActiveBackend {
        SHIZUKU_INPUT_MANAGER,
        ACCESSIBILITY_GESTURE
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var iInputManagerProxy: Any? = null
    @Volatile private var injectEventMethod: Method? = null
    @Volatile private var injectMethodParamCount: Int = 2

    fun isShizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun getActiveBackend(): ActiveBackend {
        return if (isShizukuReady() && tryInitShizukuInputManager()) {
            ActiveBackend.SHIZUKU_INPUT_MANAGER
        } else {
            ActiveBackend.ACCESSIBILITY_GESTURE
        }
    }

    /**
     * 单次开火点按。FPS 场景用极短按压，中间不发 MOVE，避免搅乱拖屏瞄准。
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

        // 三角洲开火键：短点即可，过长 DOWN 反而容易干扰瞄准手势
        val pressMs = holdMs.coerceIn(12L, 40L)

        if (isShizukuReady() && tryInitShizukuInputManager()) {
            val downTime = SystemClock.uptimeMillis()
            val downOk = injectVirtualTouch(downTime, downTime, MotionEvent.ACTION_DOWN, x, y)
            if (!downOk) {
                if (allowAccessibilityFallback && service != null) {
                    return dispatchAccessibilityTap(service, x, y, pressMs)
                }
                return false
            }
            delay(pressMs)
            injectVirtualTouch(
                downTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP,
                x,
                y
            )
            return true
        }

        if (allowAccessibilityFallback && service != null) {
            return dispatchAccessibilityTap(service, x, y, pressMs)
        }
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
        val totalDuration = durationMs.coerceAtLeast(32L)

        if (isShizukuReady() && tryInitShizukuInputManager()) {
            val downTime = SystemClock.uptimeMillis()
            if (!injectVirtualTouch(downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY)) {
                if (allowAccessibilityFallback && service != null) {
                    return dispatchAccessibilitySwipe(service, startX, startY, endX, endY, totalDuration)
                }
                return false
            }
            val steps = (totalDuration / 8L).toInt().coerceAtLeast(2)
            var cx = startX
            var cy = startY
            for (i in 1..steps) {
                if (!isStillHoldingTrigger()) break
                delay(8L)
                val f = i.toFloat() / steps
                cx = startX + (endX - startX) * f
                cy = startY + (endY - startY) * f
                injectVirtualTouch(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, cx, cy)
            }
            injectVirtualTouch(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, cx, cy)
            return true
        }

        if (allowAccessibilityFallback && service != null) {
            return dispatchAccessibilitySwipe(service, startX, startY, endX, endY, totalDuration)
        }
        return false
    }

    private fun tryInitShizukuInputManager(): Boolean {
        if (iInputManagerProxy != null && injectEventMethod != null) return true
        return try {
            val rawBinder: IBinder = SystemServiceHelper.getSystemService("input") ?: return false
            val wrapped = ShizukuBinderWrapper(rawBinder)
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            val proxy = asInterface.invoke(null, wrapped) ?: return false
            val method = proxy.javaClass.methods.firstOrNull { m ->
                m.name == "injectInputEvent" &&
                    m.parameterTypes.size >= 2 &&
                    InputEvent::class.java.isAssignableFrom(m.parameterTypes[0])
            } ?: return false
            method.isAccessible = true
            iInputManagerProxy = proxy
            injectEventMethod = method
            injectMethodParamCount = method.parameterTypes.size
            true
        } catch (e: Throwable) {
            Log.w(TAG, "IInputManager init failed", e)
            iInputManagerProxy = null
            injectEventMethod = null
            false
        }
    }

    private fun injectVirtualTouch(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float
    ): Boolean {
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = VIRTUAL_POINTER_ID
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val up = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = if (up) 0f else 1f
            size = if (up) 0f else 0.4f
            touchMajor = if (up) 0f else 10f
            touchMinor = if (up) 0f else 8f
        })
        val event = MotionEvent.obtain(
            downTime, eventTime, action, 1, props, coords,
            0, 0, 1f, 1f,
            VIRTUAL_DEVICE_ID, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        return try {
            val method = injectEventMethod ?: return false
            val proxy = iInputManagerProxy ?: return false
            val result = when (injectMethodParamCount) {
                2 -> method.invoke(proxy, event, INJECT_ASYNC)
                3 -> method.invoke(proxy, event, INJECT_ASYNC, -1)
                else -> false
            }
            (result as? Boolean) ?: true
        } catch (e: Throwable) {
            Log.w(TAG, "inject failed: ${e.message}")
            iInputManagerProxy = null
            injectEventMethod = null
            false
        } finally {
            event.recycle()
        }
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
                lineTo(x + 0.5f, y + 0.5f)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(12L, 80L)))
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
