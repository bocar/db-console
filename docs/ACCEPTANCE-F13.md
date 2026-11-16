# F-13 脚本执行验收清单（双库，待执行）

对应 PR #21（main `6118463`）；规格 [REQUIREMENTS.md](REQUIREMENTS.md) **F-13** 与 §5.2；
格式沿用 [ACCEPTANCE-T17.md](ACCEPTANCE-T17.md)。执行后本文件转为验收记录：`[ ]` → `✅/❌`，
证据（截图存 `docs/acceptance-evidence/f13-*.png`、审计摘录、命令输出）填入各项。

## 0. 环境与前置

- [ ] JDK 17.0.11 + Maven 3.10.x（Enforcer 锁定）；`mvn clean verify` 基线全绿（后端 304 + 前端 60；双库凭据在位时集成测试 0 跳过）
- [ ] MySQL 8.0.28（localhost:3306，库 work_note，**DEVELOPMENT** 数据源）；DM8 V8（schema NAVIC，**PRODUCTION** 数据源）；兼容模式均 NATIVE
- [ ] 凭据经环境变量注入：`DB_CONSOLE_MYSQL_WORKNOTE_PASSWORD` / `DB_CONSOLE_DM8_NAVIC_PASSWORD`
- [ ] 验收表就绪：MySQL `dbconsole_f13_items(id BIGINT PK, name VARCHAR(64))`；DM8 同构大写名
- [ ] 本地临时脚本文件（不入库）见附录 A；服务以外部配置启动（`mvn spring-boot:run` 或 JAR）

## 1. 预校验（上传即拒或标注，不触库）

- [ ] 扩展名拦截：`.sh` 文件上传 → 422 `SCRIPT_FILE_TYPE`，UI 明确提示仅支持 .sql/.txt
- [ ] 大小拦截：构造 >5MB 脚本（附录 A-4）→ 422 `SCRIPT_TOO_LARGE`；配置 `max-file-bytes` 调小后同脚本被拒（自定义上限生效）
- [ ] 条数拦截：>1000 条脚本 → 422 `SCRIPT_TOO_MANY_STATEMENTS`；改配置 `max-statements: 2` 后 3 条脚本被拒，恢复配置
- [ ] 范围外整份拒绝：脚本含 `TRUNCATE TABLE`（附录 A-3）→ valid=false，逐条报「第 N 条」，**预览不提供执行入口**；直接调 `/api/sql/script/execute` 执行该脚本语句需确认的拿不到凭据（服务端收口）
- [ ] 存储过程体拒绝：`CREATE PROCEDURE`（附录 A-5）→ 被边界分号切碎、逐条 OUT_OF_SCOPE，文案含「存储过程与触发器脚本不受支持」
- [ ] BOM 剥离：带 UTF-8 BOM 的脚本正常拆分，语句数正确
- [ ] 风险标注正确（重点双库分叉）：MySQL(DEV) 无 WHERE 的 UPDATE → MISSING_WHERE；DM8(PROD) 任意 UPDATE/INSERT → PRODUCTION_WRITE；两库 CREATE/ALTER/DROP 与 DM8 `COMMENT ON` → IMPLICIT_COMMIT_DDL；SELECT 无标注

## 2. 汇总确认

- [ ] 预览按语句顺序展示序号/类型/风险徽标，顶部含文件名、总条数、需确认条数、环境徽标
- [ ] 确认面板列出确认原因汇总（N 条需确认+原因文案）与目标环境；取消则不执行
- [ ] 确认一次后整个脚本连续执行，中途无逐条弹窗

## 3. 逐条执行语义（MySQL 与 DM8 各跑一遍附录 A-1/A-2）

- [ ] 全部成功路径：3 条脚本（UPDATE 有 WHERE / SELECT / CREATE TABLE）逐条 SUCCEEDED，状态/耗时/影响行数正确；SELECT 行内可展开结果表；DDL 后对象树出现新表
- [ ] **逐条独立提交**（核心）：第 3 条故意失败（MySQL 重复主键 INSERT / DM8 唯一约束冲突，附录 A-1/A-2 变体）后，**新连接查询确认第 1 条变更已生效**、第 2 条 SELECT 正常返回
- [ ] 失败即停：toast 报「第 X 条未成功，剩余 Y 条未执行」，失败行后全部 SKIPPED
- [ ] 恢复-重试本条：修正数据后「重试本条」成功 → 继续剩余 → 全部完成；恢复-跳过：「跳过本条」从下一序号继续
- [ ] 结果未知即停：脚本中 UPDATE 命中取消（见 §3 取消项）→ 终态「结果未知」+ 提示先核实数据库状态，后续未执行
- [ ] 取消两态：脚本含 `SELECT SLEEP(15)`（MySQL）/ 等价长查询（DM8 `DBMS_LOCK` 或大表慢查）→ 取消后当前语句 CANCELLED、后续 SKIPPED；查询先完成态 → 结果保留、后续继续执行不受影响
- [ ] 脚本内 SELECT 走不分页截断：>100 行结果仅显示首批且提示截断
- [ ] 并发槽逐语句进出：脚本运行中另开会话同源执行 → `SESSION_QUERY_RUNNING`/并发上限明确拒绝；脚本结束后释放
- [ ] 审计逐语句：审计 JSONL 每条语句一条指纹记录、零明文 SQL；应用日志无语句文本

