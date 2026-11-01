import type { Plugin } from "vite";
import { defineConfig } from "vitest/config";

// The only network origin: Supabase Auth and the `api` function of the pinned project.
// GitHub Pages cannot send headers, so the policy rides in a meta tag (docs/adr/003-dashboard-hosting.md).
// Added at build time only: the dev server injects <style> tags that this policy would block.
// The Sentry ingest origin joins connect-src at build time only when a DSN is set (scope-approved 2026-10-28).
const sentryIngest = process.env.VITE_SENTRY_DSN
  ? ` ${new URL(process.env.VITE_SENTRY_DSN).origin}`
  : "";

const CSP = [
  "default-src 'none'",
  "script-src 'self'",
  "style-src 'self'",
  "img-src 'self'",
  "font-src 'self'",
  `connect-src https://vhftjmiltqfwmrszdtgf.supabase.co${sentryIngest}`,
  "base-uri 'none'",
  "form-action 'none'",
].join("; ");

const contentSecurityPolicy = (): Plugin => ({
  name: "sadad-csp",
  apply: "build",
  transformIndexHtml: () => [
    {
      tag: "meta",
      attrs: { "http-equiv": "Content-Security-Policy", content: CSP },
      injectTo: "head-prepend",
    },
  ],
});

export default defineConfig({
  // Pages serves the site under /sadad/, so every asset URL stays relative.
  base: "./",
  plugins: [contentSecurityPolicy()],
  build: {
    outDir: "../../_site",
    emptyOutDir: true,
    // No data: URLs, so img-src and font-src stay 'self'.
    assetsInlineLimit: 0,
  },
  test: {
    environment: "happy-dom",
    include: ["tests/unit/**/*.test.ts"],
  },
});
