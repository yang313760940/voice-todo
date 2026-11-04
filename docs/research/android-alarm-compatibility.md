# Android 语音提醒：闹钟、通知与后台兼容性研究

核查日期：2026-11-04。仅采用 Android Developers 与 Google Play 官方资料。本文是技术栈决策研究，未开始应用开发。版本号表示运行系统版本；涉及 targetSdk 的规则另行标注。API 可用性、权限获准、成功调度、可见通知、实际发声是不同条件。

## 结论

“所有 Android 版本、所有设备状态下通知和闹钟都绝对准时可靠”不现实：关机、用户强制停止、权限撤回、通知频道关闭以及声音设置，都构成实际边界。可交付的目标应是：明确最低支持版本，在已获必要权限且设备运行等条件下尽可能可靠，并显示能力状态、提供恢复及降级路径。[1][2][3][7]

面向 Android 优先的提醒产品，建议原生 Kotlin 实现核心调度与系统集成；UI 可选择 Compose，但应先核对所选 AndroidX/Compose 版本的最低 SDK，旧系统覆盖需求应单独评估 Views 或固定兼容依赖。Flutter/React Native 同样要通过原生组件接入这些系统能力，框架不会消除系统限制。这是基于官方平台约束的工程建议，不代表已经选定技术栈或批准开发。

## 精炼版本矩阵

| 运行版本 / API | 闹钟与通知要点 | 决策含义 |
| --- | --- | --- |
| Android 4.4 / 19 起 | `setExact()` 可用；API 19 起面向现代 target 的重复闹钟按非精确处理。[1][2] | 每日准时提醒采用单次精确闹钟并计算下一次；不要把重复 API 当成精确保证。 |
| Android 5.0 / 21 起 | `setAlarmClock()` 可用，供用户可见的闹钟场景。[2] | 真正的闹钟功能优先评估它；低于 API 21 要另设调度分支。 |
| Android 6.0 / 23 起 | 引入 Doze，`setExactAndAllowWhileIdle()` 可用；普通 `setExact()` 仍会被 Doze 推迟。[1][2][4] | 需要区分普通定时工作、重要提醒与闹钟。 |
| Android 7.0 / 24 起 | Direct Boot 支持重启后首次解锁前运行指定组件。[8] | 解锁前提醒需 `directBootAware`、设备加密存储和 `LOCKED_BOOT_COMPLETED`。 |
| Android 8.0 / 26 起 | target ≥26 的通知须配置频道；用户可以改变频道重要性和通知行为。[11] | 单独设置提醒频道，检查频道状态，勿把创建频道等同用户始终允许通知。 |
| Android 10 / 29 起 | target ≥29 使用全屏通知 intent 须声明 `USE_FULL_SCREEN_INTENT`。[12] | 全屏展示是独立能力，并非安排闹钟后自动获得。 |
| Android 12 / 31、12L / 32 | target ≥31 的 PendingIntent 精确闹钟需 `SCHEDULE_EXACT_ALARM` 特殊访问或符合豁免；后台启动 FGS 原则上受限，但为完成用户请求而触发的精确闹钟属于例外。[1][5] | 调度前检查 `canScheduleExactAlarms()`；后台启动例外仍须满足服务类型等其他条件。 |
| Android 13 / 33 | 引入 `POST_NOTIFICATIONS` 运行时权限，新安装应用通知默认关闭；target ≥33 可选择 `USE_EXACT_ALARM` 或 `SCHEDULE_EXACT_ALARM`。[1][3] | 通知和精确闹钟权限独立处理。`USE_EXACT_ALARM` 安装授予，但受 Play 核心用途审核。[10] |
| Android 14 / 34 | 大多数 target ≥33 的新安装应用不再预授予 `SCHEDULE_EXACT_ALARM`；备份恢复也不继承该授权，已授权应用随设备升级则保留。target ≥34 要声明合适 FGS 类型及对应权限；全屏访问受专项规则约束。[6][9][13] | 不将 Android 14 的默认未授予规则误写成所有 Android 13 设备统一行为。检查每项能力，并提供明确状态。 |
| Android 15 / 35 | target ≥35 有特定 FGS 开机启动禁令及特定类型运行时限；所有 target 的应用进入强制停止状态时会取消 PendingIntent。[7][14] | 开机接收器负责恢复调度，避免开机直接启动被禁类型的服务；强制停止后等待用户交互恢复。 |

