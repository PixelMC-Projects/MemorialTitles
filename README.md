# MemorialTitles · 纪念称号

[中文](#中文) · [English](#english)

## 中文

为玩家收藏值得纪念的称号，并在头顶、聊天和 TAB 中展示。

### 功能

- 支持分段颜色、渐变和文字样式，可用资源包自定义菜单图标。
- 每人同时佩戴一个称号，可独立选择头顶、聊天和 TAB 的显示位置。
- 收藏公开与佩戴展示分开设置，保留玩家的隐私选择。
- 网页管理台支持编辑、批量发放、撤销和查看持有人。
- 支持首登纪念奖励；进服后的通知延迟发送，离线通知会保留。
- 跨存档保存收藏，支持逐项撤回、资料清理和备份。

### 使用

适用于 Minecraft **1.20.1 / Forge**，服务端和客户端均需安装，需要 MariaDB。

玩家使用 `/mt` 打开菜单；快捷键默认未绑定。未佩戴称号会定时提醒，可用 `/mt reminder off` 永久关闭，`/mt reminder on` 重新开启。管理员使用网页管理台，也可通过指令管理。可独立运行，或接入 PixelMC Manager。

### 部署

独立运行：

1. 准备 MariaDB：创建 `utf8mb4` 的专用数据库和账号，授予该库建表、改表及读写权限。
2. 客户端和 Forge 服务端均安装模组。首次启动生成配置后正常关服，填写 `config/memorialtitles/server.json` 的 `database.url`、`database.user`、`database.password` 和可写的 `backups` 路径，保持 `mode` 为 `database`。下方有配置示例。
3. 在 `config/memorialtitles/web.json` 设置 `enabled: true`、`bind: "0.0.0.0"` 和网页 `port`（默认 `18765`），并在面板 / Docker 映射该端口。直接使用 IP 时 `publicUrl` 可留空；使用域名或反向代理时填实际网页根地址。
4. 重新开服，等待数据库连接成功，并让管理员玩家至少成功进服一次。在**真实服务端控制台**执行 `mtconsole grant 玩家名或UUID`，再执行 `mtconsole web-user 玩家名或UUID 网页用户名`；控制台会显示生成的初始密码。
5. 打开 `http://服务器IP:网页端口`，使用刚创建的网页账号登录。需要重置密码时，在控制台使用 `mtconsole web-password 网页用户名`。

接入 **PixelMC Manager** 时，配套安装 Manager Forge 模组与常驻网页服务，将 `server.json` 的 `mode` 改为 `manager`，保留其余字段，并配置 `config/pixelmcmanager-bridge.json`。网页账号和整合包 / 存档绑定按 [Manager 部署说明](https://github.com/PixelMC-Projects/pixelmcmanager-forge/blob/mc-1.20.1/manager-service/README.md) 完成；此模式的网页和账号由 Manager 管理。

## English

Collect memorable player titles and display them above names, in chat, and in the player list.

### Features

- Create colored, gradient, and styled titles, with resource-pack support for menu icons.
- Wear one title at a time and choose its visibility above the name, in chat, and in TAB.
- Control collection privacy separately from equipped-title visibility.
- Edit titles, grant or revoke them in batches, and browse holders through the web console.
- Offer first-join rewards, delay join-time notices, and retain notifications for offline players.
- Preserve collections across worlds, with selective undo, profile cleanup, and backups.

### Usage

For Minecraft **1.20.1 / Forge**. Install on both the server and clients; MariaDB is required.

Players open the menu with `/mt`; the optional keybind is unbound by default. Unequipped-title reminders can be permanently disabled with `/mt reminder off` and re-enabled with `/mt reminder on`. Administrators use the web console or commands. Run it independently or connect it to PixelMC Manager.

### Deployment

Standalone mode:

1. Create a dedicated MariaDB database using `utf8mb4` and an account permitted to create/alter tables and read/write that database.
2. Install the mod on clients and the Forge server. Start once to generate configuration, then stop normally. Set `database.url`, `database.user`, `database.password` and a writable `backups` path in `config/memorialtitles/server.json`; keep `mode` set to `database`. See the example below.
3. In `config/memorialtitles/web.json`, set `enabled: true`, `bind: "0.0.0.0"` and the web `port` (default `18765`). Map/open that port in your panel or Docker setup. Leave `publicUrl` empty for direct IP access, or set the actual root URL for a domain/reverse proxy.
4. Restart the server, wait for its database connection, and have the administrator join successfully once. In the **actual server console**, run `mtconsole grant PlayerNameOrUUID`, then `mtconsole web-user PlayerNameOrUUID WebUsername`. The console prints the generated initial password.
5. Visit `http://SERVER_IP:WEB_PORT` and sign in with that account. Reset its password with `mtconsole web-password WebUsername` in the server console when needed.

For **PixelMC Manager** mode, install its Forge mod and independent web service together. Set `mode` to `manager` in `server.json`, retain the other fields, and configure `config/pixelmcmanager-bridge.json`. Follow the [Manager deployment guide](https://github.com/PixelMC-Projects/pixelmcmanager-forge/blob/mc-1.20.1/manager-service/README.md) for web-account creation and modpack/world binding. Manager provides the web UI and accounts in this mode.

### Configuration example / 配置示例

`config/memorialtitles/server.json`:

```json
{
  "database": {
    "url": "jdbc:mariadb://DATABASE_HOST:3306/memorialtitles",
    "user": "memorialtitles",
    "password": "CHANGE_ME"
  },
  "backups": "memorialtitles-data/backups",
  "mode": "database"
}
```

替换数据库地址和密码；数据库在另一个容器时，使用可达的数据库容器名。备份目录应持久保存。
Replace the database host and password. For a separate database container, use its reachable container/service name. Keep the backup directory on persistent storage.

---

原作者 / Original author: [zcpu · PixelMC](https://github.com/zcpu954861).

[AGPL-3.0-only](LICENSE) · [Minecraft linking permission](LICENSE-EXCEPTION.md).
保留版权与署名；分发修改版或使用修改版提供联网服务时，按许可证提供对应源码。
Retain copyright and attribution; provide corresponding source when distributing modifications or offering modified software over a network, as required by the license.
