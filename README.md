# سدد — دفتر مالي (تجريبي)

مشروع دفتر مالي عربي على Android، مع خادم Supabase ولوحة إدارة. تطبيق Android في بداية
إعادة البناء بـKotlin وCompose: الإصدار 3.0.0 الحالي هيكل يعرض اسم التطبيق فقط؛ الدخول
والدفتر والمزامنة وبقية الشاشات تُضاف في تذاكر المرحلة 3.

اقرأ [حالة العمل](docs/CONTINUE_HERE.md) و[خطة المعمارية](docs/ARCHITECTURE.md) قبل التطوير.

## أين يعيش كل شيء

| المكوّن | المسار |
| --- | --- |
| تطبيق Android (المصدر الوحيد) | `app/src/main/kotlin/com/sadad/app/` |
| قلب الدفتر الصرف بـKotlin | `ledger-core/` |
| الخادم: وظيفة Edge `api` والمخطط الأساسي | `Supabase-Setup/supabase/` — مشروع `vhftjmiltqfwmrszdtgf` |
| العقد (OpenAPI ورموز الأخطاء) | `contract/` |
| لوحة الإدارة (Vite + TypeScript) | `server/public/` |
| سجل الإصدارات | `docs/CHANGELOG.md` |
| قواعد العمل | `AGENTS.md` |

تطبيق Java واختباراته محفوظة عند الوسم `pre-kotlin-app-2026-10`، مثلًا:

```sh
git show pre-kotlin-app-2026-10:app/src/main/java/com/sadad/app/MainActivity.java
```

نسخة Flutter القديمة محفوظة عند `pre-consolidation-2026-10-11:archive/Sadad-Flutter`.
البيانات تجريبية؛ الانتقال إلى التطبيق الجديد يحتاج إعادة تثبيت، بلا ترحيل للبيانات القديمة.
إزالة التطبيق القديم تحذف بياناته المحلية.

## البناء

يحتاج JDK 21 وAndroid SDK 37. أنشئ `local.properties` في الجذر (مستبعد من Git):

```properties
sdk.dir=/path/to/Android/Sdk
```

```sh
./gradlew assembleDebug assembleRelease
```

- `debug`: معرّف `com.sadad.app.debug`؛ StrictMode ينهي التطبيق عند المخالفة.
- `release`: معرّف `com.sadad.app`؛ تفعيل R8 وتقليص الموارد. توقيع الإنتاج يتطلب
  `SADAD_KEYSTORE_FILE` و`SADAD_KEYSTORE_PASSWORD` و`SADAD_KEY_ALIAS` و`SADAD_KEY_PASSWORD`.
  بدونها ينتج `app/build/outputs/apk/release/app-release-unsigned.apk`.

عنوان Supabase مدمج وقت البناء، وافتراضيًا `https://vhftjmiltqfwmrszdtgf.supabase.co`؛
عنوان الوظيفة مشتق منه بإضافة `/functions/v1/api`. لتغيير مشروع الاختبار وقت البناء:

```sh
./gradlew assembleDebug -PSADAD_SUPABASE_URL=https://YOUR-PROJECT.supabase.co
```

المفتاح المضمّن قابل للنشر فقط (`SADAD_SUPABASE_PUBLISHABLE_KEY`). اتصالات HTTP والنسخ
الاحتياطية للنظام معطّلة في كلتا نسختي البناء.

## الفحص

```sh
./tools/check.sh
```

البوابة الموحدة تشغّل التنسيق والتوثيق وAndroid واللوحة ووظيفة Edge واختبارات قاعدة البيانات.
لتشغيل قسم واحد أو اختبارات JVM مباشرة:

```sh
./tools/check.sh android
./gradlew :ledger-core:test testDebugUnitTest
./tools/check.sh database
./tools/check.sh edge
```

مصادر اختبارات التطبيق الجديدة تحت `app/src/test/kotlin/com/sadad/app/`، واختبارات الجهاز
تُضاف لاحقًا تحت `app/src/androidTest/kotlin/com/sadad/app/` باستخدام `AndroidJUnitRunner`.
بناء APK وحده لا يثبت سلامة التشغيل على الهاتف؛ ذلك غير محقّق للهيكل الجديد.

## النشر

الفحوص تعمل عبر `.github/workflows/android-ci.yml`. نشر الخادم يتطلب تشغيل سير العمل
المحمي `.github/workflows/supabase-deploy.yml` يدويًا ثم موافقة مراجعي بيئة الإنتاج.

لوحة الإدارة تُنشر يدويًا من مستودع `Abdalrahman-py/sadad` عبر
`.github/workflows/admin-pages.yml`؛ الدفع وطلبات السحب تشغّل الفحوص فقط.
افتح [لوحة الإدارة](https://abdalrahman-py.github.io/sadad/) بحساب Supabase Auth ذي دور
الإدارة أو الدعم. للبناء محليًا: `npm ci && npm run build` داخل `server/public/`؛ الناتج `_site/`.
حالة النسخة المنشورة موثقة في [حالة العمل](docs/CONTINUE_HERE.md).

دفع وسم إصدار مطابق لـ`versionName` مثل `v3.0.0` يشغّل الفحوص ثم
`testReleaseUnitTest assembleRelease` في `.github/workflows/android-release.yml`، وينشر
`Sadad-3.0.0-release.apk` و`SHA256SUMS` في [إصدارات GitHub](https://github.com/Abdalrahman-py/sadad/releases).

إعداد التوقيع مرة واحدة في أسرار GitHub Actions:

| السر | القيمة |
| --- | --- |
| `SADAD_KEYSTORE_BASE64` | مفتاح الإنتاج بترميز Base64 |
| `SADAD_KEYSTORE_PASSWORD` | كلمة مرور مخزن المفتاح |
| `SADAD_KEY_ALIAS` | اسم المفتاح |
| `SADAD_KEY_PASSWORD` | كلمة مرور المفتاح |

احتفظ بالمفتاح نفسه لكل تحديث؛ لا تضعه أو كلمات مروره في المستودع. سير العمل يفشل عند غياب
الأسرار، أو استخدام شهادة `Android Debug`، أو إنتاج APK قابل للتصحيح أو يسمح باتصالات HTTP.
توقيع الإنتاج غير محقّق؛ الهيكل الحالي ليس إصدارًا جاهزًا للاستخدام المالي.
