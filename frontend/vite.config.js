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
    // G6 4.x is a single ~1.5 MB runtime and ECharts needs its whole core; both are already
    // split into their own chunk and only fetched by the route that renders them.
    chunkSizeWarningLimit: 1600,
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
