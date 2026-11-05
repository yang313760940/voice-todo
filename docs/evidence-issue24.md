# Issue #24：设备验收矩阵（进行中）

日期：2026-11-05，时间均为 Asia/Shanghai（UTC+08:00）。

> 状态：**未完成，不能关闭**。已执行的实体机用例中发现锁屏漏提醒，且与 2026-11-04 的
> [#25 复验记录](evidence-issue25.md) 冲突。本文件只登记已实际观测到的结果；未执行、未通过、
> 无硬件可测的组合一律标注，不用其他设备的结论代替。

## 结论摘要

- 本轮只有一台实体机可用：Xiaomi Redmi `22081212C`（`diting`，MIUI 14 `V14.0.11.0.TLFCNXM`，
  Android 13 / API 33，arm64-v8a，应用 targetSdk 34）。
- 同一台设备、同一 APK 上，**锁屏 + 息屏** 用例本轮 1 次通过、8 次失败；失败时系统侧闹钟确实到点，
  但应用没有收到投递，也没有前台服务、通知或全屏提醒。
- 该失败**推翻**了 `#25` 于 2026-11-04 19:15 在同一设备（APK 由 `159e2c2` 构建）记录的锁屏通过结论，
  属于回归或环境漂移，尚未定性。按 `#1` 的质量门槛，“必要权限具备且已声明支持场景漏提醒”
  属于**发布阻断问题**，因此本轮不把锁屏场景记为可用。
- OPPO/一加、vivo、三星、华为/荣耀真机，以及 Android 10/12/14 等版本分支**均未测试**：
  没有硬件，也没有按维护者要求执行模拟器分支（维护者明确“先只测试实体机器”）。
- 未执行项：重启、时区变化、划掉任务、FGS 任务管理器停止、设置强制停止、精确闹钟撤回、
  通知频道关闭、勿扰开启、低电量、密集提醒、重复提醒、完成后是否仍响、偏差分布。

## 环境快照

| 项 | 值 |
| --- | --- |
| 设备 | Redmi `22081212C`（`diting`），序列号 `dd9dab66` |
| 系统 | MIUI 14 `V14.0.11.0.TLFCNXM` / Android 13 / API 33 |
| ABI | arm64-v8a |
| 应用 | `com.voicetodo.app`，versionName `0.1.0-slice1`，targetSdk 34 |
| 已安装 APK SHA-256 | `86310461167D1D3D549A7FB1822AEE72C7C2B3C2244B64B3F22CEFB6D4E39A23` |
| 精确闹钟 appops | `SCHEDULE_EXACT_ALARM: allow` |
| 通知 | `POST_NOTIFICATIONS` 已授权；`reminder_channel` importance=4，未关闭 |
| 全屏/麦克风/开机恢复 | 均 granted |
| 闹钟音量 | `STREAM_ALARM` 6/15，未静音，输出 speaker |
| 勿扰 | `zen_mode=0` |
| 省电模式 | 关闭（`low_power=0`） |
| 电量 | 91%→96%，USB 供电 |
| 待机白名单 | AOSP Doze 白名单在对照实验中加过，测试结束**已移除**；`RUN_ANY_IN_BACKGROUND` 对照时设为 `allow`，结束已恢复 `default` |
| MIUI 后台策略 | 测试中由 `miuiAuto` 改为 `noRestrict`（`powerkeeper` `userTable` 已核对） |
| MIUI 自启动 | 开启（`sliding_button checked=true`） |

设备有 Magisk root，仅用于读取系统状态（`dumpsys`、MIUI `powerkeeper` 数据库副本），
没有改写系统分区，也没有通过数据库写入修改策略。

> 遗留：`noRestrict` 是通过 MIUI 界面设置后留下的改动，未还原为 `miuiAuto`。
> 本机该 ROM 的“省电策略”入口未能通过 adb 稳定定位，故未在本轮静默改回；
> 如需还原：设置 → 应用设置 → 应用管理 → 语音提醒 → 省电策略 → 智能省电限制。

## 测试方法

沿用 `#25` 的 Debug 驱动，调用真实用例层 `TodoUseCases.confirmDraft` 与
`SystemReminderScheduler`（`AlarmManager.setAlarmClock` + 系统持有 PendingIntent）。

```powershell
./scripts/test-lock-screen-reminder.ps1 -Serial dd9dab66 -EvidenceDir .work/issue24/redmi-api33/N1 -DelaySec 60
./scripts/test-lock-screen-reminder.ps1 -Serial dd9dab66 -EvidenceDir .work/issue24/redmi-api33/R2 -DelaySec 45 -ExitProcess
```

本轮对脚本做了两处改动（脚本本身不进入发布包）：

- 新增 `-DelaySec`，用于覆盖“到点前应用已长时间空闲”的情形；
- 调度前先 `am start` 主界面并等待，避免厂商系统丢弃发给后台空闲应用的 adb 广播，
  导致“根本没排上闹钟”被误判成“闹钟没响”。

`AlarmTestDriverReceiver` 增加 `AlarmTestDriver` 日志（仅 debug 源集），用于确认驱动广播是否执行、
是否成功安排、清理了哪个事项。

判定口径：不以“系统已登记闹钟”当成功。成功需同时满足前台服务 `isForeground=true`、
应用活动通知、应用 UID 的 `USAGE_ALARM state:started`、顶层 `AlarmRingActivity`；
本轮所有失败样本都卡在第一项之前。

## 结果矩阵

### 实体机：Redmi 22081212C / API 33

| # | 场景 | 提前量 | 系统侧 | 应用侧 | 结果 |
| --- | --- | --- | --- | --- | --- |
| 1 | 解锁亮屏（对照） | 45s | 到点 | FGS + 3 条通知 + USAGE_ALARM | **通过**（全屏 Activity 未成为 top，见下） |
| 2 | 锁屏 + 息屏 + 默认策略 | 45s | 到点（`device_idle_wake_from_idle`） | 无 FGS / 无通知 / 无全屏 | **失败** |
| 3 | 锁屏 + 息屏 + 默认策略（重复 3 次） | 45s | 到点 | 无 FGS / 无通知 / 无全屏 | **失败 3/3** |
| 4 | 锁屏 + 息屏 + AOSP Doze 白名单 + `RUN_ANY_IN_BACKGROUND=allow` | 45s | 到点 | 无 FGS / 无通知 / 无全屏 | **失败** |
| 5 | 锁屏 + 息屏 + MIUI `noRestrict` + 自启动开 | 45s | 到点 | 无 FGS / 无通知 / 无全屏 | **失败** |
| 6 | 锁屏 + 息屏（中途一次成功样本） | 73s | 到点 | FGS `isForeground=true`，频道 `reminder_channel` | **通过** |
| 7 | 锁屏 + 息屏 | 45s | 到点 | 无 FGS | **失败** |
| 8 | 锁屏 + 息屏 | 90s | 到点 | 无 FGS | **失败** |
| 9 | 普通进程退出 + 锁屏 + 息屏 | 45s | 到点 | 无 FGS / 进程未被拉起 | **失败** |
| 10 | 锁屏 + 息屏（清空历史测试事项后连测 3 次） | 60s | 到点 | 无 FGS | **失败 3/3** |

统计口径：锁屏/息屏 9 次，通过 1 次、失败 8 次；`#25` 于 2026-11-04 记录的 2 次通过未计入本轮。
样本量小，不足以给出失效率估计，只能说明**该配置不可判为可用**。

### 其他组合（未测，明确标注）

| 组合 | 状态 |
| --- | --- |
| OPPO / 一加真机 | 未测（无硬件） |
| vivo 真机 | 未测（无硬件） |
| 三星真机 | 未测（无硬件） |
| 华为 / 荣耀真机 | 未测（无硬件） |
| Android 10（API 29）/ 12（API 31）/ 14（API 34） | 未测（维护者要求先只测实体机，模拟器分支延期） |
| 重启 / 时区变化 / 权限撤回 / 频道关闭 / 勿扰 / 低电量 | 未测 |
| 划掉任务 / FGS 停止 / 设置强制停止 | 未测（三者必须分开记录，不能合并） |
| 密集提醒、重复提醒、完成后是否仍响、触发偏差分布 | 未测 |

模拟器分支即使后续补测，也只用于版本行为回归，不作为厂商后台可靠性结论。

## 失败样本的系统侧证据

失败样本的共同特征（以 16:39:07 那次为例，证据目录
`.work/issue24/redmi-api33/M2-90s-locked/`）：

1. 到点前 10 秒 `dumpsys alarm` 仍有本应用闹钟，且是精确唤醒：

   ```text
   Next alarm clock information:
     user:0 pendingSend:false time:1793867947000 = 2026-11-05 16:39:07.000
   RTC_WAKEUP #2: Alarm{... com.voicetodo.app}
     tag=*walarm*:com.voicetodo.action.ALARM_FIRE
     type=RTC_WAKEUP window=0 exactAllowReason=permission
     operation=PendingIntent{... com.voicetodo.app broadcastIntent}
   ```

2. 到点瞬间系统确实因该闹钟退出 Doze：

   ```text
   16:39:07.003 I device_idle_wake_from_idle: [0,*walarm*:com.voicetodo.action.ALARM_FIRE]
   ```

3. 之后没有 `AlarmReceiver`、`RingingService`、通知或全屏 Activity 的任何记录；
   `dumpsys activity broadcasts com.voicetodo.app` 里也**没有**本次 `ALARM_FIRE` 的投递记录。
4. 应用在 `dumpsys alarm` 的投递计数没有增加：仍为 `1 wakeups`，最后一次是 16:33:48 那次通过样本。
5. 应用进程并未被 AOSP freeze：`isFrozen=false`，`curProcState=15`；
   包状态 `stopped=false suspended=false hidden=false`。
6. 到点后待办闹钟从 `dumpsys alarm` 消失，既没有留下 pending，也没有留下投递记录。

由此可确认：**本轮失败不是“闹钟没排上”，也不是“应用收到了但拒绝响铃”**。
失败发生在系统把闹钟交给应用之前。具体是 MIUI `powerkeeper`（`FrozenControlNewStatus=true`）、
`AlarmManager` 的 PendingIntent 送出路径，还是应用侧 PendingIntent 被注销，
本轮证据不足以区分，**不下结论**。

已排除的变量：

- 精确闹钟权限（`appops get` 为 `allow`，`exactAllowReason=permission`）；
- Doze 白名单（加入后仍失败，已移除）；
- `RUN_ANY_IN_BACKGROUND`（`allow` 后仍失败）；
- MIUI 省电策略（`noRestrict` + 自启动开启后仍失败）；
- 后台空闲导致的 adb 广播被丢弃（脚本已改为先进入前台再调度）；
- 历史测试事项干扰（清空后连测 3 次仍失败）。

## 与 #25 记录的冲突

- `#25`：2026-11-04 19:15:30 / 19:16:53，同机同场景（锁屏息屏、普通进程退出）**通过**，
  证据见 [evidence-issue25.md](evidence-issue25.md)，当时 APK 由 `159e2c2` 构建。
- 本轮：2026-11-05 16:18 起，同机同场景**大量失败**，APK SHA-256 `86310461…`。

差异至少包含两处，尚不能归因：应用侧新增了“首次解锁前恢复提醒”的 Direct Boot 路径
（`7037624`）；设备侧本轮被反复 force-stop、改过省电策略并做过 root 只读诊断。
下一步应先做干净复现（重装同一 APK、不动厂商设置、单条事项），再判断是应用回归还是厂商环境漂移。

## 复现步骤

1. 安装上述 APK，授予精确闹钟与通知权限，确认 `reminder_channel` 未关闭。
2. `adb shell input keyevent 224` 唤醒并解锁，进入应用主界面。
3. `adb shell am broadcast -a com.voicetodo.debug.SCHEDULE -n com.voicetodo.app/com.voicetodo.debug.AlarmTestDriverReceiver --ei delaySec 60`
4. 按 Home 回桌面，`adb shell input keyevent 223` 息屏，等待到点。
5. 到点后检查 `dumpsys activity services com.voicetodo.app`、`dumpsys notification`、
   `dumpsys activity activities`、`dumpsys alarm`、`dumpsys power`，并抓 `logcat -b all`。

脚本会打印待办事宜 ID；如未响铃，务必用
`am broadcast -a com.voicetodo.debug.CLEANUP ... --es itemId <id>` 清理，
注意该清理广播在应用被判为空闲时同样会被系统丢弃，需要先把应用拉回前台。

## 后续

- 本票不关闭：三厂商真机矩阵、版本分支、以及上表中全部“未测”项仍未完成。
- 锁屏漏提醒按发布阻断处理，建议单开一张实施票（保活策略 / 厂商引导 / 投递可靠性），
  在定性前不要把它当作已修复或厂商已知限制写进发布说明。
- 已声明的支持范围不得包含“未做任何厂商设置即可锁屏可靠响铃”，直到有可复现的通过证据。
