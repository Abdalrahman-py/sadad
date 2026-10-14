# AGENTS.md — قواعد العمل في مستودع سدد

اقرأ هذا قبل أي تعديل. الهدف: مصدر واحد لكل شيء، ومنع إنشاء نسخة ثانية من أي مكوّن.

في بداية كل جلسة، اقرأ القسم الحالي في `docs/CONTINUE_HERE.md` وافحص `git status` والفرع.
ابدأ العمل الجديد من `main` المحدّث؛ الأقسام المؤرخة القديمة سجل تاريخي، وحالة نشر السحابة
مستقلة عن اكتمال المصدر. تفاصيل الإصدارات في `docs/CHANGELOG.md`.

## المصادر المعتمدة

| المكوّن | المسار المعتمد | لا تفعل |
| --- | --- | --- |
| تطبيق Android | `app/` (Java أصلي، نسختا `server` و`standalone`) | لا تُنشئ مشروع Android ثانيًا ولا نسخة WebView |
| الخادم | `Supabase-Setup/supabase/` (وظائف Edge + الترحيلات) لمشروع `vhftjmiltqfwmrszdtgf` | لا تُنشئ خادم Node/SQLite موازيًا |
| لوحة الإدارة | `server/public/` (ملفات ثابتة) تُبنى عبر `tools/build-admin-pages.cjs` إلى `_site/` | لا تنسخ `admin.html`/`admin.js` إلى مجلد آخر |
| الترحيلات | سجل الترحيلات على المشروع السحابي هو المرجع | لا تُطبّق ترحيلات جاهزة على الإنتاج بلا مراجعة |
| سجل الإصدارات | `docs/CHANGELOG.md` | لا تُضف ملف «2.5.X» جديدًا لكل إصدار |
| حالة العمل | `docs/CONTINUE_HERE.md` | لا تُضف صفحة حالة ثانية |

قاعدة صريحة: **إن احتجت تغيير سلوك مكوّن، عدّل المكوّن المعتمد. لا تُنشئ نسخة ثانية منه.**
كل التكرار في هذا المستودع جاء من مخالفة هذا السطر.

## خريطة الكود: أين تعيش كل ميزة

مصدر واحد لكل ميزة. ابدأ من نقطة البداية المذكورة، وافحص المستدعين قبل الحذف.

| الميزة | الملف | نقطة البداية |
| --- | --- | --- |
| التنقّل وحالة المسار | `MainActivity.java` | `show(String route)`، `currentRoute()`، `refreshCurrentScreen()` |
| بناء الشاشات (لا XML؛ كل العرض برمجي) | `NativeScreens.java` | `show(String page)`، ثم `buildWelcome/buildLogin/buildHome/buildHistory/buildSettings` |
| جدول المسار ← الشاشة | `NativeScreens.java` | `addPage(String page)` (سلسلة `case`) |
| قاعدة البيانات المحلية | `SadadDatabase.java` | `saveContact`، `saveDebt`، `savePayment`، `historyActivities` |
| الجلسة والدخول | `MainActivity` + `SessionStore.java` | |
| مزامنة السجل الموقّع (v3) | `LedgerQueue.java` → `SignedLedgerSync.java` + `LedgerClient.java` | |
| هوية المتجر والإدارة | `sadad-api/handler.ts` | الدخول والجلسة وإدارة الحساب؛ البروتوكول المالي القديم حُذف من المصدر |
| طابور العمليات وبناء الأمر | `LedgerQueue.java` + `LedgerCommand.java` | `LedgerCommand.now()` يبني الطابع الزمني المرسل |
| الإسقاط المحلي للسجل | `LedgerProjection.java` | |
| تصدير الكشوفات | `LedgerTablePdf.java` / `LedgerTableExcel.java` | |
| مشاركة الملفات | `PdfShareProvider.java` | |
| تذكيرات الاستحقاق | `DueReminderManager.java` | |
| بوابة الهوية (Auth envelope) | `sadad-auth/handler.ts` | `/mobile/login`، `/mobile/auth/refresh` |
| بوابة الحسابات والإدارة | `sadad-api/handler.ts` | `route()` |
| السجل الموقّع على الخادم | `sadid-ledger/handler.ts` | `/mobile/v3/enroll` ثم `verifyProof` |
| إدارة التركيبات والاستعادة | `sadid-ledger/admin.ts` | `/admin/*` |
| لوحة الإدارة | `server/public/admin.js` | |
| بناء اللوحة للنشر | `tools/build-admin-pages.cjs` | يكتب `_site/` |
| الترحيلات | `Supabase-Setup/supabase/migrations/` | |
| اختبارات الوحدة (JVM) | `app/src/test/java/com/sadad/app/` | `./gradlew testServerDebugUnitTest` |
| اختبارات الجهاز | `app/src/androidTest/java/com/sadad/app/QaInstrumentation.java` | معرّف QA (`com.sadad.qa`) + جهاز |
| اختبارات Edge | `Supabase-Setup/supabase/functions/<name>/*_test.ts` | `deno test -A` داخل المجلد |
| اختبارات قاعدة البيانات | `Supabase-Setup/scripts/*.mjs` | pglite |

