# سدد — دفتر مالي (تجريبي)

تطبيق Android بواجهة عربية، مع دفتر مالي على Supabase، وطابور محلي، ومزامنة تحافظ على
هوية العمليات، ولوحة إدارة للأجهزة والاسترداد.

## أين يعيش كل شيء

| المكوّن | المسار |
| --- | --- |
| تطبيق Android (المصدر الوحيد) | `app/` — Java أصلي، نسختا `server` و`standalone` |
| الخادم: وظائف Edge والترحيلات | `Supabase-Setup/supabase/` — مشروع `vhftjmiltqfwmrszdtgf` |
| عقد العمليات | `Supabase-Setup/docs/V3_CONTRACT_AR.md` |
| لوحة الإدارة (ملفات ثابتة) | `server/public/` + `tools/build-admin-pages.cjs` |
| أدلة الاختبار والتسليم | `delivery/` |
| APK الرسمي (النسخة المتصلة) | GitHub Releases — ينشره `.github/workflows/android-release.yml` بعد إعداد التوقيع |
| ملفات الإصدارات القديمة | `docs/archive/releases/` |
| سجل الإصدارات | `docs/CHANGELOG.md` |
| حالة العمل والمهام المتبقية | `docs/CONTINUE_HERE.md` |
| قواعد العمل للمطوّر/الوكيل | `AGENTS.md` |

نسخة Flutter المرجعية محفوظة في `archive/Sadad-Flutter` (لم تُحذف، وقابلة للاسترجاع من
الوسم `pre-consolidation-2026-10-11`).

البيانات المرفقة اصطناعية. هذه نسخة للتجربة؛ توقيع الإصدار النهائي ما زال مطلوبًا، واختبار
هاتف فعلي واستعادة قاعدة الخادم الكاملة قبل الإنتاج.

## البناء

يحتاج JDK 21 وAndroid SDK 36. أنشئ `local.properties` في الجذر (مستبعد من Git):

```sh
sdk.dir=/path/to/Android/Sdk
./gradlew assembleServerDebug assembleStandaloneDebug
```

- `serverRelease`: `com.sadad.app` — النسخة المخصصة للتوزيع، متصلة بـ Supabase وموقعة بمفتاح الإنتاج.
- `serverDebug`: `com.sadad.app` — نسخة تطوير متصلة بـ Supabase، لا تُنشر في الإصدارات الرسمية.
- `standaloneDebug`: `com.sadad.app.test.offline` — محلي فقط، دخول أولي `123 / 123`.

النسخة المتصلة تشير افتراضيًا إلى وظيفة Supabase:

```
https://vhftjmiltqfwmrszdtgf.supabase.co/functions/v1/sadad-api
```

يمكن تغيير العنوان وقت البناء (للتطوير فقط):

```sh
./gradlew assembleServerDebug -PSADAD_API_BASE_URL=https://YOUR-DOMAIN/api
```

إصدار الإنتاج يتطلب HTTPS ومفتاح توقيع عبر `SADAD_KEYSTORE_FILE` و`SADAD_KEYSTORE_PASSWORD`
و`SADAD_KEY_ALIAS` و`SADAD_KEY_PASSWORD` ثم `assembleServerRelease`. إصدار الإصدار يرفض أي
عنوان خادم غير العنوان المدمج في النسخة، فلا يمكن تحويل جلسة المتجر إلى خادم آخر.

## الاختبارات

اختبارات Instrumentation تعمل بمعرّف منفصل حتى لا تلمس بيانات التطبيق الحقيقي. لا تُضاف
بيانات اختبار إلى النسختين النهائيتين، ويُعاد البناء العادي قبل توزيع APK.

```sh
./gradlew assembleStandaloneDebug assembleStandaloneDebugAndroidTest -PSADAD_TEST_APPLICATION_ID=com.sadad.qa
adb -s emulator-5554 install -r app/build/outputs/apk/standalone/debug/app-standalone-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/standalone/debug/app-standalone-debug-androidTest.apk
adb -s emulator-5554 shell pm grant com.sadad.qa.offline android.permission.POST_NOTIFICATIONS
adb -s emulator-5554 shell am instrument -w com.sadad.qa.offline.test/com.sadad.app.QaInstrumentation
```

- اختبار واجهة محددة: `-PSADAD_TEST_RUNNER=com.sadad.app.Ui2510Instrumentation` (وأمثاله من
  الأصناف في `app/src/androidTest`).
- اختبارات قاعدة البيانات (pglite، بلا خادم حقيقي):
  ```sh
  npm install --prefix .local-tools --no-save @electric-sql/pglite@0.5.8
  npx --yes --package=node@22.16.0 node --max-old-space-size=256 Supabase-Setup/scripts/test-v3-ledger.mjs
  ```
- اختبارات وحدة JVM بلا هاتف: `./gradlew testServerDebugUnitTest` — محرّك المزامنة وعميل
  الدفتر الموقّع. تُشغَّل تلقائيًا في CI (`.github/workflows/android-ci.yml`) مع بناء النسختين.
