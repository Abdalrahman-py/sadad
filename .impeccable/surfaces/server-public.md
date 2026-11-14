---
version: 1
slug: "server-public"
primary_target: "server/public"
related_targets: []
---

# Surface brief: admin dashboard (server/public)

Scope: whole admin dashboard (sign-in, tasks, stores, store page, create store, rare tools). Mode: Operate.
Audience: admin owner (writes) and support (read-only), laptop and phone equally.
Task: unblock a waiting owner (approve device, extend subscription, create store and send a 6-char code).
Constraints: dark only, solid colours, plain column names, strict CSP (no inline style), RTL Arabic.
Rare tools (ledger read, admin audit log, store permissions) move out of the main view, still reachable.

## Direction contract
THESIS: The category standard dark admin, played straight at Vercel/Linear/Stripe craft: right-side sidebar, one labelled KPI strip, the waiting-devices table first, then the stores table. Refuses vanity tiles, icon circles, gradients, glow.
OWN-WORLD: Near-black ground #0a0a0a, surface #111, raised #1a1a1a, 1px #222 rules; ink #ededed, grey #a1a1a1; Sadad green #1ba892 only on positive status and data; solid status dots with words (green active, amber ending, red expired, blue frozen, grey archived); white primary button, 6px controls, 8px panels; Tajawal for Arabic, Inter tabular digits.
STORY: Admin opens, sees how many phones wait and which subscriptions end, approves or extends in one tap, creates a store and copies a short code message to the owner.
FIRST VIEWPORT: Sidebar right 232px (mark, المهام with count, المتاجر, إنشاء متجر; tools group; user row). Canvas: title row المهام + white إنشاء متجر button; KPI strip of 4 labelled counts in one ruled container; table أجهزة تنتظر الاعتماد with اعتماد/رفض per row; beside it the app versions panel (الزبون, المطورون) with download.
FORM: canon (category standard) chosen on the decision page; seed key 4a900f80.
FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
