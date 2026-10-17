# AGENTS.md — قواعد العمل في مستودع سدد

اقرأ هذا قبل أي تعديل. الهدف: مصدر واحد لكل مكوّن، ومنع إنشاء نسخة ثانية منه.
في بداية كل جلسة، اقرأ `docs/CONTINUE_HERE.md` وافحص `git status` والفرع.
المعمارية المستهدفة وخطة القطع النظيف في `docs/ARCHITECTURE.md`، وسجل الإصدارات في `docs/CHANGELOG.md`.

## المصادر المعتمدة

| المكوّن | المسار المعتمد | القاعدة |
| --- | --- | --- |
| تطبيق Android | `app/` (Java حاليًا؛ يُستبدل بـKotlin/Compose في المرحلة 3) | عدّل المشروع المعتمد؛ لا تنشئ مشروعًا ثانيًا |
| الخادم | `Supabase-Setup/supabase/` (مشروع `vhftjmiltqfwmrszdtgf`) | لا تنشئ خادمًا موازيًا؛ النشر والترحيل باعتماد بشري |
| لوحة الإدارة | `server/public/` تُبنى عبر `tools/build-admin-pages.cjs` إلى `_site/` | مصدر واحد للوحة |
| خطة المعمارية | `docs/ARCHITECTURE.md` | المرجع لما يحلّ محلّ كل مكوّن في المراحل 1–4 |
| سجل الإصدارات | `docs/CHANGELOG.md` | أضف سطرًا لكل إصدار أو مرحلة؛ لا تنشئ ملفات إصدار منفصلة |
| حالة العمل | `docs/CONTINUE_HERE.md` | أقل من 80 سطرًا للحالة الحالية فقط |

**إن احتجت تغيير سلوك مكوّن، عدّل المكوّن المعتمد. لا تُنشئ نسخة ثانية منه.**

## خريطة الكود (الشجرة الحالية)

انظر `docs/ARCHITECTURE.md` (الجزء 3) لمعرفة ما يستبدل كل مكوّن في المرحلتين 2 و3.

| الميزة | الملف | نقطة البداية |
| --- | --- | --- |
| التنقّل وحالة المسار | `MainActivity.java` | `show(String route)`، `currentRoute()`، `refreshCurrentScreen()` |
| بناء الشاشات (برمجي بلا XML) | `NativeScreens.java` | `show(String page)`، `addPage(String page)` |
| قاعدة البيانات المحلية | `SadadDatabase.java` | `saveContact`، `saveDebt`، `savePayment`، `importLedgerView` |
| الجلسة والدخول | `MainActivity.java` + `SessionStore.java` | |
| طابور العمليات ومزامنة السجل الموقّع (v3) | `LedgerQueue.java` → `SignedLedgerSync.java` + `LedgerClient.java` | `LedgerCommand.now()` |
| الإسقاط المحلي للسجل | `LedgerProjection.java` | |
| تصدير الكشوفات ومشاركتها | `LedgerTablePdf.java`، `LedgerTableExcel.java`، `PdfShareProvider.java` | |
| تذكيرات الاستحقاق | `DueReminderManager.java` | |
| بوابة الهوية (Auth envelope) | `sadad-auth/handler.ts` | `/mobile/login`، `/mobile/auth/refresh` |
| بوابة الحسابات والإدارة | `sadad-api/handler.ts` | `route()` |
| السجل الموقّع وإدارة التركيبات | `sadid-ledger/handler.ts` | `/mobile/v3/enroll`، `verifyProof`، `/admin/*` |
| لوحة الإدارة | `server/public/admin.js` | تُبنى بـ`tools/build-admin-pages.cjs` |
| الترحيلات | `Supabase-Setup/supabase/migrations/` | |

## البناء والفحص

```sh
# JDK 21 و Android SDK 37 (sdk.dir في local.properties المستبعد من Git)
./tools/check.sh
```

- `./tools/check.sh` هو البوابة الموحدة محليًا وفي CI، ويشغّل CI جميع أقسامه.
- إصدار الإنتاج يتطلب `SADAD_KEYSTORE_FILE` و`SADAD_KEYSTORE_PASSWORD` و`SADAD_KEY_ALIAS` و`SADAD_KEY_PASSWORD` مع `assembleServerRelease`، ويرفض أي عنوان خادم غير المدمج وقت البناء (`-PSADAD_API_BASE_URL=...`).

## المفاتيح والبيانات الحساسة

- مفتاح `sb_publishable_...` وحده مسموح في التطبيق أو اللوحة أو Gradle.
- مفاتيح `sb_secret_...` و`service_role` وكلمات المرور وقواعد بيانات المستخدمين ممنوعة في المستودع العام (`Abdalrahman-py/sadad`)؛ تقرأ وظائف Edge الأسرار من بيئة المشروع فقط.

## الاختبارات

- كل الفحوص تعمل عبر `./tools/check.sh` (أو `./tools/check.sh <section>`).
- اختبارات الجهاز (`app/src/androidTest`) تُشغَّل بمعرّف QA منفصل (`com.sadad.qa`) وتحتاج هاتفًا متصلًا.

## الكتابة والتوثيق

- العربية أولًا في ملفات المستخدم والتشغيل، والإنجليزية مقبولة في التوثيق التقني.
- وثّق الحقائق المتحققة فقط؛ كل ما لم يُتحقق منه يُوسم صراحةً بعبارة «غير محقّق».

## الحفظ والاسترجاع

- الأوسمة `pre-consolidation-2026-10-11` (الشجرة قبل التنظيف شاملة `archive/Sadad-Flutter`)، و`pre-supabase-ledger-correctness-2026-10-12` (نقطة بداية تدقيق الدفتر)، و`pre-prune-2026-10` (الشجرة قبل حذف `delivery/` و`docs/archive/` و`docs/plans/` التي حلّ محلّها `docs/ARCHITECTURE.md`) تحفظ التاريخ في Git.
- حزمة فروع الأرشيف الخاصة خارج المستودع في `~/sadad-private/sadad-archives-2026-10-14.bundle` (غير محقّق حتى ينفّذ الإنسان التذكرة 01)؛ تُسترجع عند الحاجة بالأمر:
  `git fetch ~/sadad-private/sadad-archives-2026-10-14.bundle 'refs/heads/archive/*:refs/heads/archive/*'`

## Agent skills

### Issue tracker

Issues and specs live as local markdown under `.scratch/` (gitignored, not pushed). See `docs/agents/issue-tracker.md`.
In Claude Code cloud sessions there is no `.scratch/`: the ticket is a GitHub issue on `Abdalrahman-py/sadad`. See `docs/agents/cloud-sessions.md`.

### Triage labels

Default five-role vocabulary (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: root `GLOSSARY.md` + `docs/adr/`. See `docs/agents/domain.md`.

### Implementation skills

Use only: tdd, diagnosing-bugs, code-review, ponytail, supabase skills, writing-for-agents (doc tickets). The ticket prompt replaces `implement`, which OpenCode cannot load (`disable-model-invocation`). Never use speckit-* or to-spec/to-tickets during implementation. In OpenCode, the repo `opencode.json` denies those skills and denies `git push`, destructive git and Supabase cloud commands; a human pushes and deploys.
