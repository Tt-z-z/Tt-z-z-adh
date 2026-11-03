package com.turbomacro.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.turbomacro.app.overlay.OverlayController

/**
 * 极速触控无障碍服务
 *
 * 安全承诺：
 * - `canRetrieveWindowContent="false"`：绝不读取屏幕文字、密码或任何应用隐私树
 * - 支持监听「音量+」按键一键进入/退出编辑模式（使得战斗态下顶部胶囊条彻底隐身）
 */
class MacroAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: MacroAccessibilityService? = null
            private set
    }

    var overlayController: OverlayController? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlayController = OverlayController(applicationContext) { this }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            val ctrl = overlayController
            if (ctrl != null && ctrl.isOverlayShowing()) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    ctrl.toggleCombatEditMode()
                }
                // 悬浮窗开启期间拦截音量+，专门用作「编辑态 / 战斗隐身态」切换热键，不弹出系统音量条挡视野
                return true
            }
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不监听、不采集任何界面事件，保持 0 CPU 占用与绝对隐私安全
    }

    override fun onInterrupt() {
        overlayController?.hideOverlay()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        overlayController?.hideOverlay()
        overlayController = null
        instance = null
        return super.onUnbind(intent)
    }
}
