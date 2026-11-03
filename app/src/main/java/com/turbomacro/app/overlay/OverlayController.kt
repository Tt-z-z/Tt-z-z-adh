package com.turbomacro.app.overlay

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.turbomacro.app.engine.HapticEngine
import com.turbomacro.app.engine.HoldToRunMacroEngine
import com.turbomacro.app.engine.TouchInjector
import com.turbomacro.app.model.ActionType
import com.turbomacro.app.model.MacroProfile
import com.turbomacro.app.model.MacroStep
import com.turbomacro.app.storage.ProfileRepository

/**
 * 小米 Game Turbo 风格悬浮窗总控中心（全局单例 + 双通道「音量+」呼出编辑）
 *
 * 为什么之前按「音量+」没反应？
 * 1. 当你只开了 Shizuku（没开无障碍）或者覆盖安装后无障碍未重启时，`AccessibilityService.onKeyEvent` 根本不会触发！
 * 2. 之前 `MainActivity` 和 `MacroAccessibilityService` 各自创建了不同的 `OverlayController` 实例，导致按键事件发到了空实例上！
 *
 * 彻底根治方案（3重保障，100% 呼出编辑模式）：
 * - 【保障 1：系统级音量广播监听 (`VOLUME_CHANGED_ACTION`)】：无论开没开无障碍、无论在哪个全屏游戏里，只要按一下手机「音量+」，系统底层广播立即触发切换编辑模式，并自动把音量还原（不吵人）！
 * - 【保障 2：无障碍硬件按键拦截 (`onKeyEvent`)】：若开启了无障碍，直接底层接管「音量+」。
 * - 【保障 3：触发键快速双击/三击手势兜底】：在悬浮触发圆环上「快速连点 3 下」也可随时进入编辑模式！
 */
