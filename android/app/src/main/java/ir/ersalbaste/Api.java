package ir.ersalbaste;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * لایه‌ی ارتباط با سرور (REST + JSON).
 *
 * اندروید اجازه نمی‌دهد درخواست شبکه روی «نخ اصلی» (UI Thread) انجام شود، چون صفحه هنگ می‌کند.
 * پس هر درخواست در یک نخ پس‌زمینه (ExecutorService) اجرا می‌شود و نتیجه با Handler
 * به نخ اصلی برگردانده می‌شود تا بتوانیم صفحه را به‌روز کنیم.
 */
public final class Api {
    public interface Callback {
        /** دقیقاً یکی از دو پارامتر مقدار دارد: یا نتیجه، یا پیام خطا. */
        void done(JSONObject result, String error);
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Session session;

    public Api(Context c) {
        session = new Session(c);
    }

    public void get(String path, Callback cb) { call("GET", path, null, cb); }
    public void post(String path, JSONObject body, Callback cb) { call("POST", path, body, cb); }

    public void call(final String method, final String path, final JSONObject body, final Callback cb) {
        final String base = session.server();
        final String token = session.token();
        POOL.execute(() -> {
            JSONObject result = null;
            String error = null;
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) new URL(base + path).openConnection();
                con.setRequestMethod(method);
                con.setConnectTimeout(15000);
                con.setReadTimeout(25000);
                con.setRequestProperty("Accept", "application/json");
                if (token != null) con.setRequestProperty("Authorization", "Bearer " + token);
                if (body != null) {
                    con.setDoOutput(true);
                    con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    try (OutputStream os = con.getOutputStream()) {
                        os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    }
                }
                int code = con.getResponseCode();
                InputStream in = code < 400 ? con.getInputStream() : con.getErrorStream();
                String raw = in == null ? "{}" : readAll(in);
                JSONObject json = new JSONObject(raw.isEmpty() ? "{}" : raw);
                if (code < 400) result = json;
                else error = json.optString("error", "خطای سرور (" + code + ")");
            } catch (java.net.SocketTimeoutException e) {
                error = "سرور پاسخ نداد. اینترنت گوشی را بررسی کنید و دوباره تلاش کنید.";
            } catch (java.io.IOException e) {
                error = "اتصال به سرور برقرار نشد. اینترنت را بررسی کنید.\n(" + base + ")";
            } catch (Exception e) {
                error = "پاسخ نامعتبر از سرور";
            } finally {
                if (con != null) con.disconnect();
            }
            final JSONObject r = result;
            final String err = error;
            MAIN.post(() -> cb.done(r, err));
        });
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }
}
