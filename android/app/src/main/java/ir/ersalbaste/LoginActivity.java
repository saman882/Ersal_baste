package ir.ersalbaste;

import android.app.Activity;
import android.content.Intent;
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
 * صفحه‌ی ورود و ثبت‌نام.
 * ورود فقط با «شماره موبایل + رمز عبور» است (بدون پیامک).
 * در ثبت‌نام نقش انتخاب می‌شود: کاربر (ارسال‌کننده‌ی بسته) یا راننده.
 */
public class LoginActivity extends Activity {
    private Session session;
    private boolean registerMode = false;
    private String role = "user";

    private TextView tabLogin, tabRegister, roleUser, roleDriver, title;
    private LinearLayout registerBox, serverBox;
    private EditText phone, password, name, server;
    private Button submit;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        session = new Session(this);
        if (session.loggedIn()) { openHome(); return; }
        getWindow().setStatusBarColor(Ui.BG);
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);
        LinearLayout root = Ui.vbox(this);
        int p = Ui.dp(this, 22);
        root.setPadding(p, Ui.dp(this, 48), p, p);
        scroll.addView(root);

        TextView logo = Ui.text(this, "📦", 54, false, Ui.BLACK);
        logo.setGravity(Gravity.CENTER);
        root.addView(logo, Ui.matchWrap());
        TextView appName = Ui.text(this, "ارسال بسته", 26, true, Ui.BLACK);
        appName.setGravity(Gravity.CENTER);
        root.addView(appName, Ui.matchWrap());
        TextView sub = Ui.text(this, "ارسال سریع بسته در شهر تهران", 13, false, Ui.GRAY);
        sub.setGravity(Gravity.CENTER);
        root.addView(sub, Ui.matchWrap());

        // سوییچ ورود / ثبت‌نام
        LinearLayout tabs = Ui.hbox(this);
        tabs.setBackground(Ui.round(this, Ui.WHITE, 26, Ui.BORDER));
        tabs.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        tabLogin = segment("ورود");
        tabRegister = segment("ثبت‌نام");
        tabs.addView(tabLogin, Ui.weight(1));
        tabs.addView(tabRegister, Ui.weight(1));
        root.addView(tabs, Ui.matchWrap(this, 28));
        tabLogin.setOnClickListener(v -> setMode(false));
        tabRegister.setOnClickListener(v -> setMode(true));

        LinearLayout card = Ui.card(this);
        root.addView(card, Ui.matchWrap(this, 16));
        title = Ui.text(this, "", 17, true, Ui.BLACK);
        card.addView(title);

        registerBox = Ui.vbox(this);
        card.addView(registerBox, Ui.matchWrap());
        TextView roleLabel = Ui.text(this, "نوع حساب", 12, false, Ui.GRAY);
        roleLabel.setPadding(Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 4));
        registerBox.addView(roleLabel);
        LinearLayout roles = Ui.hbox(this);
        roleUser = segment("👤  کاربر");
        roleDriver = segment("🛵  راننده");
        roles.addView(roleUser, Ui.weight(1));
        View gap = new View(this);
        roles.addView(gap, new LinearLayout.LayoutParams(Ui.dp(this, 10), 1));
        roles.addView(roleDriver, Ui.weight(1));
        registerBox.addView(roles, Ui.matchWrap());
        roleUser.setOnClickListener(v -> setRole("user"));
        roleDriver.setOnClickListener(v -> setRole("driver"));
        name = Ui.field(this, registerBox, "نام و نام خانوادگی", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);

        phone = Ui.field(this, card, "شماره موبایل", InputType.TYPE_CLASS_PHONE);
        phone.setHint("مثلاً 09121234567");
        password = Ui.field(this, card, "رمز عبور", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        submit = Ui.button(this, "");
        card.addView(submit, Ui.matchWrap(this, 20));
        submit.setOnClickListener(v -> submit());

        // تنظیم آدرس سرور (برای دمو روی شبکه‌ی وای‌فای)
        TextView serverToggle = Ui.text(this, "⚙  تنظیمات سرور", 13, true, Ui.BLUE);
        serverToggle.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 6));
        root.addView(serverToggle, Ui.matchWrap());
        serverBox = Ui.vbox(this);
        serverBox.setVisibility(View.GONE);
        root.addView(serverBox, Ui.matchWrap());
        server = Ui.field(this, serverBox, "آدرس سرور", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setTextDirection(View.TEXT_DIRECTION_LTR);
        server.setText(session.server());
        serverToggle.setOnClickListener(v -> serverBox.setVisibility(
                serverBox.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

        setMode(false);
        setRole("user");
        setContentView(scroll);
    }

    private TextView segment(String label) {
        TextView t = Ui.text(this, label, 14, true, Ui.BLACK);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(this, 11), 0, Ui.dp(this, 11));
        return t;
    }

    private void select(TextView t, boolean on) {
        t.setBackground(Ui.round(this, on ? Ui.BLACK : Ui.WHITE, 22, on ? 0 : Ui.BORDER));
        t.setTextColor(on ? Ui.WHITE : Ui.BLACK);
    }

    private void setMode(boolean reg) {
        registerMode = reg;
        select(tabLogin, !reg);
        select(tabRegister, reg);
        tabLogin.setBackground(Ui.round(this, !reg ? Ui.BLACK : Ui.WHITE, 22, 0));
        tabRegister.setBackground(Ui.round(this, reg ? Ui.BLACK : Ui.WHITE, 22, 0));
        registerBox.setVisibility(reg ? View.VISIBLE : View.GONE);
        title.setText(reg ? "ساخت حساب جدید" : "ورود به حساب");
        submit.setText(reg ? "ثبت‌نام و ورود" : "ورود");
    }

    private void setRole(String r) {
        role = r;
        select(roleUser, "user".equals(r));
        select(roleDriver, "driver".equals(r));
    }

    private void submit() {
        session.setServer(server.getText().toString().isEmpty() ? Session.DEFAULT_SERVER : server.getText().toString());
        JSONObject body = new JSONObject();
        try {
            body.put("phone", phone.getText().toString().trim());
            body.put("password", password.getText().toString());
            if (registerMode) {
                body.put("name", name.getText().toString().trim());
                body.put("role", role);
            }
        } catch (Exception ignored) { }

        submit.setEnabled(false);
        submit.setText("لطفاً صبر کنید…");
        new Api(this).post(registerMode ? "/api/register" : "/api/login", body, (res, err) -> {
            submit.setEnabled(true);
            setMode(registerMode);
            if (err != null) { Toast.makeText(this, err, Toast.LENGTH_LONG).show(); return; }
            session.save(res);
            openHome();
        });
    }

    /** بر اساس نقش، کاربر به صفحه‌ی خودش هدایت می‌شود. */
    private void openHome() {
        Class<?> target = session.isDriver() ? DriverActivity.class : HomeActivity.class;
        startActivity(new Intent(this, target));
        finish();
    }
}
