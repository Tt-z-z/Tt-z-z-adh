# TurboMacro 多指共存任务交接

更新时间：2026-11-04 18:00（UTC+8）。本文用于换电脑/换会话后继续工作。

## 1. 用户目标与验收标准

用户需要类似小米/HyperOS Game Turbo 风格的 Android 悬浮宏：按住按钮执行规律单点，松开停止；同时其他手指可持续移动、瞄准，不能被宏打断。最终应由手机独立执行，不依赖电脑脚本（权限通道仍需 Shizuku 等实际可用机制）。

优先级：触屏可恢复与多指连续性 > 按住/松手正确性 > 悬浮窗生命周期 > 外观。用户曾因旧版触屏抓取后不转发而被迫重启，不能再次用无超时的试验版本接管手机。不要声称绝对安全、零延迟或不会封号。游戏是否允许宏由其规则决定，技术可行不等于账号安全。

## 2. 仓库与分支

- 仓库：https://github.com/Tt-z-z/Tt-z-z-adh
- 当前工作分支：`fix/touchmux-validation`，不是 main/master。
- 最新功能提交：`08b3385`，修复诊断构建缺失配置页与 Shizuku provider。
- 前一提交：`89e4e72`，宏触点取消、悬浮指针跟踪、限时客户端、主机测试脚本。
- `d2573b8`：诊断页按住区及状态 socket。
- 当前手机版本：`1.6.6-diagnostic`，versionCode 22。
- 诊断包名：`com.turbomacro.app.diagnostic`，正式包名：`com.turbomacro.app`。只操作前者，避免误清正式版。
- 最近成功构建：https://github.com/Tt-z-z/Tt-z-z-adh/actions/runs/41810356730
- 构建产物名：`TurboMacro-Debug-APK`；文件：`TurboMacro-v1.6.6-diagnostic.apk`。

## 3. 设备及权限事实

实测设备：Redmi K70 Ultra / 2407FRK8EC / rothko，Dimensity 9300+，HyperOS 3（OS3.0.308.0.WNNCNXM.C11），Android 16 / SDK 36，SELinux Enforcing。

- 物理分辨率：1220 x 2712。
- 触屏：`/dev/input/event4`，`goodix_ts`，MT Protocol B，物理 slot 0..9。
- tracking ID 范围 0..65535。
- **该触屏不支持 ABS_MT_PRESSURE**；支持 TOUCH_MAJOR、WIDTH_MAJOR/MINOR 等。
- 目前通过 USB ADB shell 完成诊断；不要假设已 root。
- 2026-11-04 最后检查：Shizuku server 在运行；诊断包 `moe.shizuku.manager.permission.API_V23` 已授权；`SYSTEM_ALERT_WINDOW: allow`。
- 换电脑需重新允许该电脑 USB 调试授权。Shizuku/ADB 可用性需重新核实，不要直接照搬旧 PID、安装路径或设备序列号。

## 4. 实现原理与关键修复

普通 `input tap`、无障碍 gesture 或单独虚拟触屏可能让 Android 切换输入流并取消真人触点，无法据此保证并发拖动。

`app/src/main/cpp/touchmux.c`：使用 EVIOCGRAB 读取真屏，把真人事件转发到 `/dev/uinput`，宏使用同一虚拟触屏的额外 slot。抓取是整个设备，不是局部区域。

已实现的关键修复：
1. 虚拟轴能力按物理设备克隆。之前虚构 PRESSURE 轴导致真人 pressure 默认为 0，Android 忽略触摸，是曾经接管期间不能操作的关键原因。
2. 分离真人当前 slot 与输出 slot，宏事件后真人省略 SLOT 时仍恢复正确槽位。
3. 真人 tracking ID 重映射至 0..65534，宏独占 65535。
4. SYN_DROPPED、读写错误等 fail-closed，释放抓取并销毁虚拟设备。
5. `--diagnostic-seconds 1..15` 是不可被 PING 延长的内部硬期限；外层 Android `timeout -k 1s 17s` 提供额外强制退出。
6. 新增 `CANCEL_TAP`，回复 `OK CANCEL_TAP`。只取消宏，不调用 RELEASE、不释放真人合并。如果真人帧尚未结束，宏 UP 延后至下一 SYN 边界，避免在半帧中插入 SYN_REPORT。未发送的宏 DOWN 直接取消；重复取消幂等。

