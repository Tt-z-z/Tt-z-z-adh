package com.turbomacro.app.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.pm.PackageManager
import android.graphics.Path
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method
import kotlin.coroutines.resume

/**
 * 双模安全触控注入器（零系统损伤保证）：
 *
 * 1. Shizuku (ADB 级 IInputManager Binder 代理通道) [首选]：
 *    - 通过 ShizukuBinderWrapper 包装系统 "input" 服务，以 ADB Shell (UID 2000) 权限调用
 *      IInputManager.injectInputEvent，原理与小米 Game Turbo 原生虚拟肩键完全一致。
 *    - 支持左手按住屏幕移动/按住触发键的同时，毫秒级并发注入宏点击，互不打断。
 *    - 纯内存级 API 调用，不修改任何系统分区或驱动，随时关闭或卸载零残留。
 *
 * 2. AccessibilityService (无障碍 dispatchGesture 通道) [自动兜底]：
 *    - 无需激活 Shizuku 也能立即使用。
 *    - 带有动作取消钩子：一旦手指松开触发键，立即终止后续手势队列。
 */
class TouchInjector {

    companion object {
        private const val TAG = "TouchInjector"
        private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
    }

    enum class ActiveBackend {
        SHIZUKU_INPUT_MANAGER,
        ACCESSIBILITY_GESTURE
    }

    // 缓存 Shizuku IInputManager 代理实例与方法句柄，避免每次点击重复反射带来额外延迟
    @Volatile
    private var iInputManagerProxy: Any? = null
    @Volatile
    private var injectEventMethod: Method? = null
    @Volatile
    private var injectMethodParamCount: Int = 2

    /**
     * 检查当前是否可以使用 Shizuku 原生级注入
     */
    fun isShizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    fun getActiveBackend(): ActiveBackend {
        return if (isShizukuReady()) {
            ActiveBackend.SHIZUKU_INPUT_MANAGER
        } else {
            ActiveBackend.ACCESSIBILITY_GESTURE
        }
    }

    /**
     * 执行单点按压，支持随时被协程取消（松开按键立即发送 ACTION_UP 防卡键）
     */
    suspend fun injectTap(
        service: AccessibilityService?,
        x: Float,
        y: Float,
        holdMs: Long,
        isStillHoldingTrigger: () -> Boolean
    ) {
        if (!isStillHoldingTrigger()) return

        if (isShizukuReady() && tryInitShizukuInputManager()) {
            val downTime = SystemClock.uptimeMillis()
            var pressed = false
            try {
                injectMotionEventViaShizuku(downTime, downTime, MotionEvent.ACTION_DOWN, x, y)
                pressed = true
                // 分片等待：每 3ms 检查一次手指是否已松开，做到松手 3ms 内瞬间断触
                var remaining = holdMs.coerceAtLeast(8L)
                while (remaining > 0 && isStillHoldingTrigger()) {
                    val step = minOf(remaining, 3L)
                    delay(step)
                    remaining -= step
                }
            } finally {
                if (pressed) {
                    // 安全收尾：无论协程正常结束还是因松手被 cancel，必发 ACTION_UP 释放触控点
                    val upTime = SystemClock.uptimeMillis()
                    injectMotionEventViaShizuku(downTime, upTime, MotionEvent.ACTION_UP, x, y)
                }
            }
        } else if (service != null) {
            dispatchAccessibilityTap(service, x, y, holdMs)
        }
    }

    /**
     * 执行滑动，支持松手随时中断并安全抬起
     */
    suspend fun injectSwipe(
        service: AccessibilityService?,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
        isStillHoldingTrigger: () -> Boolean
    ) {
        if (!isStillHoldingTrigger()) return

        if (isShizukuReady() && tryInitShizukuInputManager()) {
            val downTime = SystemClock.uptimeMillis()
            val totalDuration = durationMs.coerceAtLeast(16L)
            val stepInterval = 8L // 125Hz 采样率平滑插值，媲美原生 120Hz 触控屏滑屏
            val steps = (totalDuration / stepInterval).toInt().coerceAtLeast(2)
            var currentX = startX
            var currentY = startY
            var pressed = false

            try {
                injectMotionEventViaShizuku(downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY)
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
        } else if (service != null) {
            dispatchAccessibilitySwipe(service, startX, startY, endX, endY, durationMs)
        }
    }

    /**
     * 通过 ShizukuBinderWrapper 获取具有 shell 权限的 IInputManager 远程代理
     */
    private fun tryInitShizukuInputManager(): Boolean {
        if (iInputManagerProxy != null && injectEventMethod != null) return true
        return try {
            val rawBinder: IBinder = SystemServiceHelper.getSystemService("input") ?: return false
            val wrappedBinder = ShizukuBinderWrapper(rawBinder)
            val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)
            val proxy = asInterfaceMethod.invoke(null, wrappedBinder) ?: return false

            // 兼容不同 Android 版本 IInputManager.injectInputEvent 的方法签名 (2参或3参)
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
            Log.w(TAG, "Shizuku IInputManager init failed, fallback to Accessibility", e)
            iInputManagerProxy = null
            injectEventMethod = null
            false
        }
    }

    private fun injectMotionEventViaShizuku(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float
    ) {
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
            size = 1f
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
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )
        try {
            val method = injectEventMethod ?: return
            val proxy = iInputManagerProxy ?: return
            if (injectMethodParamCount == 2) {
                method.invoke(proxy, event, INJECT_INPUT_EVENT_MODE_ASYNC)
            } else if (injectMethodParamCount == 3) {
                // 部分 Android 13/14 ROM 签名为 injectInputEvent(InputEvent, int mode, int targetUid)
                method.invoke(proxy, event, INJECT_INPUT_EVENT_MODE_ASYNC, -1)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "injectMotionEventViaShizuku error: ${e.message}")
            // 若 Binder 断开则清空缓存以便下次重连
            iInputManagerProxy = null
            injectEventMethod = null
        } finally {
            event.recycle()
        }
    }

    private suspend fun dispatchAccessibilityTap(
        service: AccessibilityService,
        x: Float,
        y: Float,
        durationMs: Long
    ) = suspendCancellableCoroutine { cont ->
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(
            path,
            0L,
            durationMs.coerceIn(10L, 500L)
        )
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(Unit)
                }
            },
            null
        )
        if (!dispatched && cont.isActive) {
            cont.resume(Unit)
        }
    }

    private suspend fun dispatchAccessibilitySwipe(
        service: AccessibilityService,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ) = suspendCancellableCoroutine { cont ->
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(
            path,
            0L,
            durationMs.coerceIn(16L, 2000L)
        )
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(Unit)
                }
            },
            null
        )
        if (!dispatched && cont.isActive) {
            cont.resume(Unit)
        }
    }
}
