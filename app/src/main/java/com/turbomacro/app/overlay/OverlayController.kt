package com.turbomacro.app.overlay

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
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
 * 小米 Game Turbo 风格悬浮窗总控中心
 *
 * 管控 4 个独立轻量图层：
 * 1. RippleFeedbackOverlayView：全屏完全穿透层（0 触控拦截），仅绘制青蓝色点击水波纹
 * 2. TargetEditorOverlayView：仅在「编辑/录制」模式下挂载，锁定后彻底移除，绝不挡视野
 * 3. FloatingTriggerButtonView：按住执行、松开即停的幽灵微透触发键
 * 4. 迷你胶囊工具条：一键切换「战斗锁定 / 编辑靶点 / 手势录制 / 隐藏」
 */
class OverlayController(
    private val context: Context,
    private val serviceProvider: () -> AccessibilityService?
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
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

    private val macroEngine = HoldToRunMacroEngine(
        touchInjector = touchInjector,
        hapticEngine = hapticEngine,
        onVisualRipple = { x, y, endX, endY ->
            rippleOverlay?.spawnRipple(x, y, endX, endY)
        },
        onRunStateChanged = { _ -> }
    )

    fun isOverlayShowing(): Boolean = isShowing

    fun showOverlay() {
        if (isShowing) return
        currentProfile = repository.loadProfile()

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
        )
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
        }

        val btn = FloatingTriggerButtonView(
            context = context,
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

        // 3. 挂载顶部侧边迷你胶囊控制条（支持一键锁定/解锁编辑/添加靶点/手势录制）
        attachMiniDock()

        isShowing = true
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
            setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC121824"))
                cornerRadius = dpToPx(20).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#6600E5FF"))
            }
            alpha = 0.72f
        }

        val modeBtn = createCapsuleTextButton(if (isCombatLocked) "🔒 战斗态" else "✏️ 编辑态")
        val addTapBtn = createCapsuleTextButton("+靶点").apply {
            visibility = if (isCombatLocked) View.GONE else View.VISIBLE
        }
        val addSwipeBtn = createCapsuleTextButton("+划屏").apply {
            visibility = if (isCombatLocked) View.GONE else View.VISIBLE
        }
        val recordBtn = createCapsuleTextButton("● 录制").apply {
            visibility = if (isCombatLocked) View.GONE else View.VISIBLE
        }
        val clearBtn = createCapsuleTextButton("清空").apply {
            visibility = if (isCombatLocked) View.GONE else View.VISIBLE
        }

        modeBtn.setOnClickListener {
            macroEngine.forceStop()
            isCombatLocked = !isCombatLocked
            triggerButton?.isLockedForCombat = isCombatLocked

            if (isCombatLocked) {
                modeBtn.text = "🔒 战斗态"
                addTapBtn.visibility = View.GONE
                addSwipeBtn.visibility = View.GONE
                recordBtn.visibility = View.GONE
                clearBtn.visibility = View.GONE
                container.alpha = 0.55f
                dismissStepInspectorPopup()
                detachTargetEditor()
                repository.saveProfile(currentProfile)
            } else {
                modeBtn.text = "✅ 完成锁定"
                addTapBtn.visibility = View.VISIBLE
                addSwipeBtn.visibility = View.VISIBLE
                recordBtn.visibility = View.VISIBLE
                clearBtn.visibility = View.VISIBLE
                container.alpha = 0.95f
                attachTargetEditor()
            }
        }

        addTapBtn.setOnClickListener {
            val nextId = currentProfile.steps.size + 1
            val step = MacroStep(
                id = nextId,
                type = ActionType.TAP,
                x = 540f + (nextId * 35f) % 240f,
                y = 920f + (nextId * 35f) % 240f,
                holdDurationMs = 14L,
                intervalAfterMs = 28L
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

        // 支持长按或拖动胶囊条本身换位置
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

        container.addView(modeBtn)
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
        )
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

    /**
     * 轻点某个准星靶点时，弹出紧凑的毫秒级参数微调悬浮卡片
     */
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

        // 1. 按压时长调节 (8ms ~ 300ms)
        val holdLabel = TextView(context).apply {
            text = "按压时长: ${step.holdDurationMs} ms"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, dpToPx(8), 0, dpToPx(2))
        }
        val holdSeek = SeekBar(context).apply {
            max = 292
            progress = (step.holdDurationMs - 8L).toInt().coerceIn(0, 292)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    step.holdDurationMs = (p + 8).toLong()
                    holdLabel.text = "按压时长: ${step.holdDurationMs} ms"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    repository.saveProfile(currentProfile)
                }
            })
        }
        card.addView(holdLabel)
        card.addView(holdSeek)

        // 2. 连点间隔调节 (6ms ~ 500ms)
        val gapLabel = TextView(context).apply {
            val cps = 1000f / (step.holdDurationMs + step.intervalAfterMs).coerceAtLeast(1L)
            text = "步间间隔: ${step.intervalAfterMs} ms (约 ${String.format("%.1f", cps)} 次/秒)"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, dpToPx(8), 0, dpToPx(2))
        }
        val gapSeek = SeekBar(context).apply {
            max = 494
            progress = (step.intervalAfterMs - 6L).toInt().coerceIn(0, 494)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    step.intervalAfterMs = (p + 6).toLong()
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

        // 3. 底部按钮行（删除此靶点 / 关闭弹窗）
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
                // 重新编号
                currentProfile.steps.forEachIndexed { index, s ->
                    val updated = s.copy(id = index + 1)
                    currentProfile.steps[index] = updated
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
