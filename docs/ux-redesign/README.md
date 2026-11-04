# UX redesign — design reference

The Android app and admin dashboard redesign from 2026-10-25. Tickets 79–91 (issues #103–#112 and #114–#116) build it.

- `DECISIONS.md` — the settled product and UX decisions (Q1–Q25, admin dashboard). Read it before changing a redesigned screen.
- `boards/` — one `.dc.html` file per board of the design canvas (https://claude.ai/artifact/AFpiaLaFzNb8frtbfBe3Hq). Each holds the board's markup, copy, colours and sample logic in its `<script type="text/x-dc">` block. The canvas runtime is not included, so open them as source, not in a browser.
- `board-text.md` — every app board as plain text: its copy, props and the start of its script. Quickest way to find a string.

The canvas is the live design; these files are the snapshot the tickets were written against.

The admin boards (`AdminBar`, `AdminTasks`, `AdminStores`, `AdminStore`, `AdminCreate`, `AdminSignIn`)
are implemented in `server/public/`: screens and routing in `src/controller.ts` + `src/views/`
(`tasks`, `stores`, `store`, `create`, `audit`), the subscription and filter rules in
`src/subscription.ts`, design tokens in `src/styles.css`. The build forbids `style=` attributes
(`style-src 'self'`), so layout lives in the stylesheet.
