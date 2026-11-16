package app.dsh.running;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * 极简诊断：把启动里程碑和崩溃堆栈写到「下载/DSH-Go/logs/」，
 * 即使没有 adb / 手机操控也能事后取日志。
 */
final class Diag {

    private static final String TAG = "RunningApps";
    private static Context app;
    private static boolean installed;

    private Diag() {
    }

    static void install(Context c) {
        app = c.getApplicationContext();
        if (installed) {
            return;
        }
        installed = true;
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            save("crash@" + t.getName(), e);
            if (prev != null) {
                prev.uncaughtException(t, e);
            }
        });
    }

    static void step(String msg) {
        Log.i(TAG, msg);
        save(msg, null);
    }

    static void save(String tag, Throwable e) {
        Context c = app;
        if (c == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(tag).append('\n');
        sb.append("time=").append(new java.util.Date()).append('\n');
        sb.append("sdk=").append(Build.VERSION.SDK_INT).append(" device=").append(Build.DEVICE).append('\n');
        if (e != null) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            sb.append(sw);
        }
        write(c, sb.toString());
    }

    private static void write(Context c, String text) {
        if (Build.VERSION.SDK_INT < 29) {
            return;
        }
        try {
            ContentResolver cr = c.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME,
                    "run-" + System.currentTimeMillis() + ".txt");
            v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/DSH-Go/logs");
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) {
                return;
            }
            try (OutputStream os = cr.openOutputStream(uri)) {
                if (os != null) {
                    os.write(text.getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Throwable ignored) {
            // 诊断失败不能影响主流程
        }
    }
}
