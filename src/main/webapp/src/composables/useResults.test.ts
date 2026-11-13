import { defineComponent } from 'vue'
import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { API_CLIENT_KEY, type ApiClient } from '../api/client'
import { fakeApiClient } from '../api/test-support'
import type { QueryResult } from '../types'
import { useResults } from './useResults'

function result(page: number, value: number, pageSize = 20): QueryResult {
  return {
    kind: 'QUERY',
    columns: [{ name: 'id', type: 'INT' }],
    rows: [[value]],
    elapsedMs: 3,
    truncated: false,
    page,
    pageSize,
    hasNextPage: page < 2,
    totalRows: 2,
    totalPages: 2,
  }
}

function mountResults(page: ApiClient['sql']['page']) {
  const client = fakeApiClient({ sql: { ...fakeApiClient().sql, page } })
  let handle: ReturnType<typeof useResults> | undefined
  const host = defineComponent({
    setup() {
      handle = useResults()
      handle.reset()
      return () => null
    },
  })
  mount(host, { global: { provide: { [API_CLIENT_KEY]: client } } })
  if (!handle) throw new Error('useResults 未初始化')
  return handle
}

describe('useResults 服务端分页', () => {
  it('翻页携带原数据源、SQL 和页大小，并显示响应页', async () => {
    const page = vi.fn<ApiClient['sql']['page']>().mockResolvedValue(result(2, 2))
    const handle = mountResults(page)
    handle.showQuery(result(1, 1), 'mysql-work-note', 'SELECT id FROM t ORDER BY id')

    await handle.changePage(2)

    expect(page).toHaveBeenCalledWith(
      'mysql-work-note', 'SELECT id FROM t ORDER BY id', 2, 20)
    expect(handle.page.value).toBe(2)
    expect(handle.visibleRows.value).toEqual([[2]])
  })

  it('修改每页行数重新请求第一页', async () => {
    const page = vi.fn<ApiClient['sql']['page']>().mockResolvedValue(result(1, 5, 50))
    const handle = mountResults(page)
    handle.showQuery(result(2, 2), 'mysql-work-note', 'SELECT id FROM t ORDER BY id')

    await handle.changePageSize(50)

    expect(page).toHaveBeenCalledWith(
      'mysql-work-note', 'SELECT id FROM t ORDER BY id', 1, 50)
    expect(handle.page.value).toBe(1)
    expect(handle.pageSize.value).toBe(50)
    expect(handle.visibleRows.value).toEqual([[5]])
  })

  it('切源重置后忽略迟到的翻页响应', async () => {
    let resolvePage: ((value: QueryResult) => void) | undefined
    const page = vi.fn<ApiClient['sql']['page']>().mockReturnValue(
      new Promise((resolve) => { resolvePage = resolve }),
    )
    const handle = mountResults(page)
    handle.showQuery(result(1, 1), 'mysql-work-note', 'SELECT id FROM t ORDER BY id')

    const pending = handle.changePage(2)
    handle.reset()
    resolvePage?.(result(2, 2))
    await pending

    expect(handle.queryResult.value).toBeNull()
    expect(handle.page.value).toBe(1)
    expect(handle.visibleRows.value).toEqual([])
  })
})
