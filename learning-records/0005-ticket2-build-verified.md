# 0005 · #2 实况验收：修好 3 处硬伤，编译通过+7单测全绿+APK 出包

- 日期：2026-11-04
- 状态：进行中（#2 未关，核心缺口=模型未随包 + 端到端未验）

## 背景

YG 在另一个会话（dsh-purge）里已把 #2 推进：搭了 Kotlin/Compose 工程骨架、领域层 TDD、sherpa-onnx 接缝、闹钟代码。但工程编译不过。本轮我以"老师+验收人"身份核实真实状态并修复，而非重写。

## 修的 3 处硬伤（真 bug，非猜测）

1. **命名空间错配**：`build.gradle.kts` namespace=`com.voicetodo.app`，但所有 Kotlin 包是 `com.voicetodo`，`AlarmReceiver` import `com.voicetodo.R` → R 类找不到 → 连锁崩掉整片编译。
   → 改 namespace=`com.voicetodo`（applicationId 保持 `com.voicetodo.app` 不动）。
2. **缺依赖**：`TodoScreen` 用 `collectAsStateWithLifecycle`（`androidx.lifecycle.compose`），但只引了 `lifecycle-runtime-ktx`，缺 `lifecycle-runtime-compose:2.8.2`。
   → 补依赖。前两处一修，20 个报错塌成 1 个。
3. **变量遮蔽**：`TodoViewModel(app: Application)` 里 `private val app = app as VoiceTodoApp`，右侧 `app` 解析到属性自身而非构造参数 → 类型退化成 Application → `app.scheduler` unresolved。
   → 属性改名 `appContext`。

## 第 4 处：中文路径导致测试 ClassNotFoundException

- 症状：编译过、测试类也生成了，但 test worker 报 `ClassNotFoundException`。
- 根因：项目路径 `E:\AI工程\语音提醒软件开发` 含中文；Windows 默认 GBK（user.language=zh），Gradle test worker 的 classpath 响应文件被解析成乱码。
- 绕法（有效）：建 ASCII junction `C:\dev\voicetodo` → 项目，从该路径跑 Gradle。`gradle.properties` 已有 `-Dfile.encoding=UTF-8` + `android.overridePathCheck=true`，但不足以救 test worker。
- 尝试过但无效：在 build.gradle.kts 给 Test 任务加 systemProperty/jvmArgs file.encoding、doFirst 写 java.class.path——仍用 @response 文件，无效。
- **结论：从 C:\dev\voicetodo 跑测试是当前唯一可靠路径**（编译/打包在原中文路径下也可，测试必须走 junction）。

## AAR 修复

- 原 `sherpa-onnx-1.13.8.aar` 是**坏的下载**（50MB，多镜像断点续传写坏 zip，`invalid stored block lengths`）。
- `.work\aar2` 里有解压出来的完整内容（classes.jar 128 类、OfflineRecognizer 在、libonnxruntime.so 合法 ELF 22MB）。
- 用 zip 重新打包 aar2 → 合法 AAR（3.3MB 压缩后），替换 app/libs。→ 编译通过，APK 内确认有 `lib/arm64-v8a/libonnxruntime.so`。

## 当前验收状态（#2 六条）

| # | 标准 | 状态 |
|---|---|---|
| 1 | 可构建APK/minSdk29/模型许可记录 | APK✅(45MB) minSdk29✅；**模型未随包❌**、许可记录未做❌ |
| 2 | 断网冷启动真实随包模型识别固定普通话 | ❌ 模型 assets 空，无法验证 |
| 3 | 手选→持久化；取消不建；重开可见 | ✅ 单测 7/7 全绿 |
| 4 | 真实系统闹钟触发+通知+声音 | ⚠️ 代码在，未真机/模拟器验证 |
| 5 | 权限最小检查不误报 | ✅ 代码+UI 能力展示 |
| 6 | 可重复演示+集成测试结果 | ⚠️ 单测✅，仪器测试未跑 |

## 环境事实（供后续复用）

- JDK：`C:\Users\User\.jdks\jdk-17.0.20.1+1`（必须设 JAVA_HOME）
- Android SDK：`C:\Users\User\AppData\Local\Android\Sdk`
- ASCII junction：`C:\dev\voicetodo` → 项目（测试用）
- 命令：`cd C:\dev\voicetodo; $env:JAVA_HOME=...; gradlew.bat :app:testDebugUnitTest --rerun-tasks`
- APK 产出：`app\build\outputs\apk\debug\app-debug.apk`（45MB，含 arm64-v8a so）

## 关键缺口 / 下一步

- **模型缺失是硬阻塞**：`paraformer-zh-small.tar.bz2` 只下到 19MB（应 64MB+，残缺）。需重新完整下载 sherpa-onnx Paraformer-zh-small（model.int8.onnx + tokens.txt），解压到 `app/src/main/assets/sherpa/`，并记录版本+许可（Apache-2.0 需核对权重许可）。
- 之后：截一段普通话 WAV 做固定测试音频 → 仪器测试/模拟器验证离线识别 → 验证真实闹钟 → 再谈真机（#3）。
- 提醒 YG：GitHub 上 #2 仍 OPEN、未 assignee、**所有代码未 commit**（本地仅有脚手架 commit）。需要提醒他提交并同步票面。

## 教学映射

- 这一整轮是五步循环"做一块→验一块"的活教材：编译错误→找第一条真错误→修根因→连锁消失；测试红→查 classpath→绕过环境坑→全绿；出包确认产物。
- 对 YG 的核心教学点：**"20 个报错"往往只是 1 个根因的连锁**；验收要看**真实证据**（测试 XML、APK 里的 so、assets 是否有模型），不看"AI 说做完了"。
