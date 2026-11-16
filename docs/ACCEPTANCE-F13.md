# F-13 脚本执行验收记录（2026-11-16，双库）

对应 PR #21（main `6118463`）；按本清单逐项验收后转为记录。证据：`/tmp/f13-evidence/` API 走查原始响应、
截图 `docs/acceptance-evidence/f13-01..05.png`、审计文件 `audit/audit-2026-11-16.jsonl` 与本记录。

## 环境与版本

| 项 | 值 |
| --- | --- |
| JDK | 17.0.11（`/usr/libexec/java_home -v 17.0.11`） |
| MySQL 服务端 | 8.x（47.98.61.214:3306，库 **zhstudy**，账号 zhuhui，DEVELOPMENT 数据源——本轮起集成测试由本机 localhost 改指服务器，改动含在本次提交） |
| MySQL 驱动 | mysql-connector-j（锁定于 pom） |
| DM8 服务端 | DM Database Server 64 V8（47.98.61.214:5236，schema NAVIC，PRODUCTION 数据源；jshell 探测 getDatabaseProductVersion=8） |
| 兼容模式 | 两数据源均 NATIVE；凭据全部经环境变量注入，未入任何被跟踪文件 |
| 应用启动 | `mvn spring-boot:run` + `config/local/`（db-console.yml 无 `script:` 段，缺省配置启动 ✅） |
| 测试规模 | 后端 304 例（`mvn clean verify` BUILD SUCCESS，含 DM8 集成实跑；MySQL 集成 4 类 19 例另指向服务器单独复跑全绿）+ 前端 60 例 |

## 1. 预校验（上传即拒或标注，不触库）

- ✅ **扩展名拦截**：`dump.sh` → 422 `SCRIPT_FILE_TYPE`「仅支持 .sql 或 .txt 脚本文件」
- ✅ **大小拦截**：900×1024 条脚本（>5MB）→ 422 `SCRIPT_TOO_LARGE`（5242880 字节）
- ✅ **条数拦截**：1001 条 → 422 `SCRIPT_TOO_MANY_STATEMENTS`（1000 条上限）
- ✅ **范围外整份拒**：`SELECT 1;` + `TRUNCATE` → valid=false、scriptId=null、errors=[(2, OUT_OF_SCOPE)]、**通过语句未签发任何凭据**（服务端收口）
- ✅ **过程体拒绝**：`CREATE PROCEDURE p() BEGIN SELECT 1; END;` 被边界分号切碎、2 条均 OUT_OF_SCOPE
- ✅ **BOM 剥离**：带 BOM 脚本正常拆分为 1 条语句
- ✅ **风险标注双库分叉**：DM8(PROD) UPDATE/INSERT → PRODUCTION_WRITE；DM8 `COMMENT ON` → IMPLICIT_COMMIT_DDL（implicitCommitDdl=true）；MySQL(DEV) 无 WHERE 的 UPDATE → MISSING_WHERE、有 WHERE 的 UPDATE → 无需确认；SELECT 一律无标注

## 2. 汇总确认（UI 走查 f13-01/f13-02）

- ✅ 预览按序展示序号/类型/「需确认」徽标（悬浮原因文案）+ 文件名、总条数、需确认条数、生产环境徽标
- ✅ 确认面板（alertdialog）：目标数据源 + 环境徽标 + 「共 3 条语句，逐条独立提交，失败即停」+ 原因汇总（「2 条需确认（生产环境…）」）
- ✅ 确认一次后连续执行，无逐条弹窗；取消面板则不执行

## 3. 逐条执行语义（DM8 实跑 + MySQL 集成测试）

