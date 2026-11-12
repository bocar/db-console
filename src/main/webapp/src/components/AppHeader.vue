<script setup lang="ts">
import { computed } from 'vue'
import { Check, ChevronDown, Database, LogOut, Menu, PlugZap, Server } from 'lucide-vue-next'
import type { DataSourceInfo } from '../types'
import { displayDatabaseType, displayEnvironment, environmentClass as envClass } from '../display'

const props = defineProps<{
  sources: DataSourceInfo[]
  selectedSourceId: string
  username: string
  testing: boolean
}>()

const emit = defineEmits<{
  changeSource: [id: string]
  testConnection: []
  logout: []
  toggleSidebar: []
}>()

const selectedSource = computed(() => props.sources.find((source) => source.id === props.selectedSourceId) ?? props.sources[0])
const environmentLabel = computed(() => (selectedSource.value ? displayEnvironment(selectedSource.value.environment) : ''))
const environmentClass = computed(() => (selectedSource.value ? envClass(selectedSource.value.environment) : ''))
const databaseTypeLabel = computed(() => (selectedSource.value ? displayDatabaseType(selectedSource.value.databaseType) : ''))
const initials = computed(() => (props.username ? props.username.slice(0, 2).toUpperCase() : '··'))

const changeSource = (event: Event) => {
  const select = event.target as HTMLSelectElement
  const nextId = select.value
  select.value = props.selectedSourceId
  emit('changeSource', nextId)
}
</script>

<template>
  <header class="app-header">
    <div class="app-bar">
      <div class="app-brand">
        <span class="app-brand-mark"><Database :size="18" :stroke-width="1.9" /></span>
        <span>db-console</span>
      </div>
      <nav class="app-nav" aria-label="主导航">
        <button type="button" class="nav-item active" aria-current="page">SQL 工作台</button>
      </nav>
      <div class="app-actions">
        <span class="user-avatar" aria-hidden="true">{{ initials }}</span>
        <span class="user-name">{{ username }}</span>
        <button type="button" class="logout-button" title="退出登录" @click="emit('logout')">
          <LogOut :size="16" />
          <span>退出</span>
        </button>
      </div>
    </div>
    <div class="source-bar">
      <button type="button" class="icon-button mobile-menu" aria-label="打开对象资源管理器" @click="emit('toggleSidebar')"><Menu :size="19" /></button>
      <Server :size="16" class="source-icon" aria-hidden="true" />
      <label for="datasource-select" class="source-label">数据源</label>
      <div class="source-select-wrap">
        <select id="datasource-select" :value="selectedSourceId" name="datasource" @change="changeSource">
          <option v-for="source in sources" :key="source.id" :value="source.id">{{ source.displayName }}</option>
        </select>
        <ChevronDown :size="14" aria-hidden="true" />
      </div>
      <span class="badge" :class="environmentClass">{{ environmentLabel }}</span>
      <span class="source-type">{{ databaseTypeLabel }}</span>
      <div class="connection-actions">
        <span class="connection-status"><span class="connection-dot"></span>连接正常</span>
        <button type="button" class="button test-button" :disabled="testing" @click="emit('testConnection')">
          <Check v-if="!testing" :size="14" />
          <PlugZap v-else :size="14" class="spin" />
          {{ testing ? '测试中…' : '测试连接' }}
        </button>
      </div>
    </div>
  </header>
</template>

<style scoped>
.app-header {
  position: relative;
  z-index: 20;
  border-block-end: 1px solid var(--line);
  background: var(--surface);
}

.app-bar {
  block-size: 51px;
  padding-inline: 18px 14px;
  display: flex;
  align-items: center;
}

.app-brand {
  min-inline-size: 249px;
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: 18px;
  font-weight: 650;
  letter-spacing: -0.035em;
}

.app-brand-mark {
  inline-size: 30px;
  block-size: 30px;
  border-radius: 6px;
  display: grid;
  place-items: center;
  color: #ffffff;
  background: var(--accent);
}

