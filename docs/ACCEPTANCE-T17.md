# T17 发布验收记录（2026-11-13/14）

对应工单 #18；按 `docs/REQUIREMENTS.md` 第 5 节逐项验收。证据：`mvn clean verify` 全量输出、
集成测试类、Playwright 走查截图（`docs/acceptance-evidence/t17-01..07.png`）与本记录。

## 环境与版本

| 项 | 值 |
| --- | --- |
| JDK | 17.0.11（Temurin，`java -version`） |
| Maven | 3.10.0（Enforcer 3.5.0 锁定 [3.9,4) 与 JDK [17,18)） |
| MySQL 服务端 | 8.0.28（localhost:3306，库 work_note，DEVELOPMENT 数据源） |
| MySQL 驱动 | mysql-connector-j 8.0.33（锁定于 pom） |
| DM8 服务端 | DM Database Server 64 V8，DB Version 0x7000d，构建 03134284604-20260707（47.98.61.214:5236，schema NAVIC，PRODUCTION 数据源） |
| DM8 驱动 | DmJdbcDriver18 8.1.3.140（锁定于 pom；getDatabaseProductVersion 报告 8.0.0.0） |
| 兼容模式 | 两数据源均为 NATIVE |
| Docker | 29.9.1（Desktop） |
| 测试规模 | 后端 237 例（双库凭据在位，0 跳过）+ 前端 52 例，`mvn clean verify` 全绿 |

## 5.1 自动化检查

✅ 全绿：`mvn clean verify` 于最终代码树（61f7437）通过；覆盖语句边界、方言分派、错误映射、
结果限制、登录、数据源标识校验、确认、事务、取消、审计（后端），以及选区执行、切换数据源、
结果处理、历史载入、执行确认（前端）。

## 5.2 数据库验收（隔离实例逐项）

- ✅ **分页连续性（MySQL + DM8）**：`SqlExecutionMySqlIntegrationTest` 与 `SqlExecutionDm8IntegrationTest`
  各以 25 行固定有序集 × 页大小 10 连续翻 3 页，断言无重漏、总数与末页 hasNextPage=false；
  UI 走查（t17-04/05）在 DM8 上验证改页大小重置第一页、末页禁用下一页
- ✅ **生产确认（标记为生产的数据源）**：dm8-navic（PRODUCTION）上 API 级
  （`productionSourceRequiresConfirmationForWrite`：无凭据 CONFIRMATION_REQUIRED/PRODUCTION_WRITE，
  带凭据放行）与 UI 级（t17-03：确认弹窗含数据源、环境徽标、SQL 文本与绑定说明）双重验证
- ✅ **只读账号无法写入**：`SHOW GRANTS FOR dbconsole_ro` 仅 USAGE + SELECT ON work_note.*，
  无任何写权限（权限矩阵证明，未做实际写入探针）
- ✅ **独立连接提交可见性与整笔回滚**：`TransactionMySqlIntegrationTest`（beforeCommit 不可见 /
  afterCommit 可见 / 回滚后清零 / 退出清理）；UI 走查验证事务面板、回滚后 COUNT=0（t17-06）
- ✅ **会话失效清理**：`TransactionMySqlIntegrationTest`（登出后事务回滚并释放连接）
- ✅ **隐式提交 DDL 拦截与独立生效**：有未提交事务时 ALTER 被 409 拦截
  （UI 走查 t17-06：状态栏「请先提交或回滚当前事务，再执行隐式提交 DDL」）；
  确认后在独立连接执行并刷新对象树（UI：建表后对象树出现 dbconsole_test_t17）
- ✅ **可控长查询取消两态**：取消成功态——真驱动集成 `realDriverLongQueryIsCancellable`
  （SLEEP(15) 挂起 → 取消 → 两端 CANCELLED）+ MockMvc/组件测试；查询先完成态——UI 走查
  （20s SLEEP 跑满后结果保留，「取消中」瞬态见 t17-07）；取消失败态——API 测试（原因透出）
- ✅ **执行限制**：超长 SQL（422 SQL_TOO_LONG）、超时（408 QUERY_TIMEOUT + FAILED 审计）、
  超并发（422 CONCURRENCY_EXCEEDED，跨会话）与同会话单飞（SESSION_QUERY_RUNNING）规则测试
- ✅ **测试表清理**：MySQL 与 DM8 的 dbconsole_test_t17 均已 DROP（查表返回表不存在）
- ✅ **审计脱敏（实库）**：走查期间 17 条审计（16 执行指纹 + 1 事务事件）零明文 SQL；
  应用日志仅记 sourceId/kind/sqlState/elapsed

## 5.3 交付验收

- ✅ **JAR 启动**：`target/db-console.jar` 以外部配置（`config/local/db-console.yml` +
  `datasources-all.yml` + 环境变量注入凭据）启动，无需应用库；登录 200；双数据源注册；
  MySQL/DM8 连接与 SQL 执行均通
- ✅ **重启后配置生效与审计可读**：重启后同凭据登录 200、执行 200、审计按天 JSONL 续写且可读
- ✅ **Docker 启动**：镜像 `db-console:t17`（348MB）运行于宿主 18080，挂载只读外部配置
  （`/config`）与审计目录（`/app/audit` → 宿主 `/tmp/t17-docker-audit`），凭据经环境变量注入；
  登录 200、DM8 经容器执行 SQL 通（CMM_USER 计数 6）、审计 JSONL 落挂载宿主目录且含指纹无明文

## 验收期间修复的交付缺陷

- `.dockerignore` 根级 `node_modules/dist` 不匹配 `src/main/webapp/` 下同名目录，宿主依赖
  被卷入镜像上下文致容器内 pnpm no-TTY 失败（54d82ef：锚定路径，并排除 audit、
  `.playwright-mcp` 与 `config/local`，本地凭据绝不入镜像）
- 缺失 `@types/node` devDependency：容器按锁文件全新安装时 vue-tsc 报 vitest d.ts 的
  NodeJS 命名空间缺失，宿主因 node_modules 与锁文件漂移（混入 vitest 5.x 构件）而掩盖
  （25cff91：声明依赖并重锁，冻结安装 + 52 例前端测试复验）

## 不适用项及原因

- **TLS 握手验证**：不适用——本地验收环境未启用 TLS，JDBC 均为明文连接；部署启用 TLS 时补验
- **事务化 DDL 目标库验证**：不适用——MySQL 8.0.28 与 DM8 均为隐式提交 DDL，无事务化 DDL 路径
- **审计滚动时区**：按 JVM 默认时区（本机 UTC）滚动；部署如需本地时区文件名，通过 TZ 环境变量配置

## 走查备注（非缺陷）

- UI 走查中一次 SQL 编辑器内容拼接为「上一条 + 新条」：Monaco 隐藏 textarea 在无焦点时接受
  外部 fill 不整替所致的**走查工具现象**；键盘/清空按钮路径不受影响，应用无此行为
- 取消走查的 20s 查询先于取消完成（工具往返时延 > 查询时长），恰好验证了「查询先完成」态；
  「取消成功」态由真驱动集成测试与组件测试覆盖
