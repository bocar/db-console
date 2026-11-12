# 手写 SQL 词法分析器，不引入第三方解析库

后端需要做语句边界判定（分号不得被字符串、引用标识符或注释干扰）、操作类型粗分类（SELECT/INSERT/UPDATE/DELETE/表级 DDL，含 WITH 的实际操作）和最外层 WHERE 检测。决定在方言层手写轻量 tokenizer + 分类器，不使用 JSqlParser 等语法解析库：DM8 方言语法（`COMMENT ON`、DM 特有类型）会让第三方解析器频繁解析失败，而需求只需粗粒度判断。安全边界不依赖解析器——单语句约束由 JDBC 执行层兜底，操作权限由数据库账号自身决定。

## Considered Options

- JSqlParser 等解析库：方言覆盖不足，DM8 语句解析失败即阻塞执行；且完整语法树对本需求是过度设计。
