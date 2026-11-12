# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目现状

这是一个浏览器数据库工作台，目标是连接预先配置的业务数据库，浏览对象结构并安全地执行 SQL。当前代码是 **Spring Boot 工程骨架 + Vue 交互原型**，不是已经打通数据库功能的产品：

- 后端只有启动类和类型化配置，没有 Controller、认证会话、JDBC 执行、方言、事务或审计实现。
- 前端使用模拟数据和本地状态；确认框、执行成功、取消、提交/回滚及分页界面不代表真实后端能力。
- `docs/REQUIREMENTS.md` 是功能与验收依据。涉及 SQL 执行时，同时阅读 F-04/F-05、F-09/F-16 和第 5 节发布验收，不要只按界面行为推断业务规则。
- 首批真实数据库验收目标是 MySQL 和 DM8；其他枚举值不代表已有方言支持。
- 当前没有自动化测试、ESLint、Prettier 或后端格式检查配置。`mvn test` 成功目前不表示有测试被执行。

## 环境与常用命令

### 后端与整体构建

在项目根目录执行。Maven Enforcer 要求 **JDK 17.x** 和 Maven `[3.9, 4)`；JDK 21 等其他版本会在编译前被拒绝。macOS 可使用：

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
```

```bash
# 启动后端；启动前必须准备下方外部配置
mvn spring-boot:run

# 执行 Maven 测试生命周期（也会触发前端构建）
mvn test

# 构建包含前端静态资源的可执行 JAR
mvn clean package
java -jar target/db-console.jar

# 发布前 Maven 检查
mvn clean verify

# 添加后端测试后，运行单个测试类或方法
mvn -Dtest=ClassName test
mvn -Dtest=ClassName#methodName test
```

当前 `pom.xml` 尚未加入测试依赖；添加测试时需一并配置测试框架。Maven 在 `generate-resources` 阶段自动安装锁定版本的 Node.js/pnpm、执行前端安装与构建，再在 `process-resources` 阶段复制到 `target/classes/static`，通常无需在打 JAR 前单独构建前端。

### 前端

在 `src/main/webapp/` 执行，使用 `pom.xml` 锁定的 pnpm 版本：

```bash
pnpm install --frozen-lockfile
pnpm dev
pnpm typecheck
pnpm build       # 先运行 vue-tsc --noEmit，再运行 vite build
pnpm preview
```

当前没有前端 `lint`、`format` 或 `test` 脚本，不要假设这些命令存在。开发服务器默认进入模拟工作台，访问 `#login` 查看登录原型；它没有真实认证守卫。

### Docker

```bash
docker build -t db-console:local .
```

Docker 构建通过 `-DskipTests` 跳过测试，不能替代 `mvn clean verify`。挂载配置、审计目录及启动方式见 `README.md`；镜像和真实数据库验收不能仅依据 JAR 构建成功来判定。

## 外部配置

应用配置和数据源配置分文件维护，样例位于 `config/`。两份文件通过同一个 `db-console` 前缀合并绑定，修改后需要重启：

```bash
export DB_CONSOLE_APP_CONFIG="/absolute/path/db-console.yml"
export DB_CONSOLE_DATASOURCES_CONFIG="/absolute/path/datasources.yml"
export DB_CONSOLE_ADMIN_PASSWORD="replace-with-a-login-password"
export SPRING_CONFIG_ADDITIONAL_LOCATION="file:${DB_CONSOLE_APP_CONFIG},file:${DB_CONSOLE_DATASOURCES_CONFIG}"
```

样例中的超时、并发和结果限制只是示例，不是已确认默认值。业务数据库的连通性和认证应在连接测试或首次使用时检查，不应因某个业务库不可达而阻止应用及其他数据源工作。

## 架构与关键边界

### 单体交付，无应用数据库

`DbConsoleApplication` 排除了 `DataSourceAutoConfiguration`：应用自身不需要数据库，JDBC 仅用于连接配置中的业务库。前端最终随 Spring Boot 可执行 JAR 分发，不依赖 CDN 或独立前端服务。业务数据库驱动目前未捆绑，接入真实数据库前需按发行要求提供并锁定驱动。

### 配置模型

`DbConsoleProperties` 将登录、会话、执行限制、事务、审计和数据源配置绑定为不可变 record，并做基础必填、正数和数据源 ID 唯一性校验。数据源使用 `credentialRef` 引用环境变量；当前尚无代码解析该引用或创建 JDBC 连接，不要把配置字段存在误认为功能已经实现。

### 前端原型边界

- `App.vue` 只通过 hash 切换登录页与工作台。
- `WorkspacePage.vue` 集中维护当前数据源、SQL、事务、结果、历史和分页等原型状态。
- `mock.ts` 提供所有数据源共用的表、结果与历史数据。
- 当前没有 HTTP 请求层或 API DTO。SQL 执行和取消使用计时器，事务使用本地布尔值，分页是内存切片。
- Monaco、选区执行、标识符插入、剪贴板、CSV 下载和原生对话框是真实浏览器能力；它们仍未连接真实数据库。

接入后端时，应抽出集中请求/API 类型和共享状态逻辑，替换上述模拟流程，而不是把前端正则判断或确认弹窗当作安全边界。

### 后端目标分层

按 `docs/REQUIREMENTS.md` 第 4 节按功能分包：Controller 校验请求/响应，服务层编排登录与执行，JDBC 层管理连接资源，方言层处理厂商差异。使用构造器注入和明确 DTO；元数据优先 JDBC，标识符按方言解析和引用，JDBC 资源使用 try-with-resources。

## 不可绕过的业务规则

- 浏览器只传数据源标识；后端读取连接信息和凭据，未知标识必须拒绝。
- 后端按方言校验单条语句边界和操作范围；允许单个末尾分号，但不能被字符串、引用标识符或注释中的分号干扰。前端判断不能替代服务端校验。
- 生产环境的数据增删改均需确认；其他环境至少确认最外层无 `WHERE` 的更新/删除。确认必须绑定当前会话、数据源和 SQL 文本。
- 手动事务绑定同一登录会话、数据源和 JDBC 连接；切换数据源、退出、会话失效或空闲超时时按需求结束或回滚。隐式提交 DDL 确认后在独立连接执行，不能与未提交事务混用。
- 查询分页由后端按方言执行，翻页复用原查询和事务上下文，不能借翻页重新执行写入或 DDL。
- 审计和日志不得记录凭据、完整 SQL 或结果数据；写入或提交状态无法确认时不得自动重试。

## 代码与验收约定

- Java 使用 Java 17 和 Google Java Style（两空格缩进、显式导入）；格式冲突以 Google Java Style 为准。
- Vue 使用单文件组件、Composition API 和 `<script setup lang="ts">`，保持 TypeScript 严格检查并显式声明属性、事件和 API 数据类型。SQL、注释、结果及数据库错误按文本渲染。
- 发布验收不只是构建成功：还需按 `docs/REQUIREMENTS.md` 第 5 节验证 SQL 边界、方言、事务清理、取消、分页，以及 MySQL/DM8 实例、JAR 和 Docker 启动。

## Agent skills

### Issue tracker

Issues and specs are tracked in GitHub Issues using the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

The five canonical triage labels are used unchanged. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: root `GLOSSARY.md` plus `docs/adr/`. See `docs/agents/domain.md`.
