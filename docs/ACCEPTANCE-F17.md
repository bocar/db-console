# F-17 结果表格编辑验收记录（2026-11-18/19，双库 + 浏览器全链路）

对应提交 `4df0b27`（feat）/ `493f081`（test）/ `d048a5f`（docs），main 分支。按
`docs/REQUIREMENTS.md` F-17 条目逐项验收后转为记录。证据：审计文件
`audit/audit-2026-11-18.jsonl`、`audit/audit-2026-11-19.jsonl`（批次级 EXECUTE 记录）与本记录；
本轮 UI 走查经 Playwright 驱动真实浏览器，截图未留存。

裁决背景：2026-11-18 复审后两笔特性级裁决并已写入需求与 ADR-0006——**保存一律先经一次汇总确认**
（含全部语句都无 F-09 风险的批次）；**审计按批次记一条**（原子提交单元，逐语句状态在整体回滚下无意义）。

## 环境与版本

| 项 | 值 |
| --- | --- |
| JDK | 17.0.11 |
| 应用形态 | 打包 JAR `target/db-console.jar`（前端产物当轮重建后打入，非 dev server） |
| 启动 | `SPRING_CONFIG_ADDITIONAL_LOCATION` 挂 `config/local/db-console.yml` + `datasources-all.yml`；`result-edit:` 段缺省启动（默认上限 200） |
| MySQL 服务端 | 8.x（47.98.61.214:3306，库 zhstudy，DEVELOPMENT 数据源） |
| DM8 服务端 | DM Database Server 64 V8（47.98.61.214:5236，schema NAVIC，PRODUCTION 数据源） |
| 验证表 | 两库各自 `dbconsole_test_f17ui`（隔离纪律前缀，测毕 DROP） |
| 浏览器 | Playwright 驱动（登录 → 编辑器 → 结果表格 → 确认弹窗真实 UI 走查） |
| 测试规模 | 后端 380 例（`mvn test` 全绿，含 F-17 双库集成实跑）+ 前端 65 例（vitest）+ `vue-tsc` 通过 |

## 1. 可编辑结果标记

- ✅ MySQL `SELECT * FROM dbconsole_test_f17ui`（3 行）执行后**新增/删除按钮出现**；未选行时删除禁用、不脏时无保存/放弃按钮
- ✅ JOIN 自连接查询结果正常显示，**编辑按钮数为 0**（整体只读降级，不报错）
- ✅ DM8（大写存储）上小写查询 `select * from dbconsole_test_f17ui` 命中标记；内联编辑 aria-label 为 `编辑 NAME`（列名按存储形态映射）

## 2. 暂存交互（GLOSSARY：暂存更改）

- ✅ **双击进入编辑**：`编辑 name` 输入框自动聚焦；单击仍归行选择/复制（F-15 行为未受影响）；Enter 提交
- ✅ **清空即 SET NULL**：第 2 行 note「备注A」清空提交，确认弹窗参数渲染 `null, 2`，落库为 NULL
- ✅ **多选删除**：勾选第 3 行 → 删除按钮可用 → 点击直接标记（划线置灰，无确认弹窗）
- ✅ **新增行**：顶部插入空行，四列输入框齐备（自增/只读列禁用、必填与留空占位文案）
- ✅ **脏态**：保存/放弃更改按钮出现，分页栏提示「有未保存更改，翻页前请先保存或放弃」

## 3. 汇总确认（2026-11-18 裁决：一律确认）

- ✅ **MySQL(DEV)**：4 条语句预览按**删除 → 更新 → 新增**顺序；逐条参数渲染（中文值 JSON 转义正确）；无风险标注（DEV 无 F-09 风险，但确认仍必经）；底部为定制文案「确认后将在独立连接的单一短事务内执行：全部成功一起生效，任一失败整体回滚」
- ✅ **DM8(PROD)**：数据源「达梦 NAVIC 库」+ **生产**徽标 + 【目标环境为生产环境，所有数据变更都必须逐条确认】风险标注；语句用**元数据命中的大写形态** `UPDATE "DBCONSOLE_TEST_F17UI" SET "NAME" = ? WHERE "ID" = ?`（复审发现的引号大小写问题在真实库验证修复）
- ✅ 确认执行后进入批次执行；取消则不执行、暂存保留

## 4. 执行语义与独立连接核验

- ✅ **MySQL 一批四变更**（DELETE id=3 / UPDATE name / UPDATE note→NULL / INSERT id=4）：toast「已提交 4 条变更」→ **自动重取当前页**（改名、NULL、删行消失、新行 66.60 全部如实回显）→ 暂存清空、按钮消失
- ✅ **独立连接核验（不经应用）**：MySQL 直连 JDBC 读回 3 行与预期逐列一致；DM8 直连读回 `达梦甲（改）`
- ✅ 行不存在整体回滚、0 行影响报「行不存在」、F-10 执行结果未知不重试：由集成测试 `ResultEditMySqlIntegrationTest`（实跑）与 `ResultEditExecutorTest` 覆盖，本轮 UI 未单独演练

## 5. 事务互斥（F-16/F-17）

- ✅ 开启手动事务 → **新增/删除按钮禁用**（悬浮提示「存在未提交手动事务」）；回滚后恢复
- ✅ 服务端互斥守卫 `TRANSACTION_OPEN` 由 `ResultEditServiceTest` 与集成测试覆盖

## 6. 审计（F-10）

- ✅ `audit-2026-11-18.jsonl` 15:53:10 与 `audit-2026-11-19.jsonl` 16:27:26（UTC）各一条批次级 EXECUTE（SUCCEEDED，16 位指纹，零明文 SQL 与数据）；保存后的当前页重取各随一条普通 EXECUTE

## 7. 自动化检查

- ✅ `mvn test` 380 例全绿（含 F-17 单测 41+4+12+6+10 例、双库集成 2+1 例）
- ✅ `vitest run` 65 例全绿；`vue-tsc --noEmit` 通过；`npm run build` 产物打入 JAR

## 记录的异常（非缺陷）

- **浏览器缓存旧 index.html**：JAR 更新后首次访问哈希资源 404（缓存的旧入口引用旧哈希），强刷/带参访问即恢复。建议后续为 `index.html` 响应增加 `Cache-Control: no-cache`（部署优化项，不影响功能）
- **SLEEP 取消竞态加固**：`realDriverLongQueryIsCancellable` 在套件高载下偶发（共享库 `KILL QUERY` 辅助连接延迟，F-13 记录的同类现象），本轮已将 SLEEP 15→30s 加固余量；单独复跑稳定通过
- **生产写授权插曲**：UI 实测前对 DM8（PRODUCTION）建表被工具权限分类器拦截——「真实环境测试」未点名生产写，经确认授权后放行。F-09 精神在工具链层的自我执行，如实记录

## 测试清理

- ✅ MySQL / DM8 的 `dbconsole_test_f17ui` 均经确认流 DROP；两库无 `dbconsole_f17%` 残留
- ✅ 应用进程已停（端口 0 监听）；临时验证脚本、cookie、走查截图与快照已清；审计文件留存备查

## 不适用项

- 视图编辑、显式空字符串（Q11：v1 不支持）、执行结果未知与取消的 UI 演练（终态语义由测试覆盖）：规格明文排除或由自动化覆盖
- TLS 握手：沿用 T17 结论（本地明文连接），部署启用 TLS 时补验
