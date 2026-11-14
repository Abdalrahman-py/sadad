# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

- **Admin (the owner):** runs Sadad as a service for shops in Palestine. Creates store accounts, sends the
  owner a username and a temporary password, approves each new phone, extends subscriptions, freezes or
  archives stores. Often acts while a shop owner waits on the phone or at the counter.
- **Support teammates:** role `support`, read-only. See everything, change nothing, answer customer questions.
- Both use the dashboard on a laptop and on a phone about equally.

## Product Purpose

Sadad (سدد) is an Android ledger app for shops: who owes the shop and who the shop owes, with signed,
offline-first entries synced to Supabase. The admin dashboard (`server/public`, GitHub Pages) is where the
service is operated: store accounts, devices, subscriptions, and the rare support actions (ledger read,
data recovery). Success: a waiting owner is unblocked in seconds, and nothing destructive happens by accident.

## Positioning

Not a SaaS console. One operator and a few helpers running a small, real service with real customers since
2026-11-04. Every number on screen must say exactly what it counts.

## Operating Context

- Daily jobs: approve or reject a waiting device (inline, from the queue), extend a subscription, create a store
  and hand over the credentials, answer "why can't I sign in".
- Rare jobs: read a store's ledger with a written reason (audited), 3-step recovery to a new phone, admin audit log,
  per-store permissions.
- Two app channels: customer release (`v*` tags, GitHub Releases `latest`) and the team build (`team-latest`
  prerelease). The dashboard shows the latest version of each.
- Arabic, RTL, Tajawal. Western digits in data.

## Capabilities and Constraints

- Lit-html today; React migration planned (tickets 189-195, #353-#359), to be rewritten to the approved redesign.
- Strict CSP from a meta tag on GitHub Pages: `style-src 'self'`, no inline styles, no runtime `<style>`.
- Roles: `admin` writes, `support` reads only. Dangerous actions need a reason and typing the username.
- Temporary password: short one-time code (6 characters, capitals and digits, no look-alikes); the owner must
  change it at first sign-in, and the first phone to sign in becomes the approved first device.
- The same GitHub Pages site will later also hold a landing page and the privacy page (`/sadad/privacy`).

## Brand Commitments

- Dark theme only. Clear solid colours. Minimal. Plain column names and labelled numbers.
- Tajawal is the dashboard face; the app's green is the brand colour.
- Standing preference (2026-11-14): the dashboard follows the category standard dark admin, at the craft level of Vercel, Linear and Stripe dashboards.

## Evidence on Hand

- Real API contract: `contract/openapi.json` (AdminStore, AdminStoreSummary, AdminOverview, AdminInstallation...).
- No public customer list, testimonials, or metrics may be invented; prototypes use clearly sample data.

## Product Principles

1. The waiting person first: pending devices and ending subscriptions lead.
2. Every number has a label that says what it counts.
3. Dangerous things are slow on purpose; daily things take one tap.
4. Rare tools stay reachable but out of the way.

## Accessibility & Inclusion

RTL Arabic throughout, keyboard reachable, WCAG AA contrast on the dark theme, phone-sized touch targets.
