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
 * 按住开火引擎（三角洲）：
 * - 仅在 Shizuku 就绪时允许按住连发（无障碍会打断屏幕其他手指，一律拒绝）
 * - 注入走 shell tap / 合成设备，不向物理触控屏塞 ACTION_DOWN
 */
class HoldToRunMacroEngine(
    private val touchInjector: TouchInjector,
    private val hapticEngine: HapticEngine,
    private val onVisualRipple: (x: Float, y: Float, endX: Float?, endY: Float?) -> Unit,
    private val onRunStateChanged: (isRunning: Boolean) -> Unit,
    private val onInjectionBlockedWarning: () -> Unit,
    private val onRequireShizuku: () -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val isHolding = AtomicBoolean(false)
    private var activeJob: Job? = null
    private var hasWarnedBlocked = false

    fun isRunning(): Boolean = isHolding.get()

    fun onTriggerPressDown(service: AccessibilityService?, profile: MacroProfile) {
        val stepsSnapshot = profile.steps.map { it.copy() }
        if (stepsSnapshot.isEmpty()) return

        // 按住连发 + 同时走位/瞄准：必须 Shizuku。无障碍会 cancel 其他手指。
        if (!touchInjector.isShizukuReady()) {
            onRequireShizuku()
            return
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

                        // 严禁无障碍降级
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
                                touchInjector.injectSwipe(
                                    service = service,
                                    startX = actualX,
                                    startY = actualY,
                                    endX = (step.endX + offsetX).coerceAtLeast(1f),
                                    endY = (step.endY + offsetY).coerceAtLeast(1f),
                                    durationMs = step.holdDurationMs.coerceAtLeast(32L),
                                    isStillHoldingTrigger = { isHolding.get() && isActive },
                                    allowAccessibilityFallback = false
                                )
                            }
                        }

                        if (!injectedOk && !hasWarnedBlocked) {
                            hasWarnedBlocked = true
                            onInjectionBlockedWarning()
                        }

                        var waitRemaining = step.intervalAfterMs.coerceAtLeast(16L)
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
