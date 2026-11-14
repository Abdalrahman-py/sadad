# Admin dashboard prototype (approved 2026-11-14)

Clickable dark-only prototype of the redesigned admin dashboard. It is the design source for the React tickets
(189-198). Sample data only (`data.js`); the two app versions come live from the GitHub API.

Run it: `python3 -m http.server 4190 --directory docs/ux-redesign/dashboard-prototype` and open
`http://localhost:4190/`.

## Screens (hash routes)
- `#/tasks` المهام: labelled KPI strip, waiting devices (approve, raise limit and approve, reject), ending or expired
  subscriptions (extend split button), latest app versions (customer release, team build).
- `#/stores` المتاجر: status filter menu, sortable columns, sticky header, row menu (open, extend, temp password, copy).
- `#/stores/<id>` store page: tabs overview / devices / subscription / recovery; "⋯" holds the rare tools
  (`/permissions`, `/ledger` with a written reason, `/activity`). Overview = KPIs, account details, recent activity,
  sensitive actions (reason + type the username).
- `#/create` one-form store creation; success shows the 6-character code, copy message, WhatsApp, copy app link.
- `#/audit` سجل الإدارة with a type filter (changes, ledger reads). `#/signin`.

## Behaviour to keep
- Ctrl/Cmd+K or `/` opens the command palette (stores and pages). G then T/S/A goes to tasks/stores/audit, C creates.
- Support role (read-only): user menu, "عرض كحساب دعم فني". Write controls disappear, a banner says so.
- Loading skeleton, empty and error states: user menu, prototype-only switch.
- Tables turn into two-line rows by container width (620px, stores 900px).
- Version answer cached 10 minutes (GitHub: 60 unauthenticated calls per hour).

## Design system
Tokens and rules: `DESIGN.md` at the repo root (written from this build). Reference measurements from Linear,
Vercel Geist and Stripe: `REFERENCES.md`. Product facts: `PRODUCT.md`. Build tickets: 189-198 on GitHub.

## Not production code
Vanilla JS with inline icon CDN and Google Fonts; the React build must self-host fonts and icons (CSP
`style-src 'self'`) and allow `connect-src https://api.github.com` for the versions panel.
