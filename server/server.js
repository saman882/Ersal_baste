// =============================================================
//  سرور «ارسال بسته»
//  - بدون هیچ کتابخانه خارجی: فقط ماژول‌های داخلی Node.js (نسخه 22 به بالا)
//  - پایگاه داده: SQLite (فایل ersal.db کنار همین فایل)
//  - ارتباط با اپ: REST API با قالب JSON روی HTTP
// =============================================================
const http = require('node:http');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { DatabaseSync } = require('node:sqlite');

const PORT = Number(process.env.PORT || 3000);
const ORDER_TTL_MS = 60 * 60 * 1000; // یک ساعت مهلت تایید راننده

// ---------- ۱) پایگاه داده ----------
const db = new DatabaseSync(path.join(__dirname, 'ersal.db'));
db.exec(`
  PRAGMA journal_mode = WAL;
  CREATE TABLE IF NOT EXISTS users (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    phone      TEXT NOT NULL UNIQUE,
    name       TEXT NOT NULL,
    role       TEXT NOT NULL CHECK (role IN ('user','driver')),
    pass_hash  TEXT NOT NULL,
    pass_salt  TEXT NOT NULL,
    created_at INTEGER NOT NULL
  );
  CREATE TABLE IF NOT EXISTS sessions (
    token      TEXT PRIMARY KEY,
    user_id    INTEGER NOT NULL REFERENCES users(id),
    created_at INTEGER NOT NULL
  );
  CREATE TABLE IF NOT EXISTS orders (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id          INTEGER NOT NULL REFERENCES users(id),
    driver_id        INTEGER REFERENCES users(id),
    status           TEXT NOT NULL DEFAULT 'pending'
                     CHECK (status IN ('pending','accepted','delivered','rejected','canceled','expired')),
    origin_lat REAL NOT NULL, origin_lng REAL NOT NULL,
    dest_lat   REAL NOT NULL, dest_lng   REAL NOT NULL,
    sender_name TEXT NOT NULL, sender_phone TEXT NOT NULL, sender_address TEXT NOT NULL,
    sender_plaque TEXT, sender_unit TEXT,
    receiver_name TEXT NOT NULL, receiver_phone TEXT NOT NULL, receiver_address TEXT NOT NULL,
    receiver_plaque TEXT, receiver_unit TEXT,
    note        TEXT,
    created_at  INTEGER NOT NULL,
    decided_at  INTEGER,
    delivered_at INTEGER
  );
  CREATE INDEX IF NOT EXISTS idx_orders_status_created ON orders(status, created_at);
`);

// مهاجرت پایگاه داده‌ی قدیمی: اضافه کردن وضعیت delivered و ستون delivered_at
{
  const def = db.prepare(`SELECT sql FROM sqlite_master WHERE type='table' AND name='orders'`).get();
  if (def && !def.sql.includes("'expired'")) {
    const cols = db.prepare(`PRAGMA table_info(orders)`).all().map((c) => c.name).join(', ');
    const newSql = def.sql
      .replace(/status IN \([^)]*\)/, "status IN ('pending','accepted','delivered','rejected','canceled','expired')")
      .replace(/decided_at\s+INTEGER\s*\)\s*$/, 'decided_at INTEGER, delivered_at INTEGER)')
      .replace('CREATE TABLE orders', 'CREATE TABLE orders_new');
    db.exec(`BEGIN; ${newSql}; INSERT INTO orders_new (${cols}) SELECT ${cols} FROM orders;
             DROP TABLE orders; ALTER TABLE orders_new RENAME TO orders;
             CREATE INDEX IF NOT EXISTS idx_orders_status_created ON orders(status, created_at); COMMIT;`);
    console.log('[migrate] جدول orders به‌روز شد');
  }
}

// ---------- کدهای ۱۰ رقمی (ثبت‌نام راننده و مدیریت) ----------
// کدها داخل کد برنامه و GitHub نیستند. از متغیرهای محیطی DRIVER_CODE و ADMIN_CODE
// یا فایل codes.json کنار همین فایل خوانده می‌شوند (این فایل در .gitignore است).
const CODES = (() => {
  let file = {};
  try { file = JSON.parse(fs.readFileSync(path.join(__dirname, 'codes.json'), 'utf8')); } catch { }
  return { driver: process.env.DRIVER_CODE || file.driver_code || '', admin: process.env.ADMIN_CODE || file.admin_code || '' };
})();
if (!CODES.driver || !CODES.admin)
  console.warn('[هشدار] codes.json پیدا نشد: ثبت‌نام راننده و بخش مدیریت غیرفعال است');
