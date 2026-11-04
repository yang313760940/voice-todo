# 语音提醒（VoiceTodo）— 票 #2 最小闭环

原生 Kotlin + Jetpack Compose 的单页待办：点击录音 → 随包 sherpa-onnx 离线普通话转写 →
可编辑标题 → 手选一个未来时间 → 保存一条事项 → 真实 AlarmManager 系统闹钟触发通知与响铃 → 关闭。

- minSdk 29（Android 10+），targetSdk 34。
- 离线识别模型与原生库**随安装包**交付，首次启动断网可用；不调用 AI。

## 获取随包二进制（首次构建前）

模型与 AAR 体积大、不入 git，按 `docs/offline-toolchain.md` 中的 URL 与 SHA-256 取得：

```bash
# 1) 官方 sherpa-onnx AAR（47.81 MB）
curl -L --ssl-no-revoke -o app/libs/sherpa-onnx-1.13.8.aar \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar"

# 2) 普通话模型（78.04 MB）+ 词表，放 assets/sherpa/
curl -L --ssl-no-revoke -C - -o app/src/main/assets/sherpa/model.int8.onnx \
  "https://hf-mirror.com/csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09/resolve/main/model.int8.onnx"
curl -L --ssl-no-revoke -o app/src/main/assets/sherpa/tokens.txt \
  "https://hf-mirror.com/csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09/resolve/main/tokens.txt"
```

## 构建与安装

```bash
export JAVA_HOME=<JDK17>          # AGP 8.5.2 需要 JDK 17
export ANDROID_HOME=<AndroidSdk>  # 需要 Platform 34 + Build-Tools 34.0.0
./gradlew :app:assembleDebug
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
```

> Windows 中文路径注意：Gradle 测试 worker 的 classpath 会因中文路径乱码。
> 建议用 ASCII 联接构建：`mklink /J C:\voicetodo "E:\AI工程\语音提醒软件开发"`，在 `C:\voicetodo` 下执行。

## 测试

```bash
./gradlew :app:testDebugUnitTest          # 用例层（JVM）7 项
./gradlew :app:connectedDebugAndroidTest  # 设备仪器测试（需模拟器/真机 + 权限）
```

仪器测试覆盖：断网真实识别、真实系统闹钟登记、保存/取消行为、端到端离线→闹钟投递。
证据见 `docs/evidence-ticket2.md`。

## 权限与提醒就绪

- 麦克风 `RECORD_AUDIO`、通知 `POST_NOTIFICATIONS`（13+）运行时申请。
- 精确闹钟 `SCHEDULE_EXACT_ALARM`（12+）为特殊访问，未授予时界面如实显示“未就绪”，
  保存事项但不谎称已安排（`TodoUseCases` 与 `SystemReminderScheduler.capabilities()` 分别反馈）。
- 到点使用 `AlarmManager.setAlarmClock` + 系统 `PendingIntent`，进程退出/锁屏仍由系统投递；
  持续响铃用 `mediaPlayback` 前台服务，默认 60 秒后停止发声并保留通知。
