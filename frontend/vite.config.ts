import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  test: {
    // Playwright's e2e/*.spec.ts files use @playwright/test's own test/expect, not
    // vitest's - without this, vitest's default glob picks them up too and they fail
    // to even load (wrong `test` import).
    exclude: ['**/node_modules/**', '**/e2e/**'],
  },
})
