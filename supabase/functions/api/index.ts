// =============================================================
//  سرور آنلاین «ارسال بسته» (Supabase Edge Function - Deno)
//  همان API نسخه‌ی server/server.js، این بار روی اینترنت و با پایگاه داده‌ی Postgres.
//  آدرس پایه در اپ:  https://<project>.supabase.co/functions/v1
// =============================================================
import { createClient } from "npm:@supabase/supabase-js@2.45.4";

const db = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  { auth: { persistSession: false } },
);
const ORDER_TTL_MS = 60 * 60 * 1000; // یک ساعت

class HttpError extends Error {
  constructor(public status: number, msg: string) { super(msg); }
}
const json = (status: number, data: unknown) =>
  new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Headers": "Content-Type, Authorization",
    },
  });

// تبدیل ارقام فارسی/عربی به انگلیسی (کیبورد فارسی گوشی)
const toEn = (s: string) =>
  s.replace(/[۰-۹]/g, (d) => String("۰۱۲۳۴۵۶۷۸۹".indexOf(d)))
   .replace(/[٠-٩]/g, (d) => String("٠١٢٣٤٥٦٧٨٩".indexOf(d)));

function text(v: unknown, field: string, required = true) {
  const s = String(v ?? "").trim();
  if (required && !s) throw new HttpError(400, `فیلد «${field}» خالی است`);
  return s.slice(0, 300);
}
function coord(v: unknown, field: string) {
  const n = Number(v);
  if (!Number.isFinite(n)) throw new HttpError(400, `مختصات «${field}» نامعتبر است`);
  return n;
}
const hex = (b: ArrayBuffer | Uint8Array) =>
  [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, "0")).join("");

// ---------- رمز عبور: PBKDF2-SHA256 با ۱۰۰ هزار تکرار و نمک تصادفی ----------
async function hashPassword(password: string, salt = hex(crypto.getRandomValues(new Uint8Array(16)))) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(password), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits(
    { name: "PBKDF2", hash: "SHA-256", salt: new TextEncoder().encode(salt), iterations: 100_000 }, key, 256);
  return { hash: hex(bits), salt };
}
function safeEqual(a: string, b: string) {
  if (a.length !== b.length) return false;
  let r = 0;
  for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}

// ---------- کدهای ۱۰ رقمی (ثبت‌نام راننده و مدیریت) ----------
// خود کدها هیچ‌جا ذخیره نشده‌اند؛ فقط هش SHA-256 آن‌ها در جدول قفل‌شده‌ی app_config است.
async function checkCode(kind: "driver" | "admin", code: unknown) {
  const c = toEn(String(code ?? "")).trim();
  if (!/^\d{10}$/.test(c)) return false;
  const digest = hex(await crypto.subtle.digest("SHA-256", new TextEncoder().encode("ersal-baste:" + c)));
  const { data } = await db.from("app_config").select("value").eq("key", `${kind}_code_sha256`).maybeSingle();
  const ok = !!data && safeEqual(digest, data.value);
  if (!ok) await new Promise((r) => setTimeout(r, 1000)); // کند کردن حدس زدن کد
  return ok;
}

async function newSession(userId: number) {
  const token = hex(crypto.getRandomValues(new Uint8Array(32)));
  const { error } = await db.from("sessions").insert({ token, user_id: userId, created_at: Date.now() });
  if (error) throw error;
  return token;
}
async function requireUser(req: Request, role?: "user" | "driver") {
  const token = (req.headers.get("authorization") || "").replace(/^Bearer\s+/i, "");
  if (!token) throw new HttpError(401, "ابتدا وارد حساب شوید");
  const { data } = await db.from("sessions").select("users(id, phone, name, role)").eq("token", token).maybeSingle();
  // deno-lint-ignore no-explicit-any
  const user = (data as any)?.users;
  if (!user) throw new HttpError(401, "ابتدا وارد حساب شوید");
  if (role && user.role !== role) throw new HttpError(403, "دسترسی مجاز نیست");
  return user as { id: number; phone: string; name: string; role: string };
}

// سفارش‌هایی که یک ساعت در انتظار ماندند: از دید راننده و کاربر حذف می‌شوند،
// ولی با وضعیت expired در پایگاه داده می‌مانند تا در تاریخچه‌ی مدیریت دیده شوند.
async function deleteExpired() {
  await db.from("orders").update({ status: "expired" })
    .eq("status", "pending").lt("created_at", Date.now() - ORDER_TTL_MS);
}

async function body(req: Request) {
  try { return await req.json(); } catch { return {}; }
}

