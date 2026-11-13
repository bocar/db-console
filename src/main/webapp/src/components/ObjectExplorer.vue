<script setup lang="ts">
import { computed, ref } from 'vue'
import { ChevronDown, ChevronRight, CornerDownLeft, Database, KeyRound, RefreshCw, Search, Table2, X } from 'lucide-vue-next'
import type { TableDefinition } from '../types'
import { formatClockTime } from '../display'

const props = defineProps<{
  tables: TableDefinition[]
  selectedTable: string
  sourceLabel: string
  refreshing: boolean
  mobileOpen: boolean
}>()

const emit = defineEmits<{
  select: [table: string]
  insert: [name: string]
  open: [table: string]
  refresh: []
  close: []
}>()

const filter = ref('')
const rootExpanded = ref(true)
const expandedTables = ref(new Set<string>(['orders']))
const lastRefresh = ref('14:32:08')

const filteredTables = computed(() => {
  const keyword = filter.value.trim().toLowerCase()
  if (!keyword) return props.tables
  return props.tables.filter((table) =>
    table.name.toLowerCase().includes(keyword)
    || table.comment.toLowerCase().includes(keyword)
    || table.columns.some((column) => column.name.toLowerCase().includes(keyword)),
  )
})

const toggleTable = (name: string) => {
  if (expandedTables.value.has(name)) expandedTables.value.delete(name)
  else expandedTables.value.add(name)
}

const refresh = () => {
  emit('refresh')
  lastRefresh.value = formatClockTime(new Date().toISOString())
}
</script>

<template>
  <aside class="object-explorer" :class="{ 'mobile-open': mobileOpen }" aria-label="对象资源管理器">
    <div class="explorer-heading">
      <div>
        <h2>对象资源管理器</h2>
        <span>TABLES & VIEWS</span>
      </div>
      <button type="button" class="icon-button" title="刷新对象" aria-label="刷新对象" :disabled="refreshing" @click="refresh">
        <RefreshCw :size="15" :class="{ rotating: refreshing }" />
      </button>
      <button type="button" class="icon-button mobile-close" aria-label="关闭对象资源管理器" @click="emit('close')"><X :size="18" /></button>
    </div>
    <form class="explorer-search" role="search" action="#objects" method="get" @submit.prevent>
      <label class="visually-hidden" for="object-filter">查找表或列</label>
      <Search :size="15" aria-hidden="true" />
      <input id="object-filter" v-model="filter" name="object-filter" type="search" placeholder="查找表或列" autocomplete="off" spellcheck="false" />
      <kbd>⌘ K</kbd>
    </form>
    <div class="tree-scroll">
      <div class="database-node">
        <button type="button" class="tree-toggle" :aria-expanded="rootExpanded" aria-controls="database-objects" @click="rootExpanded = !rootExpanded">
          <ChevronDown v-if="rootExpanded" :size="14" />
          <ChevronRight v-else :size="14" />
        </button>
        <Database :size="15" class="database-icon" />
        <span class="mono">{{ sourceLabel }}</span>
        <span class="current-label">当前数据源</span>
      </div>
      <div v-if="rootExpanded" id="database-objects" class="database-objects">
        <div class="object-group-label"><span>表</span><span>{{ filteredTables.length }}</span></div>
        <ul class="table-list">
          <li v-for="table in filteredTables" :key="table.name" class="table-item">
            <div class="table-row" :class="{ selected: selectedTable === table.name }">
              <button type="button" class="tree-toggle" :aria-expanded="expandedTables.has(table.name)" :aria-controls="`columns-${table.name}`" :aria-label="`${expandedTables.has(table.name) ? '折叠' : '展开'} ${table.name}`" @click="toggleTable(table.name)">
                <ChevronDown v-if="expandedTables.has(table.name)" :size="13" />
                <ChevronRight v-else :size="13" />
              </button>
              <button type="button" class="table-name-button" :title="table.comment" @click="emit('select', table.name)" @dblclick="emit('open', table.name)">
                <Table2 :size="14" />
                <span class="mono">{{ table.name }}</span>
              </button>
              <button type="button" class="insert-object" :title="`插入表名 ${table.name}`" :aria-label="`插入表名 ${table.name}`" @click="emit('insert', table.name)"><CornerDownLeft :size="13" /></button>
            </div>
            <ul v-if="expandedTables.has(table.name) || filter" :id="`columns-${table.name}`" class="column-list">
              <li v-for="column in table.columns" :key="column.name">
                <button type="button" class="column-button" :title="`${column.comment}，${column.nullable ? '可空' : '非空'}`" @click="emit('insert', column.name)">
                  <KeyRound v-if="column.primaryKey" :size="12" class="key-icon" />
                  <span v-else class="column-marker">#</span>
                  <span class="column-name mono">{{ column.name }}</span>
                  <span class="column-type mono">{{ column.type }}</span>
                </button>
              </li>
              <li class="metadata-links">
                <button type="button" @click="emit('select', table.name)">列 <span>{{ table.columns.length }}</span></button>
                <button type="button" @click="emit('select', table.name)">索引</button>
                <button type="button" @click="emit('select', table.name)">外键</button>
              </li>
            </ul>
          </li>
        </ul>
        <div v-if="filteredTables.length === 0" class="empty-filter">
          <Search :size="20" />
          <p>没有匹配的对象</p>
          <button type="button" @click="filter = ''">清除查找</button>
        </div>
      </div>
    </div>
    <div class="explorer-footer">
      <span>{{ tables.length }} 张表</span>
      <span>刷新于 {{ lastRefresh }}</span>
    </div>
  </aside>
