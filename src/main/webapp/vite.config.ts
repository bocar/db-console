/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  build: {
    target: 'es2022',
    chunkSizeWarningLimit: 4500,
  },
  test: {
    environment: 'jsdom',
  },
})
