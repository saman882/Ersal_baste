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
                     CHECK (status IN ('pending','accepted','rejected','canceled')),
    origin_lat REAL NOT NULL, origin_lng REAL NOT NULL,
    dest_lat   REAL NOT NULL, dest_lng   REAL NOT NULL,
    sender_name TEXT NOT NULL, sender_phone TEXT NOT NULL, sender_address TEXT NOT NULL,
    sender_plaque TEXT, sender_unit TEXT,
    receiver_name TEXT NOT NULL, receiver_phone TEXT NOT NULL, receiver_address TEXT NOT NULL,
    receiver_plaque TEXT, receiver_unit TEXT,
    note        TEXT,
    created_at  INTEGER NOT NULL,
    decided_at  INTEGER
  );
  CREATE INDEX IF NOT EXISTS idx_orders_status_created ON orders(status, created_at);
`);

// ---------- ۲) رمز عبور: هش با scrypt + نمک تصادفی ----------
function hashPassword(password, salt = crypto.randomBytes(16).toString('hex')) {
  const hash = crypto.scryptSync(password, salt, 64).toString('hex');
  return { hash, salt };
}
function checkPassword(password, user) {
  const { hash } = hashPassword(password, user.pass_salt);
  return crypto.timingSafeEqual(Buffer.from(hash, 'hex'), Buffer.from(user.pass_hash, 'hex'));
}

// ---------- ۳) حذف سفارش‌های منقضی (بیش از یک ساعت بدون تایید) ----------
function deleteExpired() {
  const r = db.prepare(`DELETE FROM orders WHERE status = 'pending' AND created_at < ?`)
              .run(Date.now() - ORDER_TTL_MS);
  if (r.changes) console.log(`[cleanup] ${r.changes} سفارش منقضی حذف شد`);
}
setInterval(deleteExpired, 30 * 1000);

// ---------- ابزارهای کمکی HTTP ----------
function send(res, status, data) {
  const body = JSON.stringify(data);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Content-Type, Authorization',
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
  const rows = db.prepare(`SELECT o.*, d.name AS driver_name, d.phone AS driver_phone
      FROM orders o LEFT JOIN users d ON d.id = o.driver_id
      WHERE o.user_id = ? ORDER BY o.id DESC LIMIT 100`).all(u.id);
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
  const mine = db.prepare(`SELECT * FROM orders WHERE driver_id=? AND status='accepted' ORDER BY decided_at DESC LIMIT 50`).all(d.id);
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

route('GET', /^\/api\/health$/, async () => ({ ok: true, now: Date.now() }));

// ---------- ۵) راه‌اندازی سرور ----------
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  if (req.method === 'OPTIONS') return send(res, 204, {});
  if (req.method === 'GET' && url.pathname === '/') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    return res.end(fs.readFileSync(path.join(__dirname, 'dashboard.html')));
  }
  if (req.method === 'GET' && url.pathname === '/api/admin/orders') {
    deleteExpired();
    return send(res, 200, { now: Date.now(), ttl: ORDER_TTL_MS, orders: db.prepare(`SELECT o.id, o.status, o.sender_name, o.receiver_name, o.created_at, u.name AS user_name, d.name AS driver_name FROM orders o JOIN users u ON u.id=o.user_id LEFT JOIN users d ON d.id=o.driver_id ORDER BY o.id DESC LIMIT 100`).all() });
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
