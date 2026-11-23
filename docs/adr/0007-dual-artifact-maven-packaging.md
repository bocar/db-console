# Maven 构件采用「普通 jar 主构件 + exec 可执行 jar」双产物，Boot 2 消费方独立进程集成

其他项目提出通过 Maven 引用 db-console 并访问其配置的业务数据库；消费方为 Spring Boot 2.x 或更老（javax / Spring 5.3 / Java 8-11）。现状两个阻塞：`spring-boot-maven-plugin` 的 repackage 把主构件替换为 Boot fat jar（类在 `BOOT-INF/classes`），任何项目按普通依赖引入都不可用；且 [ADR-0005](0005-bundled-jdk17-delivery.md) 已否决「降级 Boot 2.7 / JDK 8」（约 34 处 record、33 处 jakarta→javax 改造 + 永久双主线）。裁决：**`spring-boot-maven-plugin` 配 `<classifier>exec</classifier>` 产出双构件——主构件为普通 jar（可依赖、可 install，含前端静态资源与传递依赖），`-exec` 为可执行 fat jar 独立部署；Boot 2 消费方以独立进程（sidecar）集成，浏览器 / HTTP API 访问数据库；Boot 3 消费方（未来）以组件扫描接缝内嵌，暂不提供自动装配 starter。发布渠道暂为本机 `mvn install`。**

理由：

1. **同 JVM 内嵌对 Boot 2 宿主不可行且不可修复**：jakarta/javax 命名空间、Spring 6/5.3、Java 17 语法（records、sealed）三重冲突，唯一出路是降级分支，已被 ADR-0005 以安全基线（Boot 2.7 OSS 支持已结束、零 CVE 补丁）与双主线维护成本否决。
2. **双产物是最小修复**：仅改插件 classifier 一处构建配置，独立部署链路（Docker、离线包）只换一个源文件名（发行包内仍叫 `db-console.jar`，现场脚本零改动），却让主构件恢复为可被未来 Boot 3 项目直接依赖的形态。
3. **自动装配 starter 暂缓（YAGNI）**：把 6 个 `@Configuration` + 3 个 `@Service` 的组件扫描装配收敛为条件化装配是一次真实重构；README 记录的组件扫描接缝（扫描 `io.github.dbconsole`、排除 `DbConsoleApplication`、`@EnableConfigurationProperties(DbConsoleProperties)`）已足以让 Boot 3 宿主完成内嵌，等出现真实消费者再按需重构。

## Consequences

- `target/` 下可执行 jar 更名为 `db-console-exec.jar`；Dockerfile、`packaging/package.sh`、CI 冒烟与 README 同步更新，发行包内部命名不变；
- 依赖主构件的宿主会在 `classpath:/static` 下获得控制台前端资源，自有静态首页可能冲突（README 已提示）；
- 宿主需自行排除 `DataSourceAutoConfiguration`（若自身无 DataSource），并在排除 `DbConsoleApplication` 后自行启用 `DbConsoleProperties` 绑定；
- 发布渠道仅本机 `mvn install`，未配置远程仓库；出现多机消费需求时再补 distributionManagement（不改变构件形态）。

## 重评触发器（任一满足即重开评估）

- 出现首个真实的 Boot 3 内嵌消费方，且组件扫描接缝的接入成本显现（宿主反复踩排除 / 绑定遗漏）→ 评估自动装配 starter 改造；
- 出现「必须与 Boot 2 宿主同 JVM」的硬需求 → 按 ADR-0005 第 2 条代价重新评估降级分支或进程内隔离类加载方案；
- 出现跨机器分发需求 → 补充远程仓库 deploy 配置。
