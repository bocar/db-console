# db-console

在浏览器中连接已配置的业务数据库，浏览表结构、执行 SQL 和查看结果。当前包含 Spring Boot 工程骨架和前端交互原型，真实登录、数据库连接及 SQL 执行等业务功能尚未实现。

后端采用 Java 17、Spring Boot 和 JDBC。前端采用 Vue 3、TypeScript 和 Monaco Editor，随 Spring Boot 可执行 JAR 发布。应用以单体进程运行，只连接业务数据库，无需应用库、应用表或独立前端服务。

## 首版范围

- 首批验收 MySQL 和达梦 DM8。其他数据库适配目标见 [项目需求](docs/REQUIREMENTS.md)。
- 查看表、列、索引和外键，支持 `DESC` / `DESCRIBE`。按目标库方言执行单条查询、增删改及其 `WITH` 形式、表级 DDL 和表列注释。
- 支持取消查询、从数据库按页读取查询结果、CSV 导出、复制及会话内 SQL 历史。脚本批量执行属于扩展功能。

生产库的二次确认及 DDL 提交规则见 [确认与事务](docs/REQUIREMENTS.md#确认与事务)。

## 继续开发

- [需求文档](docs/REQUIREMENTS.md) 是实现依据。按需求编号定位任务，涉及 SQL 执行时同时核对 [确认与事务](docs/REQUIREMENTS.md#确认与事务) 和 [发布验收](docs/REQUIREMENTS.md#5-发布验收)。
- 后端当前只有启动类和类型化外部配置，不包含业务 Controller、服务或 JDBC 执行逻辑。`src/main/webapp/` 中的页面使用模拟数据，仅用于界面和交互预览。
- 建议先实现配置登录及数据源连通（F-01、F-02）。再打通 MySQL 和 DM8 的单条执行、二次确认、提交与回滚（F-04、F-05、F-09、F-16），最后补齐对象元数据、结果交互和交付验收。
- [待定参数](docs/REQUIREMENTS.md#待定参数) 单独维护。接手时核对当前文件和改动，完成后按需求编号记录已实现内容、实际验证结果和待办。

## 开发与构建

在项目根目录使用 JDK 17 和 Maven 3.9–3.10。开发启动前先准备 [外部配置](#外部配置)。 [Maven 构建生命周期](https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html)、[Spring Boot Maven 启动说明](https://docs.spring.io/spring-boot/maven-plugin/run.html)

| 用途 | 命令 |
| --- | --- |
| 启动后端 | `mvn spring-boot:run` |
| 运行后端单元测试 | `mvn test` |
| 构建包含前端资源的可执行 JAR | `mvn clean package` |
| 发布前检查 | `mvn clean verify` |

Maven 会自动安装锁定版本的 Node.js 和 pnpm，执行前端依赖安装、类型检查及 Vite 构建，再将 `src/main/webapp/dist/` 复制到 JAR 的 `BOOT-INF/classes/static/`。前端构建失败会中止整个 Maven 构建。

单独开发前端时，在 `src/main/webapp/` 执行 `pnpm install` 和 `pnpm dev`，并使用 `pom.xml` 中锁定的 pnpm 版本。开发服务器地址以终端输出为准。当前默认展示模拟工作台，访问 `#login` 可查看登录页。

浏览器联调使用包含前端资源的 JAR，无需独立前端服务。

## 当前验证状态

- 已使用 JDK 17.0.11 和 Maven 3.10.0 执行 `mvn clean verify`，前端构建、后端编译和可执行 JAR 打包通过。
- 已使用两份外部配置实际启动 `target/db-console.jar`，确认无需应用数据库，页面、JavaScript 和 CSS 均可通过 HTTP 正常加载。
- 当前没有自动化测试；前端格式检查、ESLint、前后端测试及集成检查仍需按发布验收要求补齐。
- Docker 镜像构建尚未验证，当前 Docker 守护进程不可用。MySQL、DM8 和其他目标数据库的真实实例验收尚未开始。

## 外部配置

应用配置与数据源配置分文件维护。应用配置提供一个登录账号，数据源配置引用环境变量中的数据库密码。修改配置后重启应用。

配置样例位于 `config/db-console.example.yml` 和 `config/datasources.example.yml`，可复制为实际配置文件后修改。其中的超时、并发和结果限制仅是示例值，不代表已经确认的默认值。

当前骨架已实现必填项、数值下限和数据源标识唯一性校验；驱动加载及凭据引用有效性等完整启动校验仍待实现。数据库连通性和认证应在连接测试或首次使用时检查，而不是启动时检查。

启动前，将 `DB_CONSOLE_APP_CONFIG` 和 `DB_CONSOLE_DATASOURCES_CONFIG` 设为两份文件的绝对路径，并设置登录密码。 [Spring Boot 外部配置](https://docs.spring.io/spring-boot/reference/features/external-config.html)

```bash
export DB_CONSOLE_APP_CONFIG="/absolute/path/db-console.yml"
export DB_CONSOLE_DATASOURCES_CONFIG="/absolute/path/datasources.yml"
export DB_CONSOLE_ADMIN_PASSWORD="replace-with-a-login-password"
export SPRING_CONFIG_ADDITIONAL_LOCATION="file:${DB_CONSOLE_APP_CONFIG},file:${DB_CONSOLE_DATASOURCES_CONFIG}"
```

## 部署

主要部署在阿里云、腾讯云等云平台的服务器上。JDBC 驱动按发行说明提供，当前骨架未捆绑业务数据库驱动。

### 可执行 JAR

服务器使用 Java 17。准备外部配置后，构建并启动应用，访问配置指定的地址和端口。

```bash
mvn clean package
java -jar target/db-console.jar
```

### Docker

镜像包含同一 JAR 和 Java 17 运行时，从项目根目录构建。

```bash
docker build -t db-console:local .
```

启动容器时映射应用端口，挂载两份配置文件和审计目录，并注入登录及数据源配置引用的环境变量。镜像默认读取容器内的 `/config/db-console.yml` 和 `/config/datasources.yml`。

以下示例通过 `DB_CONSOLE_AUDIT_DIRECTORY=/audit` 覆盖配置样例中的 `./audit`，确保审计目录与容器挂载位置一致。

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