矩阵覆盖本次核查的历史关键节点至 Android 15，并非已验证 Android 16 及后续所有变化；正式发布时应针对所选 targetSdk 和当期 Play 要求复核。

## 两种关键闹钟 API

| API | 语义与边界 | 本产品建议 |
| --- | --- | --- |
| `setAlarmClock()`，API 21 | 高可见性、用户设定的闹钟；官方说明系统不调整其投递时间，并会在必要时离开低功耗模式。仍受权限、强制停止、重启等条件约束。[1][2] | 用于明确的“到点响铃/播报”闹钟，而非所有后台任务。 |
| `setExactAndAllowWhileIdle()`，API 23 | 即使节能状态也可执行接近指定时刻的精确闹钟；存在每应用调度频率限制，API 文档举例正常约一分钟、低功耗时可能约十五分钟，还可能重排。[2] | 适合需穿越 Doze 的时间敏感提醒；勿承诺高频或毫秒级硬实时。 |

Doze 指南另写 allow-while-idle 闹钟每应用最多九分钟一次，而 API reference 对不同状态给出约一分钟/十五分钟的示例。这些文档描述不应拼成固定跨版本 SLA；共同结论是存在系统限流，需实机验证。[2][4]

采用系统持有的 PendingIntent 调度，可跨越普通应用进程退出；`OnAlarmListener` 的权限例外不应被当作持久后台闹钟替代方案。精确权限撤回会停止应用并取消未来精确闹钟；授权广播是在授予时发送，需重新检查权限并从持久数据重建计划。[1][2]

## 通知、全屏访问与 Google Play

- Android 13/API 33 起，普通闹钟提醒通知没有通用 `POST_NOTIFICATIONS` 豁免。FGS 的启动本身不要求先获得此权限，但仍需提供 FGS 通知；未获授权时相关提示在任务管理器可见，通知抽屉中不可见。别把媒体会话等特定豁免推广至所有提醒通知。[3]
- Android 14/API 34 使用 `NotificationManager.canUseFullScreenIntent()` 检查全屏资格；可通过 `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` 引导系统设置。用户可开关该访问；即使具备权限，锁屏与正在使用设备时的呈现方式也不同，应保留高优先级通知路径。[9][12]
- Play 的全屏默认授权仅面向核心功能为设置闹钟或接听语音/视频电话的应用。其他应用需用户授予特殊访问。官方 Play FAQ 给出的当前执行节点为 2025-01-22（target Android 14+）；同页亦保留 2024-05-31 的声明/早期生效描述，应区分时间节点，不据旧日期推断现状。[13]
- `USE_EXACT_ALARM`（API 33）是 Play 受限权限，允许的核心用途包括闹钟/计时器和提供事件提醒的日历。产品叫“语音提醒”并不自动证明资格；应根据核心功能、商店说明与审核材料决定。非匹配用途走用户授予的 `SCHEDULE_EXACT_ALARM`。[6][10]

## 前台服务：按类型核对，避免泛化

1. Android 12/API 31、target ≥31：后台 FGS 启动受到限制；用户请求的精确闹钟触发属于文档列出的例外。该例外不等于绕过全部权限和运行条件。[5]
2. Android 14/API 34、target ≥34：每个 FGS 要声明匹配类型及类型权限；涉及麦克风、摄像头、位置等 while-in-use 权限时，后台创建还有独立检查及有限例外。录音输入与播放提醒语音应拆开设计；播放语音不等于使用麦克风。[5][9][15]
3. Android 15/API 35、target ≥35：`BOOT_COMPLETED` 接收器禁止启动的类型为 `dataSync`、`camera`、`mediaPlayback`、`phoneCall`、`mediaProjection`、`microphone`；其中 microphone 的限制已在 Android 14 存在。此规则不是所有类型 FGS 的统一禁令，也不是禁止接收开机广播或重新登记闹钟。[14]
4. Android 15、target ≥35：`dataSync` 和 `mediaProcessing` 各类型的后台运行预算是每 24 小时共六小时；它不是 `mediaPlayback` 或全部 FGS 的统一时限。`shortService` 则有自己约三分钟的机制。[14][15]
5. 持续语音播放应核对 `mediaPlayback` 的实际用例；符合资格的闹钟场景还应阅读 `systemExempted` 的条件，不根据名称推断它只适用于系统预装包，也不把持有闹钟权限当作所有服务均获豁免。最终类型选择留待明确功能及发布渠道后确定。[15]

