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
 * - 零延迟按下响应：ACTION_DOWN 当帧立即启动协程并触发首点，不等任何 Timer 周期
 * - 毫秒级断触保障：AtomicBoolean + Coroutine Job 即时取消，松手 3ms 内终止一切输出
 * - 并发安全快照：执行循环前对 steps 拍快照，防止编辑与连发并发导致 ConcurrentModificationException
 * - 支持拟真微颤偏移（jitterRadiusPx）：每次点击在靶心周围微小随机分布，媲美真人手指
 */
class HoldToRunMacroEngine(
    private val touchInjector: TouchInjector,
    private val hapticEngine: HapticEngine,
    private val onVisualRipple: (x: Float, y: Float, endX: Float?, endY: Float?) -> Unit,
    private val onRunStateChanged: (isRunning: Boolean) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val isHolding = AtomicBoolean(false)
    private var activeJob: Job? = null

    /**
     * 当手指按住悬浮触发键 (ACTION_DOWN) 时立即调用
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

                        // 计算真人微颤随机偏移
                        val jitter = step.jitterRadiusPx
                        val offsetX = if (jitter > 0f) Random.nextFloat() * jitter * 2 - jitter else 0f
                        val offsetY = if (jitter > 0f) Random.nextFloat() * jitter * 2 - jitter else 0f

                        val actualX = (step.x + offsetX).coerceAtLeast(1f)
                        val actualY = (step.y + offsetY).coerceAtLeast(1f)

                        // 触发线性马达脉冲与小米风格水波纹反馈
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

                        // 执行底层点击或滑动
                        when (step.type) {
                            ActionType.TAP -> {
                                touchInjector.injectTap(
                                    service = service,
                                    x = actualX,
                                    y = actualY,
                                    holdMs = step.holdDurationMs,
                                    isStillHoldingTrigger = { isHolding.get() && isActive }
                                )
                            }
                            ActionType.SWIPE -> {
                                touchInjector.injectSwipe(
                                    service = service,
                                    startX = actualX,
                                    startY = actualY,
                                    endX = (step.endX + offsetX).coerceAtLeast(1f),
                                    endY = (step.endY + offsetY).coerceAtLeast(1f),
                                    durationMs = step.holdDurationMs.coerceAtLeast(24L),
                                    isStillHoldingTrigger = { isHolding.get() && isActive }
                                )
                            }
                        }

                        // 步骤间隔等待（每 3ms 切片检查松手状态，保证随时松手零拖泥带水）
                        var waitRemaining = step.intervalAfterMs.coerceAtLeast(6L)
                        while (waitRemaining > 0 && isHolding.get() && isActive) {
                            val slice = minOf(waitRemaining, 3L)
                            delay(slice)
                            waitRemaining -= slice
                        }
                    }
                } while (profile.loopWhileHeld && isHolding.get() && isActive)
            } catch (_: CancellationException) {
                // 松开按键正常取消
            } finally {
                if (!isHolding.get()) {
                    onRunStateChanged(false)
                }
            }
        }
    }

    /**
     * 当手指松开悬浮触发键 (ACTION_UP / ACTION_CANCEL) 时立即调用
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

    /**
     * 紧急停止一切宏执行（如切出界面、锁屏或关闭悬浮窗时安全兜底）
     */
    fun forceStop() {
        isHolding.set(false)
        activeJob?.cancel()
        activeJob = null
        onRunStateChanged(false)
    }
}
