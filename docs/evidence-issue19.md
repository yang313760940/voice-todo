# Issue #19 实施记录

## 实现

- `VoiceTodoNavigationHost` 提供“待办｜四象限｜番茄｜我的”四个底部页面。待办与四象限共享 `TodoViewModel`，番茄使用独立 `PomodoroViewModel`，设置使用 `SettingsViewModel`；切换页面时页面数据与业务状态仍由这些 ViewModel 保持。
- “我的”集中提供提醒系统设置、番茄参数、主题与字体、语音与 AI、权限状态、数据管理、回收站和关于入口。权限、数据与回收站依赖的 #10、#18、#20 尚未完成，因此对应页面明确标注依赖票并保留可达入口，不把占位页描述为已实现功能。
- `AppPreferencesStore` 同步保存主题和字体偏好；`VoiceTodoTheme` 立即重绘浅色、深色或跟随系统主题，并缩放 Material 字体档位。番茄时长与长休息间隔通过 `FocusUseCases` 立即写入持久化仓库。
- 关于页读取安装包版本，并说明事项、专注记录、偏好与回收站数据保存在本机，离线识别在本机完成，在线 AI 只发送用户主动触发时的当前文字、日期、时间和时区；录音、历史事项、API 密钥和权限状态不进入 AI 上下文。
- #17 的番茄模块合并到当前数据库结构后使用 v3→v4 显式迁移，保留既有 AutoMigration 链、待办数据和升级覆盖。

## 验证

- `AppNavigationTest`：四个页签往返切换，待办草稿状态保持。
- `SettingsScreenTest`：八组“我的”内容可达，主题与字体选择即时更新，番茄参数即时更新，各入口回调正确；关于页版本、本地数据边界与 AI 发送范围可见。
- `SettingsViewModelTest`：主题、字体和番茄设置跨独立存储读取后仍保持，权限状态正确进入页面状态。
- `AppPreferencesStoreTest`：偏好跨存储实例重建保持，未知旧值安全回退默认档位。
- `RoomFocusRepositoryTest` / `RoomReminderUpgradeTest`：验证 v3→v4 迁移与既有升级路径。
- 当前证据轮次：JVM 26 个测试套件、120 项测试全部通过；`:app:lintDebug`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest` 通过，已生成 debug APK。

## 边界

- 验证基于 Robolectric/API 33、JVM 构建与 lint，没有真机验收。
- #10、#18、#20 仍为开放依赖票；“我的”只交付稳定入口和当前可用的权限状态/已有功能，不包含完整权限引导、数据管理或回收站数据操作。
- 工作树内另有 #16 的未完成测试文件；该文件引用的整理 API 尚未完成，因此全量 JVM 证据轮次临时排除了该文件，随后已原样恢复。
