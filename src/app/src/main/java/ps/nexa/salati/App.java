package ps.nexa.salati;

import android.app.Application;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

/** Keeps the last crash on disk so the next launch can show it and the user can send it to us. */
public class App extends Application {
    static final String CRASH = "last_crash.txt";

    @Override public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String info = "صلاتي " + versionName() + " · " + Build.MANUFACTURER + " " + Build.MODEL
                        + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                        + "WebView: " + MainActivity.webViewInfo() + "\n"
                        + "thread: " + t.getName() + "\n\n" + sw;
                try (FileOutputStream o = new FileOutputStream(new File(getFilesDir(), CRASH))) {
                    o.write(info.getBytes("UTF-8"));
                }
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Throwable t) { return "?"; }
    }
}
