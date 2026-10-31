package ir.ersalbaste;

import android.app.Activity;
import android.app.AlertDialog;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * صفحه‌ی راننده.
 *
 * روش کار «Polling» است: هر ۵ ثانیه از سرور می‌پرسیم «سفارش جدید داری؟»
 * اگر شناسه‌ی سفارشی را دیدیم که قبلاً ندیده بودیم → صدای اعلان پخش و گوشی ویبره می‌شود.
 * برای هر سفارش یک شمارش معکوس یک‌ساعته نمایش داده می‌شود؛ وقتی صفر شود کارت حذف می‌شود
 * (سرور هم همان لحظه سفارش را از پایگاه داده پاک می‌کند).
 */
public class DriverActivity extends Activity {
    private static final long POLL_MS = 5000;

    private Session session;
    private Api api;
    private LinearLayout pendingList, acceptedList;
    private TextView status;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<Integer> seen = new HashSet<>();
    private long serverOffset = 0;   // اختلاف ساعت گوشی با سرور
    private long ttl = 3600000;

    /** نگهداری کارت‌ها برای به‌روزرسانی شمارش معکوس هر ثانیه. */
    private static class Countdown { long createdAt; TextView label; View card; }
    private final List<Countdown> countdowns = new ArrayList<>();

    private final Runnable poll = new Runnable() {
        @Override public void run() { load(); handler.postDelayed(this, POLL_MS); }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() { updateCountdowns(); handler.postDelayed(this, 1000); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        session = new Session(this);
        api = new Api(this);
        getWindow().setStatusBarColor(Ui.BLACK);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); // صفحه در حالت آنلاین خاموش نشود

        LinearLayout root = Ui.vbox(this);
        root.setBackgroundColor(Ui.BG);

        // نوار بالا
        LinearLayout top = Ui.hbox(this);
        top.setBackgroundColor(Ui.BLACK);
        int p = Ui.dp(this, 16);
        top.setPadding(p, p, p, p);
        LinearLayout titles = Ui.vbox(this);
        titles.addView(Ui.text(this, "🛵 پنل راننده", 18, true, Ui.WHITE));
        status = Ui.text(this, "● آنلاین — در انتظار سفارش", 12, false, 0xFF7CE0A0);
        titles.addView(status);
        top.addView(titles, Ui.weight(1));
        TextView exit = Ui.text(this, "خروج", 13, true, Ui.WHITE);
        exit.setBackground(Ui.round(this, 0xFF333333, 16, 0));
        exit.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6));
        exit.setOnClickListener(v -> HomeActivity.logout(this, session));
        top.addView(exit);
        root.addView(top, Ui.matchWrap());

        ScrollView sv = new ScrollView(this);
        LinearLayout col = Ui.vbox(this);
        col.setPadding(p, p, p, p);
        sv.addView(col);
        col.addView(Ui.text(this, "سلام " + session.name() + "، سفارش‌های جدید:", 16, true, Ui.BLACK));
        pendingList = Ui.vbox(this);
        col.addView(pendingList, Ui.matchWrap());
        col.addView(Ui.text(this, "سفارش‌های تایید شده‌ی من", 16, true, Ui.BLACK), Ui.matchWrap(this, 26));
        acceptedList = Ui.vbox(this);
        col.addView(acceptedList, Ui.matchWrap());
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override protected void onResume() { super.onResume(); handler.post(poll); handler.post(tick); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(poll); handler.removeCallbacks(tick); }

    private void load() {
        api.get("/api/driver/orders", (res, err) -> {
            if (err != null) {
                status.setText("● قطع ارتباط با سرور — " + err);
                status.setTextColor(0xFFFF8A80);
                return;
            }
            status.setText("● آنلاین — در انتظار سفارش");
            status.setTextColor(0xFF7CE0A0);
            serverOffset = res.optLong("now") - System.currentTimeMillis();
            ttl = res.optLong("ttl", ttl);
            JSONArray pending = res.optJSONArray("pending");
            JSONArray mine = res.optJSONArray("mine");

            // تشخیص سفارش جدید → صدا
            boolean hasNew = false;
            for (int i = 0; pending != null && i < pending.length(); i++)
                if (seen.add(pending.optJSONObject(i).optInt("id"))) hasNew = true;
            if (hasNew) alert();

            renderPending(pending);
            renderAccepted(mine);
        });
    }

    /** پخش صدای پیش‌فرض اعلان گوشی + ویبره. */
    private void alert() {
        try {
            Ringtone r = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
            if (r != null) r.play();
        } catch (Exception ignored) { }
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) {
                if (android.os.Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE));
                else v.vibrate(500);
            }
        } catch (Exception ignored) { }
        Toast.makeText(this, "📦 سفارش جدید رسید!", Toast.LENGTH_SHORT).show();
    }

    private void renderPending(JSONArray arr) {
        pendingList.removeAllViews();
        countdowns.clear();
        if (arr == null || arr.length() == 0) {
            pendingList.addView(empty("فعلاً سفارشی نیست. به محض ثبت سفارش، صدای اعلان پخش می‌شود."));
            return;
        }
        for (int i = 0; i < arr.length(); i++) pendingList.addView(orderCard(arr.optJSONObject(i), true), Ui.matchWrap(this, 12));
        updateCountdowns();
    }

    private void renderAccepted(JSONArray arr) {
        acceptedList.removeAllViews();
        if (arr == null || arr.length() == 0) { acceptedList.addView(empty("هنوز سفارشی تایید نکرده‌اید.")); return; }
        for (int i = 0; i < arr.length(); i++) acceptedList.addView(orderCard(arr.optJSONObject(i), false), Ui.matchWrap(this, 12));
    }

    private TextView empty(String s) {
        TextView t = Ui.text(this, s, 13, false, Ui.GRAY);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 8));
        return t;
    }

    private View orderCard(final JSONObject o, boolean isPending) {
        LinearLayout c = Ui.card(this);
        LinearLayout top = Ui.hbox(this);
        top.addView(Ui.text(this, "سفارش #" + o.optInt("id"), 16, true, Ui.BLACK), Ui.weight(1));
        if (isPending) {
            TextView timer = Ui.chip(this, "", Ui.AMBER);
            top.addView(timer);
            Countdown cd = new Countdown();
            cd.createdAt = o.optLong("created_at");
            cd.label = timer;
            cd.card = c;
            countdowns.add(cd);
        } else {
            top.addView(Ui.chip(this, "تایید شده", Ui.GREEN));
        }
        c.addView(top);
        c.addView(Ui.text(this, "🟢 مبدا: " + o.optString("sender_address") + extra(o, "sender"), 13, false, Ui.BLACK), Ui.matchWrap(this, 8));
        c.addView(Ui.text(this, "      فرستنده: " + o.optString("sender_name") + " — " + o.optString("sender_phone"), 12, false, Ui.GRAY));
        c.addView(Ui.text(this, "🔴 مقصد: " + o.optString("receiver_address") + extra(o, "receiver"), 13, false, Ui.BLACK), Ui.matchWrap(this, 6));
        c.addView(Ui.text(this, "      گیرنده: " + o.optString("receiver_name") + " — " + o.optString("receiver_phone"), 12, false, Ui.GRAY));
        if (!o.optString("note").isEmpty())
            c.addView(Ui.text(this, "📝 " + o.optString("note"), 12, false, Ui.BLUE), Ui.matchWrap(this, 6));

        TextView showMap = Ui.text(this, "🗺  نمایش مسیر روی نقشه", 13, true, Ui.BLUE);
        showMap.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        showMap.setOnClickListener(v -> showMap(o));
        c.addView(showMap);

        if (isPending) {
            LinearLayout row = Ui.hbox(this);
            Button accept = Ui.button(this, "تایید سفارش");
            Button reject = Ui.button(this, "لغو", Ui.WHITE, Ui.RED, Ui.RED);
            row.addView(accept, Ui.weight(2));
            View gap = new View(this);
            row.addView(gap, new LinearLayout.LayoutParams(Ui.dp(this, 10), 1));
            row.addView(reject, Ui.weight(1));
            c.addView(row, Ui.matchWrap(this, 10));
            accept.setOnClickListener(v -> decide(o.optInt("id"), "accept"));
            reject.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setMessage("این سفارش لغو شود؟")
                    .setPositiveButton("بله، لغو شود", (d, w) -> decide(o.optInt("id"), "reject"))
                    .setNegativeButton("خیر", null).show());
        }
        return c;
    }

    private String extra(JSONObject o, String who) {
        String s = "";
        if (!o.optString(who + "_plaque").isEmpty()) s += "، پلاک " + o.optString(who + "_plaque");
        if (!o.optString(who + "_unit").isEmpty()) s += "، واحد " + o.optString(who + "_unit");
        return s;
    }

    private void decide(int id, String action) {
        api.post("/api/driver/orders/" + id + "/" + action, new JSONObject(), (res, err) -> {
            Toast.makeText(this, err != null ? err : ("accept".equals(action) ? "سفارش تایید شد ✅" : "سفارش لغو شد"), Toast.LENGTH_SHORT).show();
            load();
        });
    }

    /** شمارش معکوس: زمان باقی‌مانده = یک ساعت − (زمان فعلی سرور − زمان ثبت). */
    private void updateCountdowns() {
        long now = System.currentTimeMillis() + serverOffset;
        for (Countdown cd : countdowns) {
            long left = ttl - (now - cd.createdAt);
            if (left <= 0) {
                cd.card.setVisibility(View.GONE);   // منقضی شد
            } else {
                cd.label.setText("⏳ " + Ui.mmss(left));
                cd.label.setTextColor(left < 5 * 60000 ? Ui.RED : Ui.AMBER);
            }
        }
    }

    private void showMap(JSONObject o) {
        MapWebView mv = new MapWebView(this);
        mv.showRoute(o.optDouble("origin_lat"), o.optDouble("origin_lng"), o.optDouble("dest_lat"), o.optDouble("dest_lng"));
        LinearLayout box = Ui.vbox(this);
        box.addView(mv, new LinearLayout.LayoutParams(-1, Ui.dp(this, 380)));
        new AlertDialog.Builder(this).setView(box).setPositiveButton("بستن", null).show();
    }
}
