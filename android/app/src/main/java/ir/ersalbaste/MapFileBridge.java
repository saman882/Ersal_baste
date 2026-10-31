package ir.ersalbaste;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * پل خواندن فایل نقشه‌ی آفلاین تهران (assets/map/tehran.pmtiles) برای جاوااسکریپت.
 *
 * فرمت PMTiles یک فایل واحد است که کاشی‌های برداری نقشه را با یک فهرست (directory) نگه می‌دارد.
 * کتابخانه‌ی نقشه برای هر کاشی فقط «بازه‌ی بایت» لازم را می‌خواهد: read(offset, length).
 * این کلاس همان بازه را از فایل می‌خواند و به صورت Base64 (رشته) به جاوااسکریپت برمی‌گرداند.
 */
public final class MapFileBridge {
    private static final String ASSET = "map/tehran.pmtiles";
    private static FileChannel channel;   // یک بار باز می‌شود و بین همه‌ی نقشه‌ها مشترک است
    private static long base;             // محل شروع فایل داخل APK

    private final Context context;

    public MapFileBridge(Context c) { context = c; }

    @JavascriptInterface
    public String read(double offset, double length) {
        try {
            FileChannel ch = open(context);
            ByteBuffer buf = ByteBuffer.allocate((int) length);
            long pos = base + (long) offset;
            while (buf.hasRemaining()) {
                int n = ch.read(buf, pos + buf.position());
                if (n < 0) break;
            }
            return Base64.encodeToString(buf.array(), 0, buf.position(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * فایل نقشه فشرده‌نشده داخل APK قرار می‌گیرد (noCompress در build.gradle)،
     * پس می‌توان مستقیم و بدون کپی از روی APK خواند. اگر به هر دلیل ممکن نبود،
     * یک بار در حافظه‌ی داخلی کپی می‌شود.
     */
    private static synchronized FileChannel open(Context c) throws Exception {
        if (channel != null) return channel;
        try {
            AssetFileDescriptor fd = c.getAssets().openFd(ASSET);
            channel = fd.createInputStream().getChannel();
            base = fd.getStartOffset();
        } catch (Exception compressed) {
            File f = new File(c.getFilesDir(), "tehran.pmtiles");
            if (!f.exists()) {
                File tmp = new File(c.getFilesDir(), "tehran.pmtiles.tmp");
                try (InputStream in = c.getAssets().open(ASSET); FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] b = new byte[1 << 16];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                }
                if (!tmp.renameTo(f)) throw new java.io.IOException("rename failed");
            }
            channel = new RandomAccessFile(f, "r").getChannel();
            base = 0;
        }
        return channel;
    }
}
