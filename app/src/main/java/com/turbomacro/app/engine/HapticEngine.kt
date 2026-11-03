package com.turbomacro.app.engine

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * 线性马达微振引擎（对标小米 MIUI / HyperOS Game Turbo 物理肩键质感）
 *
 * 带有硬件保护限流（Rate Limiter）：
 * 即使连点频率高达 40次/秒，马达脉冲也会自动限制在安全舒适间隔（>= 38ms），
 * 防止线性马达长时间高频谐振发热，同时保持颗粒分明的段落手感。
 */
class HapticEngine(context: Context) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vm.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    @Volatile
    private var lastPulseUptimeMs: Long = 0L

    companion object {
        // 马达物理保护最小间隔：限制最高约 25Hz 脉冲，手感最清脆且绝不发麻发热
        private const val MIN_PULSE_INTERVAL_MS = 38L
    }

    /**
     * 触发键按下瞬间：清脆的重音「哒」一声（类似微动开关触发）
     */
    fun performTriggerDown(anchorView: View? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.85f)
                .compose()
            vibrator.vibrate(effect)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } else {
            anchorView?.performHapticFeedback(
                HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        }
    }

    /**
     * 触发键松开瞬间：轻盈的回弹反馈（类似微动开关弹起）
     */
    fun performTriggerUp(anchorView: View? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.45f)
                .compose()
            vibrator.vibrate(effect)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } else {
            anchorView?.performHapticFeedback(
                HapticFeedbackConstants.CLOCK_TICK,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        }
    }

    /**
     * 每次宏点击执行时的极短脉冲（8ms 轻脉冲 + 限流保护）
     */
    fun performMacroTick() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastPulseUptimeMs < MIN_PULSE_INTERVAL_MS) return
        lastPulseUptimeMs = now

        if (!vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)
        ) {
            val effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.35f)
                .compose()
            vibrator.vibrate(effect)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } else {
            vibrator.vibrate(VibrationEffect.createOneShot(8L, 65))
        }
    }
}
