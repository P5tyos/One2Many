import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue(), vueDevTools()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    // lets a Cloudflare quick tunnel reach the dev server, for testing with real phones
    allowedHosts: ['.trycloudflare.com'],
    // During development Vue runs on :5173 and Spring on :8080.
    // The proxy forwards these paths to Spring, so to the browser everything
    // comes from one address: cookies just work and no CORS is needed.
    proxy: {
      // changeOrigin: false keeps the browser's address (localhost:5173) in the request,
      // so Spring's redirects (e.g. after login) come back to Vue, not to :8080
      '/api': { target: 'http://localhost:8080', changeOrigin: false },
      '/login': { target: 'http://localhost:8080', changeOrigin: false }, // Spring's login page (staff)
      '/logout': { target: 'http://localhost:8080', changeOrigin: false },
    },
  },
})
