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
 * 双模触控注入器（多指并发不抢指 · 不卡游戏操作）
 *
 * 【按住宏时屏幕其他操作失灵】的根因：
 * 1. Shizuku 通道：虚拟点击若使用真实触控屏同一 `deviceId` / pointerId=0，
 *    InputDispatcher 会把「走位手指」和「宏点击」当成同一根手指，导致摇杆/滑动被抢走。
 * 2. 无障碍通道：`dispatchGesture` 会取消屏幕上正在进行的其他手势。
 *
 * 修复：
 * - Shizuku：使用独立虚拟触控设备 ID (`VIRTUAL_TOUCH_DEVICE_ID`)，与物理屏幕手指完全隔离。
 * - 无障碍：绝不在「物理手指仍按住悬浮键」时注入；仅用于松手后的点按模式，且不回退打断并发。
 */
class TouchInjector {

    companion object {
        private const val TAG = "TouchInjector"
        private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
        /** 独立虚拟触控设备，避免与物理触控屏抢 pointer */
        private const val VIRTUAL_TOUCH_DEVICE_ID = 0x7E57_C0DE
        private const val VIRTUAL_POINTER_ID = 0
    }

    enum class ActiveBackend {
        SHIZUKU_INPUT_MANAGER,
        ACCESSIBILITY_GESTURE
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var iInputManagerProxy: Any? = null
    @Volatile
    private var injectEventMethod: Method? = null
    @Volatile
    private var injectMethodParamCount: Int = 2

    fun isShizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
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
     * @param allowAccessibilityFallback 仅在「手指已离开悬浮键」的点按模式允许降级无障碍；
     *   按住并发连发时必须为 false，否则 dispatchGesture 会打断游戏内其他手指。
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

        val effectiveHoldMs = holdMs.coerceAtLeast(26L)

        if (isShizukuReady() && tryInitShizukuInputManager()) {
            val downTime = SystemClock.uptimeMillis()
            var pressed = false
            try {
                val ok = injectMotionEventViaShizuku(
                    downTime = downTime,
                    eventTime = downTime,
                    action = MotionEvent.ACTION_DOWN,
                    x = x,
                    y = y
                )
                if (!ok) {
                    // 按住并发时禁止降级无障碍，避免抢掉游戏手指
                    if (allowAccessibilityFallback && service != null) {
                        return dispatchAccessibilityTap(service, x, y, effectiveHoldMs)
                    }
                    return false
                }
                pressed = true

                var remaining = effectiveHoldMs
                var stepCount = 0
                while (remaining > 0 && isStillHoldingTrigger()) {
                    val slice = minOf(remaining, 6L)
                    delay(slice)
                    remaining -= slice
                    stepCount++
                    // 保活 MOVE：仍走虚拟设备，不触碰物理手指
                    if (remaining > 0 && stepCount % 2 == 0) {
                        injectMotionEventViaShizuku(
                            downTime = downTime,
                            eventTime = SystemClock.uptimeMillis(),
                            action = MotionEvent.ACTION_MOVE,
                            x = x + 0.25f,
                            y = y + 0.25f
                        )
                    }
                }
                return true
            } finally {
                if (pressed) {
                    injectMotionEventViaShizuku(
                        downTime = downTime,
                        eventTime = SystemClock.uptimeMillis(),
                        action = MotionEvent.ACTION_UP,
                        x = x,
                        y = y
                    )
                }
            }
        } else if (allowAccessibilityFallback && service != null) {
            return dispatchAccessibilityTap(service, x, y, effectiveHoldMs)
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
            val stepInterval = 8L
            val steps = (totalDuration / stepInterval).toInt().coerceAtLeast(2)
            var currentX = startX
            var currentY = startY
            var pressed = false

            try {
                val ok = injectMotionEventViaShizuku(
                    downTime,
                    downTime,
                    MotionEvent.ACTION_DOWN,
                    startX,
                    startY
                )
                if (!ok) {
                    if (allowAccessibilityFallback && service != null) {
                        return dispatchAccessibilitySwipe(
                            service, startX, startY, endX, endY, totalDuration
                        )
                    }
                    return false
                }
                pressed = true
                for (i in 1..steps) {
                    if (!isStillHoldingTrigger()) break
                    delay(stepInterval)
                    val fraction = i.toFloat() / steps
                    currentX = startX + (endX - startX) * fraction
                    currentY = startY + (endY - startY) * fraction
                    injectMotionEventViaShizuku(
                        downTime,
                        SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_MOVE,
                        currentX,
                        currentY
                    )
                }
                return true
            } finally {
                if (pressed) {
                    injectMotionEventViaShizuku(
                        downTime,
                        SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_UP,
                        currentX,
                        currentY
                    )
                }
            }
        } else if (allowAccessibilityFallback && service != null) {
            return dispatchAccessibilitySwipe(service, startX, startY, endX, endY, totalDuration)
        }
        return false
    }

