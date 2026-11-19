<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Check, ChevronLeft, ChevronRight, Clipboard, Code2, Download, Info, KeyRound, Plus, Save, Table2, Trash2, Undo2, X } from 'lucide-vue-next'
import type { EditableColumn, QueryValue, TableDefinition } from '../types'
import { displayQueryValue } from '../display'
import { useResultEditing } from '../composables/useResultEditing'
import { useResults } from '../composables/useResults'
import { useToast } from '../composables/useToast'

// interface（F-17 深化）：结果数据经 useResults/useToast 模块 seam 自取，
// props 收敛为纯展示参数（对象树选中表 / 事务态 / 数据源名）
defineProps<{
  selectedTable?: TableDefinition
  transactionPending: boolean
  sourceName: string
}>()

// 结果表格编辑（F-17）：模块级暂存状态，双击编辑、删除标记与保存按钮都在本组件内呈现
const {
  isEditableResult, canEditCells, editableInfo, dirty: editDirty, saving: editSaving,
  newRows, newRowErrors,
  cellDisplay, isRowDeleted, isCellEdited, isEditing,
  beginEdit, commitEdit, cancelEdit, editDraft,
  addRow, removeNewRow, setNewRowValue,
  deleteSelectedRows,
  save: saveEdits, discardChanges,
} = useResultEditing()

const editableColumn = (columnIndex: number): EditableColumn | null =>
  editableInfo.value?.columns[columnIndex] ?? null

const results = useResults()
const toast = useToast()
// 以下本地名与模板标识符对齐（原 props 镜像的自取版）
const columns = computed(() => results.visibleColumns.value)
const rows = computed(() => results.visibleRows.value)
const page = computed(() => results.page.value)
const pageSize = computed(() => results.pageSize.value)
const totalRows = computed(() => results.totalRows.value)
const totalPages = computed(() => results.totalPages.value)
const hasNextPage = computed(() => results.hasNextPage.value)
const paginated = computed(() => results.queryResult.value?.page != null)
const paging = computed(() => results.isPaging.value)
const elapsed = computed(() => results.elapsedMs.value)
const activeTab = results.activeTab
const hasResult = computed(() => results.hasResult.value)
const updateInfo = computed(() => results.updateResult.value ?? undefined)
const truncated = computed(() => results.queryResult.value?.truncated ?? false)
const notify = (message: string) => toast.notify(message)
const pageChange = (target: number) => void results.changePage(target)
const changePageSize = (size: number) => void results.changePageSize(size)
const tabChange = (tab: 'result' | 'structure' | 'info') => {
  results.activeTab.value = tab
}

const selectedRows = ref(new Set<number>())
const selectedCell = ref<{ row: number; column: number } | null>({ row: 0, column: 1 })
const pageCount = computed(() => Math.max(1, totalPages.value ?? page.value + (hasNextPage.value ? 1 : 0)))
const nextDisabled = computed(() =>
  paging.value || editDirty.value || (totalPages.value == null ? !hasNextPage.value : page.value >= pageCount.value),
)
const selectionCount = computed(() => selectedRows.value.size)

watch(rows, () => {
  selectedRows.value.clear()
  selectedCell.value = null
})

const toggleRow = (index: number) => {
  if (selectedRows.value.has(index)) selectedRows.value.delete(index)
  else selectedRows.value.add(index)
}

const displayValue = (value: QueryValue) => displayQueryValue(value)

const copySelection = async () => {
  let text = ''
  if (selectedRows.value.size > 0) {
    text = [...selectedRows.value]
      .sort((a, b) => a - b)
      .map((index) => rows.value[index].map((value) => displayQueryValue(value)).join('\t'))
      .join('\n')
  } else if (selectedCell.value) {
    const { row, column } = selectedCell.value
    text = rows.value[row] ? displayQueryValue(rows.value[row][column]) : ''
  }
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    notify(selectedRows.value.size > 0 ? `已复制 ${selectedRows.value.size} 行` : '已复制当前单元格')
  } catch {
    notify('浏览器未允许访问剪贴板，请手动复制。')
  }
}

