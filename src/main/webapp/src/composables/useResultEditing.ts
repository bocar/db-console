import { computed, ref, watch } from 'vue'
import { useApiClient } from '../api/client'
import { apiErrorMessage } from '../api/error-message'
import type {
  EditableColumn,
  EditableInfo,
  QueryRow,
  ResultEditRequest,
  ResultEditResponse,
  ResultEditStatementPreview,
  ResultEditValue,
} from '../types'
import { useManualTransaction } from './useManualTransaction'
import { useResults } from './useResults'
import { useToast } from './useToast'

const NUMERIC_TYPE_PATTERN = /^(tiny|small|big)?int|^integer|^dec(imal)?|^num(ber|eric)?|^float|^double|^real|^bit/i

const cellEdits = ref(new Map<string, Map<number, ResultEditValue>>())
const deletedRowKeys = ref(new Set<string>())
const newRows = ref<Record<number, string>[]>([])
const newRowErrors = ref<Record<number, string>>({})
const editingCell = ref<{ rowKey: string; columnIndex: number } | null>(null)
const editDraft = ref('')
const saving = ref(false)
const pendingBatch = ref<{ statements: ResultEditStatementPreview[] } | null>(null)
let reloadingAfterSave = false

/**
 * 结果表格编辑（F-17 / GLOSSARY：暂存更改）：新增行、单元格修改与待删除行的客户端暂存，
 * 存在暂存更改期间阻止翻页、重新执行、切换数据源与调整每页行数（由工作台守卫调用 guard）。
 * 保存走语句管线：需确认时呈现整批语句预览，确认后随凭据重交。
 */