    private fun tryInitShizukuInputManager(): Boolean {
        if (iInputManagerProxy != null && injectEventMethod != null) return true
        return try {
            val rawBinder: IBinder = SystemServiceHelper.getSystemService("input") ?: return false
            val wrappedBinder = ShizukuBinderWrapper(rawBinder)
            val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)
            val proxy = asInterfaceMethod.invoke(null, wrappedBinder) ?: return false

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
            Log.w(TAG, "Shizuku IInputManager init failed", e)
            iInputManagerProxy = null
            injectEventMethod = null
            false
        }
    }

    /**
     * 通过独立虚拟触控设备注入，物理屏幕上的走位/瞄准手指不受影响。
     */
    private fun injectMotionEventViaShizuku(
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
        val isUp = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = if (isUp) 0f else 1.0f
            size = if (isUp) 0f else 0.45f
            touchMajor = if (isUp) 0f else 11f
            touchMinor = if (isUp) 0f else 9f
            toolMajor = if (isUp) 0f else 11f
            toolMinor = if (isUp) 0f else 9f
        })
        val event = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            props,
            coords,
            0,
            0,
            1.0f,
            1.0f,
            VIRTUAL_TOUCH_DEVICE_ID,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )
        return try {
            val method = injectEventMethod ?: return false
            val proxy = iInputManagerProxy ?: return false
            // ASYNC：不阻塞，避免拖慢游戏触控流水线
            val result = when (injectMethodParamCount) {
                2 -> method.invoke(proxy, event, INJECT_INPUT_EVENT_MODE_ASYNC)
                3 -> method.invoke(proxy, event, INJECT_INPUT_EVENT_MODE_ASYNC, -1)
                else -> false
            }
            (result as? Boolean) ?: true
        } catch (e: Throwable) {
            Log.w(TAG, "injectMotionEventViaShizuku error: ${e.message}")
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
    ): Boolean {
        return withTimeoutOrNull(durationMs + 350L) {
            suspendCancellableCoroutine { cont ->
                val resumed = AtomicBoolean(false)
                val path = Path().apply {
                    moveTo(x, y)
                    lineTo(x + 0.6f, y + 0.6f)
                }
                val stroke = GestureDescription.StrokeDescription(
                    path,
                    0L,
                    durationMs.coerceIn(26L, 500L)
                )
                val gesture = GestureDescription.Builder().addStroke(stroke).build()

                val dispatched = service.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (resumed.compareAndSet(false, true) && cont.isActive) {
                                cont.resume(true)
                            }
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (resumed.compareAndSet(false, true) && cont.isActive) {
                                cont.resume(false)
                            }
                        }
                    },
                    mainHandler
                )
                if (!dispatched && resumed.compareAndSet(false, true) && cont.isActive) {
                    cont.resume(false)
                }
            }
        } ?: false
    }

    private suspend fun dispatchAccessibilitySwipe(
        service: AccessibilityService,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean {
        return withTimeoutOrNull(durationMs + 400L) {
            suspendCancellableCoroutine { cont ->
                val resumed = AtomicBoolean(false)
                val path = Path().apply {
                    moveTo(startX, startY)
                    lineTo(endX, endY)
                }
                val stroke = GestureDescription.StrokeDescription(
                    path,
                    0L,
                    durationMs.coerceIn(32L, 2000L)
                )
                val gesture = GestureDescription.Builder().addStroke(stroke).build()

                val dispatched = service.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (resumed.compareAndSet(false, true) && cont.isActive) {
                                cont.resume(true)
                            }
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (resumed.compareAndSet(false, true) && cont.isActive) {
                                cont.resume(false)
                            }
                        }
                    },
                    mainHandler
                )
                if (!dispatched && resumed.compareAndSet(false, true) && cont.isActive) {
                    cont.resume(false)
                }
            }
        } ?: false
    }
}
