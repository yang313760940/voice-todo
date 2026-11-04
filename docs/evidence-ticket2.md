# 票 #2 集成证据（离线转写 → 手选时间 → 系统闹钟）

记录时间：2026-11-04（Asia/Shanghai）。环境：Windows 10、Temurin JDK 17.0.20.1、Gradle 8.7、
Android SDK Build-Tools 34.0.0 / Platform 34、Android Emulator 37.2.12（Android API 37, x86_64, google_apis_playstore）。

## 构建与工具链

- minSdk=29，targetSdk=34，compileSdk=34，Kotlin 1.9.24，AGP 8.5.2。
- 随包原生库来自官方 `sherpa-onnx-1.13.8.aar`（见 docs/offline-toolchain.md），ABI 覆盖
  arm64-v8a / armeabi-v7a / x86 / x86_64。
- 随包普通话模型 `model.int8.onnx`（81,828,675 字节 ≈ 78.04 MB）+ `tokens.txt`（75,352 字节），
  置于 `app/src/main/assets/sherpa/`，来源 hf-mirror（`csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09`），
  `model.int8.onnx` SHA-256：`3EF6C19369B912F7CAF3CEF8E545C5CCD1A33D9D7EC792A46668DC41C4B229EC`（与 GitHub release 同源同哈希）。
- 许可：sherpa-onnx 代码 Apache-2.0；**模型权重上游 Apache-2.0**（ModelScope `damo/speech_paraformer_asr_nat-zh-cn-16k-common-vocab8358-tensorflow1` 的 License 字段），
  FunASR 工具链 MIT，onnxruntime MIT。逐项见 `docs/offline-toolchain.md`。
- `./gradlew :app:assembleDebug` 成功，产物 `app/build/outputs/apk/debug/app-debug.apk`（约 215 MB，含 4 ABI + 模型）。

## 单元测试（用户操作层用例 seam，JVM）

`./gradlew :app:testDebugUnitTest`：**8/8 通过**。
覆盖：确认保存→持久化并请求系统调度；取消→不创建不调度；重开→可见；空标题/过去时刻→拒绝且零副作用；
删除→取消闹钟并移除；**调度失败→事项仍保存但如实返回 scheduled=false（AC5）**。（`TodoUseCasesTest`）

## 仪器测试（断网真机/模拟器，真实系统能力）

`adb shell am instrument -w com.voicetodo.app.test/androidx.test.runner.AndroidJUnitRunner`：**6/6 通过**。

| 测试 | 断言要点 | 结果 |
| --- | --- | --- |
| OfflineTranscriptionInstrumentedTest | 随包模型 `isReady()`；断网识别固定 `0.wav`，得到非空中文 | 通过 |
| AlarmSchedulingInstrumentedTest | 保存→`AlarmManager.nextAlarmClock` 指向目标时刻（diff<5s），即真实 setAlarmClock | 通过 |
| NoExactAlarmPermissionInstrumentedTest | 撤销精确闹钟权限后保存：不崩溃、事项仍保存、如实 `scheduled=false`（AC5 回归） | 通过 |
| TodoViewModelBehaviorTest（×2） | 输入标题+手选时间→保存后列表新增；取消→不创建；空标题→拒绝 | 通过 |

### 真实音频识别那条的逐项证据（OfflineTranscriptionInstrumentedTest）

- 输入音频：`app/src/androidTest/assets/testwavs/0.wav`（同模型仓库 `test_wavs/0.wav`，hf-mirror）。
  - 真实 WAV：单声道 / 16-bit / **16000 Hz** / 89,784 帧 / **5.61 s**；
  - 能量：峰值 **14277**、RMS **2891.5**、**非零采样占比 0.989**（真实语音，非静音/空音频/构造假数据）；
  - **SHA-256 = `668bf8df51a10027b84d5d8816a1ce11ae93545538dc05cfe2aa6811d399c250`**。
- 断网：`svc wifi/data disable` + 飞行模式；`ping -c 1 8.8.8.8` 输出 `connect: Network is unreachable`（exit 1）。
- 识别：走随包 sherpa-onnx（`sherpa-onnx-1.13.8.aar`）+ 随包 `model.int8.onnx`；非首次下载、非假转写。
- 断言：`isReady()` 为真；`result is Success`；`text.isNotBlank()`；`text` 含汉字（U+4E00..U+9FFF）。
- 实跑输出（logcat / 写盘 `recognized.txt`，非 skip、非空）：
  ```
  对我做了介绍啊那么我想说的是呢大家如果对我的研究感兴趣呢嗯
  ```

> 复核次序说明：`AlarmSchedulingInstrumentedTest` 与 `NoExactAlarmPermissionInstrumentedTest`
> 验证的是**系统闹钟登记/权限如实反馈**，`TodoViewModelBehaviorTest` 验证**保存/取消**——这三条都不做音频识别，
> 不充认识别证据；**唯一**的“真实音频走真模型识别”证据是上面的 `OfflineTranscriptionInstrumentedTest`。

## 端到端离线演示（冷启动路径 + 系统闹钟真实投递）

`DemoTicket2EndToEndTest`（飞行模式已开启，`ping 8.8.8.8` 报 Network is unreachable）：

```
OFFLINE_TRANSCRIPT=对我做了介绍啊那么我想说的是呢大家如果对我的研究感兴趣呢嗯
SAVED_ITEM=e9ab8679-5216-41b5-8ee9-bb88ff3cfb22 AT=2026-11-04T07:51:49
ALARM_NOTIFICATION_FIRED=true          # 于 07:51:49.970 命中，正好到点
```

系统日志同时证明到点由系统闹钟投递并启动响铃前台服务（非应用内计时器）：

```
11-04 07:51:49.062 ActivityManager: Background started FGS: Allowed ...
  intent: .../com.voicetodo.alarm.RingingService ; reasonCode:ALARM_MANAGER_ALARM_CLOCK
dumpsys alarm: type=RTC_WAKEUP tag=*walarm*:com.voicetodo.action.ALARM_FIRE
```

## 复现步骤

1. `./gradlew :app:assembleDebug`（或从 `C:\voicetodo` 该 ASCII 联接路径构建，见下）。
2. `adb install -r -t app/build/outputs/apk/debug/app-debug.apk`
3. 授予权限：`adb shell appops set com.voicetodo.app SCHEDULE_EXACT_ALARM allow`；
   `pm grant ... POST_NOTIFICATIONS`、`RECORD_AUDIO`。
4. 断网：`adb shell settings put global airplane_mode_on 1 && svc wifi disable && svc data disable`。
5. 构建并安装 androidTest 后：`adb shell am instrument -w com.voicetodo.app.test/androidx.test.runner.AndroidJUnitRunner`。

> 说明：本机工程路径含中文，Gradle 测试 worker 的 classpath 响应文件会乱码导致 ClassNotFoundException。
> 已通过创建 ASCII 联接 `mklink /J C:\voicetodo "E:\AI工程\语音提醒软件开发"`，在 `C:\voicetodo` 下构建/测试来解决；
> 源码仍在原目录。真机（维护者本人设备）验收见独立后继票，本票只交付模拟器证据，不声称真机已验证。
