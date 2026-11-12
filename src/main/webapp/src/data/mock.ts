import type { DataSource, HistoryEntry, QueryColumn, QueryRow, TableDefinition } from '../types'

export const dataSources: DataSource[] = [
  {
    id: 'orders-prod',
    name: '订单生产库',
    environment: '生产',
    database: 'commerce',
    currentSchema: 'commerce',
    type: 'MySQL 8.0',
  },
  {
    id: 'orders-staging',
    name: '订单测试库',
    environment: '测试',
    database: 'commerce_test',
    currentSchema: 'commerce_test',
    type: 'DM8',
  },
  {
    id: 'analytics-dev',
    name: '分析开发库',
    environment: '开发',
    database: 'analytics',
    currentSchema: 'analytics',
    type: 'MySQL 8.0',
  },
]

export const tables: TableDefinition[] = [
  {
    name: 'orders',
    comment: '客户订单主表',
    columns: [
      { name: 'id', type: 'BIGINT', nullable: false, defaultValue: null, primaryKey: true, comment: '订单主键' },
      { name: 'order_no', type: 'VARCHAR(64)', nullable: false, defaultValue: null, primaryKey: false, comment: '业务订单号' },
      { name: 'customer_id', type: 'BIGINT', nullable: false, defaultValue: null, primaryKey: false, comment: '客户 ID' },
      { name: 'status', type: 'VARCHAR(24)', nullable: false, defaultValue: "'PENDING'", primaryKey: false, comment: '订单状态' },
      { name: 'total_amount', type: 'DECIMAL(18,2)', nullable: false, defaultValue: '0.00', primaryKey: false, comment: '订单总金额' },
      { name: 'remark', type: 'VARCHAR(512)', nullable: true, defaultValue: null, primaryKey: false, comment: '订单备注' },
      { name: 'created_at', type: 'DATETIME', nullable: false, defaultValue: 'CURRENT_TIMESTAMP', primaryKey: false, comment: '创建时间' },
      { name: 'updated_at', type: 'DATETIME', nullable: false, defaultValue: 'CURRENT_TIMESTAMP', primaryKey: false, comment: '更新时间' },
    ],
  },
  {
    name: 'customers',
    comment: '客户信息',
    columns: [
      { name: 'id', type: 'BIGINT', nullable: false, defaultValue: null, primaryKey: true, comment: '客户主键' },
      { name: 'customer_name', type: 'VARCHAR(128)', nullable: false, defaultValue: null, primaryKey: false, comment: '客户名称' },
      { name: 'phone', type: 'VARCHAR(32)', nullable: true, defaultValue: null, primaryKey: false, comment: '联系电话' },
      { name: 'created_at', type: 'DATETIME', nullable: false, defaultValue: 'CURRENT_TIMESTAMP', primaryKey: false, comment: '创建时间' },
    ],
  },
  {
    name: 'order_items',
    comment: '订单明细',
    columns: [
      { name: 'id', type: 'BIGINT', nullable: false, defaultValue: null, primaryKey: true, comment: '明细主键' },
      { name: 'order_id', type: 'BIGINT', nullable: false, defaultValue: null, primaryKey: false, comment: '订单 ID' },
      { name: 'sku_id', type: 'BIGINT', nullable: false, defaultValue: null, primaryKey: false, comment: '商品 ID' },
      { name: 'quantity', type: 'INT', nullable: false, defaultValue: '1', primaryKey: false, comment: '购买数量' },
    ],
  },
  {
    name: 'payments',
    comment: '支付记录',
    columns: [
      { name: 'id', type: 'BIGINT', nullable: false, defaultValue: null, primaryKey: true, comment: '支付主键' },
      { name: 'order_id', type: 'BIGINT', nullable: false, defaultValue: null, primaryKey: false, comment: '订单 ID' },
      { name: 'paid_amount', type: 'DECIMAL(18,2)', nullable: false, defaultValue: '0.00', primaryKey: false, comment: '支付金额' },
    ],
  },
  {
    name: 'refunds',
    comment: '退款记录',
    columns: [
      { name: 'id', type: 'BIGINT', nullable: false, defaultValue: null, primaryKey: true, comment: '退款主键' },
      { name: 'payment_id', type: 'BIGINT', nullable: false, defaultValue: null, primaryKey: false, comment: '支付 ID' },
      { name: 'reason', type: 'VARCHAR(512)', nullable: true, defaultValue: null, primaryKey: false, comment: '退款原因' },
    ],
  },
  {
    name: 'inventory',
    comment: '商品库存',
    columns: [
      { name: 'sku_id', type: 'BIGINT', nullable: false, defaultValue: null, primaryKey: true, comment: '商品 ID' },
      { name: 'available_count', type: 'INT', nullable: false, defaultValue: '0', primaryKey: false, comment: '可用库存' },
      { name: 'locked_count', type: 'INT', nullable: false, defaultValue: '0', primaryKey: false, comment: '锁定库存' },
    ],
  },
]

export const defaultSql = `SELECT
  o.id,
  o.order_no,
  c.customer_name,
  o.status,
  o.total_amount,
  o.created_at
FROM orders AS o
LEFT JOIN customers AS c ON c.id = o.customer_id
WHERE o.status = 'PAID'
ORDER BY o.created_at DESC;`

export const queryColumns: QueryColumn[] = [
  { name: 'id', type: 'BIGINT' },
  { name: 'order_no', type: 'VARCHAR(64)' },
  { name: 'customer_name', type: 'VARCHAR(128)' },
  { name: 'status', type: 'VARCHAR(24)' },
  { name: 'total_amount', type: 'DECIMAL(18,2)' },
  { name: 'created_at', type: 'DATETIME' },
]

const customerNames = ['星河贸易有限公司', '青禾科技', '南山工作室', '远帆物流', '沐光设计', '森屿零售']
const statuses = ['PAID', 'PENDING', 'SHIPPED', 'REFUNDED']

export const queryRows: QueryRow[] = Array.from({ length: 57 }, (_, index) => [
  String(920000000000000000n + BigInt(index)),
  `SO2026${String(11012001 + index)}`,
  index % 9 === 8 ? null : customerNames[index % customerNames.length],
  statuses[index % statuses.length],
  ((index * 7919 + 12800) / 100).toFixed(2),
  `2026-11-${String((index % 12) + 1).padStart(2, '0')} ${String(9 + (index % 9)).padStart(2, '0')}:${String((index * 7) % 60).padStart(2, '0')}:18`,
])

export const initialHistory: HistoryEntry[] = [
  { id: 1, sourceId: 'orders-prod', sql: defaultSql, time: '14:32:18', duration: '86 ms', status: '成功' },
  { id: 2, sourceId: 'orders-prod', sql: "UPDATE orders SET status = 'PAID' WHERE order_no = 'SO20261101288';", time: '14:26:08', duration: '42 ms', status: '未提交' },
  { id: 3, sourceId: 'orders-prod', sql: 'DESC orders;', time: '14:18:51', duration: '18 ms', status: '成功' },
  { id: 4, sourceId: 'orders-prod', sql: 'SELECT * FROM customers WHERE id = 10028;', time: '13:57:03', duration: '31 ms', status: '成功' },
]
