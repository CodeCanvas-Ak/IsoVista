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
          if (path.includes('/node_modules/zrender/')) {
            return 'chart-renderer'
          }
          if (path.includes('/node_modules/echarts/')) {
            if (path.includes('/lib/chart/') || path.endsWith('/echarts/charts.js')) return 'chart-types'
            if (path.includes('/lib/component/') || path.endsWith('/echarts/components.js')) return 'chart-components'
            return 'chart-core'
          }
          const antvPackage = path.match(/\/node_modules\/@antv\/([^/]+)\//)
          if (antvPackage) {
            return `graph-${antvPackage[1]}`
          }
        },
      },
    },
  }
})