.app-nav {
  align-self: stretch;
  display: flex;
}

.nav-item {
  position: relative;
  padding-inline: 16px;
  border: 0;
  color: var(--muted);
  background: transparent;
  font-weight: 540;
}

.nav-item.active {
  color: var(--accent);
}

.nav-item.active::after {
  content: '';
  position: absolute;
  inset-inline: 15px;
  inset-block-end: 0;
  block-size: 2px;
  background: var(--accent);
}

.app-actions {
  margin-inline-start: auto;
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: 12px;
}

.preview-label {
  margin-inline-end: 12px;
  padding: 3px 7px;
  border: 1px solid var(--line);
  border-radius: 4px;
  color: var(--muted);
  background: var(--surface-subtle);
}

.user-avatar {
  inline-size: 27px;
  block-size: 27px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: #315b52;
  background: #dfece7;
  font-size: 10px;
  font-weight: 650;
}

.user-name {
  font-weight: 540;
}

.logout-button {
  min-block-size: 30px;
  margin-inline-start: 8px;
  padding: 0 8px;
  border: 0;
  border-radius: 5px;
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--muted);
  background: transparent;
}

.logout-button:hover {
  color: var(--ink);
  background: var(--surface-hover);
}

.source-bar {
  min-block-size: 43px;
  padding-inline: 18px;
  border-block-start: 1px solid #edf1ef;
  display: flex;
  align-items: center;
  gap: 9px;
  background: #fbfcfb;
}

.source-icon {
  color: var(--muted);
  flex: 0 0 auto;
}

.source-label {
  color: var(--muted);
  font-size: 12px;
}

.source-select-wrap {
  position: relative;
  min-inline-size: 154px;
}

.source-select-wrap select {
  max-inline-size: 220px;
  min-block-size: 28px;
  padding: 0 25px 0 5px;
  border: 0;
  border-radius: 4px;
  appearance: none;
  color: var(--ink);
  background: transparent;
  font-weight: 580;
}

.source-select-wrap select:hover {
  background: var(--surface-hover);
}

.source-select-wrap svg {
  position: absolute;
  inset-inline-end: 5px;
  inset-block-start: 7px;
  pointer-events: none;
  color: var(--muted);
}

.source-type,
.schema-name {
  color: var(--muted);
  font-size: 12px;
}

.source-divider {
  inline-size: 1px;
  block-size: 17px;
  margin-inline: 5px;
  background: var(--line);
}

.connection-actions {
  margin-inline-start: auto;
  display: flex;
  align-items: center;
  gap: 15px;
}

.connection-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--success);
  font-size: 12px;
}

.connection-dot {
  inline-size: 6px;
  block-size: 6px;
  border-radius: 50%;
  background: var(--success);
}

.test-button {
  min-block-size: 28px;
  padding-inline: 9px;
  font-size: 12px;
}

.mobile-menu {
  display: none;
}

.spin {
  animation: spin 900ms linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

@media (max-width: 900px) {
  .app-brand {
    min-inline-size: auto;
    margin-inline-end: 24px;
  }

  .source-type,
  .source-divider,
  .preview-label {
    display: none;
  }

  .mobile-menu {
    display: inline-flex;
  }
}

@media (max-width: 640px) {
  .app-bar {
    padding-inline: 12px;
  }

  .app-nav,
  .user-name,
  .schema-name,
  .source-icon,
  .connection-status,
  .logout-button span {
    display: none;
  }

  .source-bar {
    padding-inline: 9px;
    gap: 6px;
  }

  .source-label {
    display: none;
  }

  .source-select-wrap {
    min-inline-size: 0;
    flex: 1;
  }

  .source-select-wrap select {
    max-inline-size: 100%;
    inline-size: 100%;
  }

  .connection-actions {
    margin-inline-start: 0;
  }
}
</style>
