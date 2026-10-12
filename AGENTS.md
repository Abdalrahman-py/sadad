# AGENTS.md — قواعد العمل في مستودع سدد

اقرأ هذا قبل أي تعديل. الهدف: مصدر واحد لكل شيء، ومنع إنشاء نسخة ثانية من أي مكوّن.

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
  على كل دفع/طلب سحب يمسّ `app/` أو `gradle/` أو `Supabase-Setup/supabase/functions/`.
  اختبارات الجهاز تبقى يدوية، فالمشغّل لا يوفّر هاتفًا.

## الكتابة والتوثيق

- العربية أولًا في ملفات المستخدم والتشغيل، والإنجليزية مقبولة في التوثيق التقني.
- كل معلومة في التوثيق يجب أن تكون قابلة للتحقق؛ إن لم تُتحقق، اكتب أنها غير محقّقة.
- لا تُحدِّث ملفًا بسجل إصدار جديد؛ أضف سطرًا في `docs/CHANGELOG.md`.

## الحفظ والاسترجاع

- الوسم `pre-consolidation-2026-10-11` والفرع `archive/pre-consolidation` يحفظان شجرة
  المستودع كاملة قبل تنظيف أكتوبر 2026، بما فيها نسخة Flutter ونسخ الخوادم المحذوفة.
- نسخة Flutter المرجعية: `archive/Sadad-Flutter`.
