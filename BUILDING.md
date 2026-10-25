# 构建 / Building

使用 JDK 17（设置 `JAVA_HOME`）、Node.js 22 和 npm。
Use JDK 17 (`JAVA_HOME`), Node.js 22 and npm.

```sh
./gradlew build
```

Windows 使用 `gradlew.bat build`。Gradle 会安装锁定的前端依赖、构建网页，并从 Maven 获取 MariaDB JDBC 3.5.6；不需要本地 `libs/` 或导出的 Gradle 缓存。产物位于 `build/libs/`。
On Windows use `gradlew.bat build`. Gradle installs locked frontend packages, builds the web UI and resolves MariaDB JDBC 3.5.6 from Maven. Local `libs/` and exported Gradle caches are not required. Outputs are in `build/libs/`.

`-PcompatRuntime` 仅用于本地兼容性运行，需要自行把 Forge 1.20.1 的 TAB 5.2.1 与 Curtain 1.3.2 放到 `references/`；正常构建不需要这两个文件。
Optional `-PcompatRuntime` development runs need upstream TAB 5.2.1 and Curtain 1.3.2 JARs in `references/`. A normal build does not require them.
