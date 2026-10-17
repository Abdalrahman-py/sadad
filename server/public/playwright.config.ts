import { defineConfig } from "@playwright/test";

// Runs against the production build (`npm run build` first), so the CSP meta tag is in force.
export default defineConfig({
  testDir: "tests/e2e",
  forbidOnly: true,
  reporter: "list",
  use: { baseURL: "http://127.0.0.1:4173", browserName: "chromium", locale: "ar" },
  webServer: {
    command: "npx vite preview --host 127.0.0.1 --port 4173 --strictPort",
    url: "http://127.0.0.1:4173",
    reuseExistingServer: false,
  },
});
