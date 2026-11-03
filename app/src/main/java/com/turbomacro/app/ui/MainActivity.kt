package com.turbomacro.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.turbomacro.app.overlay.OverlayController
import com.turbomacro.app.service.MacroAccessibilityService
import com.turbomacro.app.storage.ProfileRepository
import rikka.shizuku.Shizuku

/**
 * TurboMacro 主控台（深色电竞卡片风格，对标小米 Game Turbo 控制台）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var repository: ProfileRepository
    private var standaloneOverlayController: OverlayController? = null

    private lateinit var statusChannelText: TextView
    private lateinit var statusAccessibilityText: TextView
    private lateinit var toggleOverlayButton: Button

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { _, _ ->
            refreshStatusUI()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ProfileRepository(this)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        buildContentView()
    }

    override fun onResume() {
        super.onResume()
        refreshStatusUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    }

    private fun getActiveOverlayController(): OverlayController {
        val ctrl = OverlayController.getInstance(applicationContext)
        ctrl.serviceProvider = { MacroAccessibilityService.instance }
        return ctrl
    }

    @SuppressLint("SetTextI18n", "UseSwitchCompatOrMaterialCode")
    private fun buildContentView() {
        val profile = repository.loadProfile()

        val rootScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0F17"))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(36))
        }

        // 顶部标题区
        val title = TextView(this).apply {
            text = "TURBO MACRO · 极速肩键宏"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 22f
            paint.isFakeBoldText = true
        }
        val subtitle = TextView(this).apply {
            text = "按住即发 · 松手立停 · 小米原生水波纹与线性马达反馈"
            setTextColor(Color.parseColor("#8A99AD"))
            textSize = 13f
            setPadding(0, dp(4), 0, dp(18))
        }
        container.addView(title)
        container.addView(subtitle)

        // 卡片 1：工作通道与系统安全体检
        val channelCard = createStyledCard()
        val channelHeader = TextView(this).apply {
            text = "⚡ 底层触控引擎（零系统损伤保护）"
            setTextColor(Color.WHITE)
            textSize = 16f
            paint.isFakeBoldText = true
        }
        statusChannelText = TextView(this).apply {
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 13f
            setPadding(0, dp(8), 0, dp(4))
        }
        statusAccessibilityText = TextView(this).apply {
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 13f
            setPadding(0, 0, 0, dp(10))
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val shizukuBtn = createActionButton("授权 Shizuku (推荐原生级)") {
            requestShizukuPermissionSafe()
        }
        val accBtn = createActionButton("开启无障碍服务") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnRow.addView(shizukuBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8)
        })
        btnRow.addView(accBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        channelCard.addView(channelHeader)
        channelCard.addView(statusChannelText)
        channelCard.addView(statusAccessibilityText)
        channelCard.addView(btnRow)
        container.addView(channelCard, cardLayoutParams())

        // 卡片 2：一键启动悬浮触发器
        val launchCard = createStyledCard()
        toggleOverlayButton = Button(this).apply {
            text = "🚀 启动极速悬浮触发键"
            textSize = 16f
            setTextColor(Color.parseColor("#0B0F17"))
            paint.isFakeBoldText = true
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#00E5FF"))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    return@setOnClickListener
                }
                val ctrl = getActiveOverlayController()
                if (ctrl.isOverlayShowing()) {
                    ctrl.hideOverlay()
                } else {
                    ctrl.showOverlay()
                }
                refreshStatusUI()
            }
        }
        val guideText = TextView(this).apply {
            text = "三角洲用法（按住悬浮钮连点开火）：\n" +
                "1. 必须开 Shizuku +「USB 调试（安全设置）」：按住悬浮钮自动单点开火，另一只手可同时拖屏瞄准。\n" +
                "2. 编辑靶点：按【音量+】或连点悬浮钮 3 下；把靶点拖到游戏开火键位置后锁定。\n" +
                "3. 下方可调悬浮钮大小；仅开无障碍时系统会打断拖屏，打 FPS 请用 Shizuku。"
            setTextColor(Color.parseColor("#90A4AE"))
            textSize = 12.5f
            setPadding(0, dp(12), 0, 0)
        }
        launchCard.addView(toggleOverlayButton)
        launchCard.addView(guideText)
        container.addView(launchCard, cardLayoutParams())

        // 卡片 3：手感与视觉反馈定制
        val feelCard = createStyledCard()
        val feelTitle = TextView(this).apply {
            text = "🎮 手感、反馈与防遮挡设置"
            setTextColor(Color.WHITE)
            textSize = 16f
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, dp(8))
        }
        feelCard.addView(feelTitle)

        // 按住循环开关
        val loopSwitch = Switch(this).apply {
            text = "按住期间无限循环连发（关闭则按住仅执行一轮连招）"
            setTextColor(Color.parseColor("#E0E6ED"))
            isChecked = profile.loopWhileHeld
            setOnCheckedChangeListener { _, checked ->
                val p = repository.loadProfile()
                p.loopWhileHeld = checked
                repository.saveProfile(p)
                getActiveOverlayController().reloadProfileAndRefresh()
            }
        }
        feelCard.addView(loopSwitch)

        // 线性马达微振开关
        val hapticSwitch = Switch(this).apply {
            text = "X轴线性马达清脆微振（带 25Hz 硬件护航限流，久按不发麻）"
            setTextColor(Color.parseColor("#E0E6ED"))
            isChecked = profile.enableHaptics
            setPadding(0, dp(8), 0, 0)
            setOnCheckedChangeListener { _, checked ->
                val p = repository.loadProfile()
                p.enableHaptics = checked
                repository.saveProfile(p)
                getActiveOverlayController().reloadProfileAndRefresh()
            }
        }
        feelCard.addView(hapticSwitch)

        // 小米风格水波纹开关
        val rippleSwitch = Switch(this).apply {
            text = "小米 Game Turbo 电光青点击水波纹（全穿透零遮挡）"
            setTextColor(Color.parseColor("#E0E6ED"))
            isChecked = profile.enableRipple
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, checked ->
                val p = repository.loadProfile()
                p.enableRipple = checked
                repository.saveProfile(p)
                getActiveOverlayController().reloadProfileAndRefresh()
            }
        }
        feelCard.addView(rippleSwitch)

        // 触发键大小调节
        val sizeLabel = TextView(this).apply {
            text = "触发键大小: ${profile.triggerSizeDp} dp（推荐 44~64，越小越不挡视野）"
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 13f
            setPadding(0, dp(6), 0, dp(4))
        }
        val sizeSeek = SeekBar(this).apply {
            max = 60
            progress = (profile.triggerSizeDp - 36).coerceIn(0, 60)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val sizeDp = p + 36
                    sizeLabel.text = "触发键大小: $sizeDp dp（推荐 44~64，越小越不挡视野）"
                    val cfg = repository.loadProfile()
                    cfg.triggerSizeDp = sizeDp
                    repository.saveProfile(cfg)
                    getActiveOverlayController().reloadProfileAndRefresh()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        feelCard.addView(sizeLabel)
        feelCard.addView(sizeSeek)

        // 触发键闲置透明度调节
        val alphaLabel = TextView(this).apply {
            text = "触发键闲置不透明度: ${(profile.triggerAlpha * 100).toInt()}%（越低越隐形）"
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 13f
            setPadding(0, dp(6), 0, dp(4))
        }
        val alphaSeek = SeekBar(this).apply {
            max = 85
            progress = ((profile.triggerAlpha - 0.15f) * 100).toInt().coerceIn(0, 85)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val alphaVal = (p + 15) / 100f
                    alphaLabel.text = "触发键闲置不透明度: ${p + 15}%（越低越隐形）"
                    val cfg = repository.loadProfile()
                    cfg.triggerAlpha = alphaVal
                    repository.saveProfile(cfg)
                    getActiveOverlayController().reloadProfileAndRefresh()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        feelCard.addView(alphaLabel)
        feelCard.addView(alphaSeek)

        container.addView(feelCard, cardLayoutParams())

        rootScroll.addView(container)
        setContentView(rootScroll)
    }

    @SuppressLint("SetTextI18n")
    private fun refreshStatusUI() {
        val ctrl = getActiveOverlayController()
        val shizukuReady = ctrl.touchInjector.isShizukuReady()
        val accReady = MacroAccessibilityService.instance != null

        statusChannelText.text = if (shizukuReady) {
            "● 当前触控通道：Shizuku 原生 InputManager 引擎（已激活 · 支持多指并发零冲突）"
        } else {
            "○ 当前触控通道：标准无障碍手势通道（激活 Shizuku 可解锁多指按住并发）"
        }

        statusAccessibilityText.text = if (accReady) {
            "● 无障碍服务：已就绪"
        } else {
            "○ 无障碍服务：未开启（若不使用 Shizuku，请点击下方开启无障碍）"
        }

        if (ctrl.isOverlayShowing()) {
            toggleOverlayButton.text = "⏹ 关闭悬浮触发键"
            toggleOverlayButton.background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF5252"))
                cornerRadius = dp(12).toFloat()
            }
        } else {
            toggleOverlayButton.text = "🚀 启动极速悬浮触发键"
            toggleOverlayButton.background = GradientDrawable().apply {
                setColor(Color.parseColor("#00E5FF"))
                cornerRadius = dp(12).toFloat()
            }
        }
    }

    private fun requestShizukuPermissionSafe() {
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(
                    this,
                    "未检测到 Shizuku 运行，您可以先直接使用「无障碍服务」模式，或启动 Shizuku 后重试",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Shizuku 原生级通道已授权！", Toast.LENGTH_SHORT).show()
                refreshStatusUI()
            } else {
                Shizuku.requestPermission(1001)
            }
        } catch (e: Throwable) {
            Toast.makeText(this, "Shizuku 状态检查失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createStyledCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#151C28"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#263248"))
            }
        }
    }

    private fun createActionButton(title: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = title
            textSize = 12.5f
            setTextColor(Color.parseColor("#00E5FF"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1D2738"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#00E5FF"))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun cardLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(16)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
