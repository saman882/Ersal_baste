package ir.ersalbaste;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * صفحه‌ی اصلی کاربر (مشابه تپسی) با سه تب پایین:
 *   سرویس‌ها: شبکه‌ی آیکون سرویس‌ها؛ فقط «ارسال بسته» فعال است.
 *   سفارش‌ها: لیست سفارش‌های کاربر و وضعیت هر کدام (هر ۵ ثانیه به‌روز می‌شود).
 *   پروفایل:  اطلاعات حساب و خروج.
 */
public class HomeActivity extends Activity {
    public static final String EXTRA_TAB = "tab";
    private static final int REQ_NEW_ORDER = 10;

    private Session session;
    private Api api;
    private FrameLayout content;
    private final TextView[] tabs = new TextView[3];
    private int currentTab = 0;

    private LinearLayout ordersList;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (currentTab == 1) loadOrders();
            handler.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        session = new Session(this);
        api = new Api(this);
        getWindow().setStatusBarColor(Ui.TEAL);

        LinearLayout root = Ui.vbox(this);
        root.setBackgroundColor(Ui.BG);
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(bottomBar(), Ui.matchWrap());
        setContentView(root);
        showTab(getIntent().getIntExtra(EXTRA_TAB, 0));
    }

    @Override protected void onResume() { super.onResume(); handler.post(poll); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(poll); }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_NEW_ORDER && res == RESULT_OK) showTab(1);
    }

    // ---------------- نوار پایین ----------------
    private View bottomBar() {
        LinearLayout bar = Ui.hbox(this);
        bar.setBackgroundColor(Ui.WHITE);
        bar.setElevation(Ui.dp(this, 8));
        String[][] items = {{"▦", "سرویس‌ها"}, {"🧾", "سفارش‌ها"}, {"👤", "پروفایل"}};
        for (int i = 0; i < 3; i++) {
            TextView t = Ui.text(this, items[i][0] + "\n" + items[i][1], 12, true, Ui.GRAY);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            final int idx = i;
            t.setOnClickListener(v -> showTab(idx));
            tabs[i] = t;
            bar.addView(t, Ui.weight(1));
        }
        return bar;
    }

    private void showTab(int i) {
        currentTab = i;
        for (int k = 0; k < 3; k++) tabs[k].setTextColor(k == i ? Ui.BLACK : Ui.GRAY);
        content.removeAllViews();
        if (i == 0) content.addView(servicesTab());
        else if (i == 1) { content.addView(ordersTab()); loadOrders(); }
        else content.addView(profileTab());
    }

    // ---------------- تب سرویس‌ها ----------------
    private View servicesTab() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = Ui.vbox(this);
        sv.addView(col);

        // بنر بالای صفحه
        LinearLayout banner = Ui.vbox(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Ui.TEAL, Color.parseColor("#137373")});
        banner.setBackground(g);
        int p = Ui.dp(this, 22);
        banner.setPadding(p, p, p, p);
        banner.addView(Ui.text(this, "سلام " + session.name() + " 👋", 14, false, Color.parseColor("#CDEDEA")));
        banner.addView(Ui.text(this, "بسته‌ات رو بسپار به ما!", 22, true, Color.parseColor("#FFD54F")));
        banner.addView(Ui.text(this, "ارسال درون‌شهری در تهران، با تایید راننده", 14, false, Ui.WHITE));
        Button cta = Ui.button(this, "ارسال بسته  ←", Ui.WHITE, Ui.TEAL, 0);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        cp.topMargin = Ui.dp(this, 14);
        cta.setPadding(Ui.dp(this, 22), 0, Ui.dp(this, 22), 0);
        banner.addView(cta, cp);
        cta.setOnClickListener(v -> openNewOrder());
        col.addView(banner, Ui.matchWrap());

        // شبکه‌ی سرویس‌ها مثل تپسی
        String[][] services = {
                {"🚕", "تپسی"}, {"🛒", "سوپرمارکت"}, {"🛍", "فروشگاه"}, {"💊", "پزشک و دارو"},
                {"📦", "ارسال بسته"}, {"🪙", "خرید طلا"}, {"🧰", "خدمات در منزل"}, {"🎬", "فیلم‌نت"},
                {"☂", "بیمه"}, {"🛞", "گاراژ"}, {"🧳", "بین شهری"}, {"💳", "پرداخت اعتباری"},
        };
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        grid.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        int gp = Ui.dp(this, 10);
        grid.setPadding(gp, Ui.dp(this, 18), gp, gp);
        for (String[] s : services) {
            final boolean active = "ارسال بسته".equals(s[1]);
            LinearLayout cell = Ui.vbox(this);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView icon = Ui.text(this, s[0], 30, false, Ui.BLACK);
            icon.setGravity(Gravity.CENTER);
            icon.setBackground(Ui.round(this, active ? Color.parseColor("#E6F4F1") : Ui.WHITE, 16,
                    active ? Ui.TEAL : 0));
            cell.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 70), Ui.dp(this, 70)));
            TextView label = Ui.text(this, s[1], 12, active, active ? Ui.BLACK : Ui.GRAY);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 12));
            cell.addView(label, Ui.matchWrap());
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            cell.setOnClickListener(v -> {
                if (active) openNewOrder();
                else Toast.makeText(this, "این سرویس به‌زودی فعال می‌شود", Toast.LENGTH_SHORT).show();
            });
            grid.addView(cell, lp);
        }
        col.addView(grid, Ui.matchWrap());

        LinearLayout info = Ui.card(this);
        info.addView(Ui.text(this, "چطور کار می‌کند؟", 15, true, Ui.BLACK));
        info.addView(Ui.text(this, "۱. مبدا و مقصد را روی نقشه‌ی تهران مشخص کنید\n۲. مشخصات فرستنده و گیرنده را وارد کنید\n۳. درخواست برای رانندگان ارسال می‌شود\n۴. راننده تا یک ساعت فرصت تایید دارد", 13, false, Ui.GRAY));
        LinearLayout.LayoutParams ip = Ui.matchWrap(this, 4);
        ip.leftMargin = ip.rightMargin = Ui.dp(this, 16);
        ip.bottomMargin = Ui.dp(this, 16);
        col.addView(info, ip);
        return sv;
    }

    private void openNewOrder() {
        startActivityForResult(new Intent(this, NewOrderActivity.class), REQ_NEW_ORDER);
    }

    // ---------------- تب سفارش‌ها ----------------
    private View ordersTab() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = Ui.vbox(this);
        int p = Ui.dp(this, 16);
        col.setPadding(p, Ui.dp(this, 24), p, p);
        col.addView(Ui.text(this, "سفارش‌های من", 22, true, Ui.BLACK));
        ordersList = Ui.vbox(this);
        col.addView(ordersList, Ui.matchWrap());
        sv.addView(col);
        return sv;
    }

    private void loadOrders() {
        api.get("/api/orders/mine", (res, err) -> {
            if (ordersList == null || currentTab != 1) return;
            ordersList.removeAllViews();
            if (err != null) { ordersList.addView(emptyText("⚠ " + err)); return; }
            JSONArray arr = res.optJSONArray("orders");
            long now = res.optLong("now"), ttl = res.optLong("ttl");
            if (arr == null || arr.length() == 0) {
                ordersList.addView(emptyText("هنوز سفارشی ثبت نکرده‌اید.\nاز تب سرویس‌ها «ارسال بسته» را بزنید."));
                return;
            }
            for (int i = 0; i < arr.length(); i++) ordersList.addView(orderCard(arr.optJSONObject(i), now, ttl), Ui.matchWrap(this, 12));
        });
    }

    private TextView emptyText(String s) {
        TextView t = Ui.text(this, s, 14, false, Ui.GRAY);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(this, 60), 0, 0);
        return t;
    }

    private View orderCard(final JSONObject o, long now, long ttl) {
        LinearLayout c = Ui.card(this);
        String status = o.optString("status");
        LinearLayout top = Ui.hbox(this);
        top.addView(Ui.text(this, "سفارش #" + o.optInt("id"), 15, true, Ui.BLACK), Ui.weight(1));
        top.addView(Ui.chip(this, Ui.statusLabel(status), Ui.statusColor(status)));
        c.addView(top);
        c.addView(line("🟢 از: ", o.optString("sender_address")), Ui.matchWrap(this, 8));
        c.addView(line("🔴 به: ", o.optString("receiver_address")), Ui.matchWrap(this, 4));
        c.addView(line("گیرنده: ", o.optString("receiver_name") + " — " + o.optString("receiver_phone")), Ui.matchWrap(this, 4));
        if ("pending".equals(status)) {
            long left = ttl - (now - o.optLong("created_at"));
            c.addView(Ui.text(this, "⏳ زمان باقی‌مانده برای تایید راننده: " + Ui.mmss(left), 13, false, Ui.AMBER), Ui.matchWrap(this, 6));
            Button cancel = Ui.button(this, "لغو سفارش", Ui.WHITE, Ui.RED, Ui.RED);
            c.addView(cancel, Ui.matchWrap(this, 10));
            cancel.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setMessage("سفارش لغو شود؟")
                    .setPositiveButton("بله", (d, w) -> api.post("/api/orders/" + o.optInt("id") + "/cancel", new JSONObject(), (r, e) -> {
                        Toast.makeText(this, e != null ? e : "سفارش لغو شد", Toast.LENGTH_SHORT).show();
                        loadOrders();
                    }))
                    .setNegativeButton("خیر", null).show());
        } else if ("accepted".equals(status)) {
            c.addView(Ui.text(this, "🛵 راننده: " + o.optString("driver_name") + " — " + o.optString("driver_phone"), 13, true, Ui.GREEN), Ui.matchWrap(this, 6));
        }
        return c;
    }

    private TextView line(String label, String value) {
        return Ui.text(this, label + value, 13, false, Ui.BLACK);
    }

    // ---------------- تب پروفایل ----------------
    private View profileTab() {
        LinearLayout col = Ui.vbox(this);
        int p = Ui.dp(this, 16);
        col.setPadding(p, Ui.dp(this, 24), p, p);
        col.addView(Ui.text(this, "پروفایل", 22, true, Ui.BLACK));
        LinearLayout c = Ui.card(this);
        c.addView(Ui.text(this, "👤  " + session.name(), 16, true, Ui.BLACK));
        c.addView(Ui.text(this, "📱  " + session.phone(), 14, false, Ui.GRAY), Ui.matchWrap(this, 6));
        c.addView(Ui.text(this, "نوع حساب: کاربر", 14, false, Ui.GRAY), Ui.matchWrap(this, 6));
        c.addView(Ui.text(this, "سرور: " + session.server(), 12, false, Ui.GRAY), Ui.matchWrap(this, 6));
        col.addView(c, Ui.matchWrap(this, 14));
        Button logout = Ui.button(this, "خروج از حساب", Ui.WHITE, Ui.RED, Ui.RED);
        col.addView(logout, Ui.matchWrap(this, 16));
        logout.setOnClickListener(v -> logout(this, session));
        return col;
    }

    static void logout(Activity a, Session s) {
        new Api(a).post("/api/logout", new JSONObject(), (r, e) -> { });
        s.clear();
        Intent i = new Intent(a, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        a.startActivity(i);
        a.finish();
    }
}
