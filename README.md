# db-console

在浏览器中连接预先配置的业务数据库，浏览表结构、安全地执行 SQL 并管理事务。后端采用 Java 17、Spring Boot 和 JDBC；前端采用 Vue 3、TypeScript 和 Monaco Editor，随 Spring Boot 可执行 JAR 发布。应用以单体进程运行，只连接业务数据库，**无需应用库、应用表或独立前端服务**。

## 首版范围

- 首批验收 MySQL 和达梦 DM8。其他数据库适配目标见 [项目需求](docs/REQUIREMENTS.md)。
- 单账号登录会话；数据源列表与连接测试；对象树（JDBC 元数据优先）；按方言校验的单条语句执行、二次确认、手动事务；结果限制与分页；取消、历史、CSV 导出。
- 脚本批量执行（F-13）为扩展功能，不在首版。

生产库的二次确认及 DDL 提交规则见 [确认与事务](docs/REQUIREMENTS.md#确认与事务)。

## 当前实现状态

按 [实施工单](https://github.com/bocar/db-console/issues/1) 分片推进，已合并到 `feat/workspace-core` 分支的部分：

- **已完成**：工程基线（驱动锁定、双侧测试骨架、本地配置）；登录与登录会话（F-01）；数据源与连接测试（F-02）；方言语法分析器（ADR-0001，语句边界 / 操作分类 / 最外层 WHERE 检测 / 分页包装判定与方言分页 SQL）；对象树与 DESC（F-03 / F-06）；单语句执行与结果渲染（F-04 / F-05 / F-08），含后端确认机制（F-09）；手动事务与隐式提交 DDL（F-16）；结果三级限制与执行结果未知（F-08 完整 + F-10 部分）；会话历史（F-12）；表列注释生成（F-07）；导出与复制（F-15）；JSONL 审计（F-10 后半，#14 验收待关）；服务端分页（F-11，#12 实库验收待补）。
- **进行中**：查询取消与执行限制（F-14 + F-10 前半，#13）。
- 全量测试：后端 203 + 前端 47 用例，`mvn test` 一条命令覆盖两侧（含真实 MySQL 集成验收，凭据经环境变量注入）。

## 架构与关键边界

- **分层**：Controller 校验请求/响应 → 服务层编排登录与执行 → JDBC 层管理连接资源 → 方言层处理厂商差异（MySQL / DM8）。构造器注入、try-with-resources、显式 DTO。
- **配置模型**：应用配置与数据源配置分文件维护，数据源密码通过 `credential-ref` 引用环境变量；启动时校验配置有效性（凭据引用缺失即启动失败），连通性在连接测试或首次使用时检查。
- **前端**：全部数据访问穿过唯一的 ApiClient 接口（ADR-0003），HTTP 与 Mock 两个 adapter；状态按域拆分为 composable 单例；wire 类型手工镜像后端枚举。
- **关键决策**：见 [docs/adr/](docs/adr/)（手写词法分析器、OFFSET 分页重执行、前端 ApiClient seam）。领域术语以 [GLOSSARY.md](GLOSSARY.md) 为准。

## 不可绕过的业务规则

- 浏览器只传数据源标识；后端读取连接信息和凭据，未知标识必须拒绝。
- 后端按方言校验单条语句边界和操作范围；允许单个末尾分号，字符串、引用标识符及注释中的分号不作为语句边界。前端判断不能替代服务端校验。
- 生产环境的数据增删改均需确认；其他环境至少确认最外层无 `WHERE` 的更新/删除。确认凭据绑定当前会话、数据源和 SQL 文本，任一变化即失效。
- 手动事务绑定同一登录会话、数据源和 JDBC 连接；隐式提交 DDL 确认后在独立连接执行，不与未提交事务混用。
- 审计和日志不得记录凭据、完整 SQL 或结果数据；写入或提交状态无法确认时不得自动重试。

## 开发与构建

在项目根目录使用 JDK 17 和 Maven 3.9–3.10。开发启动前先准备 [外部配置](#外部配置)。

| 用途 | 命令 |
| --- | --- |
| 启动后端 | `mvn spring-boot:run` |
| 运行全部测试（含前端构建与测试） | `mvn test` |
| 运行单个后端测试类 | `mvn -Dtest=ClassName test` |
| 前端类型检查 | 在 `src/main/webapp/` 执行 `pnpm typecheck` |
| 前端测试 | 在 `src/main/webapp/` 执行 `pnpm test` |
| 构建包含前端资源的可执行 JAR | `mvn clean package` |
| 发布前检查 | `mvn clean verify` |

Maven 会自动安装锁定版本的 Node.js 和 pnpm，执行前端依赖安装、类型检查及 Vite 构建，再复制到 JAR 的静态资源目录。MySQL 集成测试需要设置 `DB_CONSOLE_MYSQL_WORKNOTE_PASSWORD` 环境变量，缺失时自动跳过。

代码规范：Java 采用 Google Java Style（两空格缩进、显式导入）；Vue 采用单文件组件 + Composition API + TypeScript 严格检查。SQL、注释、结果及数据库错误按文本渲染。详细规范见 [开发规范](docs/REQUIREMENTS.md#4-开发规范)。

## 外部配置

应用配置与数据源配置分文件维护。应用配置提供一个登录账号，数据源配置引用环境变量中的数据库密码。修改配置后重启应用。

配置样例位于 `config/db-console.example.yml` 和 `config/datasources.example.yml`，可复制为实际配置文件后修改。其中的超时、并发和结果限制仅是示例值。

启动前，将 `DB_CONSOLE_APP_CONFIG` 和 `DB_CONSOLE_DATASOURCES_CONFIG` 设为两份文件的绝对路径，并设置登录密码。 [Spring Boot 外部配置](https://docs.spring.io/spring-boot/reference/features/external-config.html)

```bash
export DB_CONSOLE_APP_CONFIG="/absolute/path/db-console.yml"
export DB_CONSOLE_DATASOURCES_CONFIG="/absolute/path/datasources.yml"
export DB_CONSOLE_ADMIN_PASSWORD="replace-with-a-login-password"
export SPRING_CONFIG_ADDITIONAL_LOCATION="file:${DB_CONSOLE_APP_CONFIG},file:${DB_CONSOLE_DATASOURCES_CONFIG}"
```

## 部署

主要部署在云服务器上。JDBC 驱动（MySQL Connector/J 与达梦 DmJdbcDriver18，版本随 `pom.xml` 锁定）已随 JAR 打包。

### 可执行 JAR

服务器使用 Java 17。准备外部配置后，构建并启动应用。

```bash
mvn clean package
java -jar target/db-console.jar
```

### Docker

镜像包含同一 JAR 和 Java 17 运行时，从项目根目录构建。

```bash
docker build -t db-console:local .
```

启动容器时映射应用端口，挂载两份配置文件和审计目录，并注入登录及数据源配置引用的环境变量。

```bash
docker run --rm -p 8080:8080 \
  -e DB_CONSOLE_ADMIN_PASSWORD \
  -e DB_CONSOLE_AUDIT_DIRECTORY=/audit \
  -v /absolute/path/db-console.yml:/config/db-console.yml:ro \
  -v /absolute/path/datasources.yml:/config/datasources.yml:ro \
  -v db-console-audit:/audit \
  db-console:local
```

发布前按 [验收要求](docs/REQUIREMENTS.md#5-发布验收) 验证 JAR、镜像和首批数据库。