- ✅ **主脚本三连过**（DM8）：UPDATE affectedRows=1 / SELECT 返回 1 行 / COMMENT ON implicitCommitDdl=true，逐条耗时如实展示
- ✅ **逐条独立提交（核心，独立连接证明）**：脚本跑完后用 jshell 直连 DM8（不经应用）读到 `NAME='accepted'` 与表注释「F13 验收表」
- ✅ **失败即停**：第 2 条重复主键 → 400 `DATABASE_ERROR`「违反表…唯一性约束条件」，第 3 条未执行；独立连接证实第 1 条变更已生效、失败条未写入
- ✅ **恢复**：UI「跳过本条，从第 3 条继续」→ 仅执行剩余语句；失败行提供「重试本条」与「跳过本条」两个入口（f13-03/04）
- ✅ **逐语句凭据**：漏带 token 的语句被门拦下返回 CONFIRMATION_REQUIRED（复查路径有效）
- ✅ **取消两态**：空闲取消 → `COMPLETED`（查询先完成）；五表笛卡尔积运行 4s 取消 → 取消与执行两端 `CANCELLED`（脚本内只读查询取消终态正确）
- ✅ **脚本内 SELECT 截断**：1500 行脚本查询返回 1000 行 + `truncated=true`（与配置 `max-page-size: 1000` 一致；150 行未到上限时如实返回不截断）
- ✅ **审计逐语句**：`audit-2026-11-16.jsonl` 19 条记录，每语句一条 16 位指纹，零明文 SQL

## 4. 事务互斥（F-16）

- ✅ 开启手动事务后 **analyze 与 execute 均 409** `TRANSACTION_OPEN`「脚本执行使用自动提交连接，请先提交或回滚当前事务」
- ✅ 回滚后 analyze 恢复正常；脚本执行全程事务面板保持「未开启」（无未提交状态）

## 5. 历史与汇总

- ✅ 脚本逐条执行期间会话历史**计数不增**（4 → 4）
- ✅ 完整跑完 → 恰好一条汇总：`kind=SCRIPT`、文件名 `f13-ui-ok.sql`、完整脚本原文、SUCCEEDED（历史对话框 f13-05：无「载入编辑器」，提示在脚本执行页重新上传）
- ✅ 失败即停的运行未产生汇总条目（脚本汇总条数恒为 1）
- ✅ 同一 scriptId 重复入史 → 422 `SCRIPT_RUN_NOT_FOUND`；未知 scriptId 同样 422

## 6. 失效与守卫路径

- ✅ 服务重启（进程密钥轮换）后旧凭据执行 → `CONFIRMATION_REQUIRED` 且随附**新签** token（失败即重签不变式）；会话同时失效需重登（既有行为）
- ✅ 重启后旧 scriptId 入史 → 422 `SCRIPT_RUN_NOT_FOUND`（暂存清空）
- ✅ UI 运行中切换数据源被阻止（「脚本执行中…」）；预校验后切源提示作废（useScriptExecution 守卫）

## 7. 配置与交付

- ✅ **缺省配置启动**：`config/local/db-console.yml` 无 `script:` 段，服务正常启动并按默认 5MB/1000 条执行全部拦截项
- ⬜ JAR/Docker 启动：本轮以 `mvn spring-boot:run` 起服未单独验证；T17 §5.3 已验交付链路未变（前端资源随 JAR 流程未动），下轮交付验收补跑

## 5.1 自动化检查

- ✅ `mvn clean verify` BUILD SUCCESS（后端 304 + 前端 60；DM8 集成实跑，MySQL 集成因基线 shell 未注入服务器凭据而跳过，随后 4 类 19 例指向服务器单独复跑全绿）
- ✅ `vue-tsc` 严格检查通过

## 记录的异常（非缺陷）

- **取消竞态用例 WAN 偶发**：`realDriverLongQueryIsCancellable` 首跑失败（SLEEP(15) 跑满、取消未及生效——跨公网 `KILL QUERY` 辅助连接首建延迟），单独复跑 5s 通过。T17 走查备注已记录同类现象；本机 T17 环境为 localhost 零往返。属环境时延特性，不改代码
- **截断验证用例修正**：清单原写 150 行，低于本环境 `max-page-size: 1000`，如实返回不截断（行为正确）；改用 1500 行完成验证

## 测试清理

- ✅ MySQL `dbconsole_f13_items` 已 DROP；DM8 `DBCONSOLE_F13_BIG/SEQ/ITEMS` 已 DROP（两库无 `dbconsole%` 残留，集成测试自清理正常）
- ✅ 本地临时脚本与 `.playwright-mcp` 走查脚本已清；审计文件留存备查

## 不适用项

- DELIMITER/存储过程脚本、dump 回灌、全局运行时长上限、multipart 上传：规格明文排除
- TLS 握手：沿用 T17 结论（本地明文连接），部署启用 TLS 时补验
