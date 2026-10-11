import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [vue()],
    server: {
      port: 5173,
      proxy: {
        '/agent-api': {
          target: env.VITE_AGENT_TARGET || 'http://127.0.0.1:19082',
          changeOrigin: true,
          rewrite: path => path.replace(/^\/agent-api/, '/api/v1/agent')
        },
        '/api': { target: env.VITE_API_TARGET || 'http://localhost:8080', changeOrigin: true }
      }
    }
  }
})
