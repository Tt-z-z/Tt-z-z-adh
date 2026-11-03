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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * 三角洲多指共存要点（审核结论）：
 *
 * 1. 绝不能在「物理触控屏同一个 device」上再发 ACTION_DOWN——系统会重置该设备上所有手指，走位/瞄准全断。
 * 2. 伪造未注册的 deviceId 在小米 HyperOS 上常被并回主触控屏 → 等同于上面的灾难。
 * 3. 正确做法：用 shell 权限的 `input` 工具走独立注入路径，或注册真正的虚拟设备；
 *    这里用 Shizuku 执行 `input touchscreen tap`，每次完整 tap，不插入物理多指流。
 * 4. 无障碍 dispatchGesture 会取消屏幕其他手势 → 按住开火时严禁走无障碍。
 */
class TouchInjector {

    companion object {
        private const val TAG = "TouchInjector"
        private const val INJECT_ASYNC = 0
        private val tapSeq = AtomicInteger(0)
    }

    enum class ActiveBackend {
        SHIZUKU_INPUT_MANAGER,
        ACCESSIBILITY_GESTURE
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var iInputManagerProxy: Any? = null
    @Volatile private var injectEventMethod: Method? = null
    @Volatile private var injectMethodParamCount: Int = 2
    @Volatile private var preferShellTap: Boolean = true

    fun isShizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
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
     * 单次开火点按。按住悬浮钮连发时 allowAccessibilityFallback 必须为 false。
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

        val pressMs = holdMs.coerceIn(10L, 35L)
        val ix = x.toInt().coerceAtLeast(0)
        val iy = y.toInt().coerceAtLeast(0)

        if (isShizukuReady()) {
            // 1) 优先：shell input tap（不进入物理多指流，避免清掉走位/瞄准手指）
            if (preferShellTap && shellTap(ix, iy)) {
                // tap 本身已含 down+up；略等间隔由引擎控制
                delay(pressMs.coerceAtMost(16L))
                return true
            }

            // 2) 回退：InputManager 注入，但使用「与当前触控屏不同的真实 deviceId」策略失败时，
            //    仅用极短 DOWN/UP，且 deviceId 取「非触控屏」类源会无效；这里改用
            //    SOURCE_TOUCHSCREEN + 独立 downTime，并避免在用户按住期间发 MOVE。
            if (tryInitShizukuInputManager() && injectIsolatedTap(ix.toFloat(), iy.toFloat(), pressMs)) {
                return true
            }

            // 按住开火期间禁止无障碍降级
            return false
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
        // 开火宏不需要滑屏；若配置了滑屏，Shizuku 下用 input swipe，同样避免抢物理多指
        if (isShizukuReady()) {
            val ok = shellSwipe(
                startX.toInt(), startY.toInt(),
                endX.toInt(), endY.toInt(),
                durationMs.coerceIn(32L, 800L).toInt()
            )
            return ok
        }
        if (allowAccessibilityFallback && service != null) {
            return dispatchAccessibilitySwipe(
                service, startX, startY, endX, endY, durationMs.coerceAtLeast(32L)
            )
        }
        return false
    }

    /**
     * Shizuku shell：input touchscreen tap
     * 走独立命令注入，不向物理触控设备塞 ACTION_DOWN，从而不重置其他手指。
     */
    private fun shellTap(x: Int, y: Int): Boolean {
        return try {
            var code = execShizukuCommand(
                arrayOf("input", "touchscreen", "tap", x.toString(), y.toString())
            )
            if (code != 0) {
                code = execShizukuCommand(
                    arrayOf("input", "tap", x.toString(), y.toString())
                )
            }
            code == 0
        } catch (e: Throwable) {
            Log.w(TAG, "shellTap failed: ${e.message}")
            preferShellTap = false
            false
        }
    }

    private fun shellSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
        return try {
            var code = execShizukuCommand(
                arrayOf(
                    "input", "touchscreen", "swipe",
                    x1.toString(), y1.toString(), x2.toString(), y2.toString(),
                    durationMs.toString()
                )
            )
            if (code != 0) {
                code = execShizukuCommand(
                    arrayOf(
                        "input", "swipe",
                        x1.toString(), y1.toString(), x2.toString(), y2.toString(),
                        durationMs.toString()
                    )
                )
            }
            code == 0
        } catch (e: Throwable) {
            Log.w(TAG, "shellSwipe failed: ${e.message}")
            false
        }
    }

    /** 返回进程 exit code；失败返回 -1 */
    private fun execShizukuCommand(cmd: Array<String>): Int {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val remote = method.invoke(null, cmd, null, null) ?: return -1
            // rikka.shizuku.IRemoteProcess / RemoteProcess：waitFor()
            val waitFor = remote.javaClass.methods.firstOrNull {
                it.name == "waitFor" && it.parameterTypes.isEmpty()
            } ?: return -1
            (waitFor.invoke(remote) as? Int) ?: -1
        } catch (e: Throwable) {
            Log.w(TAG, "execShizukuCommand failed: ${e.message}")
            -1
        }
    }

    /**
     * 最后手段：InputManager 注入。
     * 使用每次递增的独立 downTime，且只发 DOWN→等待→UP，不发 MOVE。
     * deviceId 使用 -1（合成事件），避免占用真实触控屏 device 上的 pointer 槽位。
     */
    private suspend fun injectIsolatedTap(x: Float, y: Float, pressMs: Long): Boolean {
        val downTime = SystemClock.uptimeMillis() + tapSeq.incrementAndGet()
        if (!injectSynthetic(downTime, downTime, MotionEvent.ACTION_DOWN, x, y)) return false
        delay(pressMs)
        return injectSynthetic(
            downTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP,
            x,
            y
        )
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
            false
        }
    }

    private fun injectSynthetic(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float
    ): Boolean {
        // pointerId 固定 0，但 deviceId=-1：合成设备，不占用真机触控屏的多指槽
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val up = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = if (up) 0f else 1f
            size = if (up) 0f else 0.35f
        })
        val event = MotionEvent.obtain(
            downTime, eventTime, action, 1, props, coords,
            0, 0, 1f, 1f,
            -1, // 合成设备，禁止用未注册的「假 ID」（小米会并回主屏并清指）
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
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
            Log.w(TAG, "injectSynthetic failed: ${e.message}")
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
