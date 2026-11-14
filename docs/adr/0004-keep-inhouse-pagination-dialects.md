# 分页方言适配保持自研收口，第三方解析器列为触发式备选

分页需适配多种数据库方言（F-11），曾有提议引入主流开源分页框架。裁决：**维持自研**——`dialect.PaginationSql` 是唯一的方言 SQL 生成收口点（ADR-0002 的延伸），不引入第三方分页/解析框架；同时设定明确的**重评触发器**，触发前不做任何预承诺。

理由：

1. **动机为预研铺路而非缺陷修复**。现实现经 T17 双实库验收（MySQL 8.0.28 + DM8 V8，25 行 × 3 页无重漏等），无缺陷记录；无第三方言排期时替换，是用真实回归风险换假设性收益。
2. **主流「分页框架」均绑 ORM**。PageHelper 6.1.1（MIT）编译期依赖 MyBatis，引入即反转「纯 JDBC 单语句执行」的基础架构决策（连接管理、事务绑定、审计、取消链路全部受影响），出局。
3. **唯一现实的非 ORM 候选 JSqlParser 不覆盖首批方言的关键面**。5.4（2026-09，LGPL-2.1/Apache-2.0 双许可，约 2MB，零传递依赖）方言列表无 Dameng/DM8、TiDB、OceanBase——DM8 只能非正式搭 Oracle 兼容语法车（PageHelper 的方言表同样把 dm 映射到 OracleDialect）；且它**不提供方言间 LIMIT 变换**——解析成 AST 后跨方言改写仍是使用方代码，即引入后仍要自写每方言的包装逻辑，只省下词法解析。
4. **职责边界（若将来引入）**：解析器只允许接管分页/COUNT 的 SQL 生成；ADR-0001 的手写安全分析器（语句边界、操作分类、最外层 WHERE/INTO 检测——全部业务规则的执行地基）不外包给第三方解析器，其解析失败语义必须 fail-closed：拒绝该查询的分页、允许执行原查询（与「COUNT 包装失败→总数未知」降级口径一致）。

**重评触发器（任一满足即重开评估）**：

- 立项任何**非 MySQL 系**方言（Oracle / SQL Server 等 ROWNUM/FETCH 族）；
- 手写适配出现成本信号：新方言需要在 `PaginationSql` 之外另写专用包装器。

## Consequences

- 新方言（MySQL 系：TiDB/OceanBase 兼容模式）落地成本极低，符合预期；
- Oracle/SQL Server 立项时按触发器重评，评估对象与事实基线见附录，避免从零开始；
- 本决策不预承诺任何库——重评时的选型以当时的方言支持事实为准。

## 附录：探查事实基线（2026-11-14）

| 候选 | 版本/许可 | 关键事实 |
| --- | --- | --- |
| JSqlParser | 5.4（2026-09）LGPL-2.1/Apache-2.0 | ~2MB 零传递依赖；单语法覆盖多方言但无 DM8/TiDB/OceanBase 一等支持；无方言间 LIMIT 变换（AST 改写 DIY）；解析失败抛 `JSQLParserException` |
| PageHelper | 6.1.1（2025-06）MIT | MyBatis 插件（编译期依赖 mybatis 3.5.19），裸 JDBC 不可用；方言注册表 `dm→OracleDialect` |
| ShardingSphere / Calcite / Druid | — | 重量级（分片中间件/优化器/连接池内嵌解析器），与「分页 SQL 生成」单一需求不成比例 |

（来源：Maven Central 元数据、JSqlParser 官方 README 与 wiki、PageHelper 6.1.1 POM。）
