import { computed, ref } from 'vue'
import { useApiClient } from '../api/client'
import type { TableDefinition } from '../types'
import { useToast } from './useToast'

const tables = ref<TableDefinition[]>([])
const loading = ref(false)
const selectedTableName = ref('')

/** 对象树（F-03）：表结构与当前选中表。 */
export function useObjectTree() {
  const api = useApiClient()
  const toast = useToast()

  const selectedTable = computed<TableDefinition | null>(
    () => tables.value.find((table) => table.name === selectedTableName.value) ?? null,
  )

  async function loadTables(sourceId: string): Promise<void> {
    loading.value = true
    try {
      tables.value = await api.metadata.tables(sourceId)
      if (!tables.value.some((table) => table.name === selectedTableName.value)) {
        selectedTableName.value = tables.value[0]?.name ?? ''
      }
    } catch (error) {
      tables.value = []
      toast.notify(error instanceof Error ? `对象结构加载失败：${error.message}` : '对象结构加载失败。')
    } finally {
      loading.value = false
    }
  }

  function selectTable(name: string): void {
    selectedTableName.value = name
  }

  function reset(): void {
    tables.value = []
    loading.value = false
    selectedTableName.value = ''
  }

  return { tables, loading, selectedTableName, selectedTable, loadTables, selectTable, reset }
}
