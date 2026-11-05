# #16 · AI 多草稿重新移植与验收

日期：2026-11-05（Asia/Shanghai）。

- 基准：`main` / `6d1f280`。
- 实现分支：`codex/issue16-main-port`。
- 旧 PR #26 基于数据库 schema 4；当前 `main` 为 schema 9，未直接合并旧分支。

## 交付行为

- 在线 AI 返回 `{"drafts":[...]}`，每条草稿有独立稳定身份；旧单对象响应仍兼容。
- 核对页保留原始识别文字，可逐条选择、编辑、删除，并同步显示共几条待确认事项。
- 每条草稿可保留待澄清信息；存在未澄清、未选择“暂不设提醒”的草稿时，整批确认按钮不可用。
- 建议象限可修改，并与用户原选择一起保存；AI 建议本身只作展示。
- 支持每天、每周多选星期、每月指定日期的重复建议；重复时刻可编辑，保存后使用当前 `RecurrenceRule` 引擎生成后续实例。
- 批量确认先校验全部草稿，再以一个 Room 事务插入普通事项、重复模板和首批重复实例；数据库中途失败整批回滚。
- 保存成功后才请求系统调度；调度失败保留 `NotReady`，使用同一草稿身份重试时不会重复创建或重复安排已成功提醒。
- 本次没有新增数据库字段，数据库版本保持 9，无 schema 迁移。

## 验收映射

| #16 判据 | 自动化证据 |
| --- | --- |
| 一句话生成三条草稿、含重复安排、逐条改删 | `MultiDraftHttpTest`、`AiDraftFlowTest` 的本机 HTTP → Compose → Room 路径 |
| 批量原子保存、重复点击不重复创建 | `BatchDraftConfirmationTest` 的 SQLite 触发器故障注入、整批回滚和重复确认 |
| 确认前无事项、无闹钟；系统拒绝可重试 | `BatchDraftConfirmationTest` 的调度拒绝与重试断言 |
| 含糊时间保持待澄清；“明天买菜”只有计划日期 | `AiDraftFlowTest` 的 HTTP 批量确认路径 |
| 原文可见、象限可改、重复规则可改 | `AiDraftFlowTest` 的 Compose 交互与 `AiDraftParserTest` |
| 多草稿、部分删除、重复点击、取消 | `AiDraftFlowTest` 四条多草稿场景 |

## 最终自动化验证

- **JVM：49 个测试类、237 项，0 失败、0 错误、0 跳过。**
- **`:app:lintDebug`：0 error / 26 warning。**
- **`:app:assembleDebug`、`:app:assembleDebugAndroidTest`：BUILD SUCCESSFUL。**
- **`git diff --check`：通过。**

| 产物 | SHA-256 |
| --- | --- |
| `app-debug.apk` | `B5F4CC6CAD311409CA0027FD298E3C68C67FA431C407E4A26D572B7C3A2AB4FB` |
| `app-debug-androidTest.apk` | `90328542907EA76CD2CEC654E823A12BD2712FF3EE76663E0BC29819D509E4D6` |

## 12X 真机回归

设备：Xiaomi 12X `2112123AC`，MIUI 13 `V13.0.10.0.SLDCNXM`，Android 12 / API 31；
NoActive 保持关闭，未执行系统版本或厂商矩阵。

- 新 APK 使用 `adb install -r -t` 覆盖安装，保留应用数据；冷启动 `MainActivity` 成功。
- 锁屏亮屏提醒：通过。
- 锁屏息屏提醒：通过。
- 普通进程退出 + 锁屏亮屏提醒：通过。
- 每轮核对系统广播、响铃前台服务、通知、`USAGE_ALARM` 音频、全屏提醒和关闭后停声。

证据目录：`.work/issue16-12x/`（本机临时证据，不随源码提交）。

## 边界

- 模拟 HTTP 验证客户端契约和本地闭环，不证明真实 AI 供应商的自然语言拆分质量。
- 真机回归只覆盖当前 12X 基线和提醒链路，不代表 #24 的设备矩阵或 #1 整体完成。
- 回收站仍由 #20 独立重建。