export function useResultEditing() {
  const api = useApiClient()
  const toast = useToast()
  const results = useResults()
  const transaction = useManualTransaction()

  const editableInfo = computed<EditableInfo | null>(() => results.queryResult.value?.editable ?? null)
  const isEditableResult = computed(() => editableInfo.value !== null && results.queryResult.value !== null)
  const editingLocked = computed(() => transaction.active.value)
  const dirty = computed(
    () =>
      cellEdits.value.size > 0 ||
      deletedRowKeys.value.size > 0 ||
      newRows.value.length > 0,
  )
  const canEditCells = computed(() => isEditableResult.value && !editingLocked.value && !saving.value)

  /** 主键列在结果列中的位置（按 primaryKeys 顺序，大小写不敏感匹配）。 */
  const primaryKeyIndexes = computed<number[]>(() => {
    const editable = editableInfo.value
    if (!editable) return []
    return editable.primaryKeys.map((name) => {
      const lower = name.toLowerCase()
      return editable.columns.findIndex((column) => column?.name.toLowerCase() === lower)
    })
  })

  function rowKeyOf(row: QueryRow): string | null {
    const indexes = primaryKeyIndexes.value
    if (!indexes.length || indexes.some((index) => index < 0)) return null
    return indexes.map((index) => (row[index] === null ? 'NULL' : JSON.stringify(row[index]))).join('|')
  }

  function rowByKey(key: string): QueryRow | null {
    for (const row of results.visibleRows.value) {
      if (rowKeyOf(row) === key) return row
    }
    return null
  }

  /** 单元格当前值：暂存值优先（显示层即所见暂存）。 */
  function cellDisplay(rowIndex: number, columnIndex: number): ResultEditValue {
    const row = results.visibleRows.value[rowIndex]
    const key = row ? rowKeyOf(row) : null
    if (key) {
      const edits = cellEdits.value.get(key)
      if (edits?.has(columnIndex)) return edits.get(columnIndex) ?? null
    }
    return row ? (row[columnIndex] ?? null) : null
  }

  function isRowDeleted(rowIndex: number): boolean {
    const row = results.visibleRows.value[rowIndex]
    const key = row ? rowKeyOf(row) : null
    return key !== null && deletedRowKeys.value.has(key)
  }

  function isCellEdited(rowIndex: number, columnIndex: number): boolean {
    const row = results.visibleRows.value[rowIndex]
    const key = row ? rowKeyOf(row) : null
    return key !== null && (cellEdits.value.get(key)?.has(columnIndex) ?? false)
  }

  function isEditing(rowIndex: number, columnIndex: number): boolean {
    const row = results.visibleRows.value[rowIndex]
    const key = row ? rowKeyOf(row) : null
    return key !== null && editingCell.value?.rowKey === key && editingCell.value.columnIndex === columnIndex
  }

  /** 双击进入编辑（Q14）：单击仍归行选择/复制；主键与只读列不可编辑。 */
  function beginEdit(rowIndex: number, columnIndex: number): void {
    if (!canEditCells.value || isRowDeleted(rowIndex)) return
    const column = editableInfo.value?.columns[columnIndex]
    if (!column || column.primaryKey) return
    const row = results.visibleRows.value[rowIndex]
    const key = row ? rowKeyOf(row) : null
    if (!key) return
    editingCell.value = { rowKey: key, columnIndex }
    const current = cellDisplay(rowIndex, columnIndex)
    editDraft.value = current === null ? '' : String(current)
  }

  /** 提交编辑：空输入 = SET NULL（Q11）；数值列按格式轻校验。 */
  function commitEdit(): void {
    const editing = editingCell.value
    if (!editing) return
    const column = editableInfo.value?.columns[editing.columnIndex]
    editingCell.value = null
    if (!column) return
    const raw = editDraft.value
    let value: ResultEditValue
    if (raw === '') {
      value = null
    } else if (isNumericColumn(column) && !isParseableNumber(raw)) {
      toast.notify(`列 ${column.name} 需要数值，本次输入未保存。`)
      return
    } else {
      value = typedValue(column, raw)
    }
    const edits = cellEdits.value.get(editing.rowKey) ?? new Map<number, ResultEditValue>()
    edits.set(editing.columnIndex, value)
    cellEdits.value.set(editing.rowKey, edits)
    // 触发响应式：Map 原地变更后重新赋值
    cellEdits.value = new Map(cellEdits.value)
  }

  function cancelEdit(): void {
    editingCell.value = null
  }

  function isNumericColumn(column: EditableColumn): boolean {
    return NUMERIC_TYPE_PATTERN.test(column.type)
  }

  function isParseableNumber(raw: string): boolean {
    return Number.isFinite(Number(raw)) && /^-?\d+(\.\d+)?$/.test(raw.trim())
  }

  function typedValue(column: EditableColumn, raw: string): ResultEditValue {
    return isNumericColumn(column) ? Number(raw) : raw
  }

  function addRow(): void {
    if (!canEditCells.value) return
    newRows.value = [...newRows.value, {}]
  }

  function removeNewRow(index: number): void {
    newRows.value = newRows.value.filter((_, i) => i !== index)
    delete newRowErrors.value[index]
  }

  /** 新增行单元格输入（组件与测试共用；不可变更新触发响应式）。 */
  function setNewRowValue(rowIndex: number, columnIndex: number, value: string): void {
    newRows.value = newRows.value.map((row, index) =>
      index === rowIndex ? { ...row, [columnIndex]: value } : row,
    )
  }

  /** 标记选中行为待删除；未保存的新行不支持行选择（由其自身的 ✕ 移除）。 */
  function deleteSelectedRows(selectedRowIndexes: ReadonlySet<number>): void {
    if (!canEditCells.value || selectedRowIndexes.size === 0) return
    const keys = new Set(deletedRowKeys.value)
    let missing = 0
    for (const rowIndex of selectedRowIndexes) {
      const row = results.visibleRows.value[rowIndex]
      const key = row ? rowKeyOf(row) : null
      if (key) keys.add(key)
      else missing++
    }
    deletedRowKeys.value = keys
    if (missing > 0) toast.notify(`${missing} 行缺少主键值，无法删除。`)
  }

  /** 脏状态守卫（Q7）：翻页 / 重新执行 / 切数据源 / 调页大小前调用，true 表示已拦截并提示。 */
  function blockedByPendingChanges(): boolean {
    if (!dirty.value) return false
    toast.notify('有未保存的表格更改，请先保存或放弃更改。')
    return true
  }

  function validateNewRows(): string | null {
    const editable = editableInfo.value
    if (!editable) return null
    const messages: string[] = []
    const errors: Record<number, string> = {}
    newRows.value.forEach((inputs, index) => {
      const rowMessages: string[] = []
      editable.columns.forEach((column, columnIndex) => {
        if (!column) return
        const raw = inputs[columnIndex] ?? ''
        if (raw !== '') {
          if (isNumericColumn(column) && !isParseableNumber(raw)) {
            rowMessages.push(`列 ${column.name} 需要数值`)
          }
          return
        }
        const required =
          !column.nullable &&
          !column.autoIncrement &&
          (column.defaultValue === null || column.defaultValue === '')
        if (required) rowMessages.push(`缺少必填列 ${column.name}`)
      })
      if (rowMessages.length > 0) {
        errors[index] = rowMessages.join('、')
        messages.push(`第 ${index + 1} 条新增行：${rowMessages.join('、')}`)
      }
    })
    newRowErrors.value = errors
    return messages.length > 0 ? `新增行未通过校验——${messages.join('；')}。` : null
  }

  function buildRequest(confirmationTokens: (string | null)[] | null): ResultEditRequest {
    const editable = editableInfo.value!
    const deletes: { key: Record<string, ResultEditValue> }[] = []
    for (const key of deletedRowKeys.value) {
      const row = rowByKey(key)
      if (!row) continue
      deletes.push({ key: primaryKeyMap(editable, row) })
    }
    const updates: { key: Record<string, ResultEditValue>; set: Record<string, ResultEditValue> }[] = []
    for (const [key, edits] of cellEdits.value) {
      if (deletedRowKeys.value.has(key)) continue
      const row = rowByKey(key)
      if (!row) continue
      const set: Record<string, ResultEditValue> = {}
      for (const [columnIndex, value] of edits) {
        const column = editable.columns[columnIndex]
        if (column) set[column.name] = value
      }
      if (Object.keys(set).length > 0) updates.push({ key: primaryKeyMap(editable, row), set })
    }
    const inserts: { values: Record<string, ResultEditValue> }[] = []
    for (const inputs of newRows.value) {
      const values: Record<string, ResultEditValue> = {}
      let any = false
      for (const [columnIndexRaw, raw] of Object.entries(inputs)) {
        const columnIndex = Number(columnIndexRaw)
        const column = editable.columns[columnIndex]
        if (!column || raw === '') continue
        values[column.name] = typedValue(column, raw)
        any = true
      }
      // 未输入过的空行静默丢弃（Q8）
      if (any) inserts.push({ values })
    }
    return {
      sourceId: results.currentSourceId.value,
      tableParts: editable.tableParts,
      deletes,
      updates,
      inserts,
      confirmationTokens,
    }
  }

  function primaryKeyMap(editable: EditableInfo, row: QueryRow): Record<string, ResultEditValue> {
    const map: Record<string, ResultEditValue> = {}
    editable.primaryKeys.forEach((name, index) => {
      const columnIndex = primaryKeyIndexes.value[index]
      map[name] = columnIndex >= 0 ? (row[columnIndex] ?? null) : null
    })
    return map
  }

  async function save(): Promise<void> {
    if (!isEditableResult.value || saving.value || editingLocked.value) return
    if (!dirty.value) return
    if (editingCell.value) commitEdit()
    const invalid = validateNewRows()
    if (invalid) {
      toast.notify(invalid)
      return
    }
    await submit(buildRequest(null))
  }

  async function submit(request: ResultEditRequest): Promise<void> {
    saving.value = true
    try {
      const response = await api.resultEdit.save(request)
      if (response.kind === 'RESULT_EDIT_CONFIRMATION') {
        pendingBatch.value = { statements: response.statements }
        return
      }
      await handleOutcome(response)
    } catch (error) {
      toast.notify(apiErrorMessage(error, '保存失败。'))
    } finally {
      saving.value = false
    }
  }

  async function confirmBatch(): Promise<void> {
    const batch = pendingBatch.value
    if (!batch || saving.value) return
    const tokens: (string | null)[] = batch.statements.map((statement) => statement.confirmationToken)
    pendingBatch.value = null
    await submit(buildRequest(tokens))
  }

  function cancelBatch(): void {
    pendingBatch.value = null
  }

  async function handleOutcome(response: ResultEditResponse): Promise<void> {
    if (response.kind === 'RESULT_EDIT_APPLIED') {
      toast.notify(`已提交 ${response.statements.length} 条变更。`)
      clearStaging()
      reloadingAfterSave = true
      try {
        await results.reload()
      } finally {
        reloadingAfterSave = false
      }
      return
    }
    if (response.kind === 'RESULT_EDIT_FAILED') {
      toast.notify(`保存失败，本次变更已整体回滚——${response.message}`)
      return
    }
    if (response.kind === 'UNKNOWN_OUTCOME') {
      toast.notify(`${response.message} 暂存更改已保留，请核实数据库状态后再决定重试或放弃。`)
      return
    }
    toast.notify('保存需先确认，请重试。')
  }

  /** 放弃更改（Q5）：丢弃全部暂存并重新取当前页。 */
  async function discardChanges(): Promise<void> {
    if (!dirty.value) return
    clearStaging()
    reloadingAfterSave = true
    try {
      await results.reload()
    } finally {
      reloadingAfterSave = false
    }
    toast.notify('已放弃全部未保存更改，并重新加载当前页。')
  }

  function clearStaging(): void {
    cellEdits.value = new Map()
    deletedRowKeys.value = new Set()
    newRows.value = []
    newRowErrors.value = {}
    editingCell.value = null
    pendingBatch.value = null
  }

  // 查询结果被整体替换（新查询/新数据源）时暂存随之失效；守卫使其通常不可达，防御性清空
  watch(
    () => results.queryResult.value,
    () => {
      if (!reloadingAfterSave) clearStaging()
    },
  )

  return {
    editableInfo,
    isEditableResult,
    editingLocked,
    canEditCells,
    dirty,
    saving,
    pendingBatch,
    newRows,
    newRowErrors,
    deletedRowKeys,
    cellDisplay,
    isRowDeleted,
    isCellEdited,
    isEditing,
    beginEdit,
    commitEdit,
    cancelEdit,
    editDraft,
    addRow,
    removeNewRow,
    setNewRowValue,
    deleteSelectedRows,
    validateNewRows,
    buildRequest,
    blockedByPendingChanges,
    save,
    confirmBatch,
    cancelBatch,
    discardChanges,
    clearStaging,
  }
}