async function checkCode(kind, code) {
  const c = toEn(String(code ?? '')).trim(), real = CODES[kind];
  const ok = /^\d{10}$/.test(c) && real.length === 10 &&
    crypto.timingSafeEqual(Buffer.from(c), Buffer.from(real));
  if (!ok) await new Promise((r) => setTimeout(r, 1000)); // کند کردن حدس زدن کد
  return ok;
}

// ---------- ۲) رمز عبور: هش با scrypt + نمک تصادفی ----------
function hashPassword(password, salt = crypto.randomBytes(16).toString('hex')) {
  const hash = crypto.scryptSync(password, salt, 64).toString('hex');
  return { hash, salt };
}
function checkPassword(password, user) {
  const { hash } = hashPassword(password, user.pass_salt);
  return crypto.timingSafeEqual(Buffer.from(hash, 'hex'), Buffer.from(user.pass_hash, 'hex'));
}

// ---------- ۳) سفارش‌های منقضی (بیش از یک ساعت بدون تایید) ----------
// از دید راننده و کاربر حذف می‌شوند، ولی با وضعیت expired برای تاریخچه‌ی مدیریت می‌مانند.
function deleteExpired() {
  const r = db.prepare(`UPDATE orders SET status = 'expired' WHERE status = 'pending' AND created_at < ?`)
              .run(Date.now() - ORDER_TTL_MS);
  if (r.changes) console.log(`[cleanup] ${r.changes} سفارش منقضی شد`);
}
setInterval(deleteExpired, 30 * 1000);

// ---------- ابزارهای کمکی HTTP ----------
function send(res, status, data) {
  const body = JSON.stringify(data);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Content-Type, Authorization, X-Admin-Code',
  });
  res.end(body);
}
function readJson(req) {
  return new Promise((resolve, reject) => {
    let raw = '';
    req.on('data', (c) => { raw += c; if (raw.length > 100_000) req.destroy(); });
    req.on('end', () => { try { resolve(raw ? JSON.parse(raw) : {}); } catch { reject(new HttpError(400, 'JSON نامعتبر است')); } });
    req.on('error', reject);
  });
}
class HttpError extends Error { constructor(status, msg) { super(msg); this.status = status; } }

function requireUser(req, role) {
  const token = (req.headers.authorization || '').replace(/^Bearer\s+/i, '');
  const user = token && db.prepare(
    `SELECT u.id, u.phone, u.name, u.role FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token = ?`
  ).get(token);
  if (!user) throw new HttpError(401, 'ابتدا وارد حساب شوید');
  if (role && user.role !== role) throw new HttpError(403, 'دسترسی مجاز نیست');
  return user;
}
const toEn = (s) => s.replace(/[۰-۹]/g, (d) => '۰۱۲۳۴۵۶۷۸۹'.indexOf(d)).replace(/[٠-٩]/g, (d) => '٠١٢٣٤٥٦٧٨٩'.indexOf(d));
function text(v, field, required = true) {
  const s = (v ?? '').toString().trim();
  if (required && !s) throw new HttpError(400, `فیلد «${field}» خالی است`);
  return s.slice(0, 300);
}
function coord(v, field) {
  const n = Number(v);
  if (!Number.isFinite(n)) throw new HttpError(400, `مختصات «${field}» نامعتبر است`);
  return n;
}
function newSession(userId) {
  const token = crypto.randomBytes(32).toString('hex');
  db.prepare(`INSERT INTO sessions (token, user_id, created_at) VALUES (?, ?, ?)`).run(token, userId, Date.now());
  return token;
}

// ---------- ۴) مسیرهای API ----------
const routes = [];
const route = (method, pattern, handler) => routes.push({ method, pattern, handler });

// ثبت‌نام (کاربر یا راننده)
route('POST', /^\/api\/register$/, async (req) => {
  const b = await readJson(req);
  const phone = toEn(text(b.phone, 'شماره موبایل'));
  const name = text(b.name, 'نام');
  const role = b.role === 'driver' ? 'driver' : 'user';
  const password = (b.password || '').toString();
  if (!/^09\d{9}$/.test(phone)) throw new HttpError(400, 'شماره موبایل باید ۱۱ رقم و با 09 شروع شود');
  if (password.length < 4) throw new HttpError(400, 'رمز عبور حداقل ۴ کاراکتر باشد');
  // ثبت‌نام راننده فقط با کد ۱۰ رقمی معتبر
  if (role === 'driver' && !(await checkCode('driver', b.driver_code)))
    throw new HttpError(403, 'کد ثبت‌نام راننده نامعتبر است');
  if (db.prepare(`SELECT 1 FROM users WHERE phone = ?`).get(phone)) throw new HttpError(409, 'این شماره قبلاً ثبت شده است');
  const { hash, salt } = hashPassword(password);
  const r = db.prepare(`INSERT INTO users (phone, name, role, pass_hash, pass_salt, created_at) VALUES (?,?,?,?,?,?)`)
              .run(phone, name, role, hash, salt, Date.now());
  const id = Number(r.lastInsertRowid);
  return { token: newSession(id), user: { id, phone, name, role } };
});

