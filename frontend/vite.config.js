import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [
    vue(),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          const path = id.replaceAll('\\', '/')
          // Visualization runtimes have circular internal dependencies; keep each one intact.
          if (path.includes('/node_modules/zrender/') || path.includes('/node_modules/echarts/')) {
            return 'chart'
          }
          if (path.includes('/node_modules/@antv/')) {
            return 'graph'
          }
        },
      },
    },
  }
})
