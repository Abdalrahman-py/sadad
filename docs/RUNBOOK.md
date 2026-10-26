# دليل تشغيل سدد

مرجع التذكرة 74 ([#84](https://github.com/Abdalrahman-py/sadad/issues/84)).
راجعت الأوامر والمصادر في الشجرة بتاريخ 25 أكتوبر 2026؛ لم أنفّذ نشرًا أو تدوير أسرار أو استعادة.
الحالة السحابية أدناه من [حالة العمل](CONTINUE_HERE.md) و[سجل القطع](CHANGELOG.md)، وليست فحصًا حيًا جديدًا.
كل تغيير سحابي وتوقيع إنتاج وتنفيذ على الهاتف يخص الإنسان، وفق [قواعد المستودع](../AGENTS.md).
الأوامر متعددة الأسطر أدناه تستخدم Bash؛ شغّلها من جذر المستودع إلا حيث يُذكر غير ذلك.

## الخريطة

| المكوّن | المصدر | التشغيل |
| --- | --- | --- |
| Android | `app/` و`ledger-core/` | Kotlin/Compose؛ الحزمة `com.sadad.app`، والتطوير `com.sadad.app.debug` |
| الخادم | `Supabase-Setup/supabase/functions/api/` | وظيفة `api` الوحيدة على المشروع `vhftjmiltqfwmrszdtgf`، والمسارات تحت `/v1` |
| قاعدة البيانات | `Supabase-Setup/supabase/schemas/` | الحالة المطلوبة؛ الأساس `Supabase-Setup/supabase/migrations/20261015000000_baseline.sql` |
| لوحة الإدارة | `server/public/` | `npm run build` ينتج `_site/`؛ [اللوحة المنشورة](https://abdalrahman-py.github.io/sadad/) |

المصدر: [المعمارية](ARCHITECTURE.md)، [إعداد التطبيق](../app/build.gradle.kts)،
[إعداد اللوحة](../server/public/src/config.ts)، [إعداد Supabase](../Supabase-Setup/supabase/config.toml).
لا تنشئ خادمًا أو لوحة موازيين. المصادقة Supabase Auth؛ مفاتيح `anon` و`service_role` القديمة معطّلة حسب سجل القطع.

## النشر

1. افتح PR إلى `main` وراجع نطاقه ونتائج `ci-ok` و`scope` قبل الدمج. الفحص المحلي
   `./tools/check.sh` أو أي قسم منه يحتاج موافقة الإنسان الصريحة **لكل تشغيل**.
2. قبل نشر المخطط، قارن سجل الترحيلات في لوحة Supabase بالملفات المتتبعة:

   ```sh
   git ls-files Supabase-Setup/supabase/migrations
   ```

   سجل القطع يثبت الأساس `20261015000000` فقط. لا تشغّل `db push` عميانيًا عند اختلاف السجل.
   كل تغيير لاحق ترحيل جديد بعد الأساس المجمد باسم `YYYYMMDDHHMMSS_<slug>.sql`؛ يرفض `tools/check.sh` غير ذلك،
   ويبني [test-baseline.mjs](../Supabase-Setup/scripts/test-baseline.mjs) الأساس مع الترحيلات ويطابقه مع `schemas/`.
3. الإنسان يشغّل سير العمل من `main`:

   ```sh
   gh workflow run supabase-deploy.yml --ref main -R Abdalrahman-py/sadad
   gh run list --workflow supabase-deploy.yml -R Abdalrahman-py/sadad
   ```

   المصدر: [supabase-deploy.yml](../.github/workflows/supabase-deploy.yml).
   يشغّل جميع أقسام الفحص أولًا؛ ثم ينتظر اعتماد بيئة `production`. بعد اعتمادها ينفّذ،
   داخل `Supabase-Setup/`، الأوامر التالية؛ هذه قراءة لما يفعله السير وليست بديلًا عن الاعتماد:

   ```sh
   supabase link --project-ref "$PROJECT_REF"
   supabase db push --linked --yes
   supabase functions deploy api --project-ref "$PROJECT_REF" --use-api --yes
   ```

   يحدد السير `PROJECT_REF=vhftjmiltqfwmrszdtgf` وCLI `2.121.0`.
4. نشر اللوحة خطوة يدوية مستقلة، من `main`:

   ```sh
   gh workflow run admin-pages.yml --ref main -R Abdalrahman-py/sadad
   gh run list --workflow admin-pages.yml -R Abdalrahman-py/sadad
   ```

   المصدر: [admin-pages.yml](../.github/workflows/admin-pages.yml). يفحص اللوحة، ثم يبنيها بـ
   `npm ci --no-audit --no-fund && npm run build` داخل `server/public/` وينشر `_site/` إلى GitHub Pages.
5. افحص الصحة ثم نفّذ فحص الدخان على متجر اختبار فقط كما في قسم المراقبة؛ سجّل رابط التشغيل وSHA.

## الرجوع عن نشر

سجّل SHA المنشور والنسخة السابقة من تشغيل Actions الناجح قبل أي رجوع. لا تعِد تشغيل نشر قديم
إذا كان سيعيد مخططًا أقدم فوق بيانات أحدث.

- **الوظيفة:** حدد SHA لآخر نسخة سليمة في سجل `supabase-deploy.yml`، وافحص الفرق في
  `Supabase-Setup/supabase/functions/api/`. افتح PR يعكس التغيير المتسبب بالمشكلة، ثم أعد نشر
  `main` عبر السير المعتمد. بذلك تُنشر شيفرة النسخة السابقة دون افتراض وجود زر لاستعادة نسخة في المنصة.
- **اللوحة:** اعكس تغييرها في PR، ثم انشر `admin-pages.yml` من `main` بعد الفحوص.
- **قاعدة البيانات:** لا تعدّل ترحيلًا سبق تطبيقه ولا تحذف صفه من سجل السحابة. رجوع الشيفرة
  لا يعكس SQL؛ أي تصحيح للمخطط يحتاج ترحيلًا جديدًا يجتاز فحص قاعدة البيانات، ومراجعة واعتمادًا بشريًا.
  إن استلزم الأمر استعادة بيانات، استخدم تجربة الاستعادة أدناه قبل اتخاذ قرار يمس المشروع الأصلي.

أمر Git المستخدم في PR الرجوع، من فرع منفصل:

```sh
git revert <bad-commit>
```

لرجوع merge commit راجع الوالد الصحيح أولًا؛ لا تطبّق الأمر على merge بلا تحديد الوالد.
المصادر: [قواعد الجلسات](agents/cloud-sessions.md) وسيرا النشر أعلاه. لم تُجرّب عملية رجوع سحابية هنا: **غير محقّق**.

## الأسرار وتدويرها

لا تلصق القيم في Git أو PR أو سجل أو لقطة شاشة. هذا الجدول يحفظ **الأسماء فقط**؛ المالك هو الإنسان.
حالة وجود الأسرار في GitHub أو السحابة غير مفحوصة حيًا هنا: **غير محقّق**.

| الاسم | مكانه ومستهلكه | إجراء الإنسان عند التدوير |
| --- | --- | --- |
| `SUPABASE_ACCESS_TOKEN` | أسرار بيئة GitHub `production`؛ سير نشر Supabase | أنشئ بديلًا في حساب Supabase، حدّث سر البيئة، تحقق من نشر معتمد ثم ألغِ القديم |
| `SUPABASE_DB_PASSWORD` | بيئة `production`؛ `supabase link` و`db push` | غيّر كلمة مرور قاعدة البيانات في لوحة Supabase وحدّث سر البيئة قبل النشر التالي |
| `SUPABASE_URL` و`SUPABASE_PUBLISHABLE_KEYS` و`SUPABASE_SECRET_KEYS` | متغيرات المنصة؛ `api/index.ts` يقرأ المفتاح `default` من مجموعتي المفاتيح | أدر المفاتيح من لوحة المشروع؛ طابق الاسم `default` وتحقق من المصادقة؛ السري لا يصل إلى العميل |
| `SADAD_LEASE_PRIVATE_JWK` | سر وظيفة `api`؛ نسخته الخارجية في ملف الاعتمادات المحلي | اتبع قسم مفتاح الترخيص؛ لا تستبدله أثناء تدوير API key |
| `SADAD_ALLOWED_ORIGINS` | إعداد اختياري لوظيفة `api`، وليس سرًا | حدّثه عند تغيير أصل اللوحة؛ اختبر تسجيل الدخول وCORS |
| `SADAD_SUPABASE_URL` و`SADAD_SUPABASE_PUBLISHABLE_KEY` | خصائص Gradle؛ قيم عامة تُضمّن في APK | عند تغيير المفتاح العام أعد بناء التطبيق؛ حدّث مصدر اللوحة `server/public/src/config.ts` وأعد نشرها |
| `SADAD_LEASE_KEY_X` و`SADAD_LEASE_KEY_Y` | خصائص Gradle؛ نصف المفتاح العام المثبّت في التطبيق | تغييرهما يتطلب بناء APK جديدًا متوافقًا مع سر التوقيع |
| `SADAD_KEYSTORE_BASE64` | سر مستودع GitHub؛ سير إصدار Android | نسخة Base64 من keystore الإنتاج؛ ليست تشفيرًا؛ احفظ الأصل ونسختين خارج الحاسوب |
| `SADAD_KEYSTORE_FILE` | مسار ملف محلي أو مؤقت داخل runner، وليس سر GitHub | السير يفك `BASE64` إلى هذا المسار ثم يحذفه؛ لا تودع الملف في المستودع |
| `SADAD_KEYSTORE_PASSWORD` و`SADAD_KEY_ALIAS` و`SADAD_KEY_PASSWORD` | أسرار المستودع؛ متغيرات بيئة Gradle في الإصدار | حدّث كلمات المرور مع الملف المطابق؛ تغيير شهادة التوقيع يمنع تحديث النسخ المثبتة الموقعة بالقديمة |
| `SADAD_SENTRY_DSN` و`SENTRY_AUTH_TOKEN` و`SENTRY_ORG` و`SENTRY_PROJECT` | أعلن الإنسان ضبطها في تعليقات التذكرة 69؛ دمجها في التطبيق من مسؤولية التذكرة 70 | أدر token من Sentry وحدّث سر GitHub؛ عند تغيير المشروع طابق DSN وorg/project معًا |
| `SENTRY_DSN` و`VITE_SENTRY_DSN` | إعداد Edge وقت التشغيل واللوحة وقت البناء، مخطط لهما في التذكرة 71 | ضبطهما ووجود مشروعي Edge واللوحة **غير محقّق**؛ انتظر تنفيذ التذكرة 71 ثم أعد النشر المناسب |
| `SADAD_SMOKE_USERNAME` و`SADAD_SMOKE_PASSWORD` و`SADAD_SMOKE_NEW_PASSWORD` | بيئة عملية فحص الدخان فقط؛ حساب متجر اختبار | أعد ضبط كلمة مرور المتجر من اللوحة وحدّث بيئة الفحص؛ لا تحفظ القيم في الأوامر المتتبعة |

المصادر: [AGENTS](../AGENTS.md)، [قواعد الجلسات](agents/cloud-sessions.md)،
[مدخل Edge](../Supabase-Setup/supabase/functions/api/index.ts)،
[Gradle](../app/build.gradle.kts)، [الإصدار](../.github/workflows/android-release.yml)،
[تعليق التذكرة 69](https://github.com/Abdalrahman-py/sadad/issues/79)،
[التذكرة 71](https://github.com/Abdalrahman-py/sadad/issues/81)،
[التذكرة 72](https://github.com/Abdalrahman-py/sadad/issues/82).

## مفتاح الترخيص دون اتصال

`api/index.ts` يوقّع بـP-256 من `SADAD_LEASE_PRIVATE_JWK` المستقل عن مفاتيح API.
تدوير secret API key وحده لا يغيّر مفتاح التراخيص؛ ملاحظة اشتقاقه القديمة في تعليقات القطع تجاوزتها الشيفرة الحالية.
التطبيق يثبت `x/y` العامّين في `app/build.gradle.kts` ويتحقق منهما في
`app/src/main/kotlin/com/sadad/app/data/net/LeaseVerifier.kt`.

قبل تدويره، جهّز APK بالنصف العام الجديد وخطة توزيع واعتماد بشرية. تغيير الخاص دون تحديث
التطبيق يجعل التراخيص الجديدة مرفوضة في النسخ التي تثبت المفتاح القديم؛ وتغيير المثبّت يجعل
الترخيص المحفوظ الموقّع بالقديم غير صالح للنسخة الجديدة. اختبر تجديد الترخيص والحفظ دون اتصال على هاتف QA.
لا توجد آلية تدوير تدريجي موثقة أو ضمان لاستمرار الحفظ دون اتصال أثناء التبديل: **غير محقّق**.
لا تغيّر المفتاح للتعافي من خطأ نشر شيفرة عادي. نتائج الفحص الحي تطبع النصف العام فقط.

## النسخ الاحتياطي

خطة المشروع، مدة الاحتفاظ، تاريخ آخر نسخة قابلة للاستعادة، وإتاحة PITR: **غير محقّق**؛
يسجلها الإنسان في [التذكرة 75](https://github.com/Abdalrahman-py/sadad/issues/85) من
[صفحة النسخ للمشروع](https://supabase.com/dashboard/project/vhftjmiltqfwmrszdtgf/database/backups).
لا تفترض أن وجود Git أو ملف الأساس يعني وجود نسخة من بيانات المستخدمين.

وفق [وثائق Supabase للنسخ](https://supabase.com/docs/guides/platform/backups)، تختلف النسخ اليومية
والاحتفاظ وPITR حسب الخطة. نسخة قاعدة البيانات لا تشمل ملفات Storage نفسها.
سجل القطع يقول إنه لا توجد حاويات Storage وقت القطع؛ حالتها الحالية **غير محقّق**.
تحقق منفصلًا من حفظ شيفرة الوظيفة وإعداداتها والأسرار ومفتاح الترخيص وkeystore؛ لا تفترض استعادتها مع قاعدة البيانات.

## الاستعادة التجريبية — يملؤها الإنسان في التذكرة 75

لا تستعد فوق المشروع الأصلي. التكلفة وإمكان استعادة نوع النسخة المتاح إلى فرع تحديدًا **غير محقّق**؛
تحقق منهما في لوحة Supabase قبل الإنشاء، وتوقف إذا لم تدعم الخطة المسار المطلوب.
لا تعتبر الفرع الفارغ نسخة بيانات. لا تنفّذ نشر الإنتاج لاختبار الفرع.

| الخطوة | الإجراء المطلوب | النتيجة والزمن |
| --- | --- | --- |
| 1 | على متجر اختبار معتمد، سجّل Contact/Debt/event counts ثم أعد القياس بعد تشغيل seed الموجود أدناه | غير محقّق — الأعداد/الزمن: يُملأ |
| 2 | من Backups سجّل الخطة ونوع النسخة ووقتها والاحتفاظ وPITR وتكلفة الفرع المعتمدة | غير محقّق — يُملأ |
| 3 | أنشئ فرعًا معزولًا وسجّل ref، ثم استعد النسخة/النقطة الزمنية إليه عبر المسار المدعوم الذي تثبته اللوحة | غير محقّق — المسار الفعلي/البداية/النهاية: يُملأ |
| 4 | هيّئ مفاتيح الفرع وأسرار `api` الخاصة به ثم انشر الوظيفة عليه فقط وافحص الصحة | غير محقّق — ref/الزمن/النتيجة: يُملأ |
| 5 | ابنِ debug للفرع، سجّل الدخول واعتمد التثبيت؛ قارن الأعداد ومبلغ دين واحد بالأصل | غير محقّق — القيم المتوقعة/الفعلية/الزمن: يُملأ |
| 6 | احذف الفرع من اللوحة وتحقق من اختفائه، ثم احذف ملفات واعتمادات التجربة المحلية خارج المستودع | غير محقّق — وقت الحذف: يُملأ |

أوامر الخطوتين 1 و4 و5؛ **الإنسان فقط**، بعد تحميل الاعتمادات المناسبة من مخزنه دون طباعتها:

```sh
deno run --allow-env --allow-net --allow-read --allow-write tools/seed-store.ts
# داخل Supabase-Setup/؛ BRANCH_REF يجب أن يكون ref الفرع التجريبي وليس الأصل
supabase functions deploy api --project-ref "$BRANCH_REF" --use-api --yes
# من الجذر، بالقيم العامة للفرع ومفتاح توقيع تراخيصه
./gradlew assembleDebug -PSADAD_SUPABASE_URL="$BRANCH_URL" \
  -PSADAD_SUPABASE_PUBLISHABLE_KEY="$BRANCH_PUBLISHABLE_KEY" \
  -PSADAD_LEASE_KEY_X="$BRANCH_LEASE_KEY_X" -PSADAD_LEASE_KEY_Y="$BRANCH_LEASE_KEY_Y"
```

المصادر: [seed-store.ts](../tools/seed-store.ts) (يحتاج حد أشخاص ≥5000، ويكتب ملف مفتاح خارج Git)،
[سير النشر](../.github/workflows/supabase-deploy.yml)، [Gradle](../app/build.gradle.kts).
اضبط `SADAD_SMOKE_URL` ومفتاحه واعتمادات متجر الاختبار على الوجهة المقصودة قبل seed.
بعد التجربة استبدل كل خانة «غير محقّق» بنتيجة فعلية ورابط دليل منزوع البيانات الحساسة؛ الفشل يحتاج تذكرة قبل المتابعة.

## المراقبة وقراءة الأخطاء

- الصحة: `GET https://vhftjmiltqfwmrszdtgf.supabase.co/functions/v1/api/v1/health`؛
  المصدر [app.ts](../Supabase-Setup/supabase/functions/api/app.ts)، والمتوقع `{"ok":true}`.
  فحص الصحة لا يثبت عمل المصادقة أو الكتابة والمزامنة.
- افتح [سجلات المشروع](https://supabase.com/dashboard/project/vhftjmiltqfwmrszdtgf/logs/explorer)
  وسجلات وظيفة `api` في لوحة Supabase؛ طابق وقت الفشل مع تشغيل النشر. لا تنشر headers أو payload أو tokens.
- أعلن الإنسان في #79 عن مشروع Sentry واحد `abdalrahmanpy/sadad`؛ مشروعا
  `sadad-edge` و`sadad-dashboard` انتقلا إلى التذكرة 71. استقبال الأحداث وطمس البيانات ورفع mapping
  في النسخة المنشورة **غير محقّق**؛ لا تعدّ غياب الأحداث دليل سلامة قبل التحقق من الإعداد.
- [smoke-api.ts](../tools/smoke-api.ts) يكتب Contact/Debt/Payment ويحتاج اعتماد جهاز من اللوحة؛
  يُشغّله الإنسان على متجر اختبار. حمّل `SADAD_SMOKE_URL` و`SADAD_SMOKE_PUBLISHABLE_KEY`
  و`SADAD_SMOKE_USERNAME` و`SADAD_SMOKE_PASSWORD` و`SADAD_SMOKE_NEW_PASSWORD` في البيئة ثم:

  ```sh
  deno run --allow-env --allow-net tools/smoke-api.ts
  ```

  المهلة الافتراضية للاعتماد 600 ثانية (`SADAD_SMOKE_APPROVAL_SECONDS`). المصدر
  [api-client.ts](../tools/api-client.ts). النتائج التاريخية في سجل القطع لا تستبدل فحصًا بعد النشر الحالي.

## إصدار Android وتثبيته

المصدر [android-release.yml](../.github/workflows/android-release.yml) و[التذكرة 72](https://github.com/Abdalrahman-py/sadad/issues/82).
الإنسان وحده ينشئ keystore ويحفظ نسختين خارج الحاسوب ويضبط أسرار التوقيع الواردة أعلاه.
قبل الإصدار طابق versionName وversionCode ووجهة Supabase والمفتاح العام مع البناء المراد نشره.
لا تفترض أن الإصدار `2.5.13` المسجل في حالة العمل هو versionName الحالي للشيفرة؛ المرجع `app/build.gradle.kts`.

```sh
git tag vX.Y.Z <approved-main-sha>
git push origin vX.Y.Z
gh run list --workflow android-release.yml -R Abdalrahman-py/sadad
gh release download vX.Y.Z -R Abdalrahman-py/sadad --pattern '*.apk' --pattern SHA256SUMS --dir <outside-repo>
```

استبدل القيم بين `<...>` و`X.Y.Z` قبل التنفيذ؛ لا تدفع وسمًا قبل اعتماد الإصدار.
السير يشتق `versionCode` من الوسم (`major*10000 + minor*100 + patch`، فـ`v3.0.1` = `30001`)، ويشترط
تطابق الوسم مع `versionName` و`versionCode` في الحزمة، وينتظر CI، ثم يفحص R8 والحجم وBaseline Profile
والحزمة والتوقيع وعدم debuggable وعدم cleartext؛ وينشر APK و`SHA256SUMS` و`update.json`.
داخل مجلد التنزيل، وبأدوات SDK التي يستخدمها السير:

```sh
sha256sum -c SHA256SUMS
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs Sadad-X.Y.Z-release.apk
adb install -r Sadad-X.Y.Z-release.apk
```

طابق بصمة SHA-256 مع بصمة keystore التي سجّلها الإنسان في #82، وارفض شهادة `CN=Android Debug`.
إذا تعارضت شهادة نسخة الاختبار القديمة، لا تحذفها عميانيًا: تحقق من إرسال العمليات واحفظ نسخة استرداد
قبل إزالتها بموافقة الإنسان؛ إزالة التطبيق تمس بياناته المحلية. التوزيع sideload، دون Play Console.
سجّل الوسم والبصمة ونتيجة الدخول على هاتف QA؛ فحوص `app/src/androidTest` لم تُنفّذ هنا وتحتاج هاتف المسؤول.

## بناء الفريق من `dev`

`main` هو خط الإصدار المستقر؛ `dev` هو الخط الذي يثبّت منه الفريق. كل دفعة إلى `dev` تشغّل
[team-release.yml](../.github/workflows/team-release.yml): يعيد استخدام فحوص `android-ci.yml` كاملة،
فلا يُنشر بناء على `dev` أحمر، ثم يبني `:app:assembleTeam` ويستبدل أصول الإصدار المسبق `team-latest`
(`Sadad-team.apk` و`SHA256SUMS` و`BUILD.txt`). المصدر [PR #142](https://github.com/Abdalrahman-py/sadad/pull/142).

- حزمة البناء `com.sadad.app.debug`، فتُثبَّت بجانب التطبيق الرسمي بأيقونة ثانية، وتتصل بمشروع الإنتاج:
  استخدم متجر اختبار.
- نسخة `team` ترث `debug` وتُوقَّع بالمفتاح نفسه الذي تحمله نسخ الفريق المنشورة سابقًا
  (`team-2.5.13-20261013` و`team-3.0.0-20261026`) حتى تُحدَّث فوقها بلا إزالة؛ `versionCode` = 40 + رقم
  التشغيل. المفتاح `androiddebugkey` من `~/.android/debug.keystore`، نسخته المحفوظة
  `~/sadad-private/team-debug.jks`، وبصمة الشهادة
  `8E:72:F9:D4:9C:62:02:32:A7:F2:B6:E1:06:00:15:6A:6D:86:23:4C:BC:68:31:D4:7F:C1:53:78:C5:A4:29:B9`.
- الأسرار الأربعة: `TEAM_KEYSTORE_BASE64` و`TEAM_KEYSTORE_PASSWORD` و`TEAM_KEY_ALIAS` و`TEAM_KEY_PASSWORD`؛
  المفتاح يُوقّع حزمة `.debug` وحدها ولا يقترب من حزمة الإنتاج `com.sadad.app`.
- لا حاجة لإزالة أي نسخة سابقة: البناء الجديد يُثبَّت فوق `team-3.0.0-20261026` مباشرة. النسخة الوحيدة
  التي تطلّبت إزالة هي أول بناء من `dev` وقّعه مفتاح فريق منفصل (بصمة `5A:3D:…`) قبل هذا التعديل؛ من
  ثبّتها يزيلها مرة واحدة.
- التشغيل اليدوي لبناء جديد دون دفعة: `gh workflow run team-release.yml --ref dev`.

## تحديث التطبيق من داخله

التطبيق يحدّث نفسه من GitHub Releases، بلا متجر ولا خادم إضافي. المصدر
[`feature/updates/`](../app/src/main/kotlin/com/sadad/app/feature/updates/): `UpdateCenter` تقرأ
`update.json`، و`ApkDownloader` تنزّل وتتحقق، و`UpdateInstaller` تسلّم الملف لمثبّت النظام، و
`UpdateCheckWorker` تسأل مرة يوميًا.

- **قناتان، تختارهما الحزمة لا المستخدم:** بناء `release` (`com.sadad.app`) يقرأ
  `https://github.com/Abdalrahman-py/sadad/releases/latest/download/update.json`؛ بناء `team` وبناء
  `debug` (`com.sadad.app.debug`) يقرآن `…/releases/download/team-latest/update.json`. لا استدعاء لواجهة
  GitHub البرمجية، فلا حدّ للطلبات. تغيير المستودع عبر `-PSADAD_UPDATE_REPO=owner/repo`.
- **الترتيب بـ`versionCode` وحده.** لا تُقارن الأسماء: اسم بناء الفريق `dev-<تشغيل>-<sha>-team` لا يحمل
  ترتيبًا. ليصدر تحديث، ارفع `versionCode`؛ الوسم `vX.Y.Z` يفعل ذلك تلقائيًا في خط الإنتاج.
- **قبل التثبيت يتحقق التطبيق من:** طول الملف وبصمة `sha256` المنشورة، ثم أن الحزمة موقّعة بالمفتاح نفسه
  الذي وُقّعت به النسخة المثبّتة، ثم إذن «تثبيت التطبيقات من هذا المصدر». أي إخفاق يظهر بجملة تحدّد السبب،
  ويُحذف الملف المنزّل عند اختلاف البصمة.
- **التثبيت بجلسة `PackageInstaller`** لا بـ`Intent` إلى ملف: التطبيق يعرف النتيجة (نجح، أُلغى، فشل)،
  والمستخدم يؤكد في نافذة النظام دائمًا — لا تثبيت صامت.
- **التذكير:** إشعار من الفحص اليومي، وسؤال عند فتح التطبيق على شاشة الرئيسية، وصفّ «تحديثات التطبيق» في
  تبويب المتجر. «لاحقاً» يُسكت الأولين سبعة أيام (ثم يعودان) ولا يُخفي الصفّ أبدًا؛ يُخفيه التثبيت وحده.

**عند نشر تحديث:** ارفع `versionCode` فقط، واترك `channel` مطابقًا للقناة التي تنشر فيها — التطبيق يرفض
ملفًا لقناة أخرى. لا تُعِد نشر إصدار برقم أقل: أندرويد يرفض التثبيت، والتطبيق لا يعرضه أصلًا.

**عند بناء فريق جديد:** لا شيء يدوي؛ `team-release.yml` يكتب `update.json` من البصمة الفعلية للحزمة
ويرفعه معها في `team-latest` (استبدالًا). للتشغيل دون دفعة: `gh workflow run team-release.yml --ref dev`.

**عند تغيير مفتاح التوقيع:** كل نسخة مثبّتة ترفض أي حزمة بمفتاح آخر — وهذا مقصود (رسالة
«موقّع بمفتاح غير مفتاح نسختك»). تبديل المفتاح يعني تثبيتًا يدويًا واحدًا لكل جهاز، لا تحديثًا ذاتيًا.

**إن نُشر بناء سيئ:** انشر إصدارًا برقم `versionCode` أعلى؛ لا يوجد تراجع تلقائي في أندرويد. إن كان العيب
يمنع التطبيق من العمل، فالتثبيت اليدوي من صفحة الإصدارات هو الطريق، ووجوده مذكور في نص رسالة الخطأ.

**غير محقّق بعد:** التثبيت الفعلي على جهاز من داخل التطبيق يحتاج بناء فريق يوقّعه مفتاح الفريق (CI فقط)،
وقناة الإنتاج تحتاج مفتاح الإنتاج وأول وسم `v*` يحمل `update.json`.
