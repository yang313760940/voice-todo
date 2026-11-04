# 0002 · 仓库真相：#1 规格 + #2 实施票已存在，用真票练“读懂”

- 日期：2026-11-04
- 状态：进行中

## 发现（重要，修正了此前的教学假设）

此前 learning-record 0001 假设“仓库尚无 issue”。实际 GitHub 上已有：
- **#1**：`Android 10+ 语音提醒应用首版…` —— 一张完整 **spec**（含 G1/G3 硬边界、Problem/Solution/70 条 User Stories/Implementation Decisions/Testing Decisions/Out of Scope/Further Notes）。label `ready-for-agent`。
- **#2**：`最小闭环：随包离线转写一条事项并触发系统闹钟` —— 挂在 #1 下的**实施票**，`Parent #1`，`blocked_by=0` 可立即开工，六条可打勾验收标准，四零件齐全。label `ready-for-agent`。
- 技术栈已在 #1 定：原生 Kotlin + Jetpack Compose + Material 3 + Room/DataStore；离线 sherpa-onnx 普通话；minSdk 29。

## 教了什么

- 第 3 课：用真票 #2 作教材，教“读懂一张 issue”——四零件逐一核对；重点讲**验收标准=提前堵死“假成功”**（“非假转写”“不是应用内计时器模拟”）。
- 引入 spec / ticket / Parent 子票概念。
- 抛出一个真实决策点：**#2 的边界写“真机验收另开票”，但 YG 有真机** → 让他练习“说不/说改”，选 A(保持原样) 或 B(把真机验收并进 #2)。

## 关键决策

- 不为了“有动手”而硬建一张新票。真实存在的 #2 是更高质量的教学素材，且“读懂 + 质疑 + 说改”正对 mission（能读懂 AI 产出、做决策）。
- 把“改票”作为下一课的真实操作，等 YG 先做 A/B 决定。

## 下一步（ZPD）

- 第 4 课：根据 YG 的 A/B 选择，真的用 `gh` 改 GitHub 上的 #2（加/改边界与验收），并教打标签、指派人（`--add-assignee @me` 认领）、看 blocked_by 判断“能否开工”。
- 之后：走一遍 tdd 红-绿（#2 的第二条验收“非假转写”天然可先写失败测试）。

## 风险 / 待确认

- #2 验收标准里的“模拟器证据 vs 真机验收”是 YG 的第一个真实决策，等他拍板。
- 零基础：#2 里 PendingIntent、持久化、minSdk、ABI 等术语，需在需要时用大白话补讲。
- 需要确认 YG 是否理解“改票 ≠ 改代码”，改票只是改需求/边界的记录。