`TouchMuxClient.kt`：
- 从 `applicationInfo.nativeLibraryDir` 执行 helper；不能复制到 app 私有 filesDir 后让 Shizuku shell 执行（shell 无法穿越私有目录，曾报 start:null）。
- 当前验证版启动参数包括外层 timeout 和内部 15 秒期限；不应自动无限重启接管。
- `cancelTapAsync()` 后台串行取消，pendingCancels 阻止新 TAP 越过尚未完成的取消；tap 获取锁后再次检查 allowed 回调。
- 销毁远端进程后再关闭阻塞读流，避免 release/QUIT 同步写造成死锁。

`TouchInjector.kt` / `HoldToRunMacroEngine.kt`：
- 按下路径不再同步 ensureMuxReady；初始化由 OverlayController 后台处理。
- runGeneration 防止旧协程 finally 清除新一轮状态。
- 松手/forceStop 请求异步 CANCEL_TAP；新点击等待 pending cancel。
- 宏不允许退回无障碍注入；注入失败停止序列。

`FloatingTriggerButtonView.kt`：
- 跟踪最初触发的 pointer ID；不能让其他手指接替。
- pointer up、移出按钮、ACTION_CANCEL、detach、切换战斗锁定状态时停止。
- **仍存在快速三击打开编辑的手势**，与用户快速按松场景可能冲突，需检查。

`OverlayController.kt`：已有初始化/销毁代次控制，窗口采用 NOT_FOCUSABLE、NOT_TOUCH_MODAL、SPLIT_TOUCH；这些标志不等于已经验证跨窗口多指可靠。

## 5. 诊断页与已有实测

文件：`app/src/debug/java/com/turbomacro/app/ui/TouchDiagnosticActivity.kt`。

独立诊断页本身不接管、不注入。统计 taps、moves、cancels、anomalies、holdStarts/holdReleases、tapsAtRelease、downsAfterRelease。独立按住区跟踪 owner，松手/移出/失焦/暂停时释放。

状态接口：Android local abstract socket `com.turbomacro.app.diagnostic.state`，每连接输出一行 JSON 后关闭。主线程每 50ms 刷新快照，包含 uptimeMs、held、holdGeneration、targetX/Y 等。

```powershell
adb forward tcp:18764 localabstract:com.turbomacro.app.diagnostic.state
```

已完成的实测（不是最终成品验收）：
- 修复可选轴后，抓取期间真人触摸可正常转发，用户确认。
- 固定 20 次低频宏点击 + 真人持续拖动：收到 20 次点击，1346 MOVE，0 CANCEL，0 anomalies，用户确认顺畅。
- 按住状态由电脑脚本读取、控制宏：发送 14 次，收到 14 次，789 MOVE，0 CANCEL，0 anomalies，downsAfterRelease=0。用户确实快速反复按松且另一指持续拖动顺畅。
- 上轮有两次在松手前已 DOWN 的宏，松手后 UP 才完成点击统计。因此不能称零延迟；这促成 CANCEL_TAP 新增。
- 新 CANCEL_TAP 的 Linux host 回归测试（ASan/UBSan）已通过；**尚未在手机验证新取消链路或正式悬浮触发链路**。
- 曾一次测试脚本用精确 `OK MERGING` 匹配导致没有实际发点击；已改为 `startswith('OK MERGING ')`，不能把该轮算连点成功。

