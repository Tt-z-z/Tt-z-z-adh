package com.turbomacro.app.overlay

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
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
 * 小米 Game Turbo 风格悬浮窗总控中心（支持「音量+」呼出编辑与战斗态全隐藏）
 *
 * - 战斗锁定态 (`isCombatLocked = true`)：
 *   顶部胶囊工具条与靶点图层 **100% 隐藏移除**！屏幕上仅保留半透明微透触发键与完全穿透的水波纹层，真正零遮挡。
 *   用户只需按一下手机侧边的 **「音量+」键**，即可随时呼出编辑面板调整准星或录制手势！
 */
class OverlayController(
    private val context: Context,
    private val serviceProvider: () -> AccessibilityService?
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val repository = ProfileRepository(context)
    val touchInjector = TouchInjector()
    private val hapticEngine = HapticEngine(context)

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

    private val macroEngine: HoldToRunMacroEngine = HoldToRunMacroEngine(
        touchInjector = touchInjector,
        hapticEngine = hapticEngine,
        onVisualRipple = { x, y, endX, endY ->
            rippleOverlay?.spawnRipple(x, y, endX, endY)
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
                    "提示：请确认已在系统设置开启「TurboMacro 无障碍服务」或「授权 Shizuku」以使点击在游戏内生效",
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

        // 1. 挂载全屏穿透水波纹层 (FLAG_NOT_TOUCHABLE 保证 100% 不拦截游戏点击)
        val ripple = RippleFeedbackOverlayView(context)
        val rippleLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            applyCutoutShortEdges(this)
        }
        windowManager.addView(ripple, rippleLp)
        rippleOverlay = ripple

        // 2. 挂载按住触发键
        val sizePx = dpToPx(54)
        val triggerLp = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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
                touchInjector.getActiveBackend() == TouchInjector.ActiveBackend.SHIZUKU_INPUT_MANAGER
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
            }
        ).apply {
            idleAlpha = currentProfile.triggerAlpha
            isLockedForCombat = isCombatLocked
        }

        windowManager.addView(btn, triggerLp)
        triggerButton = btn
        triggerParams = triggerLp

        // 3. 准备编辑态胶囊条（战斗态下默认 GONE 完全隐身，按【音量+】键呼出）
        attachMiniDock()
        miniDockView?.visibility = View.GONE

        isShowing = true
        Toast.makeText(
            context,
            "已进入战斗隐身态！按一下手机【音量+】键可随时呼出/隐藏靶点编辑模式",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * 供「音量+」物理按键或界面按钮调用：一键切换「战斗全隐身态」与「靶点编辑态」
     */
    fun toggleCombatEditMode() {
        if (!isShowing) return
        mainHandler.post {
            macroEngine.forceStop()
            isCombatLocked = !isCombatLocked
            triggerButton?.isLockedForCombat = isCombatLocked
            triggerButton?.refreshVisualState()

            if (isCombatLocked) {
                // 切回战斗态：彻底隐藏顶部工具条、弹窗与靶点编辑层
                miniDockView?.visibility = View.GONE
                dismissStepInspectorPopup()
                detachTargetEditor()
                repository.saveProfile(currentProfile)
                hapticEngine.performTriggerUp()
                Toast.makeText(context, "🔒 已锁定并隐藏编辑栏（按【音量+】可再次编辑）", Toast.LENGTH_SHORT).show()
            } else {
                // 进入编辑态：显示顶部工具条与全屏准星靶点
                miniDockView?.visibility = View.VISIBLE
                attachTargetEditor()
                hapticEngine.performTriggerDown()
            }
        }
    }

    fun hideOverlay() {
        if (!isShowing) return
        macroEngine.forceStop()
        dismissStepInspectorPopup()
        detachTargetEditor()

        rippleOverlay?.let { safeRemoveView(it) }
        rippleOverlay = null

        triggerButton?.let { safeRemoveView(it) }
        triggerButton = null

        miniDockView?.let { safeRemoveView(it) }
        miniDockView = null

        isShowing = false
    }

    fun reloadProfileAndRefresh() {
        currentProfile = repository.loadProfile()
        triggerButton?.idleAlpha = currentProfile.triggerAlpha
        targetEditorOverlay?.invalidate()
    }

    @SuppressLint("SetTextI18n")
    private fun attachMiniDock() {
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
        val addTapBtn = createCapsuleTextButton("+靶点")
        val addSwipeBtn = createCapsuleTextButton("+划屏")
        val recordBtn = createCapsuleTextButton("● 录制")
        val clearBtn = createCapsuleTextButton("清空")

        lockBtn.setOnClickListener {
            if (!isCombatLocked) {
                toggleCombatEditMode()
            }
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
        container.addView(addTapBtn)
        container.addView(addSwipeBtn)
        container.addView(recordBtn)
        container.addView(clearBtn)

        val dockLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
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

        // 1. 按压时长调节 (25ms ~ 300ms，游戏帧采样至少需 25ms)
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

        // 2. 连点间隔调节 (12ms ~ 500ms)
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