// ورود با شماره و رمز (بدون پیامک)
route('POST', /^\/api\/login$/, async (req) => {
  const b = await readJson(req);
  const user = db.prepare(`SELECT * FROM users WHERE phone = ?`).get(toEn(text(b.phone, 'شماره موبایل')));
  if (!user || !checkPassword((b.password || '').toString(), user)) throw new HttpError(401, 'شماره یا رمز عبور اشتباه است');
  return { token: newSession(user.id), user: { id: user.id, phone: user.phone, name: user.name, role: user.role } };
});

route('POST', /^\/api\/logout$/, async (req) => {
  const token = (req.headers.authorization || '').replace(/^Bearer\s+/i, '');
  db.prepare(`DELETE FROM sessions WHERE token = ?`).run(token);
  return { ok: true };
});

// کاربر: ثبت سفارش جدید (فرم فرستنده + فرم گیرنده + دو نقطه روی نقشه)
route('POST', /^\/api\/orders$/, async (req) => {
  const u = requireUser(req, 'user');
  const b = await readJson(req);
  const s = b.sender || {}, r = b.receiver || {};
  const o = [
    u.id,
    coord(b.origin_lat, 'مبدا'), coord(b.origin_lng, 'مبدا'),
    coord(b.dest_lat, 'مقصد'), coord(b.dest_lng, 'مقصد'),
    text(s.name, 'نام فرستنده'), text(s.phone, 'تلفن فرستنده'), text(s.address, 'آدرس فرستنده'),
    text(s.plaque, 'پلاک', false), text(s.unit, 'واحد', false),
    text(r.name, 'نام گیرنده'), text(r.phone, 'تلفن گیرنده'), text(r.address, 'آدرس گیرنده'),
    text(r.plaque, 'پلاک', false), text(r.unit, 'واحد', false),
    text(b.note, 'توضیحات', false), Date.now(),
  ];
  const res = db.prepare(`INSERT INTO orders (user_id, origin_lat, origin_lng, dest_lat, dest_lng,
      sender_name, sender_phone, sender_address, sender_plaque, sender_unit,
      receiver_name, receiver_phone, receiver_address, receiver_plaque, receiver_unit, note, created_at)
      VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`).run(...o);
  return { id: Number(res.lastInsertRowid) };
});

// کاربر: لیست سفارش‌های خودم
route('GET', /^\/api\/orders\/mine$/, async (req) => {
  const u = requireUser(req, 'user');
  deleteExpired();
  // بعد از تحویل بسته، شماره‌ی راننده دیگر به کاربر نشان داده نمی‌شود
  const rows = db.prepare(`SELECT o.*, d.name AS driver_name,
      CASE WHEN o.status = 'delivered' THEN NULL ELSE d.phone END AS driver_phone
      FROM orders o LEFT JOIN users d ON d.id = o.driver_id
      WHERE o.user_id = ? AND o.status != 'expired' ORDER BY o.id DESC LIMIT 100`).all(u.id);
  return { now: Date.now(), ttl: ORDER_TTL_MS, orders: rows };
});

// کاربر: لغو سفارشی که هنوز تایید نشده
route('POST', /^\/api\/orders\/(\d+)\/cancel$/, async (req, m) => {
  const u = requireUser(req, 'user');
  const r = db.prepare(`UPDATE orders SET status='canceled', decided_at=? WHERE id=? AND user_id=? AND status='pending'`)
              .run(Date.now(), Number(m[1]), u.id);
  if (!r.changes) throw new HttpError(409, 'این سفارش دیگر قابل لغو نیست');
  return { ok: true };
});

// راننده: سفارش‌های در انتظار (کمتر از یک ساعت) + سفارش‌هایی که خودش قبول کرده
route('GET', /^\/api\/driver\/orders$/, async (req) => {
  const d = requireUser(req, 'driver');
  deleteExpired();
  const pending = db.prepare(`SELECT * FROM orders WHERE status='pending' ORDER BY id DESC`).all();
  const mine = db.prepare(`SELECT * FROM orders WHERE driver_id=? AND status IN ('accepted','delivered') ORDER BY decided_at DESC LIMIT 50`).all(d.id)
    // بعد از تحویل، اطلاعات سفارش‌دهنده دیگر برای راننده فرستاده نمی‌شود (فقط در بخش مدیریت)
    .map((o) => o.status !== 'delivered' ? o : {
      id: o.id, status: o.status, driver_id: o.driver_id,
      created_at: o.created_at, decided_at: o.decided_at, delivered_at: o.delivered_at,
    });
  return { now: Date.now(), ttl: ORDER_TTL_MS, pending, mine };
});