主机脚本：`tests/hold_diagnostic.py`。注意它仍使用旧设备临时 helper `/data/local/tmp/touchmux-diag-742b03f`，以及旧诊断按住 socket 方案；**不是新悬浮按钮测试，也未调用新 CANCEL_TAP**。运行前检查手机、helper、状态接口及坐标。它只适用于 Windows（winsound），ADB 路径默认为 TEMP 下的 turbo-adb-tools/platform-tools/adb.exe。

## 6. 现在手机停在哪里（交接时状态）

- 已装 1.6.6-diagnostic，显式配置主界面 MainActivity 已打开。
- 诊断版授权已核实，未启用无障碍。
- 最后一次操作仅 force-stop 诊断包、写入测试配置、重新打开 MainActivity。
- **没有开启悬浮按钮，也没有在本轮启动触屏接管。**
- 用户被告知先不要点“启动悬浮触发键”；下一轮要同步准备诊断页，因为期限从 helper 进程启动开始计时。
- 如手机状态变化或关屏/断线，下一电脑先重新检查，不能沿用上述状态假设。

已写入诊断包 `shared_prefs/turbo_macro_prefs.xml` 的 active_profile：

```json
{
  "name": "限时多指验证",
  "loopWhileHeld": true,
  "enableHaptics": false,
  "enableRipple": false,
  "triggerAlpha": 0.65,
  "triggerSizeDp": 54,
  "triggerX": 160,
  "triggerY": 760,
  "steps": [{
    "id": 1, "type": "TAP",
    "x": 939, "y": 1227, "endX": 939, "endY": 1227,
    "holdDurationMs": 20, "intervalAfterMs": 500, "jitterRadiusPx": 0
  }]
}
```

约每秒 2 次，固定目标为之前竖屏诊断页右侧点击区。重新读取实际 targetX/Y 后才能使用，旋转/布局变化会改变坐标。默认配置是约 30ms 间隔且带随机偏移，**不要不检查配置就启用默认宏**。

## 7. 换电脑后接续步骤

### A. 获取代码与工具

```powershell
git clone --branch fix/touchmux-validation https://github.com/Tt-z-z/Tt-z-z-adh.git
cd Tt-z-z-adh
git status
git log -5 --oneline
```

安装 Google 官方 Android Platform-Tools，连接数据线并由用户确认新电脑 USB 调试。GitHub 操作用 gh 登录同一有权限账号。旧电脑仓库在 `C:\Users\PC\Desktop\web\home\Tt-z-z-adh`，只有 Java 17 已确认；未配置可用本地 Android SDK/Gradle，主要通过 GitHub Actions 构建。

```powershell
adb devices -l
adb shell appops get com.turbomacro.app.diagnostic SYSTEM_ALERT_WINDOW
adb shell pidof shizuku_server
adb shell dumpsys package com.turbomacro.app.diagnostic
```

检查 versionName、Shizuku runtime permission、provider。不要粘贴包含其他应用等无关设备信息的大段输出到公开文档。

### B. 不要先重装

手机已有 1.6.6，换电脑本身不需要重装。CI 每次产生不同 debug 签名，当前覆盖安装会 UPDATE_INCOMPATIBLE。之前每次经用户同意卸载诊断包后装新版本，导致权限和配置重置。**后续应固定调试签名策略，避免重复卸载；不要提交私有正式签名密钥。**

确需下载：

```powershell
gh run download 41810356730 --name TurboMacro-Debug-APK --dir artifacts/1.6.6
```

只有明确同意清除诊断数据后才卸载诊断包；不卸载正式版。INSTALL_FAILED_USER_RESTRICTED 表示手机安装确认/USB 安装限制，需要用户处理，不绕过。

### C. 下一项真实工作：限时悬浮按钮验证

1. 再检查 MainActivity/OverlayController 的初始化顺序、配置加载与 Shizuku 连接；新版从 nativeLibraryDir 经 Shizuku 启动 helper 仍需实测。
2. 准备好在同一个限时窗口内打开诊断页的同步方法。当前配置页启动按钮会开始接管，而诊断页是另一个 Activity；旧电脑单次 adb 常耗时 5~12 秒，不能先启用再慢慢操作导致期限耗尽。不要改掉硬期限来掩盖同步问题。
3. 显式打开无注入诊断页的方法：