- اختبارات وظائف Edge: `deno test` داخل كل مجلد في `Supabase-Setup/supabase/functions/`.

## النشر الآلي: اللوحة وAPK الإنتاج

- تغييرات اللوحة على `main` تُبنى وتُنشر تلقائيًا عبر `.github/workflows/admin-pages.yml`.
- تغييرات التطبيق أو اللوحة أو وظائف الخادم تُفحص عند الدفع إلى `main` وطلبات السحب عبر
  `.github/workflows/android-ci.yml`. بناء Debug في هذه الفحوص لا ينتج حزمة للتوزيع.
- دفع وسم إصدار مثل `v2.5.12` يشغّل الفحوص نفسها، ثم يبني **`serverRelease` فقط** وينشر
  `Sadad-2.5.12-server-release.apk` و`SHA256SUMS` في GitHub Releases. لا ينشر نسخة مستقلة.

إعداد مرة واحدة: أضف الأسرار التالية في مستودع GitHub الذي تنشر منه، ضمن
Settings → Secrets and variables → Actions:

| السر | القيمة |
| --- | --- |
| `SADAD_KEYSTORE_BASE64` | محتوى مفتاح توقيع الإنتاج بترميز Base64 (`base64 -w 0 /path/to/release.jks`) |
| `SADAD_KEYSTORE_PASSWORD` | كلمة مرور مخزن المفتاح |
| `SADAD_KEY_ALIAS` | اسم مفتاح التوقيع داخل المخزن |
| `SADAD_KEY_PASSWORD` | كلمة مرور المفتاح |

احتفظ بالمفتاح نفسه لكل تحديث؛ لا تضعه أو كلمات مروره في المستودع. سير العمل يفشل عند غياب
الأسرار، أو استخدام شهادة `Android Debug`، أو إنتاج APK قابل للتصحيح أو يسمح باتصالات HTTP.
عناوين Supabase الحية ومعرّف `com.sadad.app` محددة في خطوة بناء الإصدار.

لكل إصدار: حدّث `versionName` وارفع `versionCode` في `app/build.gradle.kts`، وأضف التغييرات
إلى `docs/CHANGELOG.md`، ثم ادمج التغييرات على `main` وادفع وسمًا يطابق `versionName`:

```sh
# بعد تحديث versionName إلى 2.5.12 وversionCode إلى 38 ودمج التغييرات:
git tag v2.5.12
git push origin v2.5.12
# إذا كنت تنشر من المرآة، استخدم fork بدل origin.
```

الحزمة الحالية في `delivery/apk/` موقعة بمفتاح تطوير؛ ليست إصدار الإنتاج الرسمي. تغيير مفتاح
التوقيع يمنع تثبيت التحديث فوقها، وإزالة التطبيق تحذف بياناته المحلية؛ احفظها قبل الانتقال.
التوقيع وبناء Release لا يثبتان اكتمال اختبار الهاتف أو التحقق الحي من المزامنة والاسترداد؛
حالة هذه الفحوص في `docs/CONTINUE_HERE.md`. CI لا يطبق ترحيلات قاعدة الإنتاج ولا ينشر وظائفها.

## Team admin dashboard — live Supabase

GitHub Pages deployment is configured in `.github/workflows/admin-pages.yml` and runs for both the
upstream repo (`ibrahem57/sadad`) and the owner's mirror (`Abdalrahman-py/sadad`); any other fork
stays quiet. The dashboard is published and was verified on 12 October 2026 at
<https://abdalrahman-py.github.io/sadad/> (login with a bad password returns the live server's own
message, and the page's content policy allows the `vhftjmiltqfwmrszdtgf` origin only). The hosted
dashboard uses the same live Supabase admin account. It is rebuilt only when `server/public/**`,
`tools/build-admin-pages.cjs` or the workflow itself change, so it can lag the tree.

From the repository root, use Node.js 24 or newer:

```sh
git pull --ff-only
node tools/admin-preview.cjs
```

Open <http://localhost:8081/> and sign in with the shared `admin_sadid` account. Get the
password from the team lead. The browser authenticates against the live Supabase project
(`vhftjmiltqfwmrszdtgf`); no local admin account, SQLite database, `.env` or CLI login is
needed. See [team setup and verification](docs/ADMIN_TEAM_SETUP.md).

## خدمات لم تُفعّل بعد

لم يُنشر نطاق عام. دخول الزبائن المتصل يحتاج تفعيل SMS وبوابة موثّقة. إرسال WhatsApp الآلي
يحتاج إعداد Meta/WhatsApp Business. رابط الدعم يفتح الرقم `+970****2401` عند طلب المستخدم فقط.

## مسار واحد: الدفتر المعتمد (v3)

حُذف خادم Node/SQLite القديم (`server/server.js`) وملفات تشغيله واختباراته، فلم يكن يشغّله أي
عميل: التطبيق واللوحة يتصلان بـ Supabase. والبروتوكول القديم في `/mobile/*` في طريق الإزالة
بعد أن ينقل التطبيق كتاباته إلى الدفتر الموقّع — الحالة في `docs/CONTINUE_HERE.md`.
