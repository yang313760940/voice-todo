# Issue #17 实施记录

## 实现

- `FocusUseCases` 提供单一活动会话的状态机：开始、暂停、继续、提前结束、完整结束确认、设置更新、今日与最近七天统计。
- `RoomFocusRepository` 持久化专注会话与设置；数据库版本 1→2 迁移新增 `focus_sessions`、`focus_settings`，保留既有待办数据。
- `PomodoroViewModel` / `PomodoroScreen` 提供番茄页：绑定事项或独立计时、手动阶段、设置调整、暂停继续、提前结束、结束待确认和统计展示。
- `SystemFocusScheduler` 与 `FocusAlarmReceiver` 使用系统闹钟在阶段结束时刻投递普通通知；进程重启、锁屏后由数据库恢复进行对账。

## 验证

- `FocusUseCasesTest`：冻结时钟验证暂停不计时、恢复后延长结束时刻、提前结束保留实际时长但不计完成数、完整结束只加一次、不自动完成事项、已有活动会话时要求明确结束。
- `RoomFocusRepositoryTest`：真实临时数据库验证会话/设置关闭重开一致、v1→v2 迁移保留待办、到点只转一次“结束待确认”、跨午夜不重复计数。
- `PomodoroScreenTest`：Compose 集成测试验证绑定事项、暂停继续、提前结束统计、结束确认、手动开始休息、设置调整后新会话时长生效。

## 边界

- 本票验证基于 Robolectric/API 33 与 JVM 测试，没有真机验收；系统时间大幅回拨后的实际厂商行为仍需真机矩阵验证。
- 长休息规则保留“每 4 次专注后可手动开始长休息”的设置项，当前 UI 提供短/长休息两个手动入口，不做自动阶段流转。
