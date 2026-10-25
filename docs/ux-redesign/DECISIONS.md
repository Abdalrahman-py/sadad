# Sadad UX redesign — settled decisions (2026-10-25)

Canvas: https://claude.ai/artifact/AFpiaLaFzNb8frtbfBe3Hq — board sources in `boards/`; a plain-text dump of every board is `board-text.md`.

## Users and scale
- Q1: Core speed path for owner/cashier at the counter (customer present, one hand, interrupted). Home follow-up, Activity, contact page also serve the owner reviewing in the evening.
- Q2: Design for 50–1,000 people per store; must not break past 1,000.

## Navigation (Q5′)
- Bar: الرئيسية · الأشخاص · «+» · الحركات · المتجر.
- One home per intent: record = «+»; who owes / who to chase = Home + People; one person = Contact page; store over a period = الحركات (replaces History + Reports); documents = export buttons where the data is (Activity, Contact); sync / ops log / devices = المتجر.
- Avatar menu removed. Bell only on Home.

## Recording
- Q3: Person first. «+» → search (keyboard up) + vertical recent/frequent list; rows: avatar, name, last 3 phone digits, balance. Arabic-normalised search (أ/ا/إ, ة/ه, ى/ي) + phone. No match → «إضافة "X" كشخص جديد».
- Q21: Amount screen: person + balance header, type-specific two actions (debt first), keypad, optional chips (البيان, الاستحقاق debts only, طريقة الدفع payments only default نقدي, voice), save button with preview line.
- Q22: New person sheet: name (prefilled), phone optional, زبون/مورد default زبون → straight to amount. Duplicate phone offers «فتح سجل X».
- Q12: No review dialog. Save button states the sentence. Dialog only above 5,000 ₪ (ANOMALY_LIMIT). «تراجع» 8 s after save; later fixes via «تصحيح» / «عكس الدفعة».

## Money model
- Q4: Both directions (customers and suppliers). New feature: needs app logic (today the app only creates RECEIVABLE).
- Q9: Required type زبون/مورد (default زبون) + optional free label.
- Q10: Labels by type — customer «دين جديد» / «استلام دفعة»; supplier «بضاعة بالدين» / «دفع للمورد». Red = adds to a debt, green = settles.
- Q13: Person rows: «عليه ₪X» (owes you, #AB323A), «له ₪X» (you hold their money, #9A5A13), «مسدد» (grey); always word + icon, never colour alone; numbers LTR; screen readers hear words. Store totals: «لك عند الناس» big, «عليك للناس» smaller.
- Q14: Overpayment allowed with preview «يسدد ₪250 الدين · يبقى له ₪250 رصيدًا»; next debt shows «يُخصم من رصيده».
- Q15: Design for the net-balance/credit model; ledger change is its own ticket. Today server rejects with `overpayment` and `PaymentAllocation` throws; payments must reference a debt. Until shipped, app caps at open balance.

## Screens
- Home (Q16 + user notes): bell; hero with «لك عند الناس» big bold total, «عليك للناس» smaller; month line in smaller text: month debts + collection bar «تحصيل ₪X · N دفعة» (user likes this, keep); «تحتاج متابعة» overdue/due-today with WhatsApp «تذكير» prefilled with balance; «عرض الكل» → People filtered; empty-store card «سجّل أول دين». No record buttons, no recent strip, no movements list on Home.
- People (Q17): fixed search; chips الكل · عليهم · لهم · متأخرون · موردون; rows avatar, name, phone tail, balance word, last activity; sort آخر حركة (default) / الأعلى رصيدًا / الاسم; adding a person only via the picker create row.
- Contact (Q18): header name/type/phone + balance block; sticky bottom two type actions; ⋯ menu: reminder, share PDF statement, edit, archive; timeline newest first grouped by month with balance-after on every row; tap row → details with تصحيح / عكس الدفعة.
- Activity (Q19): period chips اليوم (default) · هذا الأسبوع · هذا الشهر · مخصص; totals ديون · تحصيل · صافي; feed grouped by day with actor/device; filters type + staff/device; PDF/Excel export in top bar.
- Store (Q20): Store (name, owner, switcher + add store) · Devices & sync (status, unsent, ops log, recovery) · Security (lock, biometric, password) · Data (export, trash) · Appearance · Support. Fact: the app knows only a per-device `permissions` map (Installation.permissions), not owner vs staff role — hide items by permission (Q6 rule), not by role.
- Q6: Restricted actions hidden in fast flows; on the contact page shown locked with «اطلب من صاحب المتجر تفعيل الدفعات».
- Q7: Sync dot always visible; if saving is impossible, Quick add shows a banner before typing with «إعادة الاتصال».
- Q23: Lock = same keypad + auto biometric. Password change = one screen, live checklist (12–128 chars, not the current one — from openapi). Approval = 3-step progress, auto re-check 30 s + «تحقق الآن».
- Q24: Notifications: one list; rejected entries pinned red with next step; due reminders with «تذكير»; read items fade.
- Sign-in: Welcome merged into Login (one screen).

## Deliverable (Q8, Q25)
Boards: map, nav, Home (normal/empty/offline), Quick add (picker, new person, amount debt/payment/overpay, done+undo), People, Contact (customer with credit, supplier), Activity, Store, Notifications, Lock, Password change, Approval, Trash, Recovery. Dark: Home, Quick add, Contact. Clickable main loop.

## Token note
Small grey text uses #5B6F63 (not #65796C): #65796C is 4.36:1 on the page bg, below 4.5:1.

## Admin dashboard (web, server/public) — 2026-10-25
Boards: AdminSignIn, AdminTasks, AdminStores, AdminStore, AdminCreate, AdminBar (row at y 8000). Tajawal kept (dashboard's own face), app green tokens.
- Users: role `admin` (writes) and `support` (read-only, session.ts). Support sees everything, no write controls, a read-only note.
- Landing = «المهام» queue, not a metrics hero: devices waiting (owner is often waiting at the counter) with inline approve/reject; full device limit offers «رفع الحد واعتماد» or replace; subscriptions ending ≤7 days or expired with one-tap extend. Separate «طلبات الأجهزة» tab merged into it (no duplication). Metrics shrink to a side card.
- Nav: المهام (count badge) · المتاجر · سجل الإدارة.
- Stores: filter chips replace the archived checkbox (الكل · نشطة · تنتهي قريبًا · منتهية · مجمّدة · مؤرشفة); row opens the store page.
- Store page: its own page with tabs (نظرة عامة · الأجهزة · الاشتراك · الإعدادات · الدفتر · الاسترداد · سجل النشاط), replacing the panel rendered under the table. Header quick actions: تمديد 30 يومًا, كلمة مرور مؤقتة (one-time secret card). Sensitive actions grouped in a red card, each confirmed by typing the username.
- Ledger read stays reason-gated and audited; recovery is a 3-step form (devices → manifest check → reason).
- Create store: permissions default to all on (today none are ticked), live username rule + taken check, then a hand-off step with next steps for the owner.
- Not designed: delete-unused-account flow, admin audit page (link placeholder), store-wide audit search.
