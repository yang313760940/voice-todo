# Issue #23 G1 真机交付验收记录

日期：2026-11-05，时间均为 Asia/Shanghai（UTC+08:00）。
验收人：Little Code Sauce（自动化代理，经 adb 驱动维护者设备 dd9dab66）。
最终收口：2026-11-05 15:20，基于已合入 #22、#11、#18、#12 的最新 `main`。

## 结论速览

- 源码重建、安装、冷启动到首页：**通过**
- 断网离线转写（普通话）：**通过**（飞行模式 + 随包 sherpa-onnx 模型，真实识别出中文）
- 四象限拖拽：**通过**
- 番茄恢复（锁屏后计时继续）：**通过**
- 在线 AI 配置流程：**通过**（服务地址/模型/密钥，保存与清除）
- 锁屏 / 普通进程退出后提醒：**通过**（全屏 + 前台响铃服务 + 通知 + 音频，可完成/延后/关闭本次提醒）
- 重启恢复：**通过**（重启 → PIN 解锁后系统投递 `BOOT_COMPLETED`，`BootReceiver.reconcileReminders()` 重排未来提醒，`dumpsys alarm` 重现）。
- 备份导入导出：**通过**（导出 → 导入预览 → 替换导入 → 自动恢复副本 → 预览并恢复副本；真机数据与文件内容均核对）。

> 10/10 项 G1 检查已在真机通过；不再以模拟器结果替代真机结论。

## 环境

| 项 | 值 |
| --- | --- |
| 设备型号 | Redmi 22081212C（codename diting），厂商 Xiaomi |
| 系统 / API | Android 13 / API 33 |
| ABI | arm64-v8a |
| 设备序列号 | dd9dab66 |
| 构建指纹 | Redmi/diting/diting:13/TKQ1.220829.002/V14.0.11.0.TLFCNXM:user/release-keys |
| 应用 | com.voicetodo.app，versionCode=1，versionName=0.1.0-slice1，minSdk=29，targetSdk=34 |

## 1. 源码重新构建与质量门禁

最终收口基于 `main` 提交 `7037624`（#12 已合入；此前 #22、#11、#18 也已合入）。JDK Temurin 17.0.20.1+1，Gradle wrapper 8.7，执行：

```powershell
.\gradlew.bat :app:clean :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

- BUILD SUCCESSFUL；产物 `app/build/outputs/apk/debug/app-debug.apk`
- 大小 192,673,715 字节；SHA-256 = `83E5C39A3B44FF0ECBB80C6C010A8445F0DF05B0744558E3680654BDE221CF83`
- 构建工具链、随包 AAR 与离线模型来源/许可见 `docs/offline-toolchain.md`（sherpa-onnx-1.13.8 Apache-2.0；Paraformer-zh-small 权重上游 Apache-2.0；模型 model.int8.onnx 81,828,675 字节，tokens.txt 75,352 字节，SHA-256 已核对）。
- 最终门禁：225 项 JVM 测试通过（0 失败、0 错误、0 跳过）；`:app:lintDebug` 0 error、24 个既有或非阻断 warning；debug 与 androidTest APK 构建通过。

## 2. 安装与冷启动

- 升级安装：`adb install -r app-debug.apk` → Success。
- firstInstallTime 保持 2026-11-05 10:10:33，lastUpdateTime 更新为 2026-11-05 14:00:35，说明为覆盖升级、原有数据保留。
- force-stop 后冷启动进入“今天”待办首页，标题“今天 / 11月5日 星期四”，底部四个页签（待办/四象限/番茄/我的）就位；原有事项仍在。
- 截图：`docs/evidence/issue23/20-final-coldstart-home.png`（最终 APK）；早先同一设备冷启动另见 `01-coldstart-home.png`。

## 3. 断网离线转写（普通话）

- 前置：`cmd connectivity airplane-mode enable`，`dumpsys connectivity` 显示 `Active default network: none`。
- 该设备无预装可用 TTS/无环境声，故使用仓库固定的随包测试音频（`androidTest/assets/testwavs/0.wav`，16k 单声道真实普通话）跑真机 instrumentation：

```powershell
adb shell am instrument -w -e class com.voicetodo.OfflineTranscriptionInstrumentedTest \
  com.voicetodo.app.test/androidx.test.runner.AndroidJUnitRunner
