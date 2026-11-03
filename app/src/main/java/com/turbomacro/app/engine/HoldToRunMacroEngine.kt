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
 * 按住开火引擎：
 * - 必须 TouchMux 已 ENGAGE（真屏抢占 + 同设备额外槽），否则拒绝启动
 * - 禁止无障碍 / input tap（都会打断拖枪）
 */
class HoldToRunMacroEngine(
    private val touchInjector: TouchInjector,
    private val hapticEngine: HapticEngine,
    private val onVisualRipple: (x: Float, y: Float, endX: Float?, endY: Float?) -> Unit,
    private val onRunStateChanged: (isRunning: Boolean) -> Unit,
    private val onInjectionBlockedWarning: () -> Unit,
    private val onRequireMux: () -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val isHolding = AtomicBoolean(false)
    private var activeJob: Job? = null
    private var hasWarnedBlocked = false

    fun isRunning(): Boolean = isHolding.get()

    fun onTriggerPressDown(service: AccessibilityService?, profile: MacroProfile) {
        val stepsSnapshot = profile.steps.map { it.copy() }
        if (stepsSnapshot.isEmpty()) return

        if (!touchInjector.isMuxMerging()) {
            // 尝试自动 ENGAGE（若用户正按着屏幕会失败，提示抬手）
            val ok = touchInjector.ensureMuxReady()
            if (!ok || !touchInjector.isMuxMerging()) {
                onRequireMux()
                return
            }
        }

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

                        val injectedOk = when (step.type) {
                            ActionType.TAP -> {
                                touchInjector.injectTap(
                                    service = service,
                                    x = actualX,
                                    y = actualY,
                                    holdMs = step.holdDurationMs.coerceIn(10L, 35L),
                                    isStillHoldingTrigger = { isHolding.get() && isActive },
                                    allowAccessibilityFallback = false
                                )
                            }
                            ActionType.SWIPE -> {
                                // 连发通道不做滑屏，避免干扰多指合并
                                false
                            }
                        }

                        if (!injectedOk && !hasWarnedBlocked) {
                            hasWarnedBlocked = true
                            onInjectionBlockedWarning()
                        }

                        var waitRemaining = step.intervalAfterMs.coerceAtLeast(12L)
                        while (waitRemaining > 0 && isHolding.get() && isActive) {
                            val slice = minOf(waitRemaining, 4L)
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
