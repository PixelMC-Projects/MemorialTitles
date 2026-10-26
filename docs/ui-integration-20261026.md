# 2026-10-26 · MemorialTitles 0.4.2-dev

本地正式源码与产物已完成，已发布[0.4.2-dev预发行版](https://github.com/PixelMC-Projects/MemorialTitles/releases/tag/v0.4.2-dev)；服务器仅独立暂存，尚未激活新程序或重启。暂存细节见[部署记录片段](server-staging-20261026.md)。UI 依据 Manager `843cdac` 的 Titles 页面与[插件页面风格规范](插件页面风格规范.md)同步。

## 改动与兼容

- 独立网页保留 `/api`、账号、会话、CSRF、哈希路由、原版头像回退、整合包资料字段及每日备份行为。
- 档案移除旧筛选与显示下拉，支持全量排序、带数量的下划线标签和直接切换密度；玩家提供三种 presence 分类，头像右侧名字前显示状态小点。
- `/api/players` 接受 `presence=all|online|offline`、`sort=status|name|ownedCount|equipped|lastSeen`、`direction=asc|desc`。先过滤与排序完整缓存集合，再分页；当前佩戴按展示名，空值最后，同值按自然名称/UUID稳定排序。保留旧 `online=true` 参数。
- `presenceCounts={all,online,offline}` 统计全部已登记玩家，不受关键词与分页影响；不会把临时在线 UUID 注册成玩家。
- `/api/history` 增加 `categoryCounts=[全部,发放与撤销,称号编辑]`，按当前 player/title 范围在 SQL 内统计，不受 q/action/page 影响，并继续排除受保护隐藏历史。记录按真实时间与 ID 倒序，网页无时间排序控件。
- 数据库状态仅在数据备份分类出现，卡片位于同一内容容器，左右对齐且宽度一致；保留原有备份、验证、配置导入/重载和逐项撤回流程。
- 无新增数据库结构，无数据清理或生产配置修改。版本在 Gradle、网页健康信息与 Manager 注册中统一为 `0.4.2-dev`。

## 验证

| 页面 / 行为 | 结果 | 证据与边界 |
| --- | --- | --- |
| 标题与说明、透明标签、完整分类数量 | 通过 | 独立正式服务实测，深浅主题截图；加载中数量显示 `—`，已知零显示 `0` |
| 档案满宽、表头、日期、排序、密度 | 通过 | 桌面截图；3 项前端排序测试覆盖45条记录跨页、数值同值及空值 |
| 玩家三分类、UUID、头像与状态点 | 通过 | 真实 HTTP 接口与浏览器实测；默认头像/离线记录保留 |
| 玩家排序及分页 | 通过 | 45名固定测试玩家，正反序跨页、在线组序、收藏数、佩戴展示名、真实时间与未知时间 |
| 历史数量与固定时间顺序 | 通过 | 搜索/分类/分页不改变分类总数，玩家范围保持；打乱时间与审计ID顺序后仍按最新时间返回 |
| 设置分类与数据库卡片 | 通过 | 展示设置0张、首登奖励0张、数据备份1张；卡片与配置区宽972.67px、左侧对齐、边距0、分隔线上下28px/27px |
| 抽屉、下拉与滚动条 | 通过 | 桌面590px、10px圆角，窄屏390px下抽屉374px；弹出下拉、编辑区均为6px滚动条；独立portal根可用 |
| 深浅主题与窄屏 | 通过 | 1280×720与390×844截图，UUID完整、无页面横向溢出、抽屉页脚可见 |
| 权限与数据保护 | 通过 | 会话、CSRF、并发编辑、清理/删除、逐项撤回、持有人隐私、备份/恢复与提醒回归 |
| 构建与网页打包 | 通过 | `clean build`、6套MariaDB/HTTP测试共191项，前端排序3项、Sites打包4项 |
| 极长称号/玩家名、断连状态的人工视觉验收 | 未验证 | 本轮未向既有本地验收资料追加极长记录，也未人为断开运行中的页面；API空结果与未知时间已验证 |
| Manager侧栏动效 | 不适用 | 独立网页无跨插件导航；由Manager项目验收 |
| 生产游戏/网页/数据库 | 未验证 | 新JAR仅在活动mods之外暂存；未激活新代码、重启或执行生产数据迁移 |

构建命令（JDK17，`MT_TEST_PASSWORD` 仅在本地测试进程环境中加载）：

```text
gradlew.bat clean build webIntegrationTest historyCollationIntegrationTest titleDeletionIntegrationTest titleHoldersIntegrationTest undoIntegrationTest reminderIntegrationTest --offline
npm run test:sorting
npm run test:sites
```

本机 Gradle 临时使用 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=D:\Gradle\.tmp` 处理已有的 loopback 环境问题，未修改全局环境或项目配置。旧Forge API产生两条弃用编译警告；构建成功。

## 本地交付与后续部署

- 工程：`E:\minecraftserver\fabricmod\MemorialTitles`。
- 产物与SHA-256：`releases/0.4.2-dev-20261026/`。网页静态ZIP仅为同版本资源副本，通常直接使用包含它们的模组JAR；不提供第二套认证服务。
- 截图：上述目录的 `ui-20261026/`；API报告：`verification/web-integration.json`。
- 目标游戏路径：`/home/rhymc/dockerserver/mc-server-1/mods`（本次未写入）。独立模式配置仍为 `config/memorialtitles/server.json` / `web.json`。Manager模式另需对应网页服务升级，路径以Manager部署记录为准。
- 需要客户端：是，MC1.20.1/Forge客户端与服务端均使用匹配JAR。MariaDB与Manager依赖不变。
- 生效：按README先完整备份、正常关服，替换旧JAR；Manager模式先更新对应网页服务。保留配置、数据库与备份。回滚替换匹配旧程序并保留当前业务数据；不要整库覆盖后续改动。

No production deployment or restart was performed. Release publication does not activate this build on a server. This build preserves standalone authentication, routing and modpack settings; its web UI and full-set listing APIs were verified locally against MariaDB.
