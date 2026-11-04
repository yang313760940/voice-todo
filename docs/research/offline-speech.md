# 安卓首版纯离线中文语音识别：技术事实

核实日期：2026-11-04。仅作独立事实研究，不选择产品方案；未下载模型、编写应用或进行真机测试。已检查技能目录并使用 research 技能；当前执行者已是受委派研究代理，直接完成一手资料检索。目标文件经 glob 检查原先不存在。

## 两条路径的条件

| 项目 | Android SpeechRecognizer 设备端识别 | 应用内置离线模型（以 sherpa-onnx 为例） |
| --- | --- | --- |
| 离线能力来源 | 手机上实际存在的设备端识别服务及其语言模型。系统离线不保证每台手机可用，也不保证中文已就绪。[1][3] | 应用集成推理库与具体模型；官方 Android 文档明确识别在手机本地处理，无需互联网。[4] |
| API 与依赖 | API 31 新增 `isOnDeviceRecognitionAvailable`、`createOnDeviceSpeechRecognizer`；前者返回 false 时，后者失败。API 33 新增请求支持检查和模型下载接口。[1][2] | 官方提供 Kotlin/Java 等接口、Android 原生库和示例；需匹配 ABI、模型格式、词表及模型配置。构建教程把模型放入应用 assets。[5][6] |
| 中文覆盖 | 服务可用不等于中文模型已安装。`RecognitionSupport` 区分已安装、待下载、支持但需下载、远程支持语言；还存在语言不支持、语言暂不可用及支持查询失败等错误。[1][3] | 由选定模型决定。官方 Android 教程示例为中英双语 Zipformer；SenseVoice 文档列出普通话、粤语等语言及 Android 支持。这些事实不代表任意中文口音或业务词汇均达到目标准确率。[5][6] |
| 包体与运行资源 | 应用不必自行携带该服务的识别模型，但模型是否预装、是否需要联网补齐由实际设备服务状态决定。[1][3] | 应用承担模型、词表、原生库的分发和存储；延迟、内存、耗电及准确率需用具体模型和手机验证，本文无实测结论。[5] |

普通 `createSpeechRecognizer` 不是纯离线保证：官方类说明指出实现可能把音频发送到远端，并说明该 API 不面向持续识别。`RecognizerIntent.EXTRA_PREFER_OFFLINE` 默认 false，且官方明确该参数可能因识别器实现而无效。因此，仅设置该参数不足以证明音频全程留在本机。[1][7]

使用系统接口还涉及 `RECORD_AUDIO` 权限、主线程调用、用毕 `destroy()`；面向 Android 11 的服务交互文档要求声明 RecognitionService 的包可见性查询。这些是集成条件，不构成服务或中文模型可用性的保证。[1]

## 随包模型与首次下载的区别

- **模型随完整安装包交付**：官方 sherpa-onnx 教程是在开发/构建阶段取得模型并放入 assets，再生成 APK。只要安装时所需资源已经完整到位，首次运行识别无需再次联网取模型；开发者构建时下载模型与用户首次运行下载是不同阶段。[5]
- **首次运行再下载模型**：模型缺失时存在联网初始化步骤；完成后可以离线推理，但这不等于“安装后第一次启动就能断网识别”。系统 `triggerModelDownload` 也是下载请求，可能需要用户交互；请求发出不代表资源已就绪，应核实支持状态或下载完成结果。[1][3]
- **已预装系统模型**：某台设备可能直接离线工作；这一结果只证明该设备当时的服务与语言资源满足条件，不足以推导所有手机都满足。[1][3]

以上为资源交付条件的技术推论，不决定产品采用哪种分发方式。

## 模型大小与许可证须逐个核实

官方 Android 构建教程的具体示例 `sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20` 列出浮点 ONNX 文件约为 encoder 315M、decoder 13M、joiner 12M，另有约 55K 的词表；教程展示的 debug APK 为 329M。这是该示例及其构建配置的历史数字，不是所有离线模型的大小，也不是当前应用包体、安装占用或运行内存估算。量化版本、ABI 数量、库版本和打包压缩均需分别核实。[5]

sherpa-onnx 项目代码使用 Apache-2.0；该项目许可证不自动证明每个第三方预训练权重都采用相同许可证。具体候选模型必须核对其上游模型卡、权重许可证、再分发条件、准确版本和实际文件清单；同时检查推理依赖及附带资源的许可。本文尚未选定模型，因此没有给出模型商业使用或最终包体结论。[5][6][8]

后续技术验证仍需覆盖：断网冷启动、中文资源缺失、录音权限状态、目标手机 ABI、内存峰值、短句响应时间，以及日期/时间/人名等实际中文输入的识别误差。此处仅列出证据缺口，不制定产品验收阈值。

## 官方一手来源

1. [Android SpeechRecognizer API](https://developer.android.com/reference/android/speech/SpeechRecognizer)：服务可用性、设备端创建、权限、生命周期、错误及下载语义。
2. Android API 差异：[API 31](https://developer.android.com/sdk/api_diff/31/changes/android.speech.SpeechRecognizer)、[API 33](https://developer.android.com/sdk/api_diff/33/changes/android.speech.SpeechRecognizer)：核实新增 API 的版本。
3. [Android RecognitionSupport API](https://developer.android.com/reference/android/speech/RecognitionSupport)：本地语言资源已安装、待下载及需下载的区别。
4. [sherpa-onnx 官方 Android 文档](https://k2-fsa.github.io/sherpa/onnx/android/index.html)：本地处理、无需联网。
5. [sherpa-onnx 官方 Android 构建教程](https://k2-fsa.github.io/sherpa/onnx/android/build-sherpa-onnx.html)：原生库、ABI、assets 模型、具体示例大小和 APK 分析。
6. [sherpa-onnx 官方 SenseVoice 文档](https://k2-fsa.github.io/sherpa/onnx/sense-voice/index.html)：中文语言覆盖、接口和 Android 支持，并链接上游模型项目。
7. [Android RecognizerIntent：EXTRA_PREFER_OFFLINE](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_PREFER_OFFLINE)：默认值及实现可能忽略参数的限制。
8. [sherpa-onnx 官方 LICENSE](https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE)：项目代码 Apache-2.0 许可证。

取证方式：sherpa 官方页面通过 web_fetch 读取；Android 官方页面直接抓取失败后，通过可用 Searchix/Tavily 网页提取工具读取同一官方网址及 API 差异页。未以第三方评测替代官方 API 契约。
