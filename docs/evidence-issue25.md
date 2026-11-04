# Issue #25：锁屏提醒真机复验

日期：2026-11-04，时间均为 Asia/Shanghai（UTC+08:00）。

## 结论与归属

- [#25](https://github.com/yang313760940/voice-todo/issues/25) 的锁屏、普通进程退出后投递、关闭停止三个验收项，在原报告设备上复验通过。
- 修复已存在于 `159e2c2971b06f7f1ba0750b493a9297aa395877`；本轮没有重新修改业务源码，补充的是回归对照、真机证据及工单状态。
- 另外实测默认约 60 秒停止音频、保留通知，未发现本次修复破坏该行为。
- 不把系统登记闹钟当作响铃成功：本次同时检查音频播放、前台服务、活动通知、全屏 Activity 及关闭后的清理。

## 环境与 APK 对应关系

- Redmi 22081212C / Android 13 / API 33 / arm64-v8a，原报告设备 `dd9dab66`。
- 包名 `com.voicetodo.app`，versionCode=1，versionName=`0.1.0-slice1`，targetSdk=34。
- `SCHEDULE_EXACT_ALARM` 和 `POST_NOTIFICATIONS` 已授权，提醒频道 importance=4。
- 闹钟音量 12/15，`Muted=false`，输出设备 speaker；`zen_mode=0`。
- 已安装 APK SHA-256：`603FAEF0879E3698CC77FD980D7707A2E5649F8C0ECE085FB650FAECD224B7FA`。
- 本地从 `159e2c2` 构建的 APK SHA-256：`596F1623E7B088FAD9AA8E9EC4DF670B1AA7683B43FCECB88F72CF98AA0E78AB`。

两台开发机的 Debug 签名不同，不能直接覆盖安装。本轮**没有卸载、清空数据、修改权限、音量或省电设置**。

为核对已安装版本，使用 SDK `apkanalyzer dex packages --defined-only` 枚举应用类，逐一运行 `apkanalyzer dex code --class <class> <apk>`：两个 APK 的 **86/86 个 `com.voicetodo.*` 类反汇编文本完全一致**。两包均有 112 个非签名、非空 payload 文件；除 6 个 DEX 容器的字节差异外，其余文件一致，包括二进制 Manifest、资源、模型和原生库。应用类一致不等于两个 APK 整包二进制一致，也不宣称第三方库的全部类均逐一比对。

原始结果保留在本机 `.work/issue25-baseline/app-code-comparison.json`。这支持当前应用逻辑的真机验收，不冒充“本机签名 APK 已覆盖安装成功”。

## 真机反馈循环

使用已有 Debug 驱动调用真实 `TodoUseCases.confirmDraft` 和 `SystemReminderScheduler`，安排 45 秒后的提醒；无需 instrumentation，避免测试结束时强制停止应用污染结果。

```powershell
./scripts/test-lock-screen-reminder.ps1 -Serial dd9dab66 -EvidenceDir .work/issue25-baseline
./scripts/test-lock-screen-reminder.ps1 -Serial dd9dab66 -EvidenceDir .work/issue25-baseline-process-exit -ExitProcess
```

两条命令均返回 `PASS`。脚本分别断言：

- 到点前 `mWakefulness=Dozing`、包 `stopped=false`、存在系统 `RTC_WAKEUP` 闹钟。
- 进程退出场景在后台通过 Debug 驱动结束自身，确认 PID 消失；不调用 `am force-stop`。
- 到点后 `RingingService isForeground=true`、应用活动通知、应用 UID 的 `USAGE_ALARM state:started`、顶层 `AlarmRingActivity` 同时存在。
- 通过 UI 层点击“关闭提醒”，随后应用的响铃前台服务、播放音频及活动通知均消失。
- finally 仅清理本轮测试事项。

| 场景 | 预定时间 | 实际音频开始 | 全屏显示日志 | 关闭后停止音频 | 结果 |
| --- | --- | --- | --- | --- | --- |
| 后台 + 息屏 | 19:15:30.000 | 19:15:30.128 | 19:15:30.675 | 19:15:39.002 | PASS |
| 普通进程退出 + 息屏 | 19:16:53.000 | 19:16:53.574 | 19:16:54.139 | 19:17:00.678 | PASS |

以上偏差是这两次观察值，不是跨设备准点 SLA。声音证据是系统实际播放状态及扬声器路由，不是单纯调用了播放函数；未另外采集外置麦克风录音。

### 最小证据摘录

```text
19:15:30.028 Background started FGS: Allowed ... com.voicetodo.alarm.RingingService
19:15:30.128 player piid:5639 state:started
19:15:30.675 Displayed com.voicetodo.app/com.voicetodo.alarm.AlarmRingActivity
19:15:39.002 player piid:5639 state:stopped

19:16:09.320 Process com.voicetodo.app (pid 31641) has died
19:16:53.021 Start proc 16310:com.voicetodo.app ... for broadcast ... AlarmReceiver
19:16:53.496 Background started FGS: Allowed ... com.voicetodo.alarm.RingingService
19:16:53.574 player piid:5647 state:started
19:16:54.139 Displayed com.voicetodo.app/com.voicetodo.alarm.AlarmRingActivity
19:17:00.678 player piid:5647 state:stopped
```

原始 dumpsys 和日志仅保留于上述 `.work/` 目录，不将其他应用的通知、系统日志批量提交到仓库。

截图：[后台息屏提醒](evidence/issue25/locked.png)、[普通进程退出后的提醒](evidence/issue25/process-exit.png)。

### 默认超时补测

通过相同 Debug 驱动安排一条提醒，进入息屏；到点后不点击关闭，先确认音频处于 started，再等待 60 秒，查询音频、通知和服务状态，最后只清理测试事项。

```text
预定：19:23:32.000
19:23:32.110 player piid:5655 state:started
19:24:32.116 player piid:5655 state:stopped
19:24:32.132 releasing player piid:5655
超时后仍有应用 NotificationRecord：importance=4 category=alarm actions=1
超时后无应用 started 闹钟音频，无 isForeground=true 响铃服务
PASS: sound stopped after default timeout and notification retained
```

音频持续约 60.006 秒。原始证据位于 `.work/issue25-timeout/`。

## 修复机制与回归测试

原实现的系统 Receiver 已经是静态声明，不能把“新增静态接收器”当作根因或本次修复。

修复前确认存在的问题：Receiver 直接尝试从后台启动 Activity，通知没有 full-screen intent、Manifest 没有 `USE_FULL_SCREEN_INTENT`；前台服务又以同一通知 ID 发布另一份不含全屏与关闭动作的通知。系统闹钟触发成功并不保证这条展示链在锁屏下有效。

`159e2c2` 的改变：由 Receiver 先交给 `mediaPlayback` 前台服务；服务使用统一的闹钟通知，保留全屏入口、关闭动作和 alarm 类别；声明全屏通知权限，声音不依赖 Activity 是否成功启动。

为验证现有测试能捕获该缺陷，而非恒绿，在独立的 `.work/issue25-pre-fix/` 中通过 `git archive 2921475` 取出修复前源码，仅加入当前 `AlarmDeliveryTest.kt`、本机 SDK 路径及编译所需 AAR。未改变活动工作区，也未将旧版安装到手机。

```text
修复前源码 + 当前 AlarmDeliveryTest：1 test completed, 1 failed
AlarmDeliveryTest.kt:37
java.lang.AssertionError: 前台通知不能丢失锁屏全屏入口

当前 159e2c2 + 同一测试：1 test, 0 failures
```

此红绿对照验证通知交接缺陷；没有重新安装历史 APK 复现“三无”全过程，不将这个 JVM 断言夸大为对历史所有症状的单一因果证明。当前真机的三个独立反馈循环均已通过，满足 #25 的复验范围。

## 构建、回归与审查

JDK 17.0.18，执行：

```powershell
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

- 全部 JVM 测试 9/9 通过；Debug APK 与仪器测试 APK 构建成功。
- Lint：0 errors、14 warnings，主要为既有 target/依赖版本、国际化和资源建议；未改变 target 或依赖规避检查。
- 未运行 `connectedDebugAndroidTest`：本机签名不能覆盖现有应用，不为运行全套 instrumentation 删除用户数据。真机闭环使用上述 adb 驱动，不依赖这一测试包。
- Standards 独立审查：无可操作的新违规。Spec 独立审查：无 #25 阻断性偏差；指出的超时证据缺口已补测。先前提交附带 Gradle 内存调整不是本轮修改。

范围仅为此 Redmi 的当前权限、声音和系统设置下的单次提醒。没有证明 Android 14+ 全屏特殊访问、其他厂商、强制 Doze、重启、设置强制停止或并发多提醒的完整兼容性；不将 `mWakefulness=Dozing` 等同于强制 deep-idle 测试。
