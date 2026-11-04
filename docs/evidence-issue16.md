# #16 · AI 多草稿实施与自动化验收

日期：2026-11-05（Asia/Shanghai）。基准：`05c70f6`（#15 单草稿闭环）。
实现分支：`codex/issue16-ai-multi-draft`。原工作区有其他会话并发修改，本票在独立工作树完成。

## 交付行为

- 在线请求返回 `{"drafts":[...]}`，每条草稿有本地产生的稳定身份。兼容旧单对象响应；不自动重试或发送积压文字。
- 核对页保留原始识别文字；可逐条选择、编辑标题/计划日期/截止/指定或相对提醒、修改象限、删除草稿。
- 支持日、周多选星期、月指定日期的重复建议解析、编辑、本地校验和持久化；重复选择同一类型不重置具体星期或日期。
- 每条含糊时间都保持待澄清；须补充明确提醒，或明确选择暂不设提醒。不能用其他条草稿的确认绕过本条澄清。
- 批量确认先校验全部草稿，再用一个 Room 事务插入所有保留项。数据库中途失败整批回滚，确认前/回滚后无新增事项或调度。
- 保存成功后才请求系统调度。重试沿用身份，不覆盖已保存事项、不重复安排成功提醒；系统拒绝的提醒保留 NotReady 并可重试。
- Room 3→4 新增可空重复建议字段；旧事项默认无重复建议，旧事项/提醒/投递记录的升级测试通过。

## 验收映射

| #16 判据 | 自动化证据 |
| --- | --- |
| 一句话三草稿、含重复安排、逐条改删 | `MultiDraftHttpTest`、`AiDraftFlowTest` 的真实本机 HTTP → Compose → Room 路径 |
| 批量原子保存、重复点击/重试、确认前无副作用 | `BatchDraftConfirmationTest`：第二条插入时通过 SQLite 故障注入令整批失败，确认无事项/闹钟；去除故障重试仅创建一次；HTTP + Compose 连续确认 |
| 含糊时间逐条确认、明天买菜只计划日期 | HTTP + Compose 全三条确认测试，未澄清第三条时整批不可提交；确认后买菜无截止或提醒 |
| 原始文字可见、象限仅建议可修改 | 原文可见断言、真实象限点击、保存后读取用户选择 |
| HTTP 端到端多草稿、部分删除、重复确认、取消 | `AiDraftFlowTest` 新增四条 HTTP 场景，取消核对后保留文字且无事项/闹钟 |

## 最终验证

- **JVM：21 个测试类、116 项，0 失败、0 错误、0 跳过。** 最终修复后强制重新执行完整 `:app:testDebugUnitTest --rerun`，没有测试过滤。
- **lint：0 error / 25 warning。**
- **`:app:assembleDebug`、`:app:assembleDebugAndroidTest` 均 BUILD SUCCESSFUL。**
- **`git diff --check` 通过。**
- 分别在多草稿接入、批量确认、系统拒绝、错误字段和重复类型编辑处执行红→绿；最后两个审查方向均复核为 0 剩余发现。

本机验证使用 `D:\AndroidDev\jdk17` 与 ASCII 联接 `C:\voicetodo-issue16`。
首次并行执行时 JVM 因本机原生内存不足退出；之后改为串行单 worker，测试 worker 堆临时设为 512 MiB、每 6 个测试类重新派生，完整测试重跑通过。只清理了该崩溃任务的已确认孤儿测试进程；未终止其他会话进程，未修改仓库默认内存配置。

最终检查入口（PowerShell；设置 `JAVA_HOME` 后）：

```powershell
.\gradlew.bat :app:testDebugUnitTest --rerun --no-daemon --no-parallel --max-workers=1
.\gradlew.bat :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --no-parallel --max-workers=1
```

| 产物 | SHA-256 |
| --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | `C9C8AFFD219B4A058D458EB18A0590D0838E00767CF625B62764EC7D61B544FC` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | `27B94341B8419134961B4941E2CC2B49A5F2F87DCCE7FFB1D1CA9DCE8C43CB01` |

## 审查

- **Standards：**初审 0 硬违规、1 项重复校验维护建议；已共用提醒结构校验和时间计算，复核剩余 0。
- **Spec：**初审发现点击已选中重复类型会重置规则；HTTP + Compose 先复现失败，再修复，复核剩余 0。

## 范围与真实状态

#15 已关闭，本票无 OPEN blocker。#7 不是本票依赖：本票保存重复建议；后续重复实例的生成、完成/漏做后的连续调度由 #7 实施，界面已明确说明能力尚未就绪。#8 的系列编辑范围也不在此票。

模拟 HTTP 证明客户端契约和本地闭环，不证明真实供应商的自然语言解析质量。AndroidTest APK 已构建，但本票没有执行设备仪器测试、安装到维护者手机或宣称跨厂商可靠性通过。父规格 #1 尚有其他实施票未完成。
