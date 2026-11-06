-- سفارش‌هایی که در یک ساعت تایید نشدند حذف نمی‌شوند؛ با وضعیت expired برای تاریخچه‌ی مدیریت می‌مانند
alter table public.orders drop constraint if exists orders_status_check;
alter table public.orders add constraint orders_status_check
  check (status in ('pending','accepted','delivered','rejected','canceled','expired'));