</template>

<style scoped>
.object-explorer {
  min-inline-size: 0;
  min-block-size: 0;
  display: flex;
  flex-direction: column;
  color: #293733;
  background: #f8faf9;
}

.explorer-heading {
  min-block-size: 47px;
  padding: 0 10px 0 15px;
  border-block-end: 1px solid var(--line);
  display: flex;
  align-items: center;
}

.explorer-heading > div {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.explorer-heading h2 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
}

.explorer-heading > div > span {
  color: var(--faint);
  font-family: var(--code-font);
  font-size: 8px;
  letter-spacing: 0.07em;
}

.explorer-heading > button:first-of-type {
  margin-inline-start: auto;
}

.explorer-search {
  position: relative;
  inline-size: calc(100% - 22px);
  margin: 11px 11px 8px;
  flex: 0 0 auto;
  min-block-size: 31px;
  padding-inline: 9px;
  border: 1px solid var(--line);
  border-radius: 5px;
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--faint);
  background: var(--surface);
}

.explorer-search:focus-within {
  border-color: var(--accent);
}

.explorer-search input {
  min-inline-size: 0;
  min-block-size: 29px;
  padding: 0;
  border: 0;
  outline: 0;
  color: var(--ink);
  background: transparent;
  flex: 1;
  font-size: 12px;
}

.explorer-search input::-webkit-search-cancel-button {
  display: none;
}

.explorer-search kbd {
  color: var(--faint);
  font-family: var(--code-font);
  font-size: 9px;
}

.tree-scroll {
  min-block-size: 0;
  padding: 2px 7px 14px;
  overflow: auto;
  overscroll-behavior: contain;
  flex: 1;
}

.database-node {
  min-block-size: 32px;
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  font-weight: 570;
}

.tree-toggle {
  inline-size: 21px;
  block-size: 27px;
  padding: 0;
  border: 0;
  border-radius: 3px;
  display: grid;
  place-items: center;
  color: var(--muted);
  background: transparent;
  flex: 0 0 auto;
}

.tree-toggle:hover {
  background: var(--surface-hover);
}

.database-icon {
  color: var(--accent);
}

.current-label {
  margin-inline-start: auto;
  padding-inline-end: 7px;
  color: var(--faint);
  font-size: 10px;
  font-weight: 400;
}

.database-objects {
  padding-inline-start: 17px;
}

.object-group-label {
  min-block-size: 29px;
  padding-inline: 6px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--muted);
  font-size: 11px;
}

