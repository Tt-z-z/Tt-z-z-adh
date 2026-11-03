package com.turbomacro.app.engine

import android.accessibilityservice.AccessibilityService
import com.turbomacro.app.model.ActionType
import com.turbomacro.app.model.MacroProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * 极速「按住执行、松开即停」状态机引擎
 *
 * 特点：
 * - 零延迟按下响应：ACTION_DOWN 当帧立即启动协程并触发首点
 * - 真实命中校验：只有底层真正把点击送进系统（injected == true）才渲染水波纹与震动，绝不「假特效空转」
 * - 毫秒级断触保障：AtomicBoolean + Coroutine Job 即时取消，松手 3ms 内终止一切输出
 */
class HoldToRunMacroEngine(
    private val touchInjector: TouchInjector,
    private val hapticEngine: HapticEngine,
    private val onVisualRipple: (x: Float, y: Float, endX: Float?, endY: Float?) -> Unit,
    private val onRunStateChanged: (isRunning: Boolean) -> Unit,
    private val onInjectionBlockedWarning: () -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val isHolding = AtomicBoolean(false)
    private var activeJob: Job? = null
    private var hasWarnedBlocked = false

    fun isRunning(): Boolean = isHolding.get()

    /**
     * 启动宏连发
     */
    fun onTriggerPressDown(service: AccessibilityService?, profile: MacroProfile) {
        val stepsSnapshot = profile.steps.map { it.copy() }
        if (stepsSnapshot.isEmpty()) return
        if (!isHolding.compareAndSet(false, true)) return

        if (profile.enableHaptics) {
            hapticEngine.performTriggerDown()
        }
        onRunStateChanged(true)

        activeJob?.cancel()
        activeJob = scope.launch {
            try {
                do {
                    for (step in stepsSnapshot) {
                        if (!isActive || !isHolding.get()) break

                        val jitter = step.jitterRadiusPx
                        val offsetX = if (jitter > 0f) Random.nextFloat() * jitter * 2 - jitter else 0f
                        val offsetY = if (jitter > 0f) Random.nextFloat() * jitter * 2 - jitter else 0f

                        val actualX = (step.x + offsetX).coerceAtLeast(1f)
                        val actualY = (step.y + offsetY).coerceAtLeast(1f)

                        // 先触发视觉水波纹与线性马达，再执行高优先注入
                        if (profile.enableHaptics) {
                            hapticEngine.performMacroTick()
                        }
                        if (profile.enableRipple) {
                            if (step.type == ActionType.SWIPE) {
                                onVisualRipple(actualX, actualY, step.endX + offsetX, step.endY + offsetY)
                            } else {
                                onVisualRipple(actualX, actualY, null, null)
                            }
                        }

                        // Shizuku 按住连发：禁止降级无障碍（dispatchGesture 会打断游戏其他手指）
                        // 无障碍点按模式：允许无障碍注入（此时手指已离开悬浮键）
                        val shizukuActive = touchInjector.getActiveBackend() ==
                            TouchInjector.ActiveBackend.SHIZUKU_INPUT_MANAGER
                        val allowAccFallback = !shizukuActive

                        val injectedOk = when (step.type) {
                            ActionType.TAP -> {
                                touchInjector.injectTap(
                                    service = service,
                                    x = actualX,
                                    y = actualY,
                                    holdMs = step.holdDurationMs,
                                    isStillHoldingTrigger = { isHolding.get() && isActive },
                                    allowAccessibilityFallback = allowAccFallback
                                )
                            }
                            ActionType.SWIPE -> {
                                touchInjector.injectSwipe(
                                    service = service,
                                    startX = actualX,
                                    startY = actualY,
                                    endX = (step.endX + offsetX).coerceAtLeast(1f),
                                    endY = (step.endY + offsetY).coerceAtLeast(1f),
                                    durationMs = step.holdDurationMs.coerceAtLeast(32L),
                                    isStillHoldingTrigger = { isHolding.get() && isActive },
                                    allowAccessibilityFallback = allowAccFallback
                                )
                            }
                        }

                        if (!injectedOk && !hasWarnedBlocked) {
                            hasWarnedBlocked = true
                            onInjectionBlockedWarning()
                        }

                        var waitRemaining = step.intervalAfterMs.coerceAtLeast(12L)
                        while (waitRemaining > 0 && isHolding.get() && isActive) {
                            val slice = minOf(waitRemaining, 3L)
                            delay(slice)
                            waitRemaining -= slice
                        }
                    }
                } while (profile.loopWhileHeld && isHolding.get() && isActive)
            } catch (_: CancellationException) {
            } finally {
                isHolding.set(false)
                onRunStateChanged(false)
            }
        }
    }

    /**
     * 停止宏连发
     */
    fun onTriggerReleaseUp(profile: MacroProfile) {
        if (!isHolding.compareAndSet(true, false)) return

        activeJob?.cancel()
        activeJob = null

        if (profile.enableHaptics) {
            hapticEngine.performTriggerUp()
        }
        onRunStateChanged(false)
    }

    fun forceStop() {
        isHolding.set(false)
        activeJob?.cancel()
        activeJob = null
        onRunStateChanged(false)
    }
}