```powershell
adb shell am start -n com.turbomacro.app.diagnostic/com.turbomacro.app.ui.TouchDiagnosticActivity
```

配置页：

```powershell
adb shell am start -n com.turbomacro.app.diagnostic/com.turbomacro.app.ui.MainActivity
```

4. 用户准备好、松开所有手指后，才启动一次 15 秒测试；确认通道 READY/MERGING 后再提示用户按悬浮按钮，另一手指在诊断区持续拖动。
5. 观察松手、移出、快速反复按松；不要把诊断页内部 hold 统计当成悬浮按钮 held 状态，两者不是同一个窗口。需要额外悬浮触发日志/统计来关联停止时刻。
6. 验证点击数、拖动连续性、ACTION_CANCEL、异常、松手后是否发起新 DOWN，以及 CANCEL_TAP 是否影响真人流。
7. 期满检查 helper 退出与虚拟设备清理；旧设备读取 `/proc/bus/input/devices` 曾 Permission denied，可用有权限的 dumpsys input 等证据，不能声称没读到就是清理成功。
8. 当前没有长期独立运行版。跨窗口、横屏、后台、屏幕锁定、Shizuku 掉线、旋转、退出、长时间运行均需补验。

## 8. 构建与测试

工作流：`.github/workflows/build-apk.yml`。该分支 push 不自动构建（只 main/master 自动）；须手动 dispatch。

```powershell
git push origin fix/touchmux-validation
gh workflow run build-apk.yml --ref fix/touchmux-validation
gh run list --branch fix/touchmux-validation --limit 3
```

工作流先运行 Linux C 回归（ASan/UBSan），然后 JDK 17、Android 34、NDK 26.1.10909125、CMake 3.22.1、Gradle 8.7 构建 assembleDebug。编译成功不代表手机端功能通过。

用户要求每个修复安装版改版本号：同时改 app/build.gradle.kts 的 versionCode/versionName 和工作流 APK 文件名。

2026-11-04 GitHub 多次返回 `fatal error in commit_refs_rules` 或 Internal Server Error，之后重试恢复。不要通过强推/关闭保护绕过，也不要声称未成功推送的提交已构建。

## 9. 已知待改进与诚实边界

- CANCEL_TAP 在半帧等待 SYN；异步客户端存在调度/IPC延迟，不承诺瞬时停止。
- 取消与新 TAP 的竞态、pending cancel 期间快速重按、异常下资源释放仍需专项测试；host C 测试不覆盖 Kotlin 生命周期。
- 悬浮按钮三击编辑手势可能与快速重复触发冲突。
- MainActivity 仍有旧文案建议无障碍和声称多指不受影响，需要改成实际验证范围，诊断包还移除了无障碍 service。
- 当前 15 秒到期不能自动重新抓屏；需用户明确重新启动。到期 UI 提示应更清楚。
- Android 跨窗口 split touch 与同页多指不同，尚未验收。
- 需要固定 CI 调试签名，防止每次重装丢授权/配置。
- 当前 debug 清单保留显式 MainActivity 和 ShizukuProvider，launcher 仅诊断页，避免默认启动宏。
- 本地 artifacts/ 是未跟踪产物，不提交。测试配置已在本文记录，不依赖旧电脑临时文件。

## 10. 给接续助手的要求

全程简体中文。尽量直接动手，但触屏接管前明确告诉用户操作时机与硬期限。优先诊断证据，别循环发送“已完成”但没有动作。子任务完成通知不等于文件已改；之前有子任务正常结束却未修改文件，必须用 git diff/实际文件确认。不要把手机手工实验结果扩大成正式应用或游戏中已通过。

当前下一步不是重新研究原理，也不是重新执行旧的 20 次固定点击实验，而是：**在已授权、已配低频目标的 1.6.6 上完成真正悬浮触发链路的限时验证。**
