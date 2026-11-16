<script setup lang="ts">
import { onMounted, ref } from 'vue'
import LoginPage from './components/LoginPage.vue'
import ScriptPage from './components/ScriptPage.vue'
import WorkspacePage from './components/WorkspacePage.vue'
import { HttpApiClient } from './api/http-client'
import { provideApiClient } from './api/client'
import { useSession } from './composables/useSession'

const client = new HttpApiClient()
provideApiClient(client)
const { status, refresh, logout, expire } = useSession(client)
client.onSessionExpired(expire)
const view = ref<'workspace' | 'script'>('workspace')

onMounted(() => {
  void refresh()
})
</script>

<template>
  <div v-if="status === 'unknown'" class="app-boot">正在检查登录状态…</div>
  <LoginPage v-else-if="status === 'anonymous'" />
  <template v-else>
    <WorkspacePage v-if="view === 'workspace'" @logout="logout" @switch-view="view = $event" />
    <ScriptPage v-else @logout="logout" @switch-view="view = $event" />
  </template>
</template>

<style scoped>
.app-boot {
  min-block-size: 100dvh;
  display: grid;
  place-items: center;
  color: var(--muted);
}
</style>
