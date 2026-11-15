# db-console 部署说明（麒麟 V10 / CentOS / Debian）

本发行包自带 JDK 17（毕昇/Kona/Dragonwell，随包目录 `jdk/`），**解压即用，不安装进系统，
不影响服务器上已有的 JDK 8**。浏览器访问的机器无需任何 Java 环境。

目标系统：银河麒麟 V10 SP1+、CentOS 7+、Debian 10+ / Ubuntu 18.04+ 等 glibc ≥ 2.17 的
Linux 发行版，x86_64 / aarch64。CentOS 6 的 glibc 过旧、无法运行 JDK 17，不在支持范围。
脚本依赖 bash 与 procps（pgrep）；极简安装如缺失，执行 `apt-get install -y procps curl`
（Debian/Ubuntu）或对应 yum/dnf 命令。`curl` 缺失时状态检查会自动跳过 HTTP 探测，不影响其余功能。

## 目录结构

```
db-console/
├── db-console.jar          # 应用（含前端静态资源与 JDBC 驱动）
├── jdk/jdk17-<架构>/        # 随包 JDK 17，start.sh 自动选用
├── bin/start.sh            # 启动（前台 / --daemon 后台 / --dry-run 自检）
├── bin/stop.sh             # 停止（--daemon 模式或裸进程时使用）
├── bin/restart.sh          # 重启（= 停止 + 后台启动；无进程时等价于启动）
├── bin/status.sh           # 状态检查（只读；退出码 0/1/2 可供监控脚本使用）
├── config/                 # 配置目录（样例 *.example.yml，复制后修改）
├── systemd/dbconsole.service
└── README.md               # 本文件
```

## 部署步骤

1. **解压**（示例目录 `/opt/dbconsole`，可自定）：

   ```bash
   tar -xzf db-console-*-linux-*.tar.gz -C /opt
   cd /opt/db-console
   ```

2. **应用配置**：

   ```bash
   cd config
   cp db-console.example.yml db-console.yml
   cp datasources.example.yml datasources.yml
   ```

   按现场修改：登录账号、端口（默认 8080）、审计目录（默认 `./audit`，即部署目录下）、
   数据源清单。`datasources.example.yml` 含 MySQL 与达梦 DM8 两种示例；每个数据源的密码
   通过 `credential-ref` 引用环境变量，配置文件本身不落明文密码。`environment` 取值
   `PRODUCTION` 或 `DEVELOPMENT`，决定写操作的二次确认规则（见样例文件内注释）。

3. **准备凭据环境变量**：将 `config/dbconsole.env.example` 复制为
   `config/dbconsole.env`（权限 600），填写登录密码 `DB_CONSOLE_ADMIN_PASSWORD`
   与各 `credential-ref` 引用的变量。`bin/start.sh` 会自动加载该文件（systemd
   部署则由 `EnvironmentFile` 加载），无需手动 source。

4. **自检**（可选但建议）：

   ```bash
   bin/start.sh --dry-run    # 确认 JDK 选择、配置文件、启动命令
   ```

5. **启动**（二选一）：

   - systemd（推荐）：
     ```bash
     # 部署目录非 /opt/dbconsole 时先改 service 文件中的三处路径
     cp systemd/dbconsole.service /etc/systemd/system/
     systemctl daemon-reload
     systemctl enable --now dbconsole
     ```
   - 裸进程：`bin/start.sh --daemon`（日志 `logs/db-console.log`）

6. **验证**：按下文「部署后验证」逐项确认。

## 部署后验证

按顺序执行，任何一步不通过按括号内提示排查：

1. `bin/status.sh` 退出码为 0，HTTP 存活一行显示"正常"（退出码含义见「停止、重启与升级」）；
2. 服务器本机 `curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:<端口>/` 返回 200
   （不通时看进程是否存活：`bin/status.sh`，再看日志，见常见问题）；
3. 浏览器打开 `http://<服务器IP>:<端口>` 登录后：
   - 数据源下拉出现所配数据源，点「测试连接」提示"连接正常"；
   - 对象资源管理器加载出表清单；
   - 在 SQL 编辑器执行一次简单查询（如 `SELECT 1`）并看到结果。
4. 浏览器无法访问时：先放行端口——服务器防火墙见常见问题；阿里云等云服务器
   **还需在控制台放行安全组**（本次 CentOS 验收实例即走了这一步）。

## 停止、重启与升级

- 停止：systemd 部署 `systemctl stop dbconsole`；裸进程 `bin/stop.sh`
- 重启：systemd 部署 `systemctl restart dbconsole`；裸进程 `bin/restart.sh`（不要对 systemd 管理的服务混用本脚本，避免脱离 systemd 拉起进程导致端口冲突）
- 状态：`bin/status.sh`（裸进程与 systemd 部署均适用，只读；退出码 0=运行中、1=未运行、2=运行中但 HTTP 不通）
- 日志：裸进程 `logs/db-console.log`；systemd `journalctl -u dbconsole -n 50`
- 配置修改（数据源、端口、凭据）仅启动时读取，改完须重启应用
- 升级：停止后用新发行包覆盖 `db-console.jar` 与 `bin/`、`jdk/`（config 不动），再启动

## 常见问题

| 现象 | 处理 |
| --- | --- |
| start.sh 报“需要 JDK 17+” | 随包 `jdk/` 目录缺失（重新解压完整发行包），或用 `DB_CONSOLE_JAVA_HOME` 指向本机已装的毕昇 JDK 17 |
| 服务器已有毕昇 JDK 8，会被用到吗 | 不会。应用只使用随包 JDK 17（或 `DB_CONSOLE_JAVA_HOME` 显式指定的 JDK 17+），系统 java 不参与启动，无需任何改动 |
| 不想用随包 JDK，本机已装毕昇 17 | `export DB_CONSOLE_JAVA_HOME=/usr/local/jdk17` 后再启动；start.sh 优先使用它 |
| 启动报配置相关错误 | 确认 `config/db-console.yml`、`config/datasources.yml` 已创建且凭据环境变量已注入；`--dry-run` 可快速定位 |
| 端口被占用 | `ss -ltnp \| grep <端口>` 查占用方；同机已有应用时改 `db-console.yml` 的 `server.port`（如 18080），并同步放行防火墙/安全组的新端口 |
| 启动后进程很快消失 | 看日志定位：裸进程 `logs/db-console.log`，systemd `journalctl -u dbconsole -n 50`。常见原因：端口被占（日志出现 webServerStartStop 启动失败）、凭据环境变量缺失或与 `credential-ref` 名字不一致 |
| 数据源「测试连接」失败 | 核对 `jdbc-url` 格式（DM8 为 `jdbc:dm://<主机>:<端口>`，默认端口 5236）、账号口令正确且数据库侧未锁定；凭据环境变量已注入且名称与 `credential-ref` 一致；改完重启应用 |
| 修改配置不生效 | 配置仅启动时读取：systemd `systemctl restart dbconsole`，裸进程 `bin/restart.sh` |
| 本机正常但浏览器访问不到 | 放行防火墙端口：CentOS `firewall-cmd --permanent --add-port=8080/tcp && firewall-cmd --reload`，Debian/Ubuntu `ufw allow 8080`（麒麟 V10 同理或用其防火墙工具）；云服务器还需检查安全组 |
| 审计目录要放独立磁盘 | 把 `db-console.audit.directory` 改为绝对路径 |
| CentOS 6 或站点锁死只允许 JDK 8 | JDK 17 需 glibc ≥ 2.17（CentOS 6 不满足）；请联系维护方评估 JDK 8 降级发行版 |
