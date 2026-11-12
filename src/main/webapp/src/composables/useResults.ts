import { computed, ref } from 'vue'
import type { QueryRow, QueryResult, UpdateResult } from '../types'

export type ResultTab = 'result' | 'structure' | 'info'

const queryResult = ref<QueryResult | null>(null)
const updateResult = ref<UpdateResult | null>(null)
const activeTab = ref<ResultTab>('result')
const page = ref(1)
const pageSize = ref(20)

/**
 * 结果区（F-08/F-11）：持有本次已加载的结果。
 * 分页当前是对已加载数据的本地视图；后端分页在分页票接入。
 */
export function useResults() {
  const hasResult = computed(() => queryResult.value !== null || updateResult.value !== null)
  const totalRows = computed(() => queryResult.value?.rows.length ?? 0)
  const visibleRows = computed<QueryRow[]>(() => {
    const rows = queryResult.value?.rows
    if (!rows) return []
    const start = (page.value - 1) * pageSize.value
    return rows.slice(start, start + pageSize.value)
  })
  const visibleColumns = computed(() => queryResult.value?.columns ?? [])
  const elapsedMs = computed(() => queryResult.value?.elapsedMs ?? updateResult.value?.elapsedMs ?? 0)

  function showQuery(result: QueryResult): void {
    queryResult.value = result
    updateResult.value = null
    page.value = 1
    activeTab.value = 'result'
  }

  function showUpdate(result: UpdateResult): void {
    updateResult.value = result
    queryResult.value = null
    page.value = 1
    activeTab.value = 'info'
  }

  function changePageSize(size: number): void {
    pageSize.value = size
    page.value = 1
  }

  function reset(): void {
    queryResult.value = null
    updateResult.value = null
    activeTab.value = 'result'
    page.value = 1
    pageSize.value = 20
  }

  return {
    queryResult,
    updateResult,
    activeTab,
    page,
    pageSize,
    hasResult,
    totalRows,
    visibleRows,
    visibleColumns,
    elapsedMs,
    showQuery,
    showUpdate,
    changePageSize,
    reset,
  }
}
