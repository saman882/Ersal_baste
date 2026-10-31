package ir.ersalbaste;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * ابزارهای ساخت رابط کاربری.
 * همه‌ی اجزای ظاهری (فیلد، دکمه، کارت) از اینجا ساخته می‌شوند تا ظاهر اپ یکدست باشد
 * و شبیه طراحی تپسی (دکمه‌ی مشکی گرد، فیلدهای با حاشیه‌ی نرم، فونت وزیرمتن) دیده شود.
 */
public final class Ui {
    public static final int BLACK = Color.parseColor("#111111");
    public static final int BG = Color.parseColor("#F5F6F8");
    public static final int WHITE = Color.WHITE;
    public static final int GRAY = Color.parseColor("#8A8F98");
    public static final int BORDER = Color.parseColor("#E3E5E8");
    public static final int BLUE = Color.parseColor("#1E6FD9");
    public static final int GREEN = Color.parseColor("#1B9E4B");
    public static final int RED = Color.parseColor("#D93025");
    public static final int AMBER = Color.parseColor("#B07800");
    public static final int TEAL = Color.parseColor("#0E5A5A");

    private static Typeface regular, bold;

    private Ui() {}

    /** فونت وزیرمتن (نسخه با ارقام فارسی) از پوشه assets بارگذاری و کش می‌شود. */
    public static Typeface font(Context c, boolean isBold) {
        if (regular == null) {
            regular = Typeface.createFromAsset(c.getAssets(), "fonts/Vazirmatn-FD-Regular.ttf");
            bold = Typeface.createFromAsset(c.getAssets(), "fonts/Vazirmatn-FD-Bold.ttf");
        }
        return isBold ? bold : regular;
    }

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    /** پس‌زمینه با گوشه‌های گرد و حاشیه‌ی اختیاری. */
    public static GradientDrawable round(Context c, int fill, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) g.setStroke(dp(c, 1), stroke);
        return g;
    }

    public static TextView text(Context c, String s, float sp, boolean isBold, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(font(c, isBold));
        t.setTextColor(color);
        t.setTextDirection(View.TEXT_DIRECTION_RTL);
        t.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    /** فیلد ورودی با برچسب بالای آن (مثل فرم «مشخصات فرستنده» تپسی). */
    public static EditText field(Context c, LinearLayout parent, String label, int inputType) {
        TextView l = text(c, label, 12, false, GRAY);
        l.setPadding(dp(c, 6), dp(c, 10), dp(c, 6), dp(c, 4));
        parent.addView(l);
        EditText e = new EditText(c);
        e.setTypeface(font(c, false));
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setTextColor(BLACK);
        e.setHintTextColor(GRAY);
        e.setInputType(inputType);
        e.setSingleLine(inputType != (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        e.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        e.setTextDirection(View.TEXT_DIRECTION_RTL);
        e.setBackground(round(c, WHITE, 26, BORDER));
        e.setPadding(dp(c, 18), dp(c, 12), dp(c, 18), dp(c, 12));
        e.setMinHeight(dp(c, 52));
        parent.addView(e, matchWrap());
        return e;
    }

    /** دکمه‌ی اصلی مشکی و گرد (مثل «ثبت و ادامه» تپسی). */
    public static Button button(Context c, String label) {
        return button(c, label, BLACK, WHITE, 0);
    }

    public static Button button(Context c, String label, int fill, int textColor, int stroke) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTypeface(font(c, true));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(textColor);
        b.setBackground(round(c, fill, 28, stroke));
        b.setStateListAnimator(null);
        b.setMinHeight(dp(c, 52));
        return b;
    }

    public static LinearLayout vbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return l;
    }

    public static LinearLayout hbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** کارت سفید با گوشه‌ی گرد. */
    public static LinearLayout card(Context c) {
        LinearLayout l = vbox(c);
        l.setBackground(round(c, WHITE, 18, BORDER));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        return l;
    }

    /** برچسب وضعیت رنگی (در انتظار / تایید شد / لغو). */
    public static TextView chip(Context c, String s, int color) {
        TextView t = text(c, s, 12, true, color);
        int bg = Color.argb(28, Color.red(color), Color.green(color), Color.blue(color));
        t.setBackground(round(c, bg, 14, 0));
        t.setPadding(dp(c, 10), dp(c, 3), dp(c, 10), dp(c, 3));
        return t;
    }

    public static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams matchWrap(Context c, int topMarginDp) {
        LinearLayout.LayoutParams p = matchWrap();
        p.topMargin = dp(c, topMarginDp);
        return p;
    }

    public static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    /** تبدیل میلی‌ثانیه‌ی باقی‌مانده به «دقیقه:ثانیه». */
    public static String mmss(long ms) {
        if (ms < 0) ms = 0;
        long m = ms / 60000, s = (ms % 60000) / 1000;
        return m + ":" + (s < 10 ? "0" : "") + s;
    }

    public static String statusLabel(String status) {
        switch (status) {
            case "pending": return "در انتظار تایید راننده";
            case "accepted": return "تایید شد";
            case "rejected": return "لغو توسط راننده";
            case "canceled": return "لغو توسط شما";
            default: return status;
        }
    }

    public static int statusColor(String status) {
        switch (status) {
            case "pending": return AMBER;
            case "accepted": return GREEN;
            default: return RED;
        }
    }
}
