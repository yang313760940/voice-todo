# 语音提醒（VoiceTodo）

原生 Kotlin + Jetpack Compose 的本地待办应用：文字输入 → 可编辑标题 → 手动创建，
或由用户自填的 OpenAI 兼容服务整理成一组可编辑草稿 →
确认后保存事项 → 真实 AlarmManager 系统闹钟触发通知与响铃 → 关闭。

- minSdk 29（Android 10+），targetSdk 34。
- 底部导航包含待办、四象限、番茄、我的；“我的”集中提供提醒系统设置入口、
  番茄计时设置、外观与字体、语音与 AI、权限状态、数据管理与回收站和关于页。
- 外观支持浅色、深色、跟随系统与小/标准/大三档字体，修改即时生效并持久化。
- 本版已移除随包离线识别模型与原生库；语音入口在在线识别接入前显示“在线服务未配置”。
- 在线 AI 只在用户主动点“用在线 AI 整理”时发送当前文字、当前日期时间和时区；
  不发送录音、历史事项或全部待办。未配置 AI 时，手动文字创建仍可用。
- AI 服务地址、模型和密钥在“高级设置”中自填；密钥用 Android Keystore 加密保存，
  不进入日志、备份或源码。远程地址必须使用 HTTPS，仅本机调试允许 `http://127.0.0.1`。
- AI 可一次返回多条草稿；每条可独立编辑、删除或修改象限，确认前不创建事项、不安排闹钟。
- 多草稿批量确认先校验全部内容，再用一个 Room 事务保存；重复点击或系统调度失败重试不会重复创建。
- 草稿可带每天、每周多选星期或每月指定日期的重复建议，并可修改重复时刻；保存后使用现有重复实例引擎。
- 删除事项立即撤销未来提醒并进入 30 天回收站；恢复保留原属性，过去提醒清除，未来提醒重新安排。
- 删除重复实例可选择仅本次或本次及以后；已完成历史与专注记录保留。

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
./gradlew :app:testDebugUnitTest          # 当前证据轮次 JVM 246 项（含模拟 HTTP 服务端到端）
./gradlew :app:connectedDebugAndroidTest  # 设备仪器测试（需模拟器/真机 + 权限）
```

仪器测试覆盖：真实系统闹钟登记、保存/取消行为、端到端保存→闹钟投递；
另含 Android Keystore 配置加密、设备本机 HTTP 回环绕行和 AI 请求闭环验证。
历史证据见 `docs/evidence-ticket2.md`。

## 权限与提醒就绪

- 麦克风 `RECORD_AUDIO`、通知 `POST_NOTIFICATIONS`（13+）运行时申请。
- `INTERNET` 用于用户主动发起的在线 AI 整理；后续在线语音识别接入后同样只在用户主动录音时联网。
- 精确闹钟 `SCHEDULE_EXACT_ALARM`（12+）为特殊访问，未授予时界面如实显示“未就绪”，
  保存事项但不谎称已安排（`TodoUseCases` 与 `SystemReminderScheduler.capabilities()` 分别反馈）。
- “我的 → 权限与提醒检查”分别显示事项保存、系统精确调度、通知运行时授权、应用通知总开关、
  提醒频道、全屏提醒、频道声音、闹钟音量和勿扰状态；从系统设置返回后自动刷新。
- Android 14 起全屏提醒特殊访问不可用时，不请求不可用的全屏弹出，但仍保留系统允许的通知、
  响铃及点通知进入提醒界面；锁屏通知和全屏页默认隐藏事项正文，解锁后再显示。
- 到点使用 `AlarmManager.setAlarmClock` + 系统 `PendingIntent`，进程退出/锁屏仍由系统投递；
  持续响铃用 `mediaPlayback` 前台服务，默认 60 秒后停止发声并保留通知。
