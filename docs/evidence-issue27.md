# Issue #27：MIUI 锁屏提醒投递与停止验收

日期：2026-11-05，时间均为 Asia/Shanghai（UTC+08:00）。

## 环境

| 项 | 值 |
| --- | --- |
| 设备 | Xiaomi 2112123AC（Xiaomi 12X，`psyche`） |
| 系统 | MIUI 13 `V13.0.10.0.SLDCNXM` / Android 12 / API 31 |
| 应用 | `com.voicetodo.app`，targetSdk 34 |
| 精确闹钟 | `SCHEDULE_EXACT_ALARM: allow` |
| 勿扰 | 关闭 |
| NoActive | 测试前已由维护者关闭模块；应用仍安装 |

## 结论

该问题不是单一因素：

1. 设备上的 NoActive（LSPosed 模块）具备拦截 Alarm、限制后台应用和冻结应用的能力。
   模块开启期间，同一 APK 首轮能进入响铃服务但未展示全屏提醒，后两轮在系统已登记
   闹钟的情况下没有进入 `AlarmReceiver`。关闭 NoActive 后，同一设备上核心投递连续
   通过，未再出现“系统到点但应用完全未收到广播”。
2. 应用自身还存在独立的停止竞态。用户点击“完成/关闭”后 Activity 会退出，但在 MIUI
   上响应铃前台服务和通知可能继续存活。原因是通知取消是异步的，原实现立即读取
   `NotificationManager.activeNotifications` 判断是否还有提醒，可能把刚关闭的最后一条
   仍算作存在，从而跳过停止服务。

因此本票按“设备根模块干扰 + 应用停止缺陷”收口；不能把未关闭 NoActive 的设备组合写成
可靠支持，也不能把关闭提醒后的前台服务残留继续视为已知现象。

## 修复

- `RingingService` 记录仍然活跃的提醒身份；关闭/完成时先从该集合移除，再取消通知。
  集合为空时由服务直接停止声音、移除前台状态并 `stopSelf()`，不再依赖异步的通知列表。
- `AlarmActionReceiver` 优先走上述服务内停止路径；仅当前进程没有存活服务时，才使用
  原有通知计数兜底。
- 回归测试改为断言最后一个提醒关闭后服务确实由自身停止。
- 锁屏验收脚本增加 UTF-8/BOM、MIUI 解锁、`-LockedScreenOn` 与停止轮询支持；
  测试 PIN 只作为运行时参数，不写入证据。

## 自动化验收

- `:app:testDebugUnitTest`：225 项通过。
- `:app:lintDebug`：通过，无 lint 阻断错误。
- `:app:assembleDebugAndroidTest`：通过。
- Debug APK SHA-256：
  `FF907FDAE9D6DCCD1DFB1398B5C725DACB23A5A338BC241F9CFEB0A047D6DA53`。

## 真机验收

修复 APK 安装到上述 Xiaomi 12X 后，使用
`scripts/test-lock-screen-reminder.ps1` 连续验证：

| 场景 | 次数 | 结果 |
| --- | --- | --- |
| 锁屏，保持亮屏 | 3 | 3/3 通过 |
| 锁屏 + 息屏 | 3 | 3/3 通过 |
| 普通进程退出 + 锁屏 + 息屏 | 3 | 3/3 通过 |

每个通过样本都同时验证：

- 系统 `ALARM_FIRE` 广播实际投递到 manifest `AlarmReceiver`；
- `RingingService` 进入前台；
- 应用通知存在；
- UID 使用 `USAGE_ALARM` 播放声音；
- `AlarmRingActivity` 成为顶层全屏提醒；
- 解锁后点击“关闭本次提醒”，声音、前台服务和通知均停止。

证据目录：

- `.work/issue27/xiaomi12x-fixed-1` 至 `xiaomi12x-fixed-3`
- `.work/issue27/xiaomi12x-fixed-lock-1` 至 `xiaomi12x-fixed-lock-3`
- `.work/issue27/xiaomi12x-fixed-exit-1` 至 `xiaomi12x-fixed-exit-3`

## 边界

- 本票只验证维护者当前的 Xiaomi 12X / MIUI 13 / API 31 设备，不代表其他厂商或版本。
- 不承诺在 NoActive 或其他根模块主动拦截 Alarm 时仍能投递。
- 跨版本模拟器、其他厂商真机、权限撤回、频道关闭、勿扰、低电量、FGS 停止等矩阵仍由
  #24 跟踪。
