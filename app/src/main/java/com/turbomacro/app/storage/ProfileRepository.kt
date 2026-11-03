package com.turbomacro.app.storage

import android.content.Context
import com.turbomacro.app.model.ActionType
import com.turbomacro.app.model.MacroProfile
import com.turbomacro.app.model.MacroStep
import org.json.JSONObject

/**
 * 宏配置持久化仓库（SharedPreferences JSON）
 */
class ProfileRepository(context: Context) {

    private val prefs = context.getSharedPreferences("turbo_macro_prefs", Context.MODE_PRIVATE)

    fun loadProfile(): MacroProfile {
        val raw = prefs.getString("active_profile", null)
        if (!raw.isNullOrBlank()) {
            return try {
                MacroProfile.fromJson(JSONObject(raw))
            } catch (_: Throwable) {
                createDefaultProfile()
            }
        }
        return createDefaultProfile()
    }

    fun saveProfile(profile: MacroProfile) {
        prefs.edit().putString("active_profile", profile.toJson().toString()).apply()
    }

    private fun createDefaultProfile(): MacroProfile {
        return MacroProfile(
            name = "小米极速连发宏",
            loopWhileHeld = true,
            enableHaptics = true,
            enableRipple = true,
            triggerAlpha = 0.38f,
            triggerSizeDp = 54,
            triggerX = 160,
            triggerY = 760,
            steps = mutableListOf(
                MacroStep(
                    id = 1,
                    type = ActionType.TAP,
                    x = 540f,
                    y = 960f,
                    holdDurationMs = 28L,
                    intervalAfterMs = 32L,
                    jitterRadiusPx = 3f
                )
            )
        )
    }
}
