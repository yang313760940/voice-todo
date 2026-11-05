# 事项朗读（#13）实施与验证记录

日期：2026-11-05

## 交付范围

- 详情页新增“朗读事项”开关，按事项持久化；到点由系统本机 `TextToSpeech` 朗读标题。
- 朗读开启后才显示“锁屏朗读”独立开关，默认关闭。锁屏且未明确允许时不尝试发声，继续使用既有提示音与通知，避免读出正文。
- 本机语音引擎不可用、缺少中文语音数据或不支持中文时，改用既有提示音；通知副标题与全屏提醒页展示回退原因。
- 关闭或延后提醒时停止该条提醒正在进行的朗读；同一时刻仍有其它提醒通知时，只停被处理的那一条，不影响其它提醒。
- 复用现有 `RingingService` 生命周期、通知与 60 秒持续响铃上限，不引入云 TTS、声音克隆、语音包下载或触发时联网合成。

## 关键实现

- `domain/TodoItem.kt`：新增 `readAloud`、`readAloudWhenLocked`，默认均为关闭。
- `data/TodoDatabase.kt`：Room v9，自动迁移新增两个带 `0` 默认值的列；`docs` 对应的 `9.json` 已生成。
- `speech/ReminderSpeech.kt`：纯策略 `decideReminderSpeech`，集中处理朗读开关、锁屏隐私、资源可用性与回退原因。
- `speech/AndroidReminderSpeaker.kt`：系统 `TextToSpeech` 边界，检查中文语言资源；只调用设备本机引擎，无应用侧网络请求。
- `alarm/RingingService.kt`：到点前准备语音边界；成功则朗读并跳过提示音，失败则回退提示音；关闭/延后按提醒键停止当前朗读。
- `alarm/AlarmNotification.kt`、`alarm/AlarmRingActivity.kt`：回退原因同时进入通知副标题和全屏提醒页。
- `ui/TodoScreen.kt`、`ui/TodoViewModel.kt`：详情页两个开关及即时持久化反馈。

## 测试覆盖

| 场景 | 覆盖 |
| --- | --- |
| 朗读成功 | `ReminderSpeechDeliveryTest.enabledReadingSpeaksTitleOnceAndKeepsPrivateNotification` |
| 锁屏默认不读 | `ReminderSpeechDeliveryTest.lockedDeviceDoesNotSpeakTitleUnlessExplicitlyAllowed` |
| 缺资源回退 | `ReminderSpeechDeliveryTest.missingLocalResourceFallsBackToToneAndShowsReason` |
| 关闭/延后停声 | `ReminderSpeechDeliveryTest.closingReminderStopsSpeechAndDuplicateDeliveryDoesNotRestartIt` |
| 并发提醒只停当前 | `ReminderSpeechDeliveryTest.closingSpeakingReminderStopsItsSpeechWhileAnotherNotificationRemains` |
| 策略分支 | `ReminderSpeechPolicyTest` 5 项 |
| 开关持久化 | `ReminderActionsTest.readAloudDefaultsOffAndLockScreenChoiceIsIndependent`、`TodoScreenTest.readAloudSwitchesPersistAndLockReadingIsAnExplicitChoice` |
| 8→9 迁移 | `RoomReminderUpgradeTest.versionEightItemsDefaultReadingOffWhenSpeechColumnsAreAdded` |

通知断言包括：私密通知正文仍为标题、公开版本仍为“解锁后查看事项”、回退原因为通知副标题。

## 验证结果

执行环境：JDK 17.0.20.1，ASCII 联接 `C:\voicetodo`。

```text
./gradlew :app:testDebugUnitTest
tests=208 failures=0 errors=0 skipped=0

./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
BUILD SUCCESSFUL
```

`app/build/outputs/apk/debug/app-debug.apk` 已生成。

## 未验证边界

- 未执行真机或模拟器音频验收：本机 TTS 是否实际发声、音色与系统引擎离线语音包状态未在设备上确认；按 #13 边界不做真机验收。
- 系统 TTS 引擎是否选择离线合成由其自身与已安装语音数据决定。应用侧只使用本机引擎，不调用网络 TTS；`LANG_MISSING_DATA` / `LANG_NOT_SUPPORTED` 会触发提示音回退。
- 同一时刻多条提醒共用现有单播放器/单语音边界；本轮只保证关闭/延后能定位停止当前朗读，不改变既有的并发响铃呈现模型。
