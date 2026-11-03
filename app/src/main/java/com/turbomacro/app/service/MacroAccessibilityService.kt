package com.turbomacro.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.turbomacro.app.overlay.OverlayController

/**
 * 极速触控无障碍服务
 *
 * 安全承诺：
 * - `canRetrieveWindowContent="false"`：绝不读取屏幕文字、密码或任何应用隐私树
 * - 仅用作悬浮层承载与无障碍手势通道兜底
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
