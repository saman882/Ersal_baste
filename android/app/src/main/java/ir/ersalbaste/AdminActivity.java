package ir.ersalbaste;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * بخش مدیریت (از داخل پنل راننده).
 *
 * با کد ۱۰ رقمی مدیریت باز می‌شود و از سرور دو چیز می‌گیرد:
 *   ۱) اطلاعات کامل همه‌ی رانندگان: نام، شماره، تاریخ ثبت‌نام، تعداد سفرهای در حال ارسال، تحویل‌شده و لغوشده
 *   ۲) تاریخچه‌ی کامل سفرها: کاربر، فرستنده، گیرنده، راننده و زمان ثبت، تایید و تحویل
 *
 * کد مدیریت فقط در سرور بررسی می‌شود (اپ کد درست را نمی‌داند) و این صفحه کد را ذخیره نمی‌کند.
 */
public class AdminActivity extends Activity {
    public static final String EXTRA_CODE = "code";

    private LinearLayout col;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BLACK);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); // اطلاعات حساس: جلوگیری از اسکرین‌شات

        LinearLayout root = Ui.vbox(this);
        root.setBackgroundColor(Ui.BG);
        LinearLayout top = Ui.hbox(this);
        top.setBackgroundColor(Ui.BLACK);
        int p = Ui.dp(this, 16);
        top.setPadding(p, p, p, p);
        top.addView(Ui.text(this, "مدیریت — تاریخچه‌ی کامل", 18, true, Ui.WHITE), Ui.weight(1));
        TextView back = Ui.text(this, "بازگشت", 13, true, Ui.WHITE);
        back.setBackground(Ui.round(this, 0xFF333333, 16, 0));
        back.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6));
        back.setOnClickListener(v -> finish());
        top.addView(back);
        root.addView(top, Ui.matchWrap());

        ScrollView sv = new ScrollView(this);
        col = Ui.vbox(this);
        col.setPadding(p, p, p, p);
        sv.addView(col);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        TextView loading = Ui.text(this, "در حال دریافت اطلاعات…", 14, false, Ui.GRAY);
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(0, Ui.dp(this, 60), 0, 0);
        col.addView(loading, Ui.matchWrap());

        JSONObject body = new JSONObject();
        try { body.put("code", getIntent().getStringExtra(EXTRA_CODE)); } catch (Exception ignored) { }
        new Api(this).post("/api/admin/history", body, (res, err) -> {
            if (err != null) {
                Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            render(res);
        });
    }

    private void render(JSONObject res) {
        col.removeAllViews();
        JSONArray orders = res.optJSONArray("orders");
        JSONArray drivers = res.optJSONArray("drivers");
        if (orders == null) orders = new JSONArray();
        if (drivers == null) drivers = new JSONArray();

        // ---- خلاصه ----
        int[] n = new int[6];
        String[] keys = {"pending", "accepted", "delivered", "rejected", "canceled", "expired"};
        for (int i = 0; i < orders.length(); i++) {
            String s = orders.optJSONObject(i).optString("status");
            for (int k = 0; k < 6; k++) if (keys[k].equals(s)) n[k]++;
        }
        LinearLayout sum = Ui.card(this);
        sum.addView(Ui.text(this, "خلاصه", 15, true, Ui.BLACK));
        sum.addView(Ui.text(this, "کل سفرها: " + orders.length() + "   ·   رانندگان: " + drivers.length(), 13, false, Ui.BLACK), Ui.matchWrap(this, 6));
        sum.addView(Ui.text(this, "تحویل‌شده: " + n[2] + "   ·   در حال ارسال: " + n[1] + "   ·   در انتظار: " + n[0], 13, false, Ui.GRAY), Ui.matchWrap(this, 4));
        sum.addView(Ui.text(this, "لغو راننده: " + n[3] + "   ·   لغو کاربر: " + n[4] + "   ·   منقضی: " + n[5], 13, false, Ui.GRAY), Ui.matchWrap(this, 4));
        col.addView(sum, Ui.matchWrap());

        // ---- رانندگان ----
        col.addView(Ui.text(this, "رانندگان (" + drivers.length() + ")", 17, true, Ui.BLACK), Ui.matchWrap(this, 24));
        if (drivers.length() == 0) col.addView(Ui.text(this, "هنوز راننده‌ای ثبت‌نام نکرده است.", 13, false, Ui.GRAY), Ui.matchWrap(this, 8));
        for (int i = 0; i < drivers.length(); i++) {
            JSONObject d = drivers.optJSONObject(i);
            LinearLayout c = Ui.card(this);
            LinearLayout h = Ui.hbox(this);
            h.addView(Ui.text(this, "🛵 " + d.optString("name"), 15, true, Ui.BLACK), Ui.weight(1));
            h.addView(Ui.chip(this, "تحویل: " + d.optInt("delivered"), Ui.BLUE));
            c.addView(h);
            c.addView(Ui.text(this, "📱 " + d.optString("phone"), 13, false, Ui.BLACK), Ui.matchWrap(this, 6));
            c.addView(Ui.text(this, "تاریخ ثبت‌نام: " + Ui.date(d.optLong("created_at")), 12, false, Ui.GRAY), Ui.matchWrap(this, 4));
            c.addView(Ui.text(this, "در حال ارسال: " + d.optInt("accepted") + "   ·   تحویل‌شده: " + d.optInt("delivered")
                    + "   ·   لغو: " + d.optInt("rejected"), 12, false, Ui.GRAY), Ui.matchWrap(this, 4));
            col.addView(c, Ui.matchWrap(this, 10));
        }

        // ---- همه‌ی سفرها ----
        col.addView(Ui.text(this, "همه‌ی سفرها (" + orders.length() + ")", 17, true, Ui.BLACK), Ui.matchWrap(this, 24));
        col.addView(Ui.text(this, "همه‌ی اطلاعات سفرها اینجا می‌ماند؛ حتی سفرهای تحویل‌شده و سفارش‌هایی که در یک ساعت تایید نشدند (منقضی).", 12, false, Ui.GRAY), Ui.matchWrap(this, 4));
        for (int i = 0; i < orders.length(); i++) col.addView(orderCard(orders.optJSONObject(i)), Ui.matchWrap(this, 10));
    }

    private View orderCard(JSONObject o) {
        String st = o.optString("status");
        LinearLayout c = Ui.card(this);
        LinearLayout h = Ui.hbox(this);
        h.addView(Ui.text(this, "سفر #" + o.optInt("id"), 15, true, Ui.BLACK), Ui.weight(1));
        h.addView(Ui.chip(this, Ui.statusLabel(st), Ui.statusColor(st)));
        c.addView(h);

        line(c, "ثبت:", Ui.date(o.optLong("created_at")));
        line(c, "کاربر:", o.optString("user_name") + " — " + o.optString("user_phone"));
        line(c, "🟢 فرستنده:", o.optString("sender_name") + " — " + o.optString("sender_phone"));
        line(c, "      آدرس:", o.optString("sender_address") + extra(o, "sender"));
        line(c, "🔴 گیرنده:", o.optString("receiver_name") + " — " + o.optString("receiver_phone"));
        line(c, "      آدرس:", o.optString("receiver_address") + extra(o, "receiver"));
        if (!o.optString("note").isEmpty()) line(c, "📝 توضیحات:", o.optString("note"));
        if (!o.isNull("driver_id")) {
            line(c, "🛵 راننده:", o.optString("driver_name") + " — " + o.optString("driver_phone"));
            line(c, "rejected".equals(st) ? "زمان لغو:" : "زمان تایید:", Ui.date(o.optLong("decided_at")));
        } else if ("canceled".equals(st)) {
            line(c, "زمان لغو:", Ui.date(o.optLong("decided_at")));
        } else if ("expired".equals(st)) {
            line(c, "⏳ وضعیت:", "در یک ساعت هیچ راننده‌ای تایید نکرد");
        }
        if ("delivered".equals(st)) line(c, "✅ زمان تحویل:", Ui.date(o.optLong("delivered_at")));
        return c;
    }

    private void line(LinearLayout c, String label, String value) {
        c.addView(Ui.text(this, label + " " + value, 12.5f, false, Ui.BLACK), Ui.matchWrap(this, 4));
    }

    private String extra(JSONObject o, String who) {
        String s = "";
        if (!o.optString(who + "_plaque").isEmpty()) s += "، پلاک " + o.optString(who + "_plaque");
        if (!o.optString(who + "_unit").isEmpty()) s += "، واحد " + o.optString(who + "_unit");
        return s;
    }
}
