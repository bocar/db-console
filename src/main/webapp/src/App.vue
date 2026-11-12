<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import LoginPage from './components/LoginPage.vue'
import WorkspacePage from './components/WorkspacePage.vue'

const currentHash = ref(window.location.hash)
const showLogin = computed(() => currentHash.value === '#login')
const syncRoute = () => {
  currentHash.value = window.location.hash
}

const navigate = (page: 'login' | 'workspace') => {
  window.location.hash = page
}

onMounted(() => window.addEventListener('hashchange', syncRoute))
onUnmounted(() => window.removeEventListener('hashchange', syncRoute))
</script>

<template>
  <LoginPage v-if="showLogin" @login="navigate('workspace')" />
  <WorkspacePage v-else @logout="navigate('login')" />
</template>