const csvValue = (value: QueryValue) => {
  const text = value === null ? '' : String(value)
  return /[",\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text
}

const exportCsv = () => {
  const header = columns.value.map((column) => csvValue(column.name)).join(',')
  const body = rows.value.map((row) => row.map(csvValue).join(',')).join('\r\n')
  const blob = new Blob([`\uFEFF${header}\r\n${body}`], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `query-result-page-${page.value}.csv`
  link.click()
  URL.revokeObjectURL(url)
  notify(truncated.value ? `已导出当前加载的 ${rows.value.length} 行（结果超过单次上限，已截断）` : `已导出当前加载的 ${rows.value.length} 行`)
}

const changePage = (event: Event) => {
  const input = event.target as HTMLInputElement
  const requested = Number.parseInt(input.value, 10)
  const target = Number.isFinite(requested) ? Math.min(pageCount.value, Math.max(1, requested)) : page.value
  input.value = String(target)
  pageChange(target)
}
</script>

<template>
  <section class="result-panel" aria-label="查询结果与执行信息">
    <div class="result-toolbar">
      <div class="result-tabs" role="tablist" aria-label="结果视图">
        <button type="button" role="tab" :aria-selected="activeTab === 'result'" :class="{ active: activeTab === 'result' }" @click="tabChange('result')">
          <Table2 :size="14" />查询结果<span class="tab-count">{{ totalRows ?? rows.length }}</span>
        </button>
        <button type="button" role="tab" :aria-selected="activeTab === 'structure'" :class="{ active: activeTab === 'structure' }" @click="tabChange('structure')">
          <Code2 :size="14" />表结构<span v-if="selectedTable" class="tab-name mono">{{ selectedTable.name }}</span>
        </button>
        <button type="button" role="tab" :aria-selected="activeTab === 'info'" :class="{ active: activeTab === 'info' }" @click="tabChange('info')">执行信息</button>
      </div>
      <div v-if="activeTab === 'result'" class="result-actions">
        <span v-if="selectionCount" class="selection-count">已选 {{ selectionCount }} 行</span>
        <button type="button" class="button" :disabled="!selectedCell && selectionCount === 0" @click="copySelection"><Clipboard :size="14" />复制</button>
        <button type="button" class="button" :disabled="rows.length === 0" @click="exportCsv"><Download :size="14" />导出 CSV</button>
        <template v-if="isEditableResult">
          <span class="edit-divider" aria-hidden="true"></span>
          <button type="button" class="button" :disabled="!canEditCells" :title="canEditCells ? '在表格顶部新增一行' : '存在未提交手动事务，表格编辑不可用'" @click="addRow"><Plus :size="14" />新增</button>
          <button type="button" class="button" :disabled="!canEditCells || selectionCount === 0" title="标记选中行为待删除，随保存一起提交" @click="deleteSelectedRows(selectedRows)"><Trash2 :size="14" />删除</button>
          <button v-if="editDirty" type="button" class="button button-primary" :disabled="editSaving || !canEditCells" @click="saveEdits"><Save :size="14" />{{ editSaving ? '保存中…' : '保存' }}</button>
          <button v-if="editDirty" type="button" class="button" :disabled="editSaving" title="丢弃全部未保存更改并重新加载当前页" @click="discardChanges"><Undo2 :size="14" />放弃更改</button>
        </template>
      </div>
    </div>

    <template v-if="activeTab === 'result'">
      <div class="result-summary" role="status">
        <template v-if="hasResult">
          <span class="execution-success"><Check :size="13" />执行成功</span>
          <span>{{ elapsed }} ms</span>
          <span>当前加载 {{ rows.length }} 行</span>
          <span v-if="truncated" class="summary-truncated">结果超过单次上限，已截断</span>
          <span class="summary-source">仅导出已加载数据</span>
        </template>
        <span v-else>尚未执行查询，结果将在执行后显示。</span>
      </div>
      <div class="table-wrapper" tabindex="0" role="region" aria-label="SQL 查询结果，可滚动表格">
        <table class="result-table">
          <caption class="visually-hidden">SQL 查询结果</caption>
          <thead>
            <tr>
              <th class="row-number-header"><span class="visually-hidden">选择行</span>#</th>
              <th v-for="column in columns" :key="column.name" scope="col">
                <span class="column-heading mono">{{ column.name }}</span>
                <span class="column-data-type mono">{{ column.type }}</span>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(inputs, newRowIndex) in newRows" :key="`new-${newRowIndex}`" class="row-new" :class="{ 'row-invalid': newRowErrors[newRowIndex] }">
              <th scope="row" class="row-number-cell new-row-marker">
                <button type="button" class="icon-button" :aria-label="`移除第 ${newRowIndex + 1} 条新增行`" :title="newRowErrors[newRowIndex] ?? '移除该新增行'" @click="removeNewRow(newRowIndex)"><X :size="12" /></button>
                <span class="mono new-badge">新</span>
              </th>
              <td v-for="(column, columnIndex) in columns" :key="`new-${newRowIndex}-${columnIndex}`" :data-label="column.name">
                <input v-if="editableColumn(columnIndex) === null" class="cell-input" disabled :aria-label="`${column.name} 不可编辑`" />
                <input
                  v-else-if="editableColumn(columnIndex)!.autoIncrement"
                  class="cell-input" disabled placeholder="自动生成" :aria-label="`${column.name} 自动生成`"
                />
                <input
                  v-else
                  class="cell-input mono"
                  :value="inputs[columnIndex] ?? ''"
                  :placeholder="editableColumn(columnIndex)!.primaryKey ? '主键必填' : editableColumn(columnIndex)!.nullable ? '留空=默认' : '必填'"
                  :aria-label="`新增行 ${column.name}`"
                  @input="setNewRowValue(newRowIndex, columnIndex, ($event.target as HTMLInputElement).value)"
                />
              </td>
            </tr>
            <tr v-for="(row, rowIndex) in rows" :key="`${page}-${rowIndex}`" :class="{ 'row-selected': selectedRows.has(rowIndex), 'row-deleted': isEditableResult && isRowDeleted(rowIndex) }">
              <th scope="row" class="row-number-cell">
                <input type="checkbox" :checked="selectedRows.has(rowIndex)" :disabled="isEditableResult && isRowDeleted(rowIndex)" :aria-label="`选择第 ${(page - 1) * pageSize + rowIndex + 1} 行`" @change="toggleRow(rowIndex)" />
                <span class="mono">{{ (page - 1) * pageSize + rowIndex + 1 }}</span>
              </th>
              <td
                v-for="(value, columnIndex) in row"
                :key="`${rowIndex}-${columnIndex}`"
                :class="{
                  'cell-selected': selectedCell?.row === rowIndex && selectedCell.column === columnIndex,
                  'cell-edited': isEditableResult && isCellEdited(rowIndex, columnIndex),
                }"
                :data-label="columns[columnIndex].name"
                :title="displayValue(isEditableResult ? cellDisplay(rowIndex, columnIndex) : value)"
                @click="selectedCell = { row: rowIndex, column: columnIndex }"
                @dblclick="beginEdit(rowIndex, columnIndex)"
              >
                <input
                  v-if="isEditing(rowIndex, columnIndex)"
                  :ref="(element) => { if (element) (element as HTMLInputElement).focus() }"
                  v-model="editDraft"
                  class="cell-input mono cell-editing-input"
                  :aria-label="`编辑 ${columns[columnIndex].name}`"
                  @keydown.enter.prevent="commitEdit()"
                  @keydown.esc.prevent="cancelEdit()"
                  @blur="commitEdit()"
                />
                <template v-else-if="isEditableResult">
                  <span v-if="cellDisplay(rowIndex, columnIndex) === null" class="null-value mono">NULL</span>
                  <span v-else-if="cellDisplay(rowIndex, columnIndex) === ''" class="empty-value">空字符串</span>
                  <span v-else class="cell-value mono">{{ displayValue(cellDisplay(rowIndex, columnIndex)) }}</span>
                </template>
                <template v-else>
                  <span v-if="value === null" class="null-value mono">NULL</span>
                  <span v-else-if="value === ''" class="empty-value">空字符串</span>
                  <span v-else class="cell-value mono">{{ displayValue(value) }}</span>
                </template>
              </td>
            </tr>
            <tr v-if="rows.length === 0 && newRows.length === 0">
              <td :colspan="columns.length + 1" class="empty-result">{{ hasResult ? '查询已执行，未返回数据行。' : '在编辑器中编写 SQL，然后选择“执行”。' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="paginated" class="pagination-bar">
        <span v-if="totalRows !== null">共 {{ totalRows }} 行</span>
        <span v-else>总数未知</span>
        <span v-if="paging" class="loaded-note">正在加载…</span>
        <span class="loaded-note">数据库分页</span>
        <span v-if="editDirty" class="edit-lock-note">有未保存更改，翻页前请先保存或放弃</span>
        <div class="page-controls">
          <label for="page-size">每页</label>
          <select id="page-size" :value="pageSize" name="page-size" :disabled="paging || editDirty" @change="changePageSize(Number(($event.target as HTMLSelectElement).value))">
            <option :value="10">10</option>
            <option :value="20">20</option>
            <option :value="50">50</option>
            <option :value="100">100</option>
          </select>
          <span class="page-size-unit">行</span>
          <button type="button" class="icon-button" aria-label="上一页" :disabled="page <= 1 || paging || editDirty" @click="pageChange(page - 1)"><ChevronLeft :size="16" /></button>
          <label class="visually-hidden" for="page-number">页码</label>
          <input id="page-number" class="page-number mono" type="number" name="page-number" min="1" :max="totalPages ?? undefined" :value="page" :disabled="paging || editDirty" @change="changePage" />
          <span>/ {{ totalPages == null ? '?' : pageCount }} 页</span>
          <button type="button" class="icon-button" aria-label="下一页" :disabled="nextDisabled" @click="pageChange(page + 1)"><ChevronRight :size="16" /></button>
        </div>
      </div>
    </template>

    <template v-else-if="activeTab === 'structure' && selectedTable">
      <div class="structure-heading">
        <div><Table2 :size="17" /><h3 class="mono">{{ selectedTable.name }}</h3><span>{{ selectedTable.comment }}</span></div>
        <span class="badge">{{ selectedTable.columns.length }} 列</span>
      </div>
      <div class="table-wrapper structure-wrapper" tabindex="0" role="region" aria-label="表结构，可滚动表格">
        <table class="structure-table">
          <caption class="visually-hidden">{{ selectedTable.name }} 的列定义</caption>
          <thead><tr><th scope="col">列名</th><th scope="col">数据类型</th><th scope="col">可空</th><th scope="col">默认值</th><th scope="col">键</th><th scope="col">注释</th></tr></thead>
          <tbody>
            <tr v-for="column in selectedTable.columns" :key="column.name">
              <th scope="row" class="mono">{{ column.name }}</th>
              <td class="mono">{{ column.type }}</td>
              <td>{{ column.nullable ? 'YES' : 'NO' }}</td>
              <td><span :class="column.defaultValue === null ? 'null-value mono' : 'mono'">{{ column.defaultValue ?? 'NULL' }}</span></td>
              <td><span v-if="column.primaryKey" class="primary-key"><KeyRound :size="12" />PRIMARY</span><span v-else class="muted-text">—</span></td>
              <td>{{ column.comment }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="structure-footer"><Info :size="13" />结构信息与 DESC / DESCRIBE 使用同一数据源。</div>
    </template>

    <template v-else>
      <div class="execution-info">
        <div class="execution-info-icon"><Check :size="23" /></div>
        <h3>语句执行成功</h3>
        <p>数据库已接受本次语句，未检测到执行错误。</p>
        <dl>
          <div><dt>目标数据源</dt><dd>{{ sourceName }}</dd></div>
          <div><dt>执行耗时</dt><dd class="mono">{{ elapsed }} ms</dd></div>
          <div v-if="updateInfo"><dt>受影响行数</dt><dd class="mono">{{ updateInfo.affectedRows === null ? '驱动未提供行数' : updateInfo.affectedRows }}</dd></div>
          <div v-else><dt>返回行数</dt><dd class="mono">{{ totalRows }}</dd></div>
          <div><dt>事务状态</dt><dd :class="{ 'pending-text': transactionPending }">{{ transactionPending ? '有未提交变更' : '无未提交变更' }}</dd></div>
          <div><dt>提交方式</dt><dd>手动提交 / 回滚</dd></div>
          <div><dt>自动重试</dt><dd>已关闭</dd></div>
        </dl>
      </div>
    </template>
  </section>
</template>

<style scoped>
.result-panel {
  min-inline-size: 0;
  min-block-size: 0;
  display: flex;
  flex-direction: column;
  background: var(--surface);
}

.result-toolbar {
  min-block-size: 43px;
  padding-inline: 12px;
  border-block-end: 1px solid var(--line);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.result-tabs {
  align-self: stretch;
  display: flex;
  gap: 3px;
}

.result-tabs button {
  position: relative;
  padding-inline: 9px;
  border: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--muted);
  background: transparent;
  font-size: 12px;
  white-space: nowrap;
}

.result-tabs button.active {
  color: var(--accent);
  font-weight: 570;
}

.result-tabs button.active::after {
  content: '';
  position: absolute;
  inset-inline: 8px;
  inset-block-end: 0;
  block-size: 2px;
  background: var(--accent);
}

.tab-count {
  min-inline-size: 20px;
  padding: 1px 5px;
  border-radius: 7px;
  color: var(--muted);
  background: #edf1ef;
  font-size: 10px;
  text-align: center;
}

.tab-name {
  max-inline-size: 90px;
  overflow: hidden;
  color: var(--faint);
  font-size: 10px;
  text-overflow: ellipsis;
}

.result-actions {
  display: flex;
  align-items: center;
  gap: 7px;
}

.result-actions .button {
  min-block-size: 28px;
  padding-inline: 9px;
  font-size: 11px;
}

.selection-count {
  color: var(--accent);
  font-size: 11px;
}

.edit-divider {
  inline-size: 1px;
  block-size: 18px;
  background: var(--line);
}

.edit-lock-note {
  color: #93651f;
  font-size: 10px;
}

.row-new td {
  background: #fbfaf4 !important;
}

.new-row-marker {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 5px;
}

.new-badge {
  color: #93651f;
  font-size: 9px;
}

.row-invalid td {
  box-shadow: inset 0 0 0 1px #d8a13a;
}

.row-deleted td,
.row-deleted .row-number-cell {
  color: #a5ada9 !important;
  background: #f4f5f4 !important;
  text-decoration: line-through;
}

.cell-edited {
  background: #fdf6e3 !important;
  box-shadow: inset 0 0 0 1px #d8a13a;
}

.cell-input {
  inline-size: 100%;
  min-block-size: 20px;
  padding: 1px 4px;
  border: 1px solid var(--line);
  border-radius: 3px;
  color: var(--ink);
  background: #ffffff;
  font-size: 10px;
}

.cell-input:disabled {
  color: #b6bdb9;
  background: #f6f7f6;
}

.cell-input:focus {
  border-color: var(--accent);
  outline: none;
}

.cell-editing-input {
  display: block;
}

.result-summary {
  min-block-size: 31px;
  padding-inline: 14px;
  border-block-end: 1px solid #edf1ef;
  display: flex;
  align-items: center;
  gap: 15px;
  color: var(--muted);
  background: #fbfcfb;
  font-size: 10px;
}

.execution-success {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--success);
  font-weight: 560;
}

.summary-source {
  margin-inline-start: auto;
  color: var(--faint);
}

.table-wrapper {
  min-inline-size: 0;
  min-block-size: 0;
  max-inline-size: 100%;
  overflow: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
  container-type: inline-size;
  flex: 1;
}

.result-table,
.structure-table {
  inline-size: 100%;
  border-spacing: 0;
  border-collapse: separate;
  font-size: 11px;
  text-align: start;
}

.result-table thead th,
.structure-table thead th {
  position: sticky;
  z-index: 3;
  inset-block-start: 0;
  min-inline-size: 160px;
  block-size: 43px;
  padding: 6px 13px;
  border-block-end: 1px solid var(--line-strong);
  border-inline-end: 1px solid #edf1ef;
  color: #405049;
  background: #f6f8f7;
  text-align: start;
  vertical-align: middle;
}

.result-table thead th:first-child {
  z-index: 5;
  inset-inline-start: 0;
}

.column-heading,
.column-data-type {
  display: block;
}

.column-heading {
  font-size: 11px;
  font-weight: 500;
}

.column-data-type {
  margin-block-start: 3px;
  color: var(--faint);
  font-size: 8px;
  font-weight: 400;
}

.result-table tbody tr:nth-child(even) td,
.result-table tbody tr:nth-child(even) .row-number-cell {
  background: #fbfcfb;
}

.result-table tbody tr:hover td {
  background: #f2f7f4;
}

.result-table .row-number-header,
.result-table .row-number-cell {
  min-inline-size: 70px;
  inline-size: 70px;
  padding: 0 9px;
  border-inline-end: 1px solid var(--line);
  color: var(--faint);
  text-align: end;
  font-size: 9px;
  font-weight: 400;
}

.result-table .row-number-cell {
  position: sticky;
  z-index: 2;
  inset-inline-start: 0;
  background: #ffffff;
}

.row-number-cell input {
  inline-size: 12px;
  block-size: 12px;
  margin: 0 7px 0 0;
  vertical-align: middle;
  accent-color: var(--accent);
}

.result-table td {
  min-inline-size: 160px;
  max-inline-size: 300px;
  block-size: 34px;
  padding: 7px 13px;
  border-block-end: 1px solid #eef2f0;
  border-inline-end: 1px solid #f0f3f1;
  overflow: hidden;
  color: #35453f;
  background: #ffffff;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: cell;
}

.result-table td.cell-selected {
  position: relative;
  background: #e8f2ee;
  box-shadow: inset 0 0 0 1px var(--accent);
}

.result-table tr.row-selected td,
.result-table tr.row-selected .row-number-cell {
  background: #edf5f1;
}

.cell-value {
  font-size: 10px;
}

.null-value,
.empty-value {
  color: #99a39e;
  font-size: 9px;
  font-style: italic;
}

.empty-result {
  padding: 40px !important;
  color: var(--muted) !important;
  text-align: center;
}

.pagination-bar {
  min-block-size: 40px;
  padding-inline: 14px 9px;
  border-block-start: 1px solid var(--line);
  display: flex;
  align-items: center;
  gap: 11px;
  color: var(--muted);
  background: #fbfcfb;
  font-size: 11px;
}

.loaded-note {
  padding-inline-start: 11px;
  border-inline-start: 1px solid var(--line);
  color: var(--faint);
}

.page-controls {
  margin-inline-start: auto;
  display: flex;
  align-items: center;
  gap: 5px;
}

.page-controls select {
  min-block-size: 27px;
  padding-inline: 5px;
  border: 1px solid var(--line);
  border-radius: 4px;
  color: var(--ink);
  background: #ffffff;
  font-size: 11px;
}

.page-size-unit {
  margin-inline-end: 8px;
}

.page-number {
  inline-size: 38px;
  min-block-size: 27px;
  padding: 0 3px;
  border: 1px solid var(--line);
  border-radius: 4px;
  color: var(--ink);
  background: #ffffff;
  text-align: center;
  font-size: 10px;
  appearance: textfield;
}

.page-number::-webkit-inner-spin-button {
  appearance: none;
}

.structure-heading {
  min-block-size: 49px;
  padding-inline: 16px;
  border-block-end: 1px solid var(--line);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.structure-heading > div {
  display: flex;
  align-items: center;
  gap: 9px;
  color: var(--accent);
}

.structure-heading h3 {
  margin: 0;
  color: var(--ink);
  font-size: 13px;
  font-weight: 500;
}

.structure-heading > div > span {
  padding-inline-start: 9px;
  border-inline-start: 1px solid var(--line);
  color: var(--muted);
  font-size: 11px;
}

.structure-table thead th {
  min-inline-size: 110px;
  block-size: 35px;
  font-size: 10px;
}

.structure-table tbody th,
.structure-table tbody td {
  block-size: 38px;
  padding: 7px 13px;
  border-block-end: 1px solid #edf1ef;
  color: #43534c;
  text-align: start;
  font-size: 11px;
  font-weight: 400;
}

.structure-table tbody th {
  color: var(--ink);
  font-weight: 500;
}

.primary-key {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: #93651f;
  font-family: var(--code-font);
  font-size: 9px;
}

.muted-text {
  color: var(--faint);
}

.structure-footer {
  min-block-size: 36px;
  padding-inline: 14px;
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--faint);
  font-size: 10px;
}

.execution-info {
  max-inline-size: 590px;
  margin: auto;
  padding: 32px;
  text-align: center;
}

.execution-info-icon {
  inline-size: 48px;
  block-size: 48px;
  margin: 0 auto 14px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: var(--success);
  background: #e6f1eb;
}

.execution-info h3 {
  margin: 0;
  font-size: 17px;
  font-weight: 580;
}

.execution-info > p {
  margin: 8px 0 24px;
  color: var(--muted);
  font-size: 12px;
}

.execution-info dl {
  margin: 0;
  border-block-start: 1px solid var(--line);
  text-align: start;
}

.execution-info dl > div {
  min-block-size: 39px;
  border-block-end: 1px solid #edf1ef;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  font-size: 12px;
}

.execution-info dt {
  color: var(--muted);
}

.execution-info dd {
  margin: 0;
}

.pending-text {
  color: var(--warning);
}

@container (width < 580px) {
  .result-table,
  .result-table tbody,
  .result-table tr,
  .result-table td {
    display: block;
  }

  .result-table thead {
    display: none;
  }

  .result-table tbody tr {
    position: relative;
    margin: 10px;
    border: 1px solid var(--line);
    border-radius: 5px;
    overflow: clip;
  }

  .result-table .row-number-cell {
    position: sticky;
    z-index: 2;
    inset-block-start: 0;
    inline-size: auto;
    min-block-size: 31px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    background: #f5f8f6;
    text-align: start;
  }

  .result-table td {
    min-inline-size: 0;
    max-inline-size: none;
    min-block-size: 34px;
    display: grid;
    grid-template-columns: minmax(90px, 0.6fr) 1fr;
    align-items: center;
    gap: 10px;
    border-inline-end: 0;
  }

  .result-table td::before {
    content: attr(data-label);
    color: var(--muted);
    font-family: var(--code-font);
    font-size: 9px;
  }
}

@media (max-width: 660px) {
  .result-toolbar {
    padding-inline: 7px;
  }

  .result-tabs button {
    padding-inline: 6px;
  }

  .tab-name,
  .result-actions .button {
    display: none;
  }

  .summary-source,
  .loaded-note {
    display: none;
  }

  .pagination-bar {
    padding-inline: 8px;
  }
}
</style>
