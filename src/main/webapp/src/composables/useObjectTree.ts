import { computed, ref } from 'vue'
import { useApiClient } from '../api/client'
import type { TableDefinition } from '../types'
import { useToast } from './useToast'

const tables = ref<TableDefinition[]>([])
const loading = ref(false)
const selectedTableName = ref('')
let loadGeneration = 0

/** 对象树（F-03）：表结构与当前选中表；迟到的旧响应被丢弃。 */
export function useObjectTree() {
  const api = useApiClient()
  const toast = useToast()

  const selectedTable = computed<TableDefinition | null>(
    () => tables.value.find((table) => table.name === selectedTableName.value) ?? null,
  )

  async function loadTables(sourceId: string): Promise<void> {
    const generation = ++loadGeneration
    loading.value = true
    try {
      const loaded = await api.metadata.tables(sourceId)
      if (generation !== loadGeneration) return
      tables.value = loaded
      if (!tables.value.some((table) => table.name === selectedTableName.value)) {
        selectedTableName.value = tables.value[0]?.name ?? ''
      }
    } catch (error) {
      if (generation !== loadGeneration) return
      tables.value = []
      toast.notify(error instanceof Error ? `对象结构加载失败：${error.message}` : '对象结构加载失败。')
    } finally {
      if (generation === loadGeneration) {
        loading.value = false
      }
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