.object-group-label span:last-child {
  min-inline-size: 19px;
  padding: 1px 5px;
  border-radius: 7px;
  text-align: center;
  background: #e9eeeb;
  font-size: 10px;
}

.table-list,
.column-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.table-row {
  min-block-size: 31px;
  border-radius: 4px;
  display: flex;
  align-items: center;
}

.table-row.selected {
  background: #e5efeb;
}

.table-row:hover .insert-object {
  opacity: 1;
}

.table-name-button {
  min-inline-size: 0;
  min-block-size: 31px;
  padding: 0;
  border: 0;
  display: flex;
  align-items: center;
  gap: 7px;
  color: #35433e;
  background: transparent;
  flex: 1;
  text-align: start;
  font-size: 12px;
}

.table-name-button svg {
  color: #71827a;
  flex: 0 0 auto;
}

.table-row.selected .table-name-button {
  color: var(--accent);
  font-weight: 570;
}

.table-row.selected .table-name-button svg {
  color: var(--accent);
}

.table-name-button span {
  overflow: hidden;
  text-overflow: ellipsis;
}

.insert-object {
  inline-size: 25px;
  block-size: 27px;
  padding: 0;
  border: 0;
  border-radius: 3px;
  display: grid;
  place-items: center;
  color: var(--accent);
  background: transparent;
  opacity: 0;
}

.insert-object:hover,
.insert-object:focus-visible {
  opacity: 1;
  background: #d6e6e0;
}

.column-list {
  position: relative;
  margin-inline-start: 24px;
  padding-inline-start: 10px;
  border-inline-start: 1px solid #dfe6e2;
}

.column-list > li {
  min-inline-size: 0;
}

.column-button {
  inline-size: 100%;
  min-block-size: 28px;
  padding: 0 4px;
  border: 0;
  border-radius: 3px;
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--muted);
  background: transparent;
  text-align: start;
}

.column-button:hover {
  background: var(--surface-hover);
}

.key-icon {
  color: #a87627;
  flex: 0 0 auto;
}

.column-marker {
  inline-size: 12px;
  color: #91a39a;
  font-family: var(--code-font);
  font-size: 10px;
  text-align: center;
  flex: 0 0 auto;
}

.column-name {
  color: #4c5b55;
  font-size: 10px;
  white-space: nowrap;
}

.column-type {
  min-inline-size: 0;
  margin-inline-start: auto;
  overflow: hidden;
  color: #95a099;
  font-size: 8px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metadata-links {
  min-block-size: 29px;
  padding-inline-start: 3px;
  display: flex;
  align-items: center;
  gap: 12px;
}

.metadata-links button {
  padding: 0;
  border: 0;
  color: #7b8982;
  background: transparent;
  font-size: 10px;
}

.metadata-links button:hover {
  color: var(--accent);
}

.metadata-links span {
  color: var(--faint);
}

.explorer-footer {
  min-block-size: 31px;
  padding-inline: 13px;
  border-block-start: 1px solid var(--line);
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--faint);
  background: var(--surface);
  font-size: 10px;
}

.empty-filter {
  padding: 32px 10px;
  display: grid;
  place-items: center;
  color: var(--faint);
  text-align: center;
}

.empty-filter p {
  margin: 9px 0;
  font-size: 12px;
}

.empty-filter button {
  padding: 0;
  border: 0;
  color: var(--accent);
  background: transparent;
  font-size: 11px;
}

.mobile-close {
  display: none;
}

.rotating {
  animation: rotate 700ms linear infinite;
}

@keyframes rotate {
  to { transform: rotate(360deg); }
}

@media (max-width: 900px) {
  .object-explorer {
    position: fixed;
    z-index: 60;
    inset-block: 95px 25px;
    inset-inline-start: 0;
    inline-size: min(310px, 88vw);
    border-inline-end: 1px solid var(--line-strong);
    box-shadow: 18px 0 40px #17251f24;
    transform: translateX(-105%);
    transition: transform 160ms ease;
  }

  .object-explorer.mobile-open {
    transform: translateX(0);
  }

  .mobile-close {
    display: inline-flex;
  }
}
</style>
