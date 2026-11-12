<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Check, ChevronLeft, ChevronRight, Clipboard, Code2, Download, Info, KeyRound, Table2 } from 'lucide-vue-next'
import type { QueryColumn, QueryRow, QueryValue, TableDefinition } from '../types'

const props = defineProps<{
  columns: QueryColumn[]
  rows: QueryRow[]
  selectedTable?: TableDefinition
  page: number
  pageSize: number
  totalRows: number
  elapsed: number
  activeTab: 'result' | 'structure' | 'info'
  transactionPending: boolean
  sourceName: string
  hasResult: boolean
}>()

const emit = defineEmits<{
  tabChange: [tab: 'result' | 'structure' | 'info']
  pageChange: [page: number]
  pageSizeChange: [size: number]
  notify: [message: string]
}>()

const selectedRows = ref(new Set<number>())
const selectedCell = ref<{ row: number; column: number } | null>({ row: 0, column: 1 })
const pageCount = computed(() => Math.max(1, Math.ceil(props.totalRows / props.pageSize)))
const selectionCount = computed(() => selectedRows.value.size)

watch(() => props.rows, () => {
  selectedRows.value.clear()
  selectedCell.value = null
})

const toggleRow = (index: number) => {
  if (selectedRows.value.has(index)) selectedRows.value.delete(index)
  else selectedRows.value.add(index)
}

const displayValue = (value: QueryValue) => value === null ? 'NULL' : value === '' ? '空字符串' : value
const statusClass = (value: QueryValue) => {
  if (value === 'PAID' || value === 'SHIPPED') return 'value-success'
  if (value === 'PENDING') return 'value-pending'
  if (value === 'REFUNDED') return 'value-muted'
  return ''
}

const copySelection = async () => {
  let text = ''
  if (selectedRows.value.size > 0) {
    text = [...selectedRows.value]
      .sort((a, b) => a - b)
      .map((index) => props.rows[index].map((value) => value ?? 'NULL').join('\t'))
      .join('\n')
  } else if (selectedCell.value) {
    const { row, column } = selectedCell.value
    text = props.rows[row]?.[column] ?? 'NULL'
  }
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    emit('notify', selectedRows.value.size > 0 ? `已复制 ${selectedRows.value.size} 行` : '已复制当前单元格')
  } catch {
    emit('notify', '浏览器未允许访问剪贴板，请手动复制。')
  }
}