```

- 结果：`OK (1 test)`，日志 `OfflineIT: RECOGNIZED_TEXT=对我做了介绍啊那么我想说的是呢大家如果对我的研究感兴趣呢嗯`
- 说明：识别在本机随包模型完成，断网（网络 none）下仍得到非空中文，非云识别、非首次下载。
- 说明：上方为“识别引擎 + 随包模型”的断网真机证明；现场无声源，未额外录制外部语音。

## 4. 权限

`dumpsys package com.voicetodo.app`：SCHEDULE_EXACT_ALARM、POST_NOTIFICATIONS、RECORD_AUDIO、USE_FULL_SCREEN_INTENT、RECEIVE_BOOT_COMPLETED、FOREGROUND_SERVICE(_MEDIA_PLAYBACK) 均 granted。
设置页“权限与提醒检查”显示：精确闹钟已具备 · 通知已具备 · 麦克风已具备。

## 5. 四象限拖拽

- 四象限页显示四个象限；对“去取快递”执行长按拖拽（`input draganddrop 245 538 746 720 1500`）。
- 拖拽前：重要且紧急=1（去取快递）；拖拽后：重要且紧急=0、重要不紧急=1（去取快递，当前：重要不紧急）。
- 截图：`docs/evidence/issue23/11-quadrant.png`、`12-quadrant-moved.png`

## 6. 番茄恢复

- 08:49:06 启动专注（25 分钟，剩余 24 分钟）。
- 08:49:16 锁屏（KEYCODE_SLEEP，mWakefulness=Dozing），08:49:46 唤醒返回番茄页：仍“进行中：专注，剩余 24 分钟”（未被清空）。
- 08:51:02 再查：剩余 22 分钟 → 倒计时在锁屏期间真实推进。
- 提前结束：提示“已提前结束，保留实际时长但不计完成数”，今日时长 2 分钟、今日完成 0（符合“提前结束只留时长不加完成数”）。
- 截图：`docs/evidence/issue23/13-pomodoro-running.png`、`14-pomodoro-restored.png`

## 7. 在线 AI 配置流程

- 我的 → 语音与 AI → 高级设置：可见服务地址（https://api.deepseek.com）、模型（deepseek-flash）、API 密钥（掩码显示），提供“保存配置 / 清除 AI 配置”。
- 页面说明：语音识别始终本机离线；仅用户主动点“用在线 AI 整理”时才发送当前文字+日期时间+时区；密钥用 Android Keystore 加密保存。
- 截图：`docs/evidence/issue23/18-ai-settings.png`

## 8. 锁屏 / 普通进程退出后提醒

使用仓库脚本 `scripts/test-lock-screen-reminder.ps1 -Serial dd9dab66 -ExitProcess`（Debug 驱动直接调用真实 `confirmDraft`/`SystemReminderScheduler`，不启用 instrumentation，不强制停止应用）。

- 预定 `fireAt=1793840105000` → 2026-11-05 08:55:05。
- 进程退出后到点：08:55:05.009 进程为 AlarmReceiver 广播拉起；08:55:05.452 `RingingService` 前台（Background started FGS: Allowed）；08:55:05.971 `AlarmRingActivity` 全屏 Displayed(+263ms)。
- 全屏界面动作齐全：提醒 / 完成 / 延后 10 分钟 / 延后 30 分钟 / 延后 1 小时 / 自选延后时间 / 关闭本次提醒。
- **脚本失败点（已修正）**：脚本用旧文案选择器 `text="关闭提醒"`，而当前按钮文案为“关闭本次提醒”，故抛 `Dismiss button not found`。这是**测试脚本文案过时**，不是功能失败——报警、前台服务、通知、音频、全屏均已实际发生。脚本已改为匹配“关闭本次提醒”。
- 同时把设备时间读取改为显式整数解析并校验，避免 Windows PowerShell 下 `%s` 输出被错误转换。
- 证据目录：`.work/issue23/lock-reminder-exit/`（含 before/after alarm、services、audio、notifications、ui.xml、ringing.png、logcat）。

## 9. 重启恢复（通过）

- 09:08:49 通过 debug 驱动安排未来提醒（“重启恢复验收”，`fireAt=1793841529000` → 09:18:49），`dumpsys alarm` 确认 `RTC_WAKEUP ... com.voicetodo.app` 已注册。
- 09:09:08 重启，09:09:39 `sys.boot_completed=1`，设备停在加密锁屏。
- 09:09:50 用维护者 PIN 解锁 → 09:10:07 系统投递 `BOOT_COMPLETED` 到 `com.voicetodo.alarm.BootReceiver`（`Start proc ... for broadcast {...BootReceiver} caller=android`），`reconcileReminders()` 执行。
- 解锁后 `dumpsys alarm` 重新出现应用未来闹钟：`RTC_WAKEUP ... origWhen 1793841529000 (09:18:49) exactAllowReason=permission`；同批还恢复另一条未来提醒（09:13:32）。**证明重启后未来提醒被重排。**
- 观察：解锁前（08:59–09:08）系统只发 `LOCKED_BOOT_COMPLETED`、不发 `BOOT_COMPLETED`，故未解锁时闹钟不重排；这与 `alarm/BootReceiver.kt` 设计一致（非 directBootAware，注释：“解锁后投递即可（首版切片；Direct Boot 见规格后续要求）”）。解锁前恢复（Direct Boot）属 #12 范畴，非本票缺陷。
- 结论：**重启后（解锁即）恢复未来提醒，真机通过。**

## 10. 备份导入导出（通过）

14:06 我的 → 数据管理 → 导出备份文件，保存到系统“下载”：

- 成功写入 `voice-todo-backup-20261105-140408.json`，5,706 字节；页面提示“已导出 5 个事项、0 个清单、1 条专注记录”。
- 文件含 `format=voice-todo-backup`、`formatVersion=1`、导出时间和应用版本；事项 5 个、专注记录 1 条。
- 文件内容未出现 AI 服务地址、密钥、录音路径等字段（逐项检查 `apiKey/baseUrl/secret/recordings/audioPath` 均为 false）。
- 截图：`docs/evidence/issue23/21-data-management.png`；字段核对：`docs/evidence/issue23/28-export-safety-check.txt`。

随后在应用内新增测试事项 `backupmarker1407`，使当前数据变为 6 项，再执行导入：

1. 选择下载的备份文件，预览显示格式版本 1、应用版本 0.1.0-slice1、导出时间 14:06、事项 5 个、专注记录 1 条；此步未修改数据。截图：`22-backup-import-preview.png`。
2. 点击“替换并重建提醒”，成功提示替换完成，并自动生成恢复副本 `restore-20261105-144249-161.json`。截图：`23-backup-imported.png`。
3. 在“恢复副本”点击预览，显示 6 个事项，证明副本保存的是替换前数据。截图：`24-restore-copy-preview.png`。
4. 点击“替换并重建提醒”，成功恢复该副本，并再次自动生成替换前副本 `restore-20261105-150613-229.json`。截图：`25-restore-copy-imported.png`。
5. 通过应用私有数据库核对恢复结果：`todo_count=6`、`marker_present=true`、`marker_count=1`；临时标记事项确实恢复且未重复。证据：`27-restore-data-check.txt`。
6. `dumpsys alarm` 在恢复前后均无遗留的未来事项闹钟，只保留已取消的 FOCUS_END 记录；与“过去提醒不补响、按新数据重建”的交付规则一致。证据：`25-alarms-before-restore.txt`、`26-alarms-after-restore.txt`。

边界：备份恢复的完整自动化覆盖仍由 #18 的 6 项端到端测试提供；本票补充的是真机 SAF 文件选择/写入、预览、替换和恢复副本链路。

## 附：主线交付完整度观察

- 最终 `main` 已包含 #22、#11、#18、#12；本票 G1 清单不依赖 #20 回收站。
- 构建时“回收站”入口仍显示由 #20 交付；若从 `main` 出正式交付包，合并 #20（及其他已关闭但未合入的实施分支）应作为独立收口事项处理，不应由本票伪装为已完成。

## 验收判定

- 通过（10/10）：源码重建、安装与冷启动、断网离线转写、四象限拖拽、番茄恢复、在线 AI 配置、锁屏/进程退出后提醒、重启恢复（解锁后重排）、备份导出、预览替换导入与恢复副本。
- 本票验收完成，可以关闭；不代表规格 #1 整体完成，#24 多品牌矩阵与 #20 主线合并仍开放。
