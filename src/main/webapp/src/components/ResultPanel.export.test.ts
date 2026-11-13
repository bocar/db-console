import { mount } from '@vue/test-utils'

// jsdom 不支持 URL.createObjectURL
URL.createObjectURL = () => 'blob:mock'
URL.revokeObjectURL = () => {}
import { describe, expect, it } from 'vitest'
import ResultPanel from './ResultPanel.vue'
import type { QueryColumn, QueryRow, QueryValue, UpdateResult } from '../types'

const columns: QueryColumn[] = [
  { name: '名称', type: 'VARCHAR' },
  { name: '备注', type: 'TEXT' },
]

const rows: QueryRow[] = [
  ['星河贸易,有限公司', '含"引号"的文本'],
  ['第二行\n换行内容', null],
]

function mountExportPanel(overrides: Record<string, unknown> = {}) {
  return mount(ResultPanel, {
    props: {
      columns,
      rows,
      page: 1,
      pageSize: 20,
      totalRows: rows.length,
      elapsed: 42,
      activeTab: 'result',
      transactionPending: false,
      sourceName: '测试库',
      hasResult: true,
      truncated: false,
      ...overrides,
    },
  })
}

describe('ResultPanel 导出与复制（F-15）', () => {
  it('CSV 转义正确处理中文、逗号、引号、换行', () => {
    const wrapper = mountExportPanel()
    const csvValue = (wrapper.vm as unknown as { csvValue: (v: QueryValue) => string }).csvValue
    expect(csvValue('普通中文')).toBe('普通中文')
    expect(csvValue('含,逗号')).toBe('"含,逗号"')
    expect(csvValue('含"引号"')).toBe('"含""引号"""')
    expect(csvValue('含\n换行')).toBe('"含\n换行"')
    expect(csvValue(null)).toBe('')
  })

  it('导出通知包含行数', () => {
    const wrapper = mountExportPanel()
    const vm = wrapper.vm as unknown as { exportCsv: () => void }
    vm.exportCsv()
    const notifications = wrapper.emitted('notify')
    expect(notifications).toBeTruthy()
    expect(notifications![notifications!.length - 1][0]).toContain(`${rows.length} 行`)
  })

  it('截断时导出通知包含截断标记', () => {
    const wrapper = mountExportPanel({ truncated: true })
    const vm = wrapper.vm as unknown as { exportCsv: () => void }
    vm.exportCsv()
    const notifications = wrapper.emitted('notify')
    expect(notifications![notifications!.length - 1][0]).toContain('已截断')
  })

  it('受影响行数缺失时明确标注（DDL）', () => {
    const ddl: UpdateResult = {
      kind: 'UPDATE', affectedRows: null, elapsedMs: 9, uncommitted: false, implicitCommitDdl: true,
    }
    const wrapper = mountExportPanel({ activeTab: 'info', updateInfo: ddl })
    expect(wrapper.text()).toContain('驱动未提供行数')
  })
})
