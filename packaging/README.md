# 离线交付打包（麒麟 V10 / CentOS / Debian + 随包 JDK 17）

面向麒麟 V10 / CentOS 7+ / Debian 系（含 Ubuntu）等 glibc ≥ 2.17 的 Linux 服务器
（aarch64：鲲鹏/飞腾；x86_64：海光/兆芯/Intel/AMD）。
策略：应用代码保持 Spring Boot 3.4 / Java 17 主线不动，交付时随包携带国产
JDK 17（毕昇/Kona/Dragonwell），解压即用；运行不探测系统 java，与服务器现有
毕昇 JDK 8 共存互不影响（决策见 [docs/adr/0005](../docs/adr/0005-bundled-jdk17-delivery.md)）。

文档分工：本文件面向维护者（**如何打包**）；现场**如何运行**见 [DEPLOY.md](DEPLOY.md)，
该文件随发行包以 README.md 名义分发。

## 打包步骤（维护者）

1. **取 JDK 17 原始包**（Linux 压缩包，放入 `packaging/packages/`，该目录不入库）：

   | 架构 | 机型 | 推荐发行版 | 下载渠道 |
   | --- | --- | --- | --- |
   | aarch64 | 鲲鹏、飞腾 | 毕昇 JDK 17 | 鲲鹏开发者社区 hikunpeng.com、mirrors.huawei.com |
   | x86_64 | 海光、兆芯 | 毕昇 JDK 17 x64；备选腾讯 Kona 17、阿里 Dragonwell 17（无信创要求的 CentOS/Debian 也可用 Eclipse Temurin 17） | 各厂商官网/镜像 |

   文件名含 `aarch64` / `x64`（或 `x86_64`、`amd64`）即可被自动识别；
   也可用 `JDK_TARBALL_AARCH64` / `JDK_TARBALL_X64` 环境变量指定绝对路径。

2. **打包**。构建机需要 JDK 17 与 Maven 3.9+（仅在 `--build` 或自动构建时需要；
   运行机不需要任何 Java）。构建机只有 JDK 8 时脚本会直接拦截并提示，此时可用
   `--no-build` 复用已在 JDK 17 机器上构建好的 `target/db-console.jar`：

   ```bash
   ./packaging/package.sh                  # 所有已就绪架构（JAR 缺失时自动构建）
   ./packaging/package.sh --arch aarch64   # 只打指定架构
   ./packaging/package.sh --build          # 强制重新构建 JAR（含前端）
   ./packaging/package.sh --no-build       # 只打包现有 target/db-console.jar
   ```

   产物在 `packaging/out/`：`db-console-<版本>-linux-<架构>.tar.gz` 及 `.sha256`
   校验文件（例：`db-console-0.1.0-linux-aarch64.tar.gz`）。脚本会校验随包 JDK
   主版本 ≥ 17，防止误打包 JDK 8 原始包。

3. **现场部署**：见发行包内 README.md（即本目录 [DEPLOY.md](DEPLOY.md)）。

## 适配记录

每完成一个站点组合的验证，建议在下表追加一行（同时作为信创适配测试报告底稿）：

| CPU | 架构 | 操作系统 | 随包 JDK | 验证日期 | 结果 |
| --- | --- | --- | --- | --- | --- |
| （例）鲲鹏 920 | aarch64 | 银河麒麟 V10 SP3 | 毕昇 JDK 17.0.x | | ✅ |
| Intel Xeon Platinum（阿里云 ECS） | x86_64 | CentOS Linux 7 (Core) | 腾讯 Kona 17.0.21.0.3 | 2026-11-15 | ✅ |

验证内容：`bin/start.sh --dry-run`、应用启动、登录、MySQL/DM8 各执行一次查询、
`bin/stop.sh`/systemctl 停止（参考 docs/REQUIREMENTS.md 第 5 节发布验收）。

## 维护说明

- `bin/start.sh` 的 JDK 目录约定为 `jdk/jdk17-<aarch64|x64>`，由 `package.sh`
  在解包时规范化（与发行商解包目录名解耦）；改命名需同步两处。
- `bin/start.sh` 不回退系统 java：随包 JDK 缺失即明确报错（现场毕昇 JDK 8
  永不参与运行，见 docs/adr/0005）。
- `bin/stop.sh` 的 `STOP_TIMEOUT` 与 `systemd/dbconsole.service` 的 `TimeoutStopSec`
  保持一致（90s，为执行中语句的取消留时间）。
- 版本号取自 `pom.xml` 中 db-console 的 `<version>`。
