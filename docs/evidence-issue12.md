# Issue #12 Direct Boot 验收记录

日期：2026-11-05

## 验收环境

- AVD：`voicetodo`
- Android API：37
- 应用包：`com.voicetodo.app`
- 首次解锁前状态：`RUNNING_LOCKED`
- 精确闹钟与通知权限：测试前显式授予

## 自动化结果

- JVM：225 项测试，0 失败、0 错误、0 跳过（含最新主线 #18 的备份恢复测试）。
- Android 仪器测试：16 项执行完成，0 失败；其中 2 项按既有环境前置条件跳过。
- Issue #12 专项仪器测试：4/4 通过，分别覆盖首次解锁前通用通知、解锁后投递回写、完成事项不复活、恢复接收器与真实 AlarmManager 登记。
- `lintDebug`、`assembleDebug`、`assembleDebugAndroidTest` 通过。

## 可重复的真实重启步骤

以下命令针对 Debug 包；`ReminderRecoveryTestDriverReceiver` 不进入发布包。

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

# 1. 安装 Debug 包并准备权限。
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb shell appops set com.voicetodo.app SCHEDULE_EXACT_ALARM allow
& $adb shell pm grant com.voicetodo.app android.permission.POST_NOTIFICATIONS

# 2. 设置未来提醒并为首次解锁前状态设置临时 PIN。
& $adb shell locksettings set-pin 1234
& $adb shell am broadcast `
  -a com.voicetodo.debug.RECOVERY_SCHEDULE `
  -n com.voicetodo.app/com.voicetodo.debug.ReminderRecoveryTestDriverReceiver `
  --el delaySeconds 180

# 3. 重启，但不要在模拟器界面输入 PIN。
& $adb reboot
& $adb wait-for-device
& $adb shell dumpsys user | Select-String RUNNING_LOCKED

# 4. 验证设备加密区记录已被 LOCKED_BOOT_COMPLETED 重新登记。
& $adb shell am broadcast `
  -a com.voicetodo.debug.DIRECT_BOOT_STATUS `
  -n com.voicetodo.app/com.voicetodo.debug.ReminderRecoveryTestDriverReceiver
# 预期：delivered=false;pending=true

# 5. 到点后再次读取状态及通知。
& $adb shell am broadcast `
  -a com.voicetodo.debug.DIRECT_BOOT_STATUS `
  -n com.voicetodo.app/com.voicetodo.debug.ReminderRecoveryTestDriverReceiver
& $adb shell dumpsys notification --noredact
# 预期：delivered=true；通知 VISIBILITY_SECRET；正文“解锁后查看事项”；无操作。

# 6. 输入临时 PIN 完成首次解锁，再检查解锁后对账。
& $adb shell input keyevent KEYCODE_WAKEUP
& $adb shell input keyevent 82
& $adb shell input text 1234
& $adb shell input keyevent 66
& $adb shell dumpsys user | Select-String RUNNING_UNLOCKED
& $adb shell am broadcast `
  -a com.voicetodo.debug.DIRECT_BOOT_STATUS `
  -n com.voicetodo.app/com.voicetodo.debug.ReminderRecoveryTestDriverReceiver
# 预期：records=0；同一通知静默升级为完整事项，并恢复“完成/延后/关闭”操作。

# 7. 清理临时锁屏凭据。
& $adb shell locksettings clear --old 1234
```

## 本次实测观察

- 重启后用户状态为 `RUNNING_LOCKED`，设备加密区记录为 `delivered=false;pending=true`。
- 到点后记录变为 `delivered=true`；通知仅显示“语音提醒 / 解锁后查看事项”，`VISIBILITY_SECRET`，无全屏入口及操作。
- 首次解锁后设备加密区返回 `records=0`；通知以 `SILENT` 更新为事项正文，恢复“完成、延后、关闭”三项操作，不再次响铃或拉起全屏界面。
- 设备加密区序列化字段只有 `itemId`、`reminderId`、`atMillis`、`revision`、`deliveredAtMillis`；单测直接检查原始偏好内容不含事项正文或 AI 密钥样例。

## 边界

- 本记录证明 API 37 模拟器上的首次解锁前/后行为，不等同于小米、OPPO、vivo 等厂商真机后台策略验收。
- 用户强制停止、关机期间执行及绕过系统通知/精确闹钟权限仍不在承诺范围。