const csvValue = (value: QueryValue) => {
  const text = value ?? ''
  return /[",\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text
}

const exportCsv = () => {
  const header = props.columns.map((column) => csvValue(column.name)).join(',')
  const body = props.rows.map((row) => row.map(csvValue).join(',')).join('\r\n')
  const blob = new Blob([`\uFEFF${header}\r\n${body}`], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `query-result-page-${props.page}.csv`
  link.click()
  URL.revokeObjectURL(url)
  emit('notify', `已导出当前加载的 ${props.rows.length} 行`)
}

const changePage = (event: Event) => {
  const input = event.target as HTMLInputElement
  const requested = Number.parseInt(input.value, 10)
  const page = Number.isFinite(requested) ? Math.min(pageCount.value, Math.max(1, requested)) : props.page
  input.value = String(page)
  emit('pageChange', page)
}
</script>

<template>
  <section class="result-panel" aria-label="查询结果与执行信息">
    <div class="result-toolbar">
      <div class="result-tabs" role="tablist" aria-label="结果视图">
        <button type="button" role="tab" :aria-selected="activeTab === 'result'" :class="{ active: activeTab === 'result' }" @click="emit('tabChange', 'result')">
          <Table2 :size="14" />查询结果<span class="tab-count">{{ totalRows }}</span>
        </button>
        <button type="button" role="tab" :aria-selected="activeTab === 'structure'" :class="{ active: activeTab === 'structure' }" @click="emit('tabChange', 'structure')">
          <Code2 :size="14" />表结构<span v-if="selectedTable" class="tab-name mono">{{ selectedTable.name }}</span>
        </button>
        <button type="button" role="tab" :aria-selected="activeTab === 'info'" :class="{ active: activeTab === 'info' }" @click="emit('tabChange', 'info')">执行信息</button>
      </div>
      <div v-if="activeTab === 'result'" class="result-actions">
        <span v-if="selectionCount" class="selection-count">已选 {{ selectionCount }} 行</span>
        <button type="button" class="button" :disabled="!selectedCell && selectionCount === 0" @click="copySelection"><Clipboard :size="14" />复制</button>
        <button type="button" class="button" :disabled="rows.length === 0" @click="exportCsv"><Download :size="14" />导出 CSV</button>
      </div>
    </div>

    <template v-if="activeTab === 'result'">
      <div class="result-summary" role="status">
        <template v-if="hasResult">
          <span class="execution-success"><Check :size="13" />执行成功</span>
          <span>{{ elapsed }} ms</span>
          <span>当前加载 {{ rows.length }} 行</span>
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
            <tr v-for="(row, rowIndex) in rows" :key="`${page}-${rowIndex}`" :class="{ 'row-selected': selectedRows.has(rowIndex) }">
              <th scope="row" class="row-number-cell">
                <input type="checkbox" :checked="selectedRows.has(rowIndex)" :aria-label="`选择第 ${(page - 1) * pageSize + rowIndex + 1} 行`" @change="toggleRow(rowIndex)" />
                <span class="mono">{{ (page - 1) * pageSize + rowIndex + 1 }}</span>
              </th>
              <td v-for="(value, columnIndex) in row" :key="`${rowIndex}-${columnIndex}`" :class="{ 'cell-selected': selectedCell?.row === rowIndex && selectedCell.column === columnIndex }" :data-label="columns[columnIndex].name" :title="value ?? 'SQL NULL'" @click="selectedCell = { row: rowIndex, column: columnIndex }">
                <span v-if="value === null" class="null-value mono">NULL</span>
                <span v-else-if="value === ''" class="empty-value">空字符串</span>
                <span v-else-if="columns[columnIndex].name === 'status'" class="status-value" :class="statusClass(value)">{{ value }}</span>
                <span v-else class="cell-value mono">{{ displayValue(value) }}</span>
              </td>
            </tr>
            <tr v-if="rows.length === 0">
              <td :colspan="columns.length + 1" class="empty-result">{{ hasResult ? '查询已执行，未返回数据行。' : '在编辑器中编写 SQL，然后选择“执行”。' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="pagination-bar">
        <span>共 {{ totalRows }} 行</span>
        <span class="loaded-note">数据库分页</span>
        <div class="page-controls">
          <label for="page-size">每页</label>
          <select id="page-size" :value="pageSize" name="page-size" @change="emit('pageSizeChange', Number(($event.target as HTMLSelectElement).value))">
            <option :value="10">10</option>
            <option :value="20">20</option>
            <option :value="50">50</option>
          </select>
          <span class="page-size-unit">行</span>
          <button type="button" class="icon-button" aria-label="上一页" :disabled="page <= 1" @click="emit('pageChange', page - 1)"><ChevronLeft :size="16" /></button>
          <label class="visually-hidden" for="page-number">页码</label>
          <input id="page-number" class="page-number mono" type="number" name="page-number" min="1" :max="pageCount" :value="page" @change="changePage" />
          <span>/ {{ pageCount }} 页</span>
          <button type="button" class="icon-button" aria-label="下一页" :disabled="page >= pageCount" @click="emit('pageChange', page + 1)"><ChevronRight :size="16" /></button>
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
          <div><dt>返回行数</dt><dd class="mono">{{ totalRows }}</dd></div>
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

.status-value {
  padding: 2px 6px;
  border-radius: 3px;
  font-family: var(--code-font);
  font-size: 9px;
}

.value-success {
  color: #2e6d52;
  background: #e7f2eb;
}

.value-pending {
  color: #93611f;
  background: #fff2de;
}

.value-muted {
  color: #68756f;
  background: #edf0ee;
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