## البناء

```sh
# JDK 21 و Android SDK 36. local.properties مستبعد من Git ويحتاج:
#   sdk.dir=/home/<user>/Android/Sdk
./gradlew assembleServerDebug assembleStandaloneDebug
```

- `app/` هو مشروع Gradle الوحيد؛ `settings.gradle.kts` في الجذر يتضمن `:app` فقط.
- إصدار الإنتاج: `SADAD_KEYSTORE_FILE`، `SADAD_KEYSTORE_PASSWORD`، `SADAD_KEY_ALIAS`،
  `SADAD_KEY_PASSWORD` ثم `assembleServerRelease`.
- عنوان الخادم مدمج وقت البناء: `-PSADAD_API_BASE_URL=...`. إصدار الإصدار يرفض أي عنوان آخر
  غير العنوان المدمج.

## المفاتيح والبيانات الحساسة

- مفتاح `sb_publishable_...` وحده يُسمح بوجوده في التطبيق أو الصفحة الثابتة أو Gradle.
- مفتاح `sb_secret_...` أو `service_role` ممنوع في التطبيق والصفحة والمستودع؛ تقرأه وظيفة
  Edge من أسرار المشروع فقط.
- لا كلمات مرور ولا قواعد بيانات مستخدمين في المستودع؛ بيانات الدخول في ملف محلي خارجي.
- المستودع عام (`ibrahem57/sadad`)؛ كل التزام مرئي للعالم. حُذفت في أكتوبر 2026 نسخ قديمة
  كانت تحتوي بيانات اعتماد قديمة — لا تُضف مثلها.

## الاختبارات

- Instrumentation في `app/src/androidTest` تُشغَّل بمعرّف QA منفصل (`com.sadad.qa`) وتحتاج جهازًا.
- اختبارات قاعدة البيانات: `Supabase-Setup/scripts/*.mjs` عبر pglite.
- وظائف Edge: `deno test` داخل كل مجلد في `Supabase-Setup/supabase/functions/`.
- CI: `.github/workflows/android-ci.yml` يشغّل اختبارات الـJVM وبناء النسختين واختبارات Edge
  واختبارات PostgreSQL المحلية. يشمل التشغيل تغييرات التطبيق والبناء والوظائف والترحيلات
  وسكربتات قاعدة البيانات.
  اختبارات الجهاز تبقى يدوية، فالمشغّل لا يوفّر هاتفًا.

## الكتابة والتوثيق

- العربية أولًا في ملفات المستخدم والتشغيل، والإنجليزية مقبولة في التوثيق التقني.
- كل معلومة في التوثيق يجب أن تكون قابلة للتحقق؛ إن لم تُتحقق، اكتب أنها غير محقّقة.
- لا تُحدِّث ملفًا بسجل إصدار جديد؛ أضف سطرًا في `docs/CHANGELOG.md`.

## الحفظ والاسترجاع

- الوسم `pre-consolidation-2026-10-11` والفرع `archive/pre-consolidation` يحفظان شجرة
  المستودع كاملة قبل تنظيف أكتوبر 2026، بما فيها نسخة Flutter ونسخ الخوادم المحذوفة.
- الوسم `pre-supabase-ledger-correctness-2026-10-12` والفرع
  `archive/pre-supabase-ledger-correctness` يحفظان نقطة البداية مع التعديلات الستة المعلقة (340 ملفًا متتبّعًا).
- نسخة Flutter حُذفت من الشجرة الحالية؛ مرجعها `pre-consolidation-2026-10-11:archive/Sadad-Flutter` في Git.
- أكّد المستخدم أن جميع المتاجر والبيانات تجريبية. لا تُبقِ بروتوكولًا ماليًا ثانيًا لحماية عملاء قدامى؛
  لا يزال نشر الوظائف وحذف الجداول السحابية يحتاجان مراجعة واعتمادًا وفق `docs/CONTINUE_HERE.md`.

## Agent skills

### Issue tracker

Issues and specs live as local markdown under `.scratch/` (gitignored, not pushed). See `docs/agents/issue-tracker.md`.

### Triage labels

Default five-role vocabulary (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: root `GLOSSARY.md` + `docs/adr/`. See `docs/agents/domain.md`.

### Implementation skills

Use only: implement, tdd, diagnosing-bugs, code-review, ponytail, supabase skills, writing-for-agents (doc tickets). Never use speckit-* or to-spec/to-tickets during implementation. In OpenCode, the repo `opencode.json` denies those skills and denies `git push`, destructive git and Supabase cloud commands; a human pushes and deploys.
