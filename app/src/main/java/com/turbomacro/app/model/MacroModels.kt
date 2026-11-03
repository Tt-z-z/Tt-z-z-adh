package com.turbomacro.app.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 宏动作类型：
 * - TAP: 单点触控
 * - SWIPE: 滑动轨迹（如压枪下拉、技能划出）
 */
enum class ActionType {
    TAP,
    SWIPE
}

/**
 * 单个宏步骤节点
 * @param id 节点序号 (1, 2, 3...)
 * @param type 动作类型 (点击 / 滑动)
 * @param x 起点 X 坐标 (px)
 * @param y 起点 Y 坐标 (px)
 * @param endX 终点 X 坐标 (仅 SWIPE 有效)
 * @param endY 终点 Y 坐标 (仅 SWIPE 有效)
 * @param holdDurationMs 按住持续时长 (ms)，默认 14ms 接近真人极速点按
 * @param intervalAfterMs 当前动作结束后到下一步的间隔 (ms)
 * @param jitterRadiusPx 随机偏移半径 (px)，模拟真人手指微颤，保护账号并防检测
 */
data class MacroStep(
    val id: Int,
    var type: ActionType = ActionType.TAP,
    var x: Float,
    var y: Float,
    var endX: Float = x,
    var endY: Float = y,
    var holdDurationMs: Long = 14L,
    var intervalAfterMs: Long = 35L,
    var jitterRadiusPx: Float = 3f
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("endX", endX.toDouble())
        put("endY", endY.toDouble())
        put("holdDurationMs", holdDurationMs)
        put("intervalAfterMs", intervalAfterMs)
        put("jitterRadiusPx", jitterRadiusPx.toDouble())
    }

    companion object {
        fun fromJson(obj: JSONObject): MacroStep = MacroStep(
            id = obj.optInt("id", 1),
            type = ActionType.valueOf(obj.optString("type", ActionType.TAP.name)),
            x = obj.optDouble("x", 540.0).toFloat(),
            y = obj.optDouble("y", 960.0).toFloat(),
            endX = obj.optDouble("endX", 540.0).toFloat(),
            endY = obj.optDouble("endY", 960.0).toFloat(),
            holdDurationMs = obj.optLong("holdDurationMs", 14L),
            intervalAfterMs = obj.optLong("intervalAfterMs", 35L),
            jitterRadiusPx = obj.optDouble("jitterRadiusPx", 3.0).toFloat()
        )
    }
}

/**
 * 宏配置方案
 * @param name 方案名称
 * @param loopWhileHeld true = 按住期间无限循环序列；false = 按下仅执行一轮序列（松手依然随时打断）
 * @param enableHaptics 是否开启线性马达清脆反馈
 * @param enableRipple 是否显示小米 Game Turbo 风格点击波纹
 * @param triggerAlpha 触发按钮闲置透明度 (0.15 ~ 1.0)，保证不遮挡屏幕
 */
data class MacroProfile(
    var name: String = "默认极速连点",
    var loopWhileHeld: Boolean = true,
    var enableHaptics: Boolean = true,
    var enableRipple: Boolean = true,
    var triggerAlpha: Float = 0.35f,
    var triggerX: Int = 180,
    var triggerY: Int = 800,
    val steps: MutableList<MacroStep> = mutableListOf()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("loopWhileHeld", loopWhileHeld)
        put("enableHaptics", enableHaptics)
        put("enableRipple", enableRipple)
        put("triggerAlpha", triggerAlpha.toDouble())
        put("triggerX", triggerX)
        put("triggerY", triggerY)
        val arr = JSONArray()
        steps.forEach { arr.put(it.toJson()) }
        put("steps", arr)
    }

    companion object {
        fun fromJson(obj: JSONObject): MacroProfile {
            val stepsList = mutableListOf<MacroStep>()
            val arr = obj.optJSONArray("steps")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    stepsList.add(MacroStep.fromJson(arr.getJSONObject(i)))
                }
            }
            return MacroProfile(
                name = obj.optString("name", "默认极速连点"),
                loopWhileHeld = obj.optBoolean("loopWhileHeld", true),
                enableHaptics = obj.optBoolean("enableHaptics", true),
                enableRipple = obj.optBoolean("enableRipple", true),
                triggerAlpha = obj.optDouble("triggerAlpha", 0.35).toFloat(),
                triggerX = obj.optInt("triggerX", 180),
                triggerY = obj.optInt("triggerY", 800),
                steps = stepsList
            )
        }
    }
}