class OverlayController private constructor(
    private val context: Context
) {
    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var INSTANCE: OverlayController? = null

        fun getInstance(context: Context): OverlayController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OverlayController(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val repository = ProfileRepository(context)
    val touchInjector = TouchInjector(context)
    private val hapticEngine = HapticEngine(context)

    var serviceProvider: () -> AccessibilityService? = { null }

    var currentProfile: MacroProfile = repository.loadProfile()
        private set

    private var rippleOverlay: RippleFeedbackOverlayView? = null
    private var targetEditorOverlay: TargetEditorOverlayView? = null
    private var triggerButton: FloatingTriggerButtonView? = null
    private var triggerParams: WindowManager.LayoutParams? = null
    private var miniDockView: View? = null
    private var miniDockParams: WindowManager.LayoutParams? = null
    private var stepConfigPopupView: View? = null

    private var isShowing = false
    private var isCombatLocked = true

    // 音量+ 广播防抖与自动恢复音量
    private var isVolumeReceiverRegistered = false
    private var lastVolumeToggleTimeMs = 0L
    private var isRestoringVolume = false

    private val volumeBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (!isShowing || isRestoringVolume) return
            if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                // 仅监听媒体音量(MUSIC)或系统主音量变化
                if (streamType == AudioManager.STREAM_MUSIC || streamType == AudioManager.STREAM_RING) {
                    val newVol = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
                    val oldVol = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
                    // 当用户按下「音量+」(新音量 > 旧音量，或者已达最大音量时继续按音量+)
                    if (newVol > oldVol && oldVol >= 0) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastVolumeToggleTimeMs > 450L) {
                            lastVolumeToggleTimeMs = now
                            // 自动将刚才加上的 1 格音量静默还原，避免玩游戏时声音变大
                            isRestoringVolume = true
                            try {
                                audioManager.setStreamVolume(streamType, oldVol, 0)
                            } catch (_: Throwable) {
                            } finally {
                                mainHandler.postDelayed({ isRestoringVolume = false }, 180L)
                            }
                            toggleCombatEditMode()
                        }
                    }
                }
            }
        }
    }

    private val macroEngine: HoldToRunMacroEngine = HoldToRunMacroEngine(
        touchInjector = touchInjector,
        hapticEngine = hapticEngine,
        onVisualRipple = { x, y, endX, endY ->
            // 不用全屏悬浮层画波纹（小米上全屏窗会干扰多指）；只在触发钮上闪一下
            mainHandler.post { triggerButton?.refreshVisualState() }
        },
        onRunStateChanged = { _ ->
            mainHandler.post {
                triggerButton?.refreshVisualState()
            }
        },
        onInjectionBlockedWarning = {
            mainHandler.post {
                Toast.makeText(
                    context,
                    "开火注入失败：TouchMux 未合并多指。请确认 Shizuku + USB 调试（安全设置），并先松开屏幕所有手指再开悬浮键",
                    Toast.LENGTH_LONG
                ).show()
            }
        },
        onRequireMux = {
            mainHandler.post {
                val detail = touchInjector.muxError() ?: "unknown"
                Toast.makeText(
                    context,
                    "需要先启用多指通道：松开屏幕全部手指约 1 秒，再按住开火键（$detail）",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    )

    fun isOverlayShowing(): Boolean = isShowing

    fun showOverlay() {
        if (isShowing) return
        currentProfile = repository.loadProfile()
        isCombatLocked = true

        // 战斗态不挂全屏水波纹窗：HyperOS 上即使 FLAG_NOT_TOUCHABLE 的全屏悬浮层也会干扰多指分发

        // 挂载按住触发键（三角洲：按住连点开火，其他手指照常走位/瞄准）
        // FLAG_SPLIT_TOUCH：按住本钮时，屏幕其他区域的多指仍交给游戏
        // FLAG_NOT_TOUCH_MODAL：只有按钮矩形吃触摸，外侧全部穿透
        val sizePx = dpToPx(currentProfile.triggerSizeDp.coerceIn(36, 96))
        val triggerLp = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = currentProfile.triggerX
            y = currentProfile.triggerY
            applyCutoutShortEdges(this)
        }

        val btn = FloatingTriggerButtonView(
            context = context,
            isShizukuModeProvider = {
                touchInjector.isMuxMerging()
            },
            isMacroRunningProvider = {
                macroEngine.isRunning()
            },
            onHoldStart = {
                macroEngine.onTriggerPressDown(serviceProvider(), currentProfile)
            },
            onHoldEnd = {
                macroEngine.onTriggerReleaseUp(currentProfile)
            },
            onDragMoved = { dx, dy ->
                triggerLp.x = (triggerLp.x + dx).coerceAtLeast(0)
                triggerLp.y = (triggerLp.y + dy).coerceAtLeast(0)
                currentProfile.triggerX = triggerLp.x
                currentProfile.triggerY = triggerLp.y
                repository.saveProfile(currentProfile)
                windowManager.updateViewLayout(triggerButton, triggerLp)
            },
            onOpenMenuClicked = {
                if (currentProfile.steps.isNotEmpty()) {
                    showStepInspectorPopup(currentProfile.steps.first())
                }
            },
            onQuickEditGesture = {
                toggleCombatEditMode()
            }
        ).apply {
            idleAlpha = currentProfile.triggerAlpha
            isLockedForCombat = isCombatLocked
        }

        windowManager.addView(btn, triggerLp)
        triggerButton = btn
        triggerParams = triggerLp

        // 编辑条战斗态不挂到 WindowManager（GONE 仍可能占窗影响触控），编辑时再 attach
        // 音量+ 广播呼出编辑
        registerVolumeUpReceiver()

        isShowing = true

        // 启动 TouchMux：必须短暂无触摸才能 EVIOCGRAB，否则会拖枪即断
        mainHandler.post {
            if (!touchInjector.isShizukuReady()) {
                Toast.makeText(
                    context,
                    "请先授权 Shizuku，并打开「USB 调试（安全设置）」",
                    Toast.LENGTH_LONG
                ).show()
                return@post
            }
            Toast.makeText(
                context,
                "正在启用多指通道：请松开屏幕全部手指…",
                Toast.LENGTH_SHORT
            ).show()
            Thread {
                val ok = touchInjector.ensureMuxReady()
                mainHandler.post {
                    if (ok) {
                        Toast.makeText(
                            context,
                            "✅ 多指通道已就绪：按住开火键连点，可同时拖枪/走位。编辑：音量+ 或连点钮3下",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        val err = touchInjector.muxError() ?: "unknown"
                        Toast.makeText(
                            context,
                            "多指通道未就绪（$err）。请松开全部手指后再开一次悬浮键，或先按住开火键时会自动重试",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }.start()
        }
    }

    /**
     * 供「音量+」物理按键、音量广播或触发键手势调用：一键切换「战斗全隐身态」与「靶点编辑态」
     */
    fun toggleCombatEditMode() {
        if (!isShowing) return
        val now = SystemClock.uptimeMillis()
        if (now - lastVolumeToggleTimeMs in 1..250L) return
        lastVolumeToggleTimeMs = now

        mainHandler.post {
            macroEngine.forceStop()
            isCombatLocked = !isCombatLocked
            triggerButton?.isLockedForCombat = isCombatLocked
            triggerButton?.refreshVisualState()

            if (isCombatLocked) {
                // 彻底移出 WindowManager，避免残留窗口干扰多指
                dismissStepInspectorPopup()
                detachTargetEditor()
                detachMiniDock()
                repository.saveProfile(currentProfile)
                hapticEngine.performTriggerUp()
                Toast.makeText(context, "🔒 已锁定（按【音量+】可再编辑）", Toast.LENGTH_SHORT).show()
            } else {
                attachMiniDock()
                attachTargetEditor()
                hapticEngine.performTriggerDown()
                Toast.makeText(context, "✏️ 编辑模式（再按【音量+】锁定）", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun hideOverlay() {
        if (!isShowing) return
        unregisterVolumeUpReceiver()
        macroEngine.forceStop()
        touchInjector.releaseMux()
        dismissStepInspectorPopup()
        detachTargetEditor()

        rippleOverlay?.let { safeRemoveView(it) }
        rippleOverlay = null

        triggerButton?.let { safeRemoveView(it) }
        triggerButton = null
        triggerParams = null

        detachMiniDock()

        isShowing = false
    }

    private fun detachMiniDock() {
        miniDockView?.let { safeRemoveView(it) }
        miniDockView = null
        miniDockParams = null
    }

    fun reloadProfileAndRefresh() {
        currentProfile = repository.loadProfile()
        triggerButton?.idleAlpha = currentProfile.triggerAlpha
        applyTriggerButtonSize(currentProfile.triggerSizeDp)
        targetEditorOverlay?.invalidate()
    }

    /** 动态调整触发圆环大小（36~96 dp） */
    fun applyTriggerButtonSize(sizeDp: Int) {
        val lp = triggerParams ?: return
        val btn = triggerButton ?: return
        val sizePx = dpToPx(sizeDp.coerceIn(36, 96))
        if (lp.width == sizePx && lp.height == sizePx) {
            btn.invalidate()
            return
        }
        lp.width = sizePx
        lp.height = sizePx
        try {
            windowManager.updateViewLayout(btn, lp)
        } catch (_: Throwable) {
        }
        btn.invalidate()
    }

    private fun registerVolumeUpReceiver() {
        if (isVolumeReceiverRegistered) return
        try {
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(volumeBroadcastReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(volumeBroadcastReceiver, filter)
            }
            isVolumeReceiverRegistered = true
        } catch (_: Throwable) {
        }
    }

    private fun unregisterVolumeUpReceiver() {
        if (!isVolumeReceiverRegistered) return
        try {
            context.unregisterReceiver(volumeBroadcastReceiver)
        } catch (_: Throwable) {
        }
        isVolumeReceiverRegistered = false
    }

    @SuppressLint("SetTextI18n")
    private fun attachMiniDock() {
        if (miniDockView != null) return
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6121824"))
                cornerRadius = dpToPx(20).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#00E5FF"))
            }
        }

        val lockBtn = createCapsuleTextButton("✅ 完成锁定(或按音量+)")
        val sizeSmallBtn = createCapsuleTextButton("键缩小")
        val sizeBigBtn = createCapsuleTextButton("键放大")
        val addTapBtn = createCapsuleTextButton("+靶点")
        val addSwipeBtn = createCapsuleTextButton("+划屏")
        val recordBtn = createCapsuleTextButton("● 录制")
        val clearBtn = createCapsuleTextButton("清空")

        lockBtn.setOnClickListener {
            if (!isCombatLocked) {
                toggleCombatEditMode()
            }
        }

        sizeSmallBtn.setOnClickListener {
            val next = (currentProfile.triggerSizeDp - 6).coerceIn(36, 96)
            currentProfile.triggerSizeDp = next
            repository.saveProfile(currentProfile)
            applyTriggerButtonSize(next)
            Toast.makeText(context, "触发键: ${next}dp", Toast.LENGTH_SHORT).show()
        }
        sizeBigBtn.setOnClickListener {
            val next = (currentProfile.triggerSizeDp + 6).coerceIn(36, 96)
            currentProfile.triggerSizeDp = next
            repository.saveProfile(currentProfile)
            applyTriggerButtonSize(next)
            Toast.makeText(context, "触发键: ${next}dp", Toast.LENGTH_SHORT).show()
        }

        addTapBtn.setOnClickListener {
            val nextId = currentProfile.steps.size + 1
            val step = MacroStep(
                id = nextId,
                type = ActionType.TAP,
                x = 540f + (nextId * 35f) % 240f,
                y = 920f + (nextId * 35f) % 240f,
                holdDurationMs = 28L,
                intervalAfterMs = 32L
            )
            currentProfile.steps.add(step)
            repository.saveProfile(currentProfile)
            targetEditorOverlay?.invalidate()
        }

        addSwipeBtn.setOnClickListener {
            val nextId = currentProfile.steps.size + 1
            val step = MacroStep(
                id = nextId,
                type = ActionType.SWIPE,
                x = 540f,
                y = 1050f,
                endX = 540f,
                endY = 720f,
                holdDurationMs = 80L,
                intervalAfterMs = 45L
            )
            currentProfile.steps.add(step)
            repository.saveProfile(currentProfile)
            targetEditorOverlay?.invalidate()
        }

        recordBtn.setOnClickListener {
            val editor = targetEditorOverlay ?: return@setOnClickListener
            editor.isRecordingGesture = !editor.isRecordingGesture
            if (editor.isRecordingGesture) {
                recordBtn.text = "⏹ 停止录制"
                recordBtn.setTextColor(Color.parseColor("#FF5252"))
            } else {
                recordBtn.text = "● 录制"
                recordBtn.setTextColor(Color.parseColor("#00E5FF"))
                repository.saveProfile(currentProfile)
            }
        }

        clearBtn.setOnClickListener {
            currentProfile.steps.clear()
            repository.saveProfile(currentProfile)
            targetEditorOverlay?.invalidate()
            Toast.makeText(context, "已清空靶点，请点击 +靶点 或 ●录制", Toast.LENGTH_SHORT).show()
        }

        // 支持拖动编辑胶囊条位置
        var lastDockRawX = 0f
        var lastDockRawY = 0f
        container.setOnTouchListener { _, ev ->
            val lp = miniDockParams ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    lastDockRawX = ev.rawX
                    lastDockRawY = ev.rawY
                    false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - lastDockRawX).toInt()
                    val dy = (ev.rawY - lastDockRawY).toInt()
                    if (kotlin.math.abs(dx) > 4 || kotlin.math.abs(dy) > 4) {
                        lp.x = (lp.x + dx).coerceAtLeast(0)
                        lp.y = (lp.y + dy).coerceAtLeast(0)
                        windowManager.updateViewLayout(container, lp)
                        lastDockRawX = ev.rawX
                        lastDockRawY = ev.rawY
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }

        container.addView(lockBtn)
        container.addView(sizeSmallBtn)
        container.addView(sizeBigBtn)
        container.addView(addTapBtn)
        container.addView(addSwipeBtn)
        container.addView(recordBtn)
        container.addView(clearBtn)

        val dockLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(24)
            y = dpToPx(48)
            applyCutoutShortEdges(this)
        }

        windowManager.addView(container, dockLp)
        miniDockView = container
        miniDockParams = dockLp
    }

    private fun attachTargetEditor() {
        if (targetEditorOverlay != null) return
        val editor = TargetEditorOverlayView(
            context = context,
            steps = currentProfile.steps,
            onStepSelected = { step ->
                showStepInspectorPopup(step)
            },
            onStepsChanged = {
                repository.saveProfile(currentProfile)
            }
        )
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            applyCutoutShortEdges(this)
        }
        windowManager.addView(editor, lp)
        targetEditorOverlay = editor

        // 确保触发按钮与顶部胶囊条处于最上层
        triggerButton?.let {
            safeRemoveView(it)
            windowManager.addView(it, triggerParams)
        }
        miniDockView?.let {
            safeRemoveView(it)
            windowManager.addView(it, miniDockParams)
        }
    }

    private fun detachTargetEditor() {
        targetEditorOverlay?.let { safeRemoveView(it) }
        targetEditorOverlay = null
    }

    @SuppressLint("SetTextI18n")
    private fun showStepInspectorPopup(step: MacroStep) {
        dismissStepInspectorPopup()

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F0141B29"))
                cornerRadius = dpToPx(16).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#00E5FF"))
            }
        }

        val title = TextView(context).apply {
            text = "靶点 #${step.id} (${if (step.type == ActionType.TAP) "极速点击" else "轨迹滑动"})"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 15f
        }
        card.addView(title)

        val holdLabel = TextView(context).apply {
            text = "按压时长: ${step.holdDurationMs} ms (游戏推荐 ≥26ms)"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, dpToPx(8), 0, dpToPx(2))
        }
        val holdSeek = SeekBar(context).apply {
            max = 280
            progress = (step.holdDurationMs - 20L).toInt().coerceIn(0, 280)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    step.holdDurationMs = (p + 20).toLong()
                    holdLabel.text = "按压时长: ${step.holdDurationMs} ms (游戏推荐 ≥26ms)"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    repository.saveProfile(currentProfile)
                }
            })
        }
        card.addView(holdLabel)
        card.addView(holdSeek)

        val gapLabel = TextView(context).apply {
            val cps = 1000f / (step.holdDurationMs + step.intervalAfterMs).coerceAtLeast(1L)
            text = "步间间隔: ${step.intervalAfterMs} ms (约 ${String.format("%.1f", cps)} 次/秒)"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, dpToPx(8), 0, dpToPx(2))
        }
        val gapSeek = SeekBar(context).apply {
            max = 488
            progress = (step.intervalAfterMs - 12L).toInt().coerceIn(0, 488)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    step.intervalAfterMs = (p + 12).toLong()
                    val cps = 1000f / (step.holdDurationMs + step.intervalAfterMs).coerceAtLeast(1L)
                    gapLabel.text = "步间间隔: ${step.intervalAfterMs} ms (约 ${String.format("%.1f", cps)} 次/秒)"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    repository.saveProfile(currentProfile)
                }
            })
        }
        card.addView(gapLabel)
        card.addView(gapSeek)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dpToPx(10), 0, 0)
        }

        val deleteBtn = Button(context).apply {
            text = "删除节点"
            setTextColor(Color.parseColor("#FF5252"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                currentProfile.steps.remove(step)
                currentProfile.steps.forEachIndexed { index, s ->
                    currentProfile.steps[index] = s.copy(id = index + 1)
                }
                repository.saveProfile(currentProfile)
                targetEditorOverlay?.invalidate()
                dismissStepInspectorPopup()
            }
        }

        val closeBtn = Button(context).apply {
            text = "完成"
            setTextColor(Color.parseColor("#00E5FF"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                repository.saveProfile(currentProfile)
                dismissStepInspectorPopup()
            }
        }

        btnRow.addView(deleteBtn)
        btnRow.addView(closeBtn)
        card.addView(btnRow)

        val popupLp = WindowManager.LayoutParams(
            dpToPx(290),
            WindowManager.LayoutParams.WRAP_CONTENT,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        windowManager.addView(card, popupLp)
        stepConfigPopupView = card
    }

    private fun dismissStepInspectorPopup() {
        stepConfigPopupView?.let { safeRemoveView(it) }
        stepConfigPopupView = null
    }

    private fun createCapsuleTextButton(label: String): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 13f
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
        }
    }

    private fun getOverlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun applyCutoutShortEdges(lp: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun safeRemoveView(view: View) {
        try {
            windowManager.removeView(view)
        } catch (_: Throwable) {
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
