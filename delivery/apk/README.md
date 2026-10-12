# حزمة التثبيت

`Sadad-2.5.11-server-release.apk` — إصدار التطبيق المتصل بالخادم (flavor `server`، buildType `release`).

- الحجم 252,643 بايت · بصمة SHA-256 في الملف `Sadad-2.5.11-server-release.apk.sha256`.
- `versionName 2.5.11` · `versionCode 37` · `minSdk 24` · `targetSdk 34`.
- نقاط النهاية المدمجة داخل الـdex: `.../functions/v1/sadad-api`، `.../functions/v1/sadad-auth`،
  `.../functions/v1/sadid-ledger/api` على المشروع `vhftjmiltqfwmrszdtgf`.
- `usesCleartextTraffic=false`، والتطبيق ليس `debuggable`، وحقل عنوان الخادم غير قابل للتحرير:
  يُرفض أي عنوان غير العنوان المدمج.
- **التوقيع**: مُوقَّع بمفتاح التطوير المحلي، لأن متغيرات `SADAD_KEYSTORE_*` غير متاحة في بيئة
  البناء التي أُنتج فيها. الإصدار الرسمي يحتاج إعادة البناء بالمفتاح الرسمي؛ وتوقيع مختلف يعني أن
  التثبيت فوق نسخة قديمة يتطلب إزالة التطبيق أولًا (وحذف بياناته).
- التثبيت: `adb install -r Sadad-2.5.11-server-release.apk`، ثم منح إذن الإشعارات على Android 13+.
- هذه الحزمة هي التطبيق وحده: لا حزمة اختبارات جهاز (`androidTest`) ولا معرّف QA.
