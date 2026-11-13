import { computed, ref } from 'vue'
import { useApiClient } from '../api/client'
import type { TableDefinition } from '../types'

const tables = ref<TableDefinition[]>([])
const loading = ref(false)
const loadError = ref('')
const selectedTableName = ref('')
let loadGeneration = 0

/** 对象树（F-03）：表结构与当前选中表；迟到的旧响应被丢弃。 */
export function useObjectTree() {
  const api = useApiClient()

  const selectedTable = computed<TableDefinition | null>(
    () => tables.value.find((table) => table.name === selectedTableName.value) ?? null,
  )

  async function loadTables(sourceId: string): Promise<void> {
    const generation = ++loadGeneration
    loading.value = true
    try {
      currentSourceId = sourceId
      const loaded = await api.metadata.tables(sourceId)
      if (generation !== loadGeneration) return
      tables.value = loaded
      loadError.value = ''
      if (!tables.value.some((table) => table.name === selectedTableName.value)) {
        selectedTableName.value = tables.value[0]?.name ?? ''
      }
      // 选中表时补充索引/外键完整结构（列表接口返回简要结构）
      await refreshStructureDetail(sourceId)
    } catch (error) {
      if (generation !== loadGeneration) return
      tables.value = []
      // 错误态记录在 loadError 供面板展示（T06 端点落地时消费），不弹 toast 干扰执行反馈
      loadError.value = error instanceof Error ? error.message : '对象结构加载失败。'
    } finally {
      if (generation === loadGeneration) {
        loading.value = false
      }
    }
  }

  function selectTable(name: string): void {
    selectedTableName.value = name
    // 切表时异步补全结构（索引/外键）
    const sourceId = currentSourceId
    if (sourceId) void refreshStructureDetail(sourceId)
  }

  let currentSourceId = ''

  async function refreshStructureDetail(sourceId: string): Promise<void> {
    const tableName = selectedTableName.value
    if (!tableName) return
    try {
      const detail = await api.metadata.tableStructure(sourceId, tableName)
      // 原位更新，保持选中
      const index = tables.value.findIndex((table) => table.name === tableName)
      if (index >= 0) {
        tables.value = tables.value.map((table, i) => (i === index ? detail : table))
      }
    } catch {
      // 结构补全失败保持列表数据
    }
  }

  function reset(): void {
    tables.value = []
    loading.value = false
    loadError.value = ''
    selectedTableName.value = ''
  }

  return { tables, loading, loadError, selectedTableName, selectedTable, loadTables, selectTable, reset }
}
