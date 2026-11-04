package ir.ersalbaste;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * نگهداری اطلاعات ورود کاربر روی گوشی (SharedPreferences).
 * توکن بعد از ورود اینجا ذخیره می‌شود تا کاربر هر بار مجبور به ورود دوباره نباشد.
 */
public final class Session {
    private static final String PREF = "ersal_session";
    /** سرور آنلاین (Supabase). برای دمو روی لپ‌تاپ، در «تنظیمات سرور» آدرس محلی وارد شود. */
    public static final String DEFAULT_SERVER = "https://qbmtrekzehzjtoanibfu.supabase.co/functions/v1";
    private static final String OLD_DEFAULT = "http://192.168.1.100:3000";

    private final SharedPreferences p;

    public Session(Context c) {
        p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public String server() {
        String s = p.getString("server", DEFAULT_SERVER);
        return (s == null || s.isEmpty() || OLD_DEFAULT.equals(s)) ? DEFAULT_SERVER : s;
    }
    public void setServer(String s) {
        s = s.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://" + s;
        p.edit().putString("server", s).apply();
    }

    public String token() { return p.getString("token", null); }
    public String role() { return p.getString("role", "user"); }
    public String name() { return p.getString("name", ""); }
    public String phone() { return p.getString("phone", ""); }
    public boolean loggedIn() { return token() != null; }
    public boolean isDriver() { return "driver".equals(role()); }


    /** پاسخ سرور بعد از ورود/ثبت‌نام: {token, user:{name, phone, role}} */
    public void save(JSONObject res) {
        JSONObject u = res.optJSONObject("user");
        p.edit()
                .putString("token", res.optString("token"))
                .putString("role", u != null ? u.optString("role") : "user")
                .putString("name", u != null ? u.optString("name") : "")
                .putString("phone", u != null ? u.optString("phone") : "")
                .apply();
    }

    public void clear() {
        p.edit().remove("token").remove("role").remove("name").remove("phone").apply();
    }
}
