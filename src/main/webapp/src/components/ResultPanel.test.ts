import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ResultPanel from './ResultPanel.vue'
import type { QueryColumn, QueryRow, UpdateResult } from '../types'

const queryColumns: QueryColumn[] = [
  { name: 'id', type: 'BIGINT' },
  { name: 'name', type: 'VARCHAR(64)' },
]

const queryRows: QueryRow[] = [
  ['920000000000000001', '星河贸易有限公司'],
  [null, ''],
]

function mountPanel(overrides: Record<string, unknown> = {}) {
  return mount(ResultPanel, {
    props: {
      columns: queryColumns,
      rows: queryRows,
      page: 1,
      pageSize: 20,
      totalRows: 2,
      elapsed: 42,
      activeTab: 'result',
      transactionPending: false,
      sourceName: 'MySQL 工作笔记库',
      hasResult: true,
      ...overrides,
    },
  })
}

describe('ResultPanel', () => {
  it('renders columns and rows by position', () => {
    const wrapper = mountPanel()

    expect(wrapper.text()).toContain('id')
    expect(wrapper.text()).toContain('BIGINT')
    expect(wrapper.text()).toContain('星河贸易有限公司')
    expect(wrapper.text()).toContain('920000000000000001')
  })

  it('distinguishes SQL NULL from empty string in cells', () => {
    const wrapper = mountPanel()

    expect(wrapper.text()).toContain('NULL')
    expect(wrapper.text()).toContain('空字符串')
  })

  it('keeps column headings for zero-row queries', () => {
    const wrapper = mountPanel({ rows: [], totalRows: 0 })

    expect(wrapper.text()).toContain('id')
    expect(wrapper.text()).toContain('查询已执行，未返回数据行。')
  })

  it('shows affected rows for update results and marks missing counts', async () => {
    const update: UpdateResult = { kind: 'UPDATE', affectedRows: 3, elapsedMs: 12, uncommitted: false, implicitCommitDdl: false }
    const withCount = mountPanel({ activeTab: 'info', updateInfo: update })
    expect(withCount.text()).toContain('受影响行数')
    expect(withCount.text()).toContain('3')

    const withoutCount: UpdateResult = { kind: 'UPDATE', affectedRows: null, elapsedMs: 9, uncommitted: false, implicitCommitDdl: true }
    const ddl = mountPanel({ activeTab: 'info', updateInfo: withoutCount })
    expect(ddl.text()).toContain('驱动未提供行数')
  })

  it('uses server total pages and disables next on the last page', () => {
    const wrapper = mountPanel({ page: 2, totalRows: 2, totalPages: 2, hasNextPage: false, paginated: true })

    expect(wrapper.text()).toContain('/ 2 页')
    expect(wrapper.get('button[aria-label="下一页"]').attributes('disabled')).toBeDefined()
  })

  it('allows the next page with unknown totals when the server has more rows', () => {
    const wrapper = mountPanel({ totalRows: null, totalPages: null, hasNextPage: true, paginated: true })

    expect(wrapper.text()).toContain('总数未知')
    expect(wrapper.get('button[aria-label="下一页"]').attributes('disabled')).toBeUndefined()
  })

  it('hides pagination for non-query outcomes', () => {
    const wrapper = mountPanel({ paginated: false })

    expect(wrapper.find('.pagination-bar').exists()).toBe(false)
  })
})
