package ir.ersalbaste;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * صفحه‌ی «پایان نسخه‌ی نمایشی».
 * وقتی سقف دفعات باز شدن اپ رد شود، به جای صفحه‌ی ورود این صفحه نشان داده می‌شود.
 * پیام روشن است تا کاربر بداند این یک نسخه‌ی نمایشی بوده و گیج نشود.
 */
public class LockActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BLACK);

        LinearLayout root = Ui.vbox(this);
        root.setBackgroundColor(Ui.BG);
        root.setGravity(Gravity.CENTER);
        int p = Ui.dp(this, 32);
        root.setPadding(p, p, p, p);

        TextView icon = Ui.text(this, "📦", 56, false, Ui.BLACK);
        icon.setGravity(Gravity.CENTER);
        root.addView(icon, Ui.matchWrap());

        TextView title = Ui.text(this, "نسخه‌ی نمایشی به پایان رسید", 20, true, Ui.BLACK);
        title.setGravity(Gravity.CENTER);
        root.addView(title, Ui.matchWrap(this, 16));

        TextView body = Ui.text(this,
                "این یک نسخه‌ی نمایشی از اپلیکیشن «ارسال بسته» بود و مهلت استفاده از آن تمام شده است.\n\n"
                        + "برای دریافت نسخه‌ی کامل، با سازنده‌ی برنامه تماس بگیرید.",
                14, false, Ui.GRAY);
        body.setGravity(Gravity.CENTER);
        root.addView(body, Ui.matchWrap(this, 12));

        setContentView(root);
    }

    /** دکمه‌ی بازگشت هم اپ را نمی‌بندد تا دوباره باز نشود. */
    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }
}