## 状态边界与恢复建议

| 状态 | 官方边界 | 建议 |
| --- | --- | --- |
| 普通进程退出、屏幕熄灭 | 系统闹钟独立于应用进程；是否唤醒、是否穿越 Doze 由 API 决定。[1][2] | 使用持久提醒记录和 PendingIntent；不要用常驻进程/JS 定时器承担精确计时。 |
| Doze | 标准闹钟、网络及作业会被推迟；allow-while-idle 有频率限制，alarm-clock 有专门待遇。[4] | 到点播放所需内容尽量本地准备，避免依赖即时联网合成。 |
| 关机 / 重启 | 关机时普通应用无执行能力；关机会清除系统闹钟，开机后需重新登记，接收开机广播要求用户至少启动过应用一次。[1] | 持久化规则，开机恢复；明确过期提醒补播/跳过策略。 |
| 重启后尚未解锁 | 凭据加密数据尚不可访问；Direct Boot 需专门组件和设备加密数据。[8] | 仅将解锁前必要的提醒信息放入设备加密存储；普通数据库可用性需单独处理。 |
| 设置中的“强制停止” | stopped 状态应持续到用户直接或间接交互；Android 15 明确额外取消全部 PendingIntent，离开该状态后会发送开机广播供恢复。[7] | 明示需重新打开应用恢复，在恢复入口重查权限、重排计划。勿宣称重启即可绕过强制停止。 |
| Android 13 FGS 任务管理器“停止” | 它是不同于设置“强制停止”的操作，须按该功能官方行为单独验证。[16] | 测试记录区分普通退出、划掉任务、FGS 停止、强制停止。 |
| 用户关闭权限、频道或调整勿扰/音量 | 调度成功不等于通知可见或声音可闻；频道与通知用户设置仍生效。[3][11][12] | 状态页分别展示精确调度、通知、全屏、声音条件；安排测试提醒。 |

建议验收矩阵至少覆盖：新装/升级/备份恢复，授予与撤回权限，锁屏/Doze，重启前后及未解锁，时间和时区改变，通知频道关闭、勿扰与低音量，三类以上厂商真机。以上是后续验证计划，并非已有测试结果。

## 官方来源

1. [Schedule alarms](https://developer.android.com/develop/background-work/services/alarms/schedule)
2. [AlarmManager API reference](https://developer.android.com/reference/android/app/AlarmManager)
3. [Notification runtime permission](https://developer.android.com/develop/ui/views/notifications/notification-permission)
4. [Optimize for Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)
5. [Restrictions on starting a foreground service from the background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
6. [Android 14: Schedule exact alarms are denied by default](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms)
7. [Android 15: changes to package stopped state, all apps](https://developer.android.com/about/versions/15/behavior-changes-all#stopped-state)
8. [Support Direct Boot mode](https://developer.android.com/privacy-and-security/direct-boot)
9. [Android 14: behavior changes for target 34+](https://developer.android.com/about/versions/14/behavior-changes-14)
10. [Google Play: Permissions and APIs that Access Sensitive Information](https://support.google.com/googleplay/android-developer/answer/9888170)
11. [Create and manage notification channels](https://developer.android.com/develop/ui/views/notifications/channels)
12. [Create a notification: urgent messages](https://developer.android.com/develop/ui/views/notifications/build-notification#urgent-message)
13. [Google Play: Understanding foreground service and full-screen intent requirements](https://support.google.com/googleplay/android-developer/answer/13392821)
14. [Android 15: foreground service changes for target 35+](https://developer.android.com/about/versions/15/behavior-changes-15#foreground-services)
15. [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
16. [Handle user-initiated stopping of apps running foreground services](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping)
