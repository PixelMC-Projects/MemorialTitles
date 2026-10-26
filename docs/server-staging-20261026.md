# 2026-10-26 · Titles网页正式接入发行与服务器暂存

目的：将已验收的新称号网页和正式列表接口用于下一次授权启动，不打断当前游戏进程，不提前修改本地客户端。

- 本地工程：`E:\minecraftserver\fabricmod\MemorialTitles`；版本 `0.4.2-dev`，MC1.20.1/Forge，客户端和服务端均需匹配模组。
- 模组源码与tag提交：`f8f0363a51651ab1605cc0c094a667655010a97c`，已推送 `main` 和 `v0.4.2-dev`。
- Release：[MemorialTitles 0.4.2-dev](https://github.com/PixelMC-Projects/MemorialTitles/releases/tag/v0.4.2-dev)，沿用上一版预发行状态、简洁中英说明及JAR+SHA256SUMS附件；网页资源已内置JAR。MariaDB辅助服务未修改，无额外数据库包。
- JAR：`memorialtitles-forge-1.20.1-0.4.2-dev.jar`，1492139字节，SHA-256 `2c906e6d702e407222448c3ef4a3b95a2d582e31b660c4d0bc70d2ebd713ed54`。已核对上传资产digest及大小，Release公开状态已读回。
- 本地产物：`E:\minecraftserver\fabricmod\MemorialTitles\releases\0.4.2-dev-20261026`。静态资源ZIP仅供核对；JAR内网页与构建资源逐文件一致，无旧哈希资源；本地测试凭据与常见令牌模式扫描通过。

## 暂存边界与下次启动

- 服务器暂存：`/home/rhymc/dockerserver/mc-server-1/pixelmcmanager/deploy-titles-ui-0.4.2-20261026`，目录权限0700；JAR、校验表及配置副本为受限文件。
- 入口：`activate-titles-ui.sh`，SHA-256 `9de6771e74b1f171b1ed83f9029f50297ddc1498936393e3d92b8d504ade8e8b`；对应公开源码为 `deploy/activate-titles-ui.sh`。
- AutoModpack next副本克隆昨日reminder暂存配置，仅将Titles0.4.1同步条目替换为0.4.2，保留FramedBlocks、其他同步文件、服务端模组排除、TLS与现有安全配置。活动配置与已发布清单均未改；不在当前运行期间发布新客户端模组。
- 总启动入口已由配套Manager项目安装并完成只读交叉核对。最终顺序为未来启动协调器、旧reminder更新、FramedBlocks、新Manager就绪wrapper、新Manager、Titles、原始JVM；新Manager网页能力检查未就绪时，两份新游戏模组延期。旧空服自动重启执行器已由Manager任务暂停，原有暂存文件保留。
- Titles入口还要求新Manager暂存的 `applied.txt`、0.5.2游戏JAR、唯一旧Titles0.4.1及其哈希、预期AutoModpack配置哈希均匹配。漂移或前置未完成则保留旧文件并说明原因，不强制覆盖。
- 成功时先备份旧Titles与AutoModpack配置，再原子切换JAR和配置，写入本阶段 `applied.txt`；AutoModpack按原先 `generateModpackOnStart` 在该次启动生成客户端清单。失败回滚两类文件并重新校验；回滚无法核实时停止该次启动供检查。
- 未改MCSM原始JVM参数、生产数据库、网页账号、本地客户端或活动mods；未请求停服、开服、重启、重载或运行就绪probe。

## 已验证与待验证

- 已验证 `bash -n`、全部暂存payload SHA-256；隔离临时目录模拟成功激活与重复执行、基线漂移延期、后期复制失败回滚均通过，临时测试目录已清理。
- 暂存前后游戏容器PID `1473227`、StartedAt `2026-10-25T03:25:27.741595875Z`；网页PID `1472134`、StartedAt `2026-10-25T03:23:38.875812076Z`，均不变。活动Titles仍为0.4.0-dev，SHA-256 `79a366e0b24678c01782f90cc83252ea2877b8b028c73e9c159874a3bf2a010b`。
- 服务器报告位于暂存目录的 `staging-report.json` 与 `activation-fixture-report.json`；本地非敏感副本在产物目录 `verification/`。初次总入口报告为 `verification/startup-hook-crosscheck.json`，加入自动协调后的最终报告为 `verification/future-startup-coordination-crosscheck.json`：三个暂存payload均核对通过，新applied标记均不存在；最终总入口哈希 `f6fd32717ad3f35ee436910cd905c20dd73b8b3cd4a8e74dbb2dc3df5a7fe3f8`，新wrapper哈希 `8c265ce4267e1b7b4040b7d385282dac4c0919c4061a8b3a72f97e2bf5692315`。
- 当前已配置下次正常游戏启动时自动准备网页、再依次加载暂存更新，尚未生效。早先要求手动先重启网页的前置步骤已改为下述自动协调。网页MCSM未来启动器仍指向 `deployment/manager-ui-0.5.2-20261026/launch-manager.sh`。原JVM参数保留；本次未执行任何重启、生产gate或迁移。
- 未验证：真实下一次启动、Manager网页新能力、AutoModpack实际重新生成与客户端下载、新游戏会话内的视觉/功能。本次无新增数据库结构（仍schema6、备份格式4）。

## 回滚与依赖

需要匹配的Manager0.5.2网页与游戏桥接、MariaDB及既有reminder/FramedBlocks阶段；不要绕过前置能力检查。未激活前可从总启动入口移除本阶段调用并保留暂存；激活后正常关服，恢复本阶段 `before-activation` 的旧JAR与AutoModpack配置，并恢复对应Manager程序。数据库不做整库回退，保留后续业务数据与备份。

Only staged for the next authorized startup. No production process was restarted and no local client was updated. The staged client-sync configuration retains existing security fields; activation requires matching Manager readiness and verified baselines.

## 追加：未来游戏启动自动协调网页

- `coordinate-startup.sh` 已位于旧reminder钩子之前；只在下一次正常游戏启动生成带时间与UUID nonce的 `startup-request.json`，等待宿主机准备网页。就绪后继续原有钩子与原始JVM，不要求额外手动先重启网页。
- 宿主机 `pixelmc-manager-ui-startup-0.5.2.path` 已enabled、active/waiting；对应worker service仍inactive/dead、PID0、`ExecMainStartTimestampMonotonic=0`，从未执行。当前request、response、coordination-report和新applied均不存在。
- worker固定绑定游戏实例 `6ef85649e42a4b19be35e22d55e8c8e0` 与网页实例 `e4f28a43dc284b9291efde888c081e11`、各自cwd及对应未来网页启动器。请求必须新鲜、发生在新游戏容器启动后，且该容器不能已经运行Forge游戏JVM；同一nonce至多请求一次网页启动/重启，不请求数据库重启。
- 当前游戏容器StartedAt仍为被worker明确禁止操作的旧BASELINE，已有游戏JVM在运行，因此当前会话不会进入网页重启路径。监听安装不等于worker执行，更不等于新版已加载。
- 网页能力不足、准备失败或超时会延期不兼容更新；payload完整性校验损坏则由 `set -e` 停止那次未来启动，供复核，不能表述为所有故障都会继续启动旧版。
- 仅通过读取脚本、systemd状态、实例配置和哈希交叉核对；没有运行生产能力gate、启动JVM、创建request或调用实例重启。活动JAR、AutoModpack配置/在线清单、两个进程PID及StartedAt均未变。
- 协调脚本SHA-256：`f7e2b1791f76bdbafe98641dc065595dbc4944767a02a5a19f8d83fd7d6619c3`；宿主机worker SHA-256：`5caa60b9705aa206d9165c62a7202d6984e99831bda72f06f59794a8c73fe03d`。实际下一次启动仍未验证。
