# 离线工具链、模型与许可（票 #2 最小闭环）

本文件记录构建最小 APK 所需工具链、随包原生库与模型的**来源、具体版本、许可证**，供验收与后续维护。

## 构建工具链

| 组件 | 版本 | 来源 | 说明 |
| --- | --- | --- | --- |
| JDK | Temurin 17.0.20.1+1 | https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/ | AGP 8.5.2 要求 JDK 17 |
| Gradle | 8.7 | https://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip（SHA-256 `544c35d6bd849ae8a5ed0bcea39ba677dc40f49df7d1835561582da2009b961d`，与官方一致） | wrapper 已固定 |
| Android Gradle Plugin | 8.5.2 | Google Maven | 见 `build.gradle.kts` |
| Kotlin | 1.9.24 | Maven Central | Compose 编译器扩展 1.5.14 |
| Compose BOM | 2024.06.00 | Google Maven | Material3 |
| Android SDK | Platform 34、Build-Tools 34.0.0 | Android SDK Manager（`dl.google.com`） | 本机 `%LOCALAPPDATA%\Android\Sdk` |
| Emulator | 37.2.12，系统镜像 `android-37.0;google_apis_playstore;x86_64` | SDK Manager | 仅用于本票模拟器证据 |
| minSdk / targetSdk / compileSdk | 29 / 34 / 34 | — | 最低 Android 10 |

> 说明：本机为 Windows 且工程路径含中文。Gradle 的测试 worker 会用系统默认编码解析 `@argfile`
> 中的 classpath，中文路径导致 `ClassNotFoundException`。解决方式：用 ASCII 联接
> `mklink /J C:\voicetodo "E:\AI工程\语音提醒软件开发"`，在 `C:\voicetodo` 下执行构建/测试；
> 源码仍保留在原目录。根 `build.gradle.kts` 不改变构建目录，避免跨盘符根目录冲突。

## 离线识别原生库

**sherpa-onnx 官方 Android AAR**

- 制品：`sherpa-onnx-1.13.8.aar`（47.81 MB）
- 来源：k2-fsa/sherpa-onnx GitHub Release `v1.13.8`
  （资产 URL：`https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar`）
- 放置：`app/libs/sherpa-onnx-1.13.8.aar`，以 `implementation(files("libs/sherpa-onnx-1.13.8.aar"))` 引入
- 完整性：ZIP 含 26 项、含有效 EOCD；4 个 ABI：
  `arm64-v8a / armeabi-v7a / x86 / x86_64`
- 包内原生库：`libonnxruntime.so`、`libsherpa-onnx-c-api.so`、`libsherpa-onnx-cxx-api.so`、`libsherpa-onnx-jni.so`
- 许可证：sherpa-onnx 项目代码为 **Apache-2.0**（https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE）；
  onnxruntime 亦为 MIT。第三方预训练权重按各自模型卡许可（见下）。

## 离线普通话模型

**Paraformer-zh-small（sherpa-onnx 转换版）**

- 制品：`model.int8.onnx`（**81,828,675 字节**，78.04 MB）+ `tokens.txt`（**75,352 字节**）
- 目录名：`sherpa-onnx-paraformer-zh-small-2024-03-09`
- 来源（按维护者要求，统一使用 HuggingFace 镜像）：
  - `https://hf-mirror.com/csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09/resolve/main/model.int8.onnx`
  - `https://hf-mirror.com/csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09/resolve/main/tokens.txt`
- 放置：`app/src/main/assets/sherpa/model.int8.onnx`、`app/src/main/assets/sherpa/tokens.txt`
- 完整性：与 GitHub release 同源同哈希，
  `model.int8.onnx` SHA-256 = `3EF6C19369B912F7CAF3CEF8E545C5CCD1A33D9D7EC792A46668DC41C4B229EC`
  （模型字节数 81828675、词表 75352，均已实测核对一致）
- 运行时：模型随安装包（assets）交付，首次启动**无需联网下载**。应用首次使用时将 assets 中模型复制到
  `filesDir/sherpa-model/` 后再初始化 `OfflineRecognizer`。

### 权重许可证（单独核对，非沿用 AAR 许可）

sherpa-onnx 的 Apache-2.0 只覆盖**推理框架代码**，不自动代表随附**模型权重**的许可。本切片单独核对模型权重的上游许可：

- 模型转换链：`csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09`（HF/HF 镜像）
  → 其 README 注明来自 `crazyant/speech_paraformer_asr_nat-zh-cn-16k-common-vocab8358-onnx`（ModelScope）
  → 该仓库标注为 `damo/speech_paraformer_asr_nat-zh-cn-16k-common-vocab8358-tensorflow1` 的 ONNX 量化导出。
- **权重上游许可**：ModelScope 模型卡 `damo/speech_paraformer_asr_nat-zh-cn-16k-common-vocab8358-tensorflow1`
  的 `License` 字段为 **Apache License 2.0**（标签 FunASR / Paraformer / Alibaba / INTERSPEECH 2022）。
  API 核对：`https://modelscope.cn/api/v1/models/damo/speech_paraformer_asr_nat-zh-cn-16k-common-vocab8358-tensorflow1` → `License: "Apache License 2.0"`。
- 相关代码许可：FunASR 工具链为 **MIT**（https://github.com/modelscope/FunASR/blob/main/LICENSE）；
  sherpa-onnx 为 **Apache-2.0**（https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE）；
  onnxruntime 为 **MIT**。
- 论文：Gao et al., *Paraformer: Fast and Accurate Parallel Transformer for Non-autoregressive End-to-end Speech Recognition*, INTERSPEECH 2022。
- 说明：中上游 `crazyant` 镜像仓库自身的 LICENSE 字段为空，故以最上游 `damo/...-tensorflow1` 模型卡的 Apache-2.0 为准；
  若用于商业再分发，仍建议再对最上游模型卡与数据集条款做一次法务复核。

## 固定测试音频

- `app/src/androidTest/assets/testwavs/0.wav`
- 格式：单声道 / 16-bit / 16000 Hz / 89,784 帧 / 5.61 s；非零采样占比 0.989，峰值 14277，RMS 2891.5（真实语音，非静音）
- SHA-256：`668bf8df51a10027b84d5d8816a1ce11ae93545538dc05cfe2aa6811d399c250`
- 来源：同模型仓库 `test_wavs/0.wav`（hf-mirror），用于断网真实识别断言。

## 关于替代获取路径的说明（网络受限时）

本机对 `github.com` / `release-assets.githubusercontent.com` 的下载在部分时段被重置，curl 亦报
`CRYPT_E_REVOCATION_OFFLINE`。可用 `curl --ssl-no-revoke` 规避证书吊销检查；模型优先走 hf-mirror。
若官方 AAR 无法取得，另存在第三方 Maven 包装 `com.bihe0832.android:lib-sherpa-onnx`（GPL 应用工厂，
API 同 `com.k2fsa.sherpa.onnx`），但为保持许可与来源清晰，**本票采用官方 AAR**。
