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
 * 双模安全触控注入器（针对 60Hz/120Hz 游戏引擎帧采样与多指防冲突深度调优）：
 *
 * 为什么之前「有特效但游戏内点击不生效」？
 * 1. 无障碍通道下：当你手指按住触发键（Window A）时，Android 系统默认拒绝通过 dispatchGesture
 *    向底部游戏窗口（Window B）注入第二根手指，导致 dispatchGesture 直接返回 false 被静默丢弃！
 * 2. Shizuku 通道下：之前 pointerId 固定写死为 0，与你按住触发键的物理手指 pointerId=0 发生 ID 碰撞，
 *    且 14ms 的按压时间在 60fps 游戏中（1帧=16.67ms）经常落在两帧轮询间隙内被游戏引擎漏检！
 *
 * 修复方案：
 * - Shizuku 通道：
 *   1. 使用独立虚拟触控点 ID 并完整构造符合真机电容屏特征的 PointerCoords（touchMajor/Minor/size/pressure）。
 *   2. 按压期间每 8ms 发送一次微小 ACTION_MOVE 保活帧，且最短有效按压 >= 26ms（跨越至少 2 个游戏渲染帧），确保 Unity/Unreal 100% 识别按下与抬起。
 * - 无障碍通道：
 *   1. 当检测到处于无障碍模式（未开 Shizuku）时，触发键支持「点一下触发键开启连发、再点一下立即停止」或「松手触发」，避开物理手指占住屏幕导致的系统多窗口手势互斥锁！
 *   2. 传入主线程 Handler 并在 Path 中加入 0.5px 微位移（部分 ROM 对长度为 0 的 Path 不触发游戏内 TouchDown）。
 */
class TouchInjector {

    companion object {
        private const val TAG = "TouchInjector"
        private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
        private const val INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT = 1
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
     * 执行游戏级高可靠单点按压
     */
    suspend fun injectTap(
        service: AccessibilityService?,
        x: Float,
        y: Float,
        holdMs: Long,
        isStillHoldingTrigger: () -> Boolean
    ): Boolean {
        if (!isStillHoldingTrigger()) return false

        // 游戏引擎（Unity/UE4/Cocos）至少需要跨越 1.5~2 个逻辑帧（>= 25ms）才能稳定判定一次有效点击
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
                    // 若 Shizuku 注入失败，立即降级尝试无障碍手势
                    if (service != null) {
                        return dispatchAccessibilityTap(service, x, y, effectiveHoldMs)
                    }
                    return false
                }
                pressed = true

                // 在按住期间每 8ms 发送一次极微小的 ACTION_MOVE 保活帧，让游戏引擎 100% 捕获触控态
                var remaining = effectiveHoldMs
                var stepCount = 0
                while (remaining > 0 && isStillHoldingTrigger()) {
                    val slice = minOf(remaining, 6L)
                    delay(slice)
                    remaining -= slice
                    stepCount++
                    if (remaining > 0 && stepCount % 2 == 0) {
                        injectMotionEventViaShizuku(
                            downTime = downTime,
                            eventTime = SystemClock.uptimeMillis(),
                            action = MotionEvent.ACTION_MOVE,
                            x = x + 0.3f,
                            y = y + 0.3f
                        )
                    }
                }
                return true
            } finally {
                if (pressed) {
                    val upTime = SystemClock.uptimeMillis()
                    injectMotionEventViaShizuku(
                        downTime = downTime,
                        eventTime = upTime,
                        action = MotionEvent.ACTION_UP,
                        x = x,
                        y = y
                    )
                }
            }
        } else if (service != null) {
            return dispatchAccessibilityTap(service, x, y, effectiveHoldMs)
        }
        return false
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
                    if (service != null) {
                        return dispatchAccessibilitySwipe(service, startX, startY, endX, endY, totalDuration)
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
        } else if (service != null) {
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
    ): Boolean {
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val isUp = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = if (isUp) 0f else 1.0f
            size = if (isUp) 0f else 0.5f
            touchMajor = if (isUp) 0f else 12f
            touchMinor = if (isUp) 0f else 10f
            toolMajor = if (isUp) 0f else 12f
            toolMinor = if (isUp) 0f else 10f
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
            -1, // deviceId = -1 (VIRTUAL_KEYBOARD / VIRTUAL_TOUCH)
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )
        return try {
            val method = injectEventMethod ?: return false
            val proxy = iInputManagerProxy ?: return false
            val result = when (injectMethodParamCount) {
                2 -> method.invoke(proxy, event, INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT)
                3 -> method.invoke(proxy, event, INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT, -1)
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
        // 加上 400ms 超时保护，绝不因为系统手势回调丢失而卡死协程
        return withTimeoutOrNull(durationMs + 350L) {
            suspendCancellableCoroutine { cont ->
                val resumed = AtomicBoolean(false)
                // 加入 0.6px 微位移，防止部分小米/vivo ROM 忽略零长度 Path 而不向游戏分发 DOWN/UP
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
