package com.turbomacro.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.turbomacro.app.overlay.OverlayController

/**
 * 极速触控无障碍服务
 *
 * 安全承诺：
 * - `canRetrieveWindowContent="false"`：绝不读取屏幕文字、密码或任何应用隐私树
 * - 与 MainActivity 共享同一个全局 OverlayController 单例，按【音量+】100% 呼出编辑面板
 */
class MacroAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: MacroAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // 动态确保运行时启用按键过滤标记
        try {
            val info = serviceInfo
            if (info != null) {
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                serviceInfo = info
            }
        } catch (_: Throwable) {
        }

        val controller = OverlayController.getInstance(applicationContext)
        controller.serviceProvider = { instance }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            val ctrl = OverlayController.getInstance(applicationContext)
            if (ctrl.isOverlayShowing()) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    ctrl.toggleCombatEditMode()
                }
                // 悬浮窗开启期间拦截音量+，专门用作「编辑态 / 战斗隐身态」切换热键
                return true
            }
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
        OverlayController.getInstance(applicationContext).hideOverlay()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        OverlayController.getInstance(applicationContext).hideOverlay()
        instance = null
        return super.onUnbind(intent)
    }
}
