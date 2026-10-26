# سدد — دفتر ديون المتاجر الصغيرة

تطبيق Android عربي لمتجر واحد يبيع بالدين: يسجّل الأشخاص (زبون أو مورد) والديون والدفعات، ويعمل
دون اتصال ويزامن كل عملية موقّعة مع الخادم عند عودة الشبكة. معه وظيفة Edge واحدة على Supabase
ولوحة إدارة على GitHub Pages للمشرفين والدعم.

**الحالة:** التطبيق الجديد بـKotlin + Compose (`3.0.0`) تحت البناء في `app/`، والمشروع السحابي
`vhftjmiltqfwmrszdtgf` منشور. لا يوجد بعد إصدار إنتاج موقّع (أسرار `SADAD_KEYSTORE_*` غير متوفّرة)؛
آخر حزمة قابلة للتثبيت هي بناء الفريق `team-latest` من فرع `dev` بمعرّف `com.sadad.app.debug`
فتُثبّت بجانب التطبيق الرسمي. الحالة التفصيلية في [`docs/CONTINUE_HERE.md`](docs/CONTINUE_HERE.md).

## ما يفعله التطبيق

- **الرئيسية:** «لك عند الناس» و«عليك للناس» منفصلتين، تحصيل الشهر، وقائمة «تحتاج متابعة» مع
  تذكير واتساب يحمل اسم المتجر والرصيد.
- **الأشخاص:** كل شخص زبون أو مورد؛ صفحته تجمع ديونه ودفعاته، والإضافة السريعة تبدأ بالشخص ثم
  المبلغ على لوحة أرقام وتُنتهي بجملة واحدة وزر تراجع.
- **السجل:** الحركات، سجل العمليات، سلة المحذوفات، الإشعارات، والتقارير مع التصدير.
- **القفل:** رمز PIN مع بصمة عند توفرها.
- **العمل دون اتصال:** دفتر محلي (Room) مع طابور إرسال، ومزامنة دورية عبر WorkManager وزر مزامنة فوري.
- **المال والحماية:** المبالغ أعداد صحيحة بالسنت، ولكل تثبيت مفتاح P-256 في Android Keystore يوقّع
  كل عملية؛ الخادم يرد بإيصال لكل عملية، ويمنح ترخيصًا للعمل دون اتصال، والاسترداد على جهاز آخر
  يحتاج موافقة الإدارة.

## أين يعيش كل شيء

| المكوّن | المسار | التقنية |
| --- | --- | --- |
| تطبيق Android | `app/` | Kotlin + Compose + Room + WorkManager + OkHttp |
| قلب الدفتر | `ledger-core/` | Kotlin/JVM صرف بلا اعتماد على Android |
| الخادم | `Supabase-Setup/supabase/` | وظيفة Edge واحدة `api` (Hono + zod)، `schemas/` ومهاجرات مرتبة |
| العقد | `contract/` | `openapi.json` مولَّد + `errors.json` |
| لوحة الإدارة | `server/public/` | Vite + TypeScript + lit-html، تُبنى إلى `_site/` |

## البناء

يحتاج JDK 21 وAndroid SDK 37. أنشئ `local.properties` في الجذر (مستبعد من Git):

```properties
sdk.dir=/path/to/Android/Sdk
```

```sh
./gradlew assembleDebug
```

- `debug` معرّفه `com.sadad.app.debug`، وStrictMode فيه يوقف التطبيق عند مخالفة قادمة من كود سدد.
- `release` معرّفه `com.sadad.app`، مع R8 وتقليص الموارد؛ بلا مفاتيح توقيع ينتج APK غير موقّع.
- `assembleTeam` ينتج نسخة الفريق (وريث `debug`) الموقّعة بمفتاح الفريق.
- عنوان Supabase مدمج وقت البناء وافتراضيًا مشروع الاختبار؛ لتغييره:
  `./gradlew assembleDebug -PSADAD_SUPABASE_URL=https://YOUR-PROJECT.supabase.co`

## الفحص

`./tools/check.sh` هي البوابة الموحدة محليًا وفي CI، وأقسامها: `docs` و`format` و`dashboard`
و`android` و`edge` و`database`. التشغيل الكامل ثقيل (يبني APK الإصدار ويشغّل Playwright)، فشغّله
عند الحاجة إلى ذلك أو استخدم قسمًا واحدًا:

```sh
./tools/check.sh android        # أو: docs | format | dashboard | edge | database
./tools/check.sh --changed main # الأقسام التي تمسّها تغييراتك فقط، مع --list للعرض بلا تشغيل
./gradlew :ledger-core:test testDebugUnitTest
```

## النشر

| المسار | ما يحدث |
| --- | --- |
| دفع أو PR | `.github/workflows/android-ci.yml`: بوابة الفحص كوظائف CI منفصلة |
| دفعة إلى `dev` | `team-release.yml`: بناء الفريق يُنشر كـ`team-latest` للفريق |
| وسم `v*` يطابق `versionName` | `android-release.yml`: فحص ثم `assembleRelease` ونشر APK مع `SHA256SUMS` |
| `supabase-deploy.yml` (يدوي من `main`) | فحص، ثم موافقة مراجعي بيئة `production`، ثم `db push` ونشر `api` |
| `admin-pages.yml` (يدوي) | نشر لوحة الإدارة على GitHub Pages |

`main` هو خط الإصدار المستقر و`dev` خط التثبيت للفريق. لا تُرفع الأسرار ولا مفاتيح `sb_secret_`
إلى المستودع؛ المسموح في التطبيق واللوحة مفتاح `sb_publishable_` والنصف العام لمفتاح التراخيص فقط.

## التوثيق

| ابدأ من | لماذا |
| --- | --- |
| [`docs/CONTINUE_HERE.md`](docs/CONTINUE_HERE.md) | الحالة الحالية والمنشور والمعلّق |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | المعمارية والمراحل وقاعدة القطع النظيف |
| [`docs/RUNBOOK.md`](docs/RUNBOOK.md) | النشر والرجوع والأسرار والنسخ والاستعادة |
| [`docs/CHANGELOG.md`](docs/CHANGELOG.md) | ما تغيّر في كل إصدار |
| [`GLOSSARY.md`](GLOSSARY.md) | أسماء النطاق والمعمارية كما تُستعمل في الكود |
| [`AGENTS.md`](AGENTS.md) | قواعد العمل: مصدر واحد لكل مكوّن، وحدود الصلاحيات |
| [`docs/adr/`](docs/adr) | قرارات معمارية مسجَّلة |

**غير محقّق:** توقيع الإنتاج، وفحوص الأجهزة (تبديل الحسابات، إلغاء كل التثبيتات، الاسترداد الحي)
لم تُجرَ بعد؛ التفاصيل في [`docs/CONTINUE_HERE.md`](docs/CONTINUE_HERE.md).
