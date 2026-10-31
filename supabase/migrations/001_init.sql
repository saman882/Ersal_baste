-- جدول‌های «ارسال بسته» روی Postgres (Supabase)
-- دسترسی فقط از طریق Edge Function (کلید service role) است؛ RLS روشن و بدون policy
-- یعنی هیچ‌کس مستقیم از بیرون به جدول‌ها دسترسی ندارد.

create table public.users (
  id         bigint generated always as identity primary key,
  phone      text not null unique,
  name       text not null,
  role       text not null check (role in ('user','driver')),
  pass_hash  text not null,
  pass_salt  text not null,
  created_at bigint not null
);

create table public.sessions (
  token      text primary key,
  user_id    bigint not null references public.users(id) on delete cascade,
  created_at bigint not null
);
create index sessions_user_id_idx on public.sessions(user_id);

create table public.orders (
  id               bigint generated always as identity primary key,
  user_id          bigint not null references public.users(id) on delete cascade,
  driver_id        bigint references public.users(id) on delete set null,
  status           text not null default 'pending'
                   check (status in ('pending','accepted','rejected','canceled')),
  origin_lat double precision not null, origin_lng double precision not null,
  dest_lat   double precision not null, dest_lng   double precision not null,
  sender_name text not null, sender_phone text not null, sender_address text not null,
  sender_plaque text, sender_unit text,
  receiver_name text not null, receiver_phone text not null, receiver_address text not null,
  receiver_plaque text, receiver_unit text,
  note       text,
  created_at bigint not null,
  decided_at bigint
);
create index orders_status_created_idx on public.orders(status, created_at);
create index orders_user_id_idx on public.orders(user_id);
create index orders_driver_id_idx on public.orders(driver_id);

alter table public.users    enable row level security;
alter table public.sessions enable row level security;
alter table public.orders   enable row level security;
