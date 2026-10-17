# خادم سدد على Supabase

المصدر المعتمد للمشروع `vhftjmiltqfwmrszdtgf` هو هذا المجلد. حالة النشر الفعلية في [CONTINUE_HERE](../../docs/CONTINUE_HERE.md)، والمعمارية في [ARCHITECTURE](../../docs/ARCHITECTURE.md).

- `functions/api/`: وظيفة Edge الوحيدة (Hono + zod): الهاتف (التسجيل، الترخيص، النسخة المكتملة، العمليات الموقّعة، النشاط، الاسترداد)، والحساب، والإدارة. هويتها Supabase Auth وحدها، وعقدها في `contract/` بجذر المستودع.
- `schemas/`: المخطط مقسّمًا بالموضوع.
- `migrations/`: ترحيل واحد `*_baseline.sql` هو ملفات `schemas/*.sql` مدمجة بترتيب الاسم؛ يتحقق `Supabase-Setup/scripts/test-baseline.mjs` من تطابقهما حرفيًا.

الشاشات تقرأ SQLite المحلي بعد إسقاط النسخة المكتملة والعمليات المعلقة داخل معاملة. اعتماد الهاتف شرط لقراءة الدفتر؛ إلغاء الهاتف لا يحذف مالية المتجر. المتجر الجديد يبدأ بلا مالية سابقة، ويُجهّز دفتره عند اعتماد الهاتف.

## الاختبارات

```sh
./tools/check.sh database # test-baseline.mjs على PGlite
./tools/check.sh edge     # deno test -A داخل functions/api/
```

اختبارات SQL تحتاج PGlite المثبت في `.local-tools/`، ويثبّته `./tools/check.sh database` عند غيابه.

مفتاح `sb_publishable_...` وحده يُسمح بنشره. أسرار الخدمة تقرؤها الوظيفة من أسرار المشروع. تتحقق `api` من رمز Supabase Auth وتوقيع الهاتف بنفسها، لذا `verify_jwt = false` في `config.toml`؛ لا تغيّره دون مراجعة مسار التفويض.

## النشر

النشر يدوي عبر `.github/workflows/supabase-deploy.yml` من `main`: يشغّل `./tools/check.sh`، ثم ينتظر موافقة مراجعي بيئة `production` قبل `supabase db push` و`supabase functions deploy api`. لا تشغّل هذه الأوامر يدويًا.
