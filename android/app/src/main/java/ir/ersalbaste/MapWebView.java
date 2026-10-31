package ir.ersalbaste;

import android.annotation.SuppressLint;
import android.content.Context;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * نقشه‌ی آفلاین تهران.
 *
 * داده‌ی نقشه (فایل tehran.pmtiles، داده‌ی OpenStreetMap فقط برای محدوده‌ی تهران) داخل خود اپ است
 * و با MapFileBridge خوانده می‌شود؛ پس نقشه به اینترنت یا هیچ API خارجی نیاز ندارد.
 *
 * نقشه یک صفحه‌ی HTML محلی است (assets/map/map.html) که با کتابخانه‌ی Leaflet کار می‌کند
 * و داخل یک WebView نمایش داده می‌شود. هیچ GPS یا مجوز موقعیتی لازم نیست؛
 * کاربر نقشه را جابه‌جا می‌کند تا سنجاق وسط صفحه روی نقطه‌ی دلخواه قرار بگیرد.
 *
 * ارتباط جاوا ← جاوااسکریپت با evaluateJavascript انجام می‌شود:
 *   جاوا تابعی مثل getCenter() را صدا می‌زند و نتیجه را به صورت JSON دریافت می‌کند.
 */
public class MapWebView extends WebView {
    public interface PointCallback { void onPoint(double lat, double lng); }

    private boolean ready = false;
    private final List<String> pending = new ArrayList<>();

    @SuppressLint("SetJavaScriptEnabled")
    public MapWebView(Context c) {
        super(c);
        WebSettings s = getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // پل جاوا ← جاوااسکریپت: صفحه‌ی نقشه بایت‌های فایل آفلاین تهران را از اینجا می‌خواند
        addJavascriptInterface(new MapFileBridge(c.getApplicationContext()), "MapFile");
        setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                ready = true;
                for (String js : pending) evaluateJavascript(js, null);
                pending.clear();
            }
        });
        loadUrl("file:///android_asset/map/map.html");
    }

    /** اگر صفحه هنوز بارگذاری نشده، دستور در صف می‌ماند. */
    private void run(String js) {
        if (ready) evaluateJavascript(js, null);
        else pending.add(js);
    }

    /** حالت انتخاب نقطه: سنجاق ثابت وسط نقشه با رنگ مشخص (سبز = مبدا، قرمز = مقصد). */
    public void pickMode(String kind) { run("pickMode('" + kind + "')"); }

    /** حالت نمایش: سنجاق وسط مخفی می‌شود. */
    public void viewMode() { run("viewMode()"); }

    public void setMarker(String kind, double lat, double lng) {
        run(String.format(Locale.US, "setMarker('%s',%f,%f)", kind, lat, lng));
    }

    public void showRoute(double oLat, double oLng, double dLat, double dLng) {
        run(String.format(Locale.US, "showRoute(%f,%f,%f,%f)", oLat, oLng, dLat, dLng));
    }

    /** مختصات مرکز نقشه (همان نقطه‌ای که سنجاق نشان می‌دهد) را برمی‌گرداند. */
    public void getCenter(final PointCallback cb) {
        evaluateJavascript("getCenter()", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                try {
                    // مقدار برگشتی رشته‌ای JSON مثل "[35.7,51.4]" است
                    String v = value;
                    if (v.startsWith("\"")) v = new JSONArray("[" + v + "]").getString(0);
                    JSONArray a = new JSONArray(v);
                    cb.onPoint(a.getDouble(0), a.getDouble(1));
                } catch (Exception e) {
                    cb.onPoint(35.6997, 51.3380); // میدان انقلاب، در صورت خطا
                }
            }
        });
    }
}
