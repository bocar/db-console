# 验收记录

## 可事务化 DDL

MySQL 和 DM8 的常规建表、改表、删表语句会触发隐式提交，不支持事务化 DDL（无 `BEGIN TRANSACTION` 包裹 DDL 的语法），首批验收中标记为**不适用**。

- MySQL：InnoDB 的 DDL 操作自带隐式提交（参考 [MySQL 隐式提交](https://dev.mysql.com/doc/refman/8.4/en/implicit-commit.html)）。
- DM8：Oracle 系语法，DDL 同样触发隐式提交（参考 [达梦事务管理](https://eco.dameng.com/document/dm/zh-cn/pm/management-affairs.html)）。

后续支持事务化 DDL 的目标库（如 PostgreSQL 的 `BEGIN; CREATE TABLE; ROLLBACK;`）验收时，需验证各环境均可直接执行、再手动提交或回滚。

## 环境说明

- 本轮未启用 TLS；MySQL localhost 与 DM8 远程均为明文连接，验收记录注明。
- DM8 驱动 `getDatabaseMinorVersion` 存在已知异常，元数据层避免依赖该方法。
