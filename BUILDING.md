# 构建 / Building

本分支：Minecraft 1.21.1 / Fabric / Java 21。This branch targets Minecraft 1.21.1 / Fabric / Java 21.

使用 JDK 21（设置 `JAVA_HOME`）、Node.js 22+ 和 npm，运行 `./gradlew build`；Windows 使用 `gradlew.bat build`。
Use JDK 21 (`JAVA_HOME`), Node.js 22+ and npm; run `./gradlew build` (`gradlew.bat build` on Windows).

Gradle 安装锁定的前端依赖，构建完整网页并嵌入 MariaDB JDBC 3.5.6。产物位于 `build/libs/`；客户端和服务端使用同一个目标 JAR。普通构建不需要本地缓存导出、数据库、Manager 或 Sites 项目配置。
Gradle installs locked frontend packages, builds the complete web UI and embeds MariaDB JDBC 3.5.6. Outputs are in `build/libs/`; use the same target JAR on clients and server. A normal build requires no exported local cache, database, Manager or Sites project configuration.

两端均安装 Fabric Loader 0.16.14+ 与对应 Minecraft 版本的 Fabric API。
Install Fabric Loader 0.16.14+ and matching Fabric API on both sides.
