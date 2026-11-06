# AGENTS.md

## Agent skills

### Issue tracker

Issues and specs live as GitHub issues, managed with the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical roles, label strings equal their names. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout: `GLOSSARY.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.

## 无人值守执行手册

适用于 `codex/voice-capture-v1` 集成分支及相关实施票。无人值守时不请示、不猜测，按以下规则执行。

### 构建与测试

- 一律从 ASCII 目录联接 `C:\voicetodo` 执行 Gradle 命令，不要直接用含中文的仓库路径；中文路径会导致测试 worker `ClassNotFoundException`。
- 必须设置 `JAVA_HOME=C:\Users\User\.jdks\jdk-17.0.20.1+1`；Android SDK 位于 `C:\Users\User\AppData\Local\Android\Sdk`（由 `local.properties` 指向）。
- 并发受限时使用 `--no-daemon --max-workers=1`；开始前确认没有残留的 `java` 进程占用构建目录。
- 最低验收命令：`.\gradlew.bat testDebugUnitTest lintDebug assembleDebug`。只有全绿才算通过；不要用“只跑相关测试”代替整轮验收。

### 数据库与备份

- 任何表结构变化都要提升 Room 版本并写迁移；构建后核对生成的 schema JSON（`app/schemas/`）是否包含新列、版本号是否正确，并把新 schema 一并提交。
- 迁移必须有覆盖旧版本数据的测试；备份格式变化必须兼容旧备份，导入失败不得部分覆盖。

### 分支与提交

- 集成分支为 `codex/voice-capture-v1`，从 `origin/main` 创建；每张票完成后提交到该分支，不每票合并 `main`。
- 一次只做一张票，禁止并行开票、禁止在同一工作区同时跑两个 Gradle 构建。
- 提交信息使用 `feat|fix|test|docs|refactor: 摘要 (#票号)`；只提交本票相关文件，不顺带提交他人未完成的改动。

### 失败处理

- 任何构建、测试、lint 或迁移核对失败都立即停止本票，不继续后续票，也不把红色状态提交为完成。
- 保留证据：失败命令、关键日志片段、失败用例名；写入票据评论，日志文件放 `.work/`（已 gitignore）。
- 同一票最多重试两次；仍失败则评论原因、保持票据打开，结束本轮，下一轮再试。

### 凭据与隐私

- 真实服务凭据只从 `.secrets/voice-services.env` 读取（该目录已 gitignore）；不得打印、回显、写入日志、票据评论、提交或备份。
- 没有可用凭据时，按“适配器 + 本地假 WebSocket 测试”完成实现，并另开 `ready-for-human` 的真服务联调票。
