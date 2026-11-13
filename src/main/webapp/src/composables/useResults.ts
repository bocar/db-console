import { computed, ref } from 'vue'
import { useApiClient } from '../api/client'
import type { QueryRow, QueryResult, UpdateResult } from '../types'
import { useToast } from './useToast'

export type ResultTab = 'result' | 'structure' | 'info'

const queryResult = ref<QueryResult | null>(null)
const updateResult = ref<UpdateResult | null>(null)
const activeTab = ref<ResultTab>('result')
const page = ref(1)
const pageSize = ref(20)
const isPaging = ref(false)
const currentSourceId = ref('')
const currentSql = ref('')
let requestSerial = 0

/** 结果区（F-08/F-11）：只保存服务端返回的当前页及原查询上下文。 */
export function useResults() {
  const api = useApiClient()
  const toast = useToast()
  const hasResult = computed(() => queryResult.value !== null || updateResult.value !== null)
  const totalRows = computed(() => queryResult.value?.totalRows ?? null)
  const totalPages = computed(() => queryResult.value?.totalPages ?? null)
  const hasNextPage = computed(() => queryResult.value?.hasNextPage ?? false)
  const visibleRows = computed<QueryRow[]>(() => queryResult.value?.rows ?? [])
  const visibleColumns = computed(() => queryResult.value?.columns ?? [])
  const elapsedMs = computed(() => queryResult.value?.elapsedMs ?? updateResult.value?.elapsedMs ?? 0)

  function showQuery(result: QueryResult, sourceId?: string, sql?: string): void {
    requestSerial++
    if (sourceId !== undefined) currentSourceId.value = sourceId
    if (sql !== undefined) currentSql.value = sql
    queryResult.value = result
    updateResult.value = null
    page.value = result.page ?? 1
    if (result.pageSize != null) pageSize.value = result.pageSize
    isPaging.value = false
    activeTab.value = 'result'
  }

  function showUpdate(result: UpdateResult): void {
    requestSerial++
    updateResult.value = result
    queryResult.value = null
    currentSourceId.value = ''
    currentSql.value = ''
    page.value = 1
    isPaging.value = false
    activeTab.value = 'info'
  }

  async function changePage(target: number): Promise<void> {
    await loadPage(target, pageSize.value)
  }

  async function changePageSize(size: number): Promise<void> {
    await loadPage(1, size)
  }

  async function loadPage(target: number, size: number): Promise<void> {
    const sourceId = currentSourceId.value
    const sql = currentSql.value
    if (!queryResult.value || !sourceId || !sql || isPaging.value) return
    const lastPage = totalPages.value
    const requested = Math.max(1, Math.floor(target))
    const targetPage = lastPage == null ? requested : Math.min(requested, Math.max(1, lastPage))
    if (targetPage === page.value && size === pageSize.value) return
    const serial = ++requestSerial
    isPaging.value = true
    try {
      const result = await api.sql.page(sourceId, sql, targetPage, size)
      if (serial !== requestSerial || sourceId !== currentSourceId.value || sql !== currentSql.value) return
      if (result.kind !== 'QUERY') {
        // 翻页请求被取消（F-14）：终态提示，结果区保持原页
        toast.notify('查询已取消，本次翻页未完成。')
        return
      }
      showQuery(result)
    } catch (error) {
      if (serial === requestSerial) {
        toast.notify(error instanceof Error ? `分页失败：${error.message}` : '分页失败。')
      }
    } finally {
      if (serial === requestSerial) isPaging.value = false
    }
  }

  function reset(): void {
    requestSerial++
    queryResult.value = null
    updateResult.value = null
    activeTab.value = 'result'
    page.value = 1
    pageSize.value = 20
    isPaging.value = false
    currentSourceId.value = ''
    currentSql.value = ''
  }

  return {
    queryResult,
    updateResult,
    activeTab,
    page,
    pageSize,
    isPaging,
    hasResult,
    totalRows,
    totalPages,
    hasNextPage,
    visibleRows,
    visibleColumns,
    elapsedMs,
    showQuery,
    showUpdate,
    changePage,
    changePageSize,
    reset,
  }
}
