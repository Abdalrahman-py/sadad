# ADR-003: Dashboard hosting stays on GitHub Pages

## Context

The rebuilt dashboard (`server/public/`, Vite + TypeScript + lit-html, ticket 25) is a static site. It signs in with Supabase Auth and calls only `/v1/admin/*` on the `api` function. `docs/ARCHITECTURE.md` C6 records that GitHub Pages cannot send response headers: no `Content-Security-Policy` header, no `frame-ancestors`, no HSTS, and no control over `Cache-Control`. Cloudflare Pages can send them from a `_headers` file, but it needs a new account, a new deploy token in the repository secrets, and a DNS or `*.pages.dev` address that admins would have to switch to.

## Decision

Keep GitHub Pages (`https://abdalrahman-py.github.io/sadad/`, built by `.github/workflows/admin-pages.yml`). Cover the missing headers in the page itself:

- **CSP as a meta tag**, added at build time by `server/public/vite.config.ts`: `default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; font-src 'self'; connect-src https://vhftjmiltqfwmrszdtgf.supabase.co; base-uri 'none'; form-action 'none'`. No inline script or style; Vite emits external hashed files and `assetsInlineLimit: 0` keeps fonts and images off `data:` URLs. The Playwright smoke runs against the production build, so the policy is enforced in the test.
- **Clickjacking:** `frame-ancestors` cannot be set from a meta tag. `src/main.ts` refuses to render when the page is framed (`window.top !== window.self`). A frame with scripts disabled shows an empty page with nothing to click.
- **XSS:** lit-html escapes every interpolated value; no view writes `innerHTML`. A Vitest case renders a store name containing `<img onerror>` and checks it stays text.
- **Session:** Supabase Auth keeps the session in `sessionStorage` (closing the tab signs out), never in `localStorage`.
- **Referrer:** `<meta name="referrer" content="no-referrer">`.
- **Caching:** Vite puts a content hash in every asset name, so a new `index.html` never loads an old script. How long Pages caches `index.html` itself is «غير محقّق».
- **Publishing is manual.** The workflow checks every push and pull request, but deploys only on `workflow_dispatch`. A merge to `main` therefore never switches the live dashboard to a backend that is not deployed yet. Ticket 27 publishes it once the `api` function is live.

## Consequences

- No HSTS header. `github.io` should be on the browsers' HSTS preload list, which would still enforce HTTPS for this address («غير محقّق»). A custom domain would not have that and should move to a host that sends headers.
- A framed copy cannot be blocked by the browser before scripts run. The JS guard is weaker than `frame-ancestors`, and that is accepted for an internal admin tool.
- Moving to Cloudflare Pages later needs no code change: copy the same CSP into a `_headers` file with `frame-ancestors 'none'` and HSTS, point the workflow at it, and drop the meta tag. Revisit if the dashboard gets a custom domain, or if a security review asks for real headers.
