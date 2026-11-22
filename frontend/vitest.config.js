import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'

// Unit tests for the plain logic in src/lib/ (no browser needed). Separate from vite.config.js
// so the tests don't load the Vue devtools plugin. Run: npm test (in frontend/).
export default defineConfig({
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'node',
    include: ['tests/unit/**/*.test.js'],
  },
})