// راننده: تایید یا لغو. شرط status='pending' تضمین می‌کند دو راننده یک سفارش را نگیرند.
function decide(newStatus) {
  return async (req, m) => {
    const d = requireUser(req, 'driver');
    const r = db.prepare(`UPDATE orders SET status=?, driver_id=?, decided_at=?
        WHERE id=? AND status='pending' AND created_at >= ?`)
      .run(newStatus, d.id, Date.now(), Number(m[1]), Date.now() - ORDER_TTL_MS);
    if (!r.changes) throw new HttpError(409, 'این سفارش منقضی شده یا قبلاً بررسی شده است');
    return { ok: true };
  };
}
route('POST', /^\/api\/driver\/orders\/(\d+)\/accept$/, decide('accepted'));
route('POST', /^\/api\/driver\/orders\/(\d+)\/reject$/, decide('rejected'));

// راننده: «تحویل داده شد» — فقط راننده‌ای که سفارش را قبول کرده، و فقط از وضعیت accepted
route('POST', /^\/api\/driver\/orders\/(\d+)\/deliver$/, async (req, m) => {
  const d = requireUser(req, 'driver');
  const r = db.prepare(`UPDATE orders SET status='delivered', delivered_at=? WHERE id=? AND driver_id=? AND status='accepted'`)
              .run(Date.now(), Number(m[1]), d.id);
  if (!r.changes) throw new HttpError(409, 'این سفارش قابل ثبت به عنوان تحویل‌شده نیست');
  return { ok: true };
});

// مدیریت: تاریخچه‌ی کامل سفرها + اطلاعات کامل رانندگان (ورود راننده + کد ۱۰ رقمی مدیریت)
function adminHistory() {
  deleteExpired();
  const orders = db.prepare(`SELECT o.*, u.name AS user_name, u.phone AS user_phone, d.name AS driver_name, d.phone AS driver_phone
      FROM orders o JOIN users u ON u.id=o.user_id LEFT JOIN users d ON d.id=o.driver_id ORDER BY o.id DESC LIMIT 500`).all();
  const drivers = db.prepare(`SELECT u.id, u.name, u.phone, u.created_at,
      SUM(o.status='accepted') AS accepted, SUM(o.status='delivered') AS delivered, SUM(o.status='rejected') AS rejected
      FROM users u LEFT JOIN orders o ON o.driver_id=u.id WHERE u.role='driver' GROUP BY u.id ORDER BY u.id`).all()
      .map((d) => ({ ...d, accepted: d.accepted || 0, delivered: d.delivered || 0, rejected: d.rejected || 0 }));
  return { now: Date.now(), orders, drivers };
}
route('POST', /^\/api\/admin\/history$/, async (req) => {
  requireUser(req, 'driver');
  const b = await readJson(req);
  if (!(await checkCode('admin', b.code))) throw new HttpError(403, 'کد مدیریت اشتباه است');
  return adminHistory();
});
// پنل وب لپ‌تاپ: فقط با کد مدیریت (در هدر X-Admin-Code)
route('GET', /^\/api\/admin\/orders$/, async (req) => {
  if (!(await checkCode('admin', req.headers['x-admin-code']))) throw new HttpError(403, 'کد مدیریت اشتباه است');
  return { ...adminHistory(), ttl: ORDER_TTL_MS };
});

route('GET', /^\/api\/health$/, async () => ({ ok: true, now: Date.now() }));

// ---------- ۵) راه‌اندازی سرور ----------
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  if (req.method === 'OPTIONS') return send(res, 200, {});
  if (req.method === 'GET' && url.pathname === '/') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    return res.end(fs.readFileSync(path.join(__dirname, 'dashboard.html')));
  }
  for (const r of routes) {
    const m = req.method === r.method && url.pathname.match(r.pattern);
    if (!m) continue;
    try {
      return send(res, 200, await r.handler(req, m));
    } catch (e) {
      if (e instanceof HttpError) return send(res, e.status, { error: e.message });
      console.error(e);
      return send(res, 500, { error: 'خطای داخلی سرور' });
    }
  }
  send(res, 404, { error: 'مسیر پیدا نشد' });
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`سرور ارسال بسته روی پورت ${PORT} اجرا شد`);
  for (const list of Object.values(require('node:os').networkInterfaces()))
    for (const a of list || []) if (a.family === 'IPv4' && !a.internal)
      console.log(`  آدرس برای وارد کردن در اپ:  http://${a.address}:${PORT}`);
});
