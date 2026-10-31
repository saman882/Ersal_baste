package ir.ersalbaste;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;

/**
 * کلاس Application که یک بار در هر «باز شدن اپ» (اجرای فرایند) ساخته می‌شود.
 *
 * اینجا دو کار انجام می‌شود:
 *   ۱) شمارنده‌ی دفعات باز شدن یکی زیاد می‌شود (countOpen).
 *   ۲) یک ناظر چرخه‌ی حیات ثبت می‌شود تا اگر اپ قفل شده باشد، هر صفحه‌ای که باز شود
 *      بلافاصله به صفحه‌ی «پایان نسخه‌ی نمایشی» هدایت شود.
 *
 * علمی: چون Application.onCreate دقیقاً یک بار در هر اجرای فرایند اجرا می‌شود (نه با چرخش
 * صفحه)، شمارش «باز شدن اپ» درست انجام می‌شود. ناظر چرخه‌ی حیات هم قفل را به‌صورت متمرکز
 * روی همه‌ی Activityها اعمال می‌کند، حتی وقتی اندروید مستقیم صفحه‌ی داخلی را بازسازی کند.
 */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        final Session session = new Session(this);
        session.countOpen();

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity a, Bundle b) {
                if (session.locked() && !(a instanceof LockActivity)) {
                    a.startActivity(new Intent(a, LockActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                    a.finish();
                }
            }

            @Override public void onActivityStarted(Activity a) { }
            @Override public void onActivityResumed(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
    }
}
