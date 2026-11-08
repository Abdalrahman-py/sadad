# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

Sadad staff, in two roles. An admin runs the platform: approves store phones, renews subscriptions, creates store accounts, and handles recovery and account actions. A support agent reads everything and changes nothing; each ledger read is recorded with a reason.

Their main job is to clear the task queue. A store owner is often waiting on a phone for a device approval. Looking up one store is the second job.

Scope note: this record covers the admin dashboard in `server/public/`. The Android app in `app/` has its own design record in `docs/ux-redesign/`.

## Product Purpose

Sadad is a debt ledger for small shops. The dashboard controls who may use it: store accounts, the phones approved for each store, subscriptions, and the audit trail. Success is a queue that reaches zero quickly, with no wrong action on a real store.

## Operating Context

Used mostly on a laptop, in work sessions between other tasks. A phone must still complete every task. Production holds real customer data since 2026-11-04.

## Capabilities and Constraints

- Screens: tasks (landing), stores with the open store beside the list (account, ledger, activity log; settings and recovery open from the account), create store, admin audit log, sign-in.
- Arabic first, right to left. English appears in usernames, IDs and emails.
- Vite, TypeScript and lit-html. Views are pure functions of the model; `controller.ts` owns state.
- Hosted on GitHub Pages under a strict CSP: `style-src 'self'`, `font-src 'self'`, `img-src 'self'`. Fonts and images ship with the build. No inline styles and no external assets.
- Temporary passwords show once and never again.
- Dangerous account actions need a typed username, and most need a recorded reason.

## Brand Commitments

- Name: سدد. Subtitle: لوحة الإدارة.
- Font: Tajawal, regular and bold, shipped as four woff2 files in `server/public/src/assets/` (Arabic and Latin subsets, OFL licence beside them).
- The dashboard does not use the app's green. It is black and white with one accent (user decision, 2026-11-05).
- No cards, no chips, no rounded corners (user decision, 2026-11-05).

## Evidence on Hand

Typed sample rows in `server/public/tests/data.ts`. Real store data stays out of the repo, screenshots and mockups.

## Product Principles

1. The queue comes first: work waiting on a person leads every session.
2. Functional and simple: nothing on screen that does not help the task.
3. State is plain text a person can read, not decoration.
4. A destructive action is never one accidental click away.

## Accessibility & Inclusion

Full keyboard use, visible focus, and text contrast of at least 4.5:1. Right-to-left layout is correct by construction, using logical CSS properties.