## 4. 事务互斥（F-16）

- [ ] 开启手动事务（工作台）后上传脚本 → 预校验即拒（TRANSACTION_OPEN「脚本执行使用自动提交连接…」）
- [ ] 脚本运行中途于工作台开启事务 → 下一条语句执行被拒 409，脚本停止并报告
- [ ] 脚本执行完成后事务面板仍「未开启」——脚本语句不产生未提交状态；UPDATE 后无「未提交」标识（uncommitted=false）

## 5. 历史与汇总

- [ ] 脚本逐条执行期间与结束后，工作台会话历史**计数不增**（脚本语句不写历史）
- [ ] 完整跑完 → 恰好一条汇总历史：文件名标识 + 完整脚本原文 + SUCCEEDED；点击无「载入编辑器」，提示改在脚本执行页重新上传
- [ ] 中途停止的运行**不产生**汇总条目；恢复至全部完成后恰产生一条（不因恢复分段重复入史）
- [ ] 汇总条目参与 100 条容量淘汰，普通语句条目行为不变

## 6. 失效与守卫路径

- [ ] 上传后重启服务 → 执行需确认语句 → CONFIRMATION_REQUIRED → UI 提示凭据失效重新上传；汇总调用 `SCRIPT_RUN_NOT_FOUND` 提示（重启后脚本运行暂存清空）
- [ ] 预校验后切换数据源 → 提示预校验与凭据作废；脚本**运行中**切换数据源被阻止
- [ ] 运行中取消请求失败/页面刷新：后续语句不被下发（刷新后重新上传恢复），无孤儿执行

## 7. 配置与交付

- [ ] 配置**缺省**（无 `script:` 段）启动正常，行为等于 5MB/1000 条默认值
- [ ] JAR 启动脚本入口可用（外部配置 + 环境变量凭据，沿用 T17 §5.3 流程）；Docker 同（可选，可引用 T17 已验基线）

## 测试清理

- [ ] MySQL/DM8 验收表及脚本建表（`dbconsole_f13_*`）全部 DROP；审计文件留存备查
- [ ] `config/local` 临时调整的上限配置还原

## 不适用项

- DELIMITER/存储过程脚本、dump 回灌、全局运行时长上限、multipart 上传：规格明文排除
- TLS 握手：沿用 T17 结论（本地明文连接），部署启用 TLS 时补验

## 附录 A：验收脚本素材（本地临时，勿入库）

**A-1 MySQL 主脚本（全部成功）** `f13-mysql.sql`

```sql
-- 初始化
UPDATE dbconsole_f13_items SET name = 'accepted' WHERE id = 1;
SELECT COUNT(*) AS items FROM dbconsole_f13_items;
CREATE TABLE dbconsole_f13_notes (id INT PRIMARY KEY, remark VARCHAR(64));
```

**A-1 变体（失败即停）**：末条改为 `INSERT INTO dbconsole_f13_items VALUES (1, 'dup');`（主键冲突）

**A-2 DM8 主脚本** `f13-dm8.sql`

```sql
UPDATE DBCONSOLE_F13_ITEMS SET NAME = 'accepted' WHERE ID = 1;
SELECT COUNT(*) AS ITEMS FROM DBCONSOLE_F13_ITEMS;
COMMENT ON TABLE DBCONSOLE_F13_ITEMS IS 'F13 验收表';
```

**A-3 范围外** `f13-bad.sql`：`SELECT 1;` + `TRUNCATE TABLE dbconsole_f13_items;`
**A-4 超大**：循环生成 `SELECT 1;` 至 >5MB
**A-5 过程体** `f13-proc.sql`：`CREATE PROCEDURE p() BEGIN SELECT 1; END;`（MySQL 族）
**A-6 取消素材**：`SELECT SLEEP(15);`（MySQL）；DM8 用大表笛卡尔积慢查或会话级锁等待
