-- وضعیت «تحویل داده شد» و زمان تحویل
alter table public.orders add column if not exists delivered_at bigint;
alter table public.orders drop constraint if exists orders_status_check;
alter table public.orders add constraint orders_status_check
  check (status in ('pending','accepted','delivered','rejected','canceled'));

-- جدول تنظیمات قفل‌شده (RLS بدون policy): هش SHA-256 کدهای ۱۰ رقمی ثبت‌نام راننده و مدیریت.
-- مقدارها عمداً در مخزن نیستند؛ با این دستور وارد می‌شوند (به جای <hash> مقدار
-- sha256('ersal-baste:' || '<کد ۱۰ رقمی>') را بگذارید):
--   insert into public.app_config(key, value) values ('driver_code_sha256','<hash>'), ('admin_code_sha256','<hash>')
--   on conflict (key) do update set value = excluded.value;
create table if not exists public.app_config (
  key   text primary key,
  value text not null
);
alter table public.app_config enable row level security;
