# 前端唯一数据缝：ApiClient 接口 + 域 composable 单例

后端接入前（T02 起），前端把工作台状态沉入按域拆分的 composable 单例（会话/数据源/执行/手动事务/结果/历史），全部数据访问穿过唯一的 ApiClient 接口；现有 mock 升格为测试 adapter，T02 的 HTTP 客户端是第二个 adapter。决定不引入 Pinia（单实例工作台，无多 store 共享与 devtools 需求）、不引入 OpenAPI 类型生成（手写镜像后端 wire 类型，漂移靠 seam 两侧同型测试兜住）：两者都为这个规模不存在的问题引入工具链。

## Considered Options

- Pinia：其价值在多 store 跨组件树共享；本应用单会话单工作台，模块级 composable 单例已满足，新增依赖违背 YAGNI。
- springdoc + openapi-typescript 生成：DTO 集小而稳定，生成链路（两个新工具 + 版本锁定）成本大于漂移检测收益。