// ---------- مسیرها ----------
async function handle(req: Request): Promise<unknown> {
  const path = new URL(req.url).pathname.replace(/^\/functions\/v1/, "").replace(/\/+$/, "");
  const m = req.method;

  if (m === "GET" && (path === "/api/health" || path === "/api")) return { ok: true, now: Date.now() };

  if (m === "POST" && path === "/api/register") {
    const b = await body(req);
    const phone = toEn(text(b.phone, "شماره موبایل"));
    const name = text(b.name, "نام");
    const role = b.role === "driver" ? "driver" : "user";
    const password = String(b.password || "");
    if (!/^09\d{9}$/.test(phone)) throw new HttpError(400, "شماره موبایل باید ۱۱ رقم و با 09 شروع شود");
    if (password.length < 4) throw new HttpError(400, "رمز عبور حداقل ۴ کاراکتر باشد");
    // ثبت‌نام راننده فقط با کد ۱۰ رقمی معتبر
    if (role === "driver" && !(await checkCode("driver", b.driver_code)))
      throw new HttpError(403, "کد ثبت‌نام راننده نامعتبر است");
    const { hash, salt } = await hashPassword(password);
    const { data, error } = await db.from("users")
      .insert({ phone, name, role, pass_hash: hash, pass_salt: salt, created_at: Date.now() })
      .select("id").single();
    if (error) {
      if (error.code === "23505") throw new HttpError(409, "این شماره قبلاً ثبت شده است. از «ورود» استفاده کنید");
      throw error;
    }
    return { token: await newSession(data.id), user: { id: data.id, phone, name, role } };
  }

  if (m === "POST" && path === "/api/login") {
    const b = await body(req);
    const phone = toEn(text(b.phone, "شماره موبایل"));
    const { data: u } = await db.from("users").select("*").eq("phone", phone).maybeSingle();
    if (!u || !safeEqual((await hashPassword(String(b.password || ""), u.pass_salt)).hash, u.pass_hash))
      throw new HttpError(401, "شماره یا رمز عبور اشتباه است");
    return { token: await newSession(u.id), user: { id: u.id, phone: u.phone, name: u.name, role: u.role } };
  }

  if (m === "POST" && path === "/api/logout") {
    const token = (req.headers.get("authorization") || "").replace(/^Bearer\s+/i, "");
    if (token) await db.from("sessions").delete().eq("token", token);
    return { ok: true };
  }

  if (m === "POST" && path === "/api/orders") {
    const u = await requireUser(req, "user");
    const b = await body(req);
    const s = b.sender || {}, r = b.receiver || {};
    const row = {
      user_id: u.id,
      origin_lat: coord(b.origin_lat, "مبدا"), origin_lng: coord(b.origin_lng, "مبدا"),
      dest_lat: coord(b.dest_lat, "مقصد"), dest_lng: coord(b.dest_lng, "مقصد"),
      sender_name: text(s.name, "نام فرستنده"), sender_phone: toEn(text(s.phone, "تلفن فرستنده")),
      sender_address: text(s.address, "آدرس فرستنده"),
      sender_plaque: toEn(text(s.plaque, "پلاک", false)), sender_unit: toEn(text(s.unit, "واحد", false)),
      receiver_name: text(r.name, "نام گیرنده"), receiver_phone: toEn(text(r.phone, "تلفن گیرنده")),
      receiver_address: text(r.address, "آدرس گیرنده"),
      receiver_plaque: toEn(text(r.plaque, "پلاک", false)), receiver_unit: toEn(text(r.unit, "واحد", false)),
      note: text(b.note, "توضیحات", false), created_at: Date.now(),
    };
    const { data, error } = await db.from("orders").insert(row).select("id").single();
    if (error) throw error;
    return { id: data.id };
  }

  if (m === "GET" && path === "/api/orders/mine") {
    const u = await requireUser(req, "user");
    await deleteExpired();
    const { data, error } = await db.from("orders").select("*, driver:users!orders_driver_id_fkey(name, phone)")
      .eq("user_id", u.id).neq("status", "expired").order("id", { ascending: false }).limit(100);
    if (error) throw error;
    // بعد از تحویل بسته، شماره‌ی راننده دیگر به کاربر نشان داده نمی‌شود
    // deno-lint-ignore no-explicit-any
    const orders = (data || []).map(({ driver, ...o }: any) => ({
      ...o,
      driver_name: driver?.name ?? null,
      driver_phone: o.status === "delivered" ? null : (driver?.phone ?? null),
    }));
    return { now: Date.now(), ttl: ORDER_TTL_MS, orders };
  }

  let mm = path.match(/^\/api\/orders\/(\d+)\/cancel$/);
  if (m === "POST" && mm) {
    const u = await requireUser(req, "user");
    const { data } = await db.from("orders").update({ status: "canceled", decided_at: Date.now() })
      .eq("id", Number(mm[1])).eq("user_id", u.id).eq("status", "pending").select("id");
    if (!data?.length) throw new HttpError(409, "این سفارش دیگر قابل لغو نیست");
    return { ok: true };
  }

  if (m === "GET" && path === "/api/driver/orders") {
    const d = await requireUser(req, "driver");
    await deleteExpired();
    const [p, mine] = await Promise.all([
      db.from("orders").select("*").eq("status", "pending").order("id", { ascending: false }),
      db.from("orders").select("*").eq("driver_id", d.id).in("status", ["accepted", "delivered"]).order("decided_at", { ascending: false }).limit(50),
    ]);
    if (p.error) throw p.error;
    // بعد از تحویل، اطلاعات سفارش‌دهنده (فرستنده، گیرنده، آدرس‌ها، شماره‌ها، توضیحات، مختصات)
    // دیگر برای راننده فرستاده نمی‌شود؛ این اطلاعات فقط در بخش مدیریت در دسترس است.
    // deno-lint-ignore no-explicit-any
    const minimal = (o: any) => o.status !== "delivered" ? o : ({
      id: o.id, status: o.status, driver_id: o.driver_id,
      created_at: o.created_at, decided_at: o.decided_at, delivered_at: o.delivered_at,
    });
    return { now: Date.now(), ttl: ORDER_TTL_MS, pending: p.data, mine: (mine.data || []).map(minimal) };
  }

  // تایید یا لغو راننده: به‌روزرسانی شرطی اتمیک؛ فقط یک راننده موفق می‌شود
  mm = path.match(/^\/api\/driver\/orders\/(\d+)\/(accept|reject)$/);
  if (m === "POST" && mm) {
    const d = await requireUser(req, "driver");
    const now = Date.now();
    const { data } = await db.from("orders")
      .update({ status: mm[2] === "accept" ? "accepted" : "rejected", driver_id: d.id, decided_at: now })
      .eq("id", Number(mm[1])).eq("status", "pending").gte("created_at", now - ORDER_TTL_MS).select("id");
    if (!data?.length) throw new HttpError(409, "این سفارش منقضی شده یا قبلاً بررسی شده است");
    return { ok: true };
  }

  // راننده: «تحویل داده شد» — فقط راننده‌ای که سفارش را قبول کرده، و فقط از وضعیت accepted
  mm = path.match(/^\/api\/driver\/orders\/(\d+)\/deliver$/);
  if (m === "POST" && mm) {
    const d = await requireUser(req, "driver");
    const { data } = await db.from("orders").update({ status: "delivered", delivered_at: Date.now() })
      .eq("id", Number(mm[1])).eq("driver_id", d.id).eq("status", "accepted").select("id");
    if (!data?.length) throw new HttpError(409, "این سفارش قابل ثبت به عنوان تحویل‌شده نیست");
    return { ok: true };
  }

  // مدیریت: تاریخچه‌ی کامل سفرها + اطلاعات کامل رانندگان (نیاز به ورود راننده + کد ۱۰ رقمی مدیریت)
  if (m === "POST" && path === "/api/admin/history") {
    await requireUser(req, "driver");
    const b = await body(req);
    if (!(await checkCode("admin", b.code))) throw new HttpError(403, "کد مدیریت اشتباه است");
    await deleteExpired();
    const [o, dr] = await Promise.all([
      db.from("orders")
        .select("*, user:users!orders_user_id_fkey(name, phone), driver:users!orders_driver_id_fkey(id, name, phone)")
        .order("id", { ascending: false }).limit(500),
      db.from("users").select("id, name, phone, created_at").eq("role", "driver").order("id"),
    ]);
    if (o.error) throw o.error;
    // deno-lint-ignore no-explicit-any
    const orders = (o.data || []).map(({ user, driver, ...r }: any) => ({
      ...r, user_name: user?.name ?? null, user_phone: user?.phone ?? null,
      driver_name: driver?.name ?? null, driver_phone: driver?.phone ?? null,
    }));
    // deno-lint-ignore no-explicit-any
    const drivers = (dr.data || []).map((d: any) => ({
      ...d,
      accepted: orders.filter((x) => x.driver_id === d.id && x.status === "accepted").length,
      delivered: orders.filter((x) => x.driver_id === d.id && x.status === "delivered").length,
      rejected: orders.filter((x) => x.driver_id === d.id && x.status === "rejected").length,
    }));
    return { now: Date.now(), orders, drivers };
  }

  throw new HttpError(404, "مسیر پیدا نشد");
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return json(200, {});
  try {
    return json(200, await handle(req));
  } catch (e) {
    if (e instanceof HttpError) return json(e.status, { error: e.message });
    console.error(e);
    return json(500, { error: "خطای داخلی سرور" });
  }
});
