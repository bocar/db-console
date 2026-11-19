import { mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'

// jsdom 不支持 URL.createObjectURL
URL.createObjectURL = () => 'blob:mock'
URL.revokeObjectURL = () => {}
import { describe, expect, it } from 'vitest'
import ResultPanel from './ResultPanel.vue'
import { API_CLIENT_KEY } from '../api/client'
import { fakeApiClient } from '../api/test-support'
import { useResults } from '../composables/useResults'
import { useToast } from '../composables/useToast'
import type { QueryColumn, QueryRow, QueryResult, QueryValue, UpdateResult } from '../types'

const columns: QueryColumn[] = [
  { name: '名称', type: 'VARCHAR' },
  { name: '备注', type: 'TEXT' },
]

const rows: QueryRow[] = [
  ['星河贸易,有限公司', '含"引号"的文本'],
  ['第二行\n换行内容', null],
]

function mountExportPanel(options: { truncated?: boolean; update?: UpdateResult } = {}) {
  // F-17 深化：ResultPanel 经 useResults 自取数据——种子状态 + 宿主组件挂载
  const Host = defineComponent({
    setup() {
      const results = useResults()
      results.reset()
      if (options.update) {
        results.showUpdate(options.update)
        return () => h(ResultPanel, { transactionPending: false, sourceName: '测试库' })
      }
      results.showQuery({
        kind: 'QUERY',
        columns,
        rows,
        elapsedMs: 42,
        truncated: options.truncated ?? false,
        page: 1,
        pageSize: 20,
        totalRows: rows.length,
      } as QueryResult)
      return () => h(ResultPanel, { transactionPending: false, sourceName: '测试库' })
    },
  })
  return mount(Host, { global: { provide: { [API_CLIENT_KEY]: fakeApiClient() } } })
}
describe('ResultPanel 导出与复制（F-15）', () => {
  it('CSV 转义正确处理中文、逗号、引号、换行', () => {
    const wrapper = mountExportPanel()
    const csvValue = (wrapper.getComponent(ResultPanel).vm as unknown as { csvValue: (v: QueryValue) => string }).csvValue
    expect(csvValue('普通中文')).toBe('普通中文')
    expect(csvValue('含,逗号')).toBe('"含,逗号"')
    expect(csvValue('含"引号"')).toBe('"含""引号"""')
    expect(csvValue('含\n换行')).toBe('"含\n换行"')
    expect(csvValue(null)).toBe('')
  })

  it('导出通知包含行数', () => {
    const wrapper = mountExportPanel()
    ;(wrapper.getComponent(ResultPanel).vm as unknown as { exportCsv: () => void }).exportCsv()
    // notify 现经 useToast 单例（F-17 深化），不再走组件 emit
    expect(useToast().message.value).toContain(`${rows.length} 行`)
  })

  it('截断时导出通知包含截断标记', () => {
    const wrapper = mountExportPanel({ truncated: true })
    ;(wrapper.getComponent(ResultPanel).vm as unknown as { exportCsv: () => void }).exportCsv()
    expect(useToast().message.value).toContain('已截断')
  })

  it('受影响行数缺失时明确标注（DDL）', () => {
    const ddl: UpdateResult = {
      kind: 'UPDATE', affectedRows: null, elapsedMs: 9, uncommitted: false, implicitCommitDdl: true,
    }
    const wrapper = mountExportPanel({ update: ddl })
    expect(wrapper.text()).toContain('驱动未提供行数')
  })
})
