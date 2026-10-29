# MemorialTitles Fabric 1.21.1

本分支支持 Minecraft 1.21.1 / Fabric，Java 21。保留 AGPLv3 与原作者署名。
This branch targets Minecraft 1.21.1 / Fabric on Java 21; retain AGPLv3 and original attribution.

移植保持称号、菜单、渲染、隐私、奖励收据、通知、提醒、异步 IO、独立 MariaDB 和统一 Manager 存储行为。`forge` Java 包保留历史 API 名称，不表示运行时依赖 Forge。
Preserve titles, menus, rendering, privacy, reward receipts, notices, reminders, async IO, standalone MariaDB and unified Manager storage. The historic `forge` Java package is retained for API compatibility and does not imply a Forge runtime dependency.

仅使用隔离本地测试实例；不得提交运行配置、凭据、数据库或玩家资料。常规构建为 `gradlew build`，部署参见 README。
Use isolated local test instances; never commit runtime configuration, credentials, databases or player data. Build with `gradlew build`; deployment is documented in README.
