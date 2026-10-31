package ir.ersalbaste;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * ثبت سفارش «ارسال بسته» به صورت یک ماشین حالت (State Machine) با ۶ مرحله:
 *
 *   PICK_ORIGIN → SENDER_FORM → PICK_DEST → RECEIVER_FORM → SUMMARY → DONE
 *
 * بالای صفحه نقشه است و پایین صفحه یک «برگه‌ی پایینی» (bottom sheet) که محتوایش
 * با هر مرحله عوض می‌شود. دکمه‌ی بازگشت گوشی یک مرحله به عقب برمی‌گردد.
 */
public class NewOrderActivity extends Activity {
    private enum Step { PICK_ORIGIN, SENDER_FORM, PICK_DEST, RECEIVER_FORM, SUMMARY, DONE }

    private Step step = Step.PICK_ORIGIN;
    private MapWebView map;
    private LinearLayout sheet;

    // داده‌های سفارش
    private double oLat, oLng, dLat, dLng;
    private final String[] sender = new String[5];   // نام، تلفن، آدرس، پلاک، واحد
    private final String[] receiver = new String[5];
    private String note = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BLACK);
        LinearLayout root = Ui.vbox(this);
        root.setBackgroundColor(Ui.BG);

        map = new MapWebView(this);
        root.addView(map, new LinearLayout.LayoutParams(-1, 0, 1));

        ScrollView sv = new ScrollView(this);
        sv.setBackground(Ui.round(this, Ui.WHITE, 24, 0));
        sv.setElevation(Ui.dp(this, 12));
        sheet = Ui.vbox(this);
        int p = Ui.dp(this, 20);
        sheet.setPadding(p, Ui.dp(this, 10), p, p);
        sv.addView(sheet);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = -Ui.dp(this, 20); // روی نقشه کمی بالا بیاید، مثل تپسی
        root.addView(sv, sp);

        setContentView(root);
        render();
    }

    @Override
    public void onBackPressed() {
        switch (step) {
            case PICK_ORIGIN: case DONE: super.onBackPressed(); return;
            case SENDER_FORM: step = Step.PICK_ORIGIN; break;
            case PICK_DEST: step = Step.SENDER_FORM; break;
            case RECEIVER_FORM: step = Step.PICK_DEST; break;
            case SUMMARY: step = Step.RECEIVER_FORM; break;
        }
        render();
    }

    /** بر اساس مرحله‌ی فعلی، محتوای برگه‌ی پایینی و حالت نقشه را می‌سازد. */
    private void render() {
        sheet.removeAllViews();
        View handle = new View(this);
        handle.setBackground(Ui.round(this, Ui.BORDER, 3, 0));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 5));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = Ui.dp(this, 12);
        sheet.addView(handle, hp);

        switch (step) {
            case PICK_ORIGIN:
                map.pickMode("origin");
                pickSheet("مبدا را مشخص کنید", "نقشه را جابه‌جا کنید تا سنجاق سبز روی محل تحویل گرفتن بسته قرار بگیرد.", "تایید مبدا", () ->
                        map.getCenter((lat, lng) -> { oLat = lat; oLng = lng; map.setMarker("origin", lat, lng); step = Step.SENDER_FORM; render(); }));
                break;
            case SENDER_FORM:
                map.viewMode();
                formSheet("مشخصات فرستنده", "فرستنده", sender, () -> { step = Step.PICK_DEST; render(); });
                break;
            case PICK_DEST:
                map.setMarker("origin", oLat, oLng);
                map.pickMode("dest");
                pickSheet("مقصد را مشخص کنید", "نقشه را جابه‌جا کنید تا سنجاق قرمز روی محل تحویل دادن بسته قرار بگیرد.", "تایید مقصد", () ->
                        map.getCenter((lat, lng) -> { dLat = lat; dLng = lng; map.setMarker("dest", lat, lng); step = Step.RECEIVER_FORM; render(); }));
                break;
            case RECEIVER_FORM:
                map.viewMode();
                formSheet("مشخصات گیرنده", "گیرنده", receiver, () -> { step = Step.SUMMARY; render(); });
                break;
            case SUMMARY:
                map.showRoute(oLat, oLng, dLat, dLng);
                summarySheet();
                break;
            case DONE:
                doneSheet();
                break;
        }
    }

    private void header(String title) {
        LinearLayout h = Ui.hbox(this);
        h.addView(Ui.text(this, title, 18, true, Ui.BLACK), Ui.weight(1));
        TextView close = Ui.text(this, "✕", 18, false, Ui.GRAY);
        close.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 4), 0);
        close.setOnClickListener(v -> finish());
        h.addView(close);
        sheet.addView(h);
    }

    private void pickSheet(String title, String hint, String btn, Runnable onConfirm) {
        header(title);
        sheet.addView(Ui.text(this, hint, 13, false, Ui.GRAY), Ui.matchWrap(this, 6));
        Button b = Ui.button(this, btn);
        b.setOnClickListener(v -> onConfirm.run());
        sheet.addView(b, Ui.matchWrap(this, 18));
    }

    /** فرم فرستنده/گیرنده دقیقاً مثل فرم تپسی: نام، شماره همراه، آدرس، پلاک و واحد. */
    private void formSheet(String title, final String who, final String[] data, final Runnable next) {
        header(title);
        final EditText name = Ui.field(this, sheet, "نام " + who, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        final EditText phone = Ui.field(this, sheet, "شماره همراه " + who, InputType.TYPE_CLASS_PHONE);
        final EditText address = Ui.field(this, sheet, "آدرس " + who, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS);
        LinearLayout row = Ui.hbox(this);
        LinearLayout c1 = Ui.vbox(this), c2 = Ui.vbox(this);
        final EditText plaque = Ui.field(this, c1, "پلاک", InputType.TYPE_CLASS_NUMBER);
        final EditText unit = Ui.field(this, c2, "واحد", InputType.TYPE_CLASS_NUMBER);
        row.addView(c1, Ui.weight(1));
        View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams(Ui.dp(this, 12), 1));
        row.addView(c2, Ui.weight(1));
        sheet.addView(row, Ui.matchWrap());

        if ("فرستنده".equals(who) && data[0] == null) {
            data[0] = new Session(this).name();   // پیش‌فرض: خود کاربر فرستنده است
            data[1] = new Session(this).phone();
        }
        EditText[] fields = {name, phone, address, plaque, unit};
        for (int i = 0; i < 5; i++) if (data[i] != null) fields[i].setText(data[i]);

        Button b = Ui.button(this, "ثبت و ادامه");
        sheet.addView(b, Ui.matchWrap(this, 22));
        b.setOnClickListener(v -> {
            for (int i = 0; i < 5; i++) data[i] = fields[i].getText().toString().trim();
            data[1] = Ui.toEn(data[1]);   // شماره همراه
            data[3] = Ui.toEn(data[3]);   // پلاک
            data[4] = Ui.toEn(data[4]);   // واحد
            if (data[0].isEmpty() || data[2].isEmpty()) { toast("نام و آدرس " + who + " را وارد کنید"); return; }
            if (!data[1].matches("0\\d{10}")) { toast("شماره همراه " + who + " باید ۱۱ رقم و با ۰ شروع شود"); return; }
            next.run();
        });
    }

    private void summarySheet() {
        header("تایید نهایی درخواست");
        LinearLayout box = Ui.vbox(this);
        box.setBackground(Ui.round(this, Ui.BG, 16, 0));
        int p = Ui.dp(this, 14);
        box.setPadding(p, p, p, p);
        box.addView(Ui.text(this, "🟢 مبدا: " + sender[2] + plaqueUnit(sender), 13, false, Ui.BLACK));
        box.addView(Ui.text(this, "      فرستنده: " + sender[0] + " — " + sender[1], 12, false, Ui.GRAY));
        box.addView(Ui.text(this, "🔴 مقصد: " + receiver[2] + plaqueUnit(receiver), 13, false, Ui.BLACK), Ui.matchWrap(this, 10));
        box.addView(Ui.text(this, "      گیرنده: " + receiver[0] + " — " + receiver[1], 12, false, Ui.GRAY));
        sheet.addView(box, Ui.matchWrap(this, 8));
        final EditText noteField = Ui.field(this, sheet, "توضیحات برای راننده (اختیاری)", InputType.TYPE_CLASS_TEXT);
        noteField.setText(note);
        final Button b = Ui.button(this, "ثبت درخواست ارسال بسته");
        sheet.addView(b, Ui.matchWrap(this, 18));
        b.setOnClickListener(v -> {
            note = noteField.getText().toString().trim();
            b.setEnabled(false);
            b.setText("در حال ارسال…");
            new Api(this).post("/api/orders", buildOrder(), (res, err) -> {
                if (err != null) { toast(err); b.setEnabled(true); b.setText("ثبت درخواست ارسال بسته"); return; }
                step = Step.DONE;
                render();
            });
        });
    }

    private void doneSheet() {
        TextView ok = Ui.text(this, "✅", 44, false, Ui.BLACK);
        ok.setGravity(Gravity.CENTER);
        sheet.addView(ok, Ui.matchWrap());
        TextView t = Ui.text(this, "درخواست شما ثبت شد", 18, true, Ui.BLACK);
        t.setGravity(Gravity.CENTER);
        sheet.addView(t, Ui.matchWrap());
        TextView s = Ui.text(this, "درخواست برای رانندگان ارسال شد. اگر تا یک ساعت تایید نشود، به‌صورت خودکار حذف می‌شود.", 13, false, Ui.GRAY);
        s.setGravity(Gravity.CENTER);
        sheet.addView(s, Ui.matchWrap(this, 6));
        Button b = Ui.button(this, "پیگیری در سفارش‌ها");
        sheet.addView(b, Ui.matchWrap(this, 18));
        b.setOnClickListener(v -> { setResult(RESULT_OK); finish(); });
    }

    private String plaqueUnit(String[] d) {
        String s = "";
        if (d[3] != null && !d[3].isEmpty()) s += "، پلاک " + d[3];
        if (d[4] != null && !d[4].isEmpty()) s += "، واحد " + d[4];
        return s;
    }

    /** ساخت بدنه‌ی JSON درخواست طبق قرارداد API سرور. */
    private JSONObject buildOrder() {
        JSONObject o = new JSONObject();
        try {
            o.put("origin_lat", oLat).put("origin_lng", oLng).put("dest_lat", dLat).put("dest_lng", dLng);
            o.put("sender", person(sender));
            o.put("receiver", person(receiver));
            o.put("note", note);
        } catch (Exception ignored) { }
        return o;
    }

    private JSONObject person(String[] d) throws org.json.JSONException {
        return new JSONObject().put("name", d[0]).put("phone", d[1]).put("address", d[2])
                .put("plaque", d[3]).put("unit", d[4]);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
