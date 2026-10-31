# ارسال بسته

اپلیکیشن اندروید درخواست ارسال بسته در تهران (الگو گرفته از تپسی) + سرور Node.js.

- `android/` — سورس اپ (Java، بدون کتابخانه‌ی جانبی)
- `server/` — سرور: `node server.js` (Node.js 22+)
- APK در تب **Actions** → آخرین اجرا → **Artifacts** ساخته می‌شود.

## سرور آنلاین
اپ به‌طور پیش‌فرض به سرور آنلاین وصل می‌شود (Supabase Edge Function در `supabase/functions/api`):
`https://qbmtrekzehzjtoanibfu.supabase.co/functions/v1`

پنل سفارش‌ها (JSON): `https://qbmtrekzehzjtoanibfu.supabase.co/functions/v1/api/admin/orders`

برای دمو بدون اینترنت، `node server/server.js` را روی لپ‌تاپ اجرا کنید و آدرس آن را در «تنظیمات سرور» اپ وارد کنید.
