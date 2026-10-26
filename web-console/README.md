# MemorialTitles Web Console

[中文](#中文) · [English](#english)

## 中文

MemorialTitles 的网页管理界面，方便管理称号和玩家收藏。

### 功能

- 编辑文字、颜色、渐变，并预览称号效果。
- 批量发放或撤销称号，查看持有人与操作记录。
- 管理首登奖励、展示设置、备份和逐项撤回。
- 档案和玩家按完整结果排序后分页；玩家提供全部 / 在线 / 离线分类，历史标签使用服务端全量计数。

### 使用

此目录用于独立运行模式的网页界面，随 MemorialTitles 构建打包。接入 PixelMC Manager 时，使用 Manager 的扩展页面。

本界面的 `/api`、账号、会话和 CSRF 由模组内嵌网页服务提供；不要用 Manager 的认证或路由文件覆盖本目录。开发使用 `npm ci`、`npm run dev`；正式运行使用包含构建资源的模组 JAR。`npm run build` 与 `npm run test:sites` 验证静态资源打包。

## English

The web administration interface for MemorialTitles and player title collections.

### Features

- Edit text, colors, and gradients with title previews.
- Grant or revoke titles in batches and browse holders and operation history.
- Manage first-join rewards, display settings, backups, and selective undo.
- Sort complete archive/player results before pagination, filter online/offline players, and read full history-category totals from the service.

### Usage

This directory provides the interface for standalone mode and is packaged with MemorialTitles. In Manager mode, use the extension pages in PixelMC Manager.

The mod's embedded service owns `/api`, accounts, sessions, and CSRF. Preserve these standalone authentication and routing files when sharing components with Manager. Use `npm ci` and `npm run dev` for development; deploy the mod JAR containing the built UI. Run `npm run build` and `npm run test:sites` to verify the static package.

---

原作者 / Original author: [zcpu · PixelMC](https://github.com/zcpu954861).

[AGPL-3.0-only](../LICENSE) · [Minecraft linking permission](../LICENSE-EXCEPTION.md).
保留版权与署名；分发修改版或使用修改版提供联网服务时，按许可证提供对应源码。
Retain copyright and attribution; provide corresponding source when distributing modifications or offering modified software over a network, as required by the license.
