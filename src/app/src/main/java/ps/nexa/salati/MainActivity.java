package ps.nexa.salati;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String BASE = "https://" + HOST + "/";
    private static final int REQ_LOC = 51, REQ_NOTIF = 52;

    private static WeakReference<MainActivity> live = new WeakReference<>(null);

    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean locSilent = false, locBusy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bars();
        try {
            android.graphics.Bitmap ic = android.graphics.BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher);
            setTaskDescription(new android.app.ActivityManager.TaskDescription("صلاتي", ic, Color.parseColor("#13294B")));
        } catch (Exception ignored) { }

        String crash = readCrash();
        if (crash == null) crash = lastExit();
        if (crash != null) { safeScreen(crash); return; }
        start();
    }

    private void start() {
        getSharedPreferences("boot", MODE_PRIVATE).edit().putLong("launching", System.currentTimeMillis()).commit();
        try {
            web = new WebView(this);
        } catch (Throwable t) {
            fallback("لا يمكن تشغيل صلاتي على هذا الجهاز لأن مكوّن «Android System WebView» غير مثبّت أو معطّل.\n\n"
                    + "فعّله أو حدّثه من: الإعدادات ← التطبيقات ← Android System WebView، ثم افتح صلاتي من جديد.",
                    stack(t));
            return;
        }
        if (getSharedPreferences("boot", MODE_PRIVATE).getBoolean("soft", false)) web.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        web.setBackgroundColor(Color.parseColor(dark() ? "#0A0F1A" : "#F3F4F7"));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return serve(request.getUrl());
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail d) {
                // the page's process died (not our code): keep the app alive, remember why, and show it next time
                saveReport("WebView renderer gone · didCrash=" + (Build.VERSION.SDK_INT >= 26 && d != null && d.didCrash())
                        + " · priority=" + (Build.VERSION.SDK_INT >= 26 && d != null ? d.rendererPriorityAtExit() : -1));
                try { if (web != null) { ((android.view.ViewGroup) web.getParent()).removeView(web); web.destroy(); } } catch (Throwable ignored) { }
                web = null;
                main.post(() -> safeScreen(readCrash()));
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                getSharedPreferences("boot", MODE_PRIVATE).edit().putLong("launching", 0).apply();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                openExternal(u.toString());
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        // edge-to-edge (enforced from Android 15): colour the system-bar areas ourselves and keep the page between them
        topBar = new android.widget.FrameLayout(this);
        bottomBar = new android.widget.FrameLayout(this);
        bottomBar.addView(web, new android.widget.FrameLayout.LayoutParams(-1, -1));
        topBar.addView(bottomBar, new android.widget.FrameLayout.LayoutParams(-1, -1));
        setContentView(topBar);
        bars();
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            topBar.setOnApplyWindowInsetsListener((v, in) -> {
                android.graphics.Insets b = in.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = in.getInsets(android.view.WindowInsets.Type.ime());
                topBar.setPadding(b.left, b.top, b.right, 0);
                bottomBar.setPadding(0, 0, 0, Math.max(b.bottom, ime.bottom));
                return android.view.WindowInsets.CONSUMED;
            });
        }
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
        web.loadUrl(BASE + "index.html");
        live = new WeakReference<>(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Engine.screenOn = true;
        live = new WeakReference<>(this);
        new Thread(() -> {
            try { new Engine(this).refresh(false, false); } catch (Throwable ignored) { }
        }).start();
        js("window.onResumeApp && onResumeApp()");
    }

    @Override
    protected void onPause() {
        super.onPause();
        Engine.screenOn = false;
        compassStop();
        js("window.onPauseApp && onPauseApp()");
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        bars();
        js("window.applyTheme && applyTheme()");
    }

    private String readCrash() {
        try {
            java.io.File f = new java.io.File(getFilesDir(), App.CRASH);
            if (!f.exists()) return null;
            byte[] b = new byte[(int) Math.min(f.length(), 20000)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { int n = in.read(b); f.delete(); return new String(b, 0, Math.max(n, 0), "UTF-8"); }
        } catch (Throwable t) { return null; }
    }

    /** Why did the last run end? Catches what our own handler cannot (native/WebView crashes, the system killing us at start). */
    private String lastExit() {
        android.content.SharedPreferences bp = getSharedPreferences("boot", MODE_PRIVATE);
        long launching = bp.getLong("launching", 0);
        StringBuilder r = new StringBuilder();
        boolean bad = false;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                List<android.app.ApplicationExitInfo> l = am.getHistoricalProcessExitReasons(getPackageName(), 0, 3);
                long seen = bp.getLong("exitSeen", 0);
                if (l != null && !l.isEmpty()) {
                    android.app.ApplicationExitInfo e = l.get(0);
                    if (e.getTimestamp() > seen) {
                        bp.edit().putLong("exitSeen", e.getTimestamp()).commit();
                        int why = e.getReason();
                        boolean crashy = why == android.app.ApplicationExitInfo.REASON_CRASH
                                || why == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE
                                || why == android.app.ApplicationExitInfo.REASON_ANR
                                || why == android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE;
                        if (crashy || launching > 0) {
                            bad = true;
                            r.append("exit: reason=").append(why).append(" (").append(reasonName(why)).append(")")
                                    .append(" status=").append(e.getStatus())
                                    .append(" importance=").append(e.getImportance())
                                    .append(" pss=").append(e.getPss() / 1024).append("MB")
                                    .append(" process=").append(e.getProcessName()).append('\n')
                                    .append("desc: ").append(e.getDescription()).append('\n');
                            if (launching > 0) r.append("died before the page finished loading\n");
                            r.append(trace(e));
                        }
                    }
                }
            }
        } catch (Throwable t) { r.append("exit info error: ").append(t).append('\n'); }
        if (!bad && launching > 0) { bad = true; r.append("the app stopped while starting (no system details on this Android)\n"); }
        bp.edit().putLong("launching", 0).commit();
        if (!bad) return null;
        String v = "?";
        try { v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable ignored) { }
        return "صلاتي " + v + " · " + Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")\nWebView: " + webViewInfo()
                + (bp.getBoolean("soft", false) ? " · soft" : "") + "\n\n" + r;
    }

    private static String reasonName(int r) {
        switch (r) {
            case 4: return "CRASH"; case 5: return "CRASH_NATIVE"; case 6: return "ANR"; case 7: return "INIT_FAILURE";
            case 2: return "SIGNALED"; case 3: return "LOW_MEMORY"; case 1: return "EXIT_SELF"; case 9: return "EXCESSIVE_RESOURCE";
            case 10: return "USER_REQUESTED"; case 11: return "USER_STOPPED"; case 12: return "DEPENDENCY_DIED"; case 13: return "OTHER";
            case 8: return "PERMISSION_CHANGE"; case 14: return "FREEZER"; case 15: return "PACKAGE_STATE_CHANGE"; case 16: return "PACKAGE_UPDATED";
            default: return "UNKNOWN";
        }
    }

    /** readable text from the system's crash trace (a tombstone is binary: keep the words) */
    private static String trace(android.app.ApplicationExitInfo e) {
        if (Build.VERSION.SDK_INT < 30) return "";
        try (InputStream in = e.getTraceInputStream()) {
            if (in == null) return "";
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n, tot = 0;
            while ((n = in.read(b)) > 0 && tot < 400000) { bo.write(b, 0, n); tot += n; }
            byte[] d = bo.toByteArray();
            StringBuilder out = new StringBuilder("\ntrace:\n"), w = new StringBuilder();
            for (byte x : d) {
                int c = x & 0xff;
                if (c >= 32 && c < 127) w.append((char) c);
                else { if (w.length() >= 6) { out.append(w).append('\n'); if (out.length() > 6000) break; } w.setLength(0); }
            }
            if (w.length() >= 6 && out.length() <= 6000) out.append(w);
            return out.toString();
        } catch (Throwable t) { return "\ntrace error: " + t; }
    }

    static String webViewInfo() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                android.content.pm.PackageInfo p = WebView.getCurrentWebViewPackage();
                if (p != null) return p.packageName + " " + p.versionName;
            }
        } catch (Throwable ignored) { }
        return "?";
    }

    private void saveReport(String what) {
        try {
            String v = "?";
            try { v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable ignored) { }
            String info = "صلاتي " + v + " · " + Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE
                    + " (API " + Build.VERSION.SDK_INT + ")\nWebView: " + webViewInfo() + "\n\n" + what;
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(getFilesDir(), App.CRASH))) { o.write(info.getBytes("UTF-8")); }
        } catch (Throwable ignored) { }
    }

    /** shown instead of the app right after a crash: lets the user send us the report, then carry on */
    private void safeScreen(String report) {
        if (report == null) report = "";
        final String rep = report;
        android.widget.LinearLayout l = new android.widget.LinearLayout(this);
        l.setOrientation(android.widget.LinearLayout.VERTICAL);
        l.setGravity(android.view.Gravity.CENTER);
        int p = (int) (24 * getResources().getDisplayMetrics().density);
        l.setPadding(p, p, p, p);
        l.setBackgroundColor(Color.parseColor("#13294B"));
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText("توقّف صلاتي في المرة السابقة.\nنعتذر عن ذلك. أرسل التقرير إلى المطوّر ليصلح المشكلة، ثم افتح التطبيق.");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(17);
        tv.setGravity(android.view.Gravity.CENTER);
        l.addView(tv);
        android.widget.Button b = new android.widget.Button(this);
        b.setText("إرسال التقرير إلى المطوّر");
        b.setOnClickListener(v -> shareReport(rep));
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = p;
        l.addView(b, lp);
        android.widget.Button go = new android.widget.Button(this);
        go.setText("افتح التطبيق");
        go.setOnClickListener(v -> {
            // stopped while starting: try again without the graphics card (some phones crash in it)
            if (rep.contains("before the page") || rep.contains("while starting") || rep.contains("CRASH_NATIVE") || rep.contains("renderer gone"))
                getSharedPreferences("boot", MODE_PRIVATE).edit().putBoolean("soft", true).commit();
            start();
        });
        l.addView(go, new android.widget.LinearLayout.LayoutParams(-2, -2));
        android.widget.TextView small = new android.widget.TextView(this);
        small.setText(rep.length() > 600 ? rep.substring(0, 600) + "…" : rep);
        small.setTextColor(Color.parseColor("#B3FFFFFF"));
        small.setTextSize(11);
        small.setTextIsSelectable(true);
        android.widget.LinearLayout.LayoutParams lp2 = new android.widget.LinearLayout.LayoutParams(-1, -2);
        lp2.topMargin = p;
        l.addView(small, lp2);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(Color.parseColor("#13294B"));
        sv.addView(l);
        setContentView(sv);
    }

    private static String stack(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return "صلاتي · " + Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")\n\n" + sw;
    }

    private void shareReport(String report) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "تقرير خطأ صلاتي").putExtra(Intent.EXTRA_TEXT, report);
            startActivity(Intent.createChooser(i, "إرسال التقرير"));
        } catch (Throwable ignored) { }
    }

    private void crashDialog(String report) {
        try {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("توقّف صلاتي في المرة السابقة")
                    .setMessage("نعتذر عن ذلك. أرسل التقرير إلى المطوّر ليصلح المشكلة.")
                    .setPositiveButton("إرسال التقرير", (d, w) -> shareReport(report))
                    .setNegativeButton("لاحقًا", null).show();
        } catch (Throwable ignored) { }
    }

    private void fallback(String msg, String report) {
        android.widget.LinearLayout l = new android.widget.LinearLayout(this);
        l.setOrientation(android.widget.LinearLayout.VERTICAL);
        l.setGravity(android.view.Gravity.CENTER);
        int p = (int) (24 * getResources().getDisplayMetrics().density);
        l.setPadding(p, p, p, p);
        l.setBackgroundColor(Color.parseColor("#13294B"));
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(msg);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(17);
        tv.setGravity(android.view.Gravity.CENTER);
        l.addView(tv);
        android.widget.Button b = new android.widget.Button(this);
        b.setText("إرسال التقرير إلى المطوّر");
        b.setOnClickListener(v -> shareReport(report));
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = p;
        l.addView(b, lp);
        android.widget.Button s = new android.widget.Button(this);
        s.setText("فتح إعدادات WebView");
        s.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:com.google.android.webview"))); }
            catch (Throwable t) { try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Throwable ignored) { } }
        });
        l.addView(s, new android.widget.LinearLayout.LayoutParams(-2, -2));
        setContentView(l);
    }

    boolean dark() {
        return (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private android.widget.FrameLayout topBar, bottomBar;

    /** colours the status / navigation bars; never allowed to take the app down (it did on some Android 13 phones) */
    private void bars() {
        try { barsUnsafe(); } catch (Throwable ignored) { }
    }

    private void barsUnsafe() {
        getWindow().getDecorView();   // make sure the window's views exist before asking for its controller
        boolean d = dark();
        int top = Color.parseColor(d ? "#0B1830" : "#13294B"), bot = Color.parseColor(d ? "#121A2A" : "#FFFFFF");
        if (topBar != null) topBar.setBackgroundColor(top);
        if (bottomBar != null) bottomBar.setBackgroundColor(bot);
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) c.setSystemBarsAppearance(d ? 0 : android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            if (Build.VERSION.SDK_INT < 35) {
                getWindow().setStatusBarColor(top);
                getWindow().setNavigationBarColor(bot);
            }
            return;
        }
        getWindow().setStatusBarColor(top);
        getWindow().setNavigationBarColor(bot);
        if (Build.VERSION.SDK_INT >= 26) {
            View v = getWindow().getDecorView();
            int f = v.getSystemUiVisibility();
            if (d) f &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR; else f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            v.setSystemUiVisibility(f);
        }
    }

    static void audioChanged(String json) {
        MainActivity a = live.get();
        if (a != null) a.js("window.onAudio && onAudio(" + json + ")");
    }

    /** called from the receiver when something changed in the background */
    static void notifyChanged() {
        MainActivity a = live.get();
        if (a != null) a.js("window.onChanged && onChanged()");
    }

    private void js(String code) {
        main.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    /* ---------- serving assets ---------- */
    private WebResourceResponse serve(Uri u) {
        if (u == null || !HOST.equals(u.getHost())) return null;
        String path = u.getPath() == null ? "/" : u.getPath();
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-store");
        try {
            String file = path.startsWith("/") ? path.substring(1) : path;
            if (file.isEmpty()) file = "index.html";
            InputStream in = getAssets().open(file);
            String m = mime(file);
            return new WebResourceResponse(m, m.startsWith("text") || m.contains("javascript") || m.contains("json") ? "utf-8" : null,
                    200, "OK", headers, in);
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", headers,
                    new java.io.ByteArrayInputStream(new byte[0]));
        }
    }

    private static String mime(String file) {
        if (file.endsWith(".html")) return "text/html";
        if (file.endsWith(".js")) return "application/javascript";
        if (file.endsWith(".json")) return "application/json";
        if (file.endsWith(".css")) return "text/css";
        if (file.endsWith(".ttf")) return "font/ttf";
        if (file.endsWith(".svg")) return "image/svg+xml";
        String ext = MimeTypeMap.getFileExtensionFromUrl(file);
        String m = ext == null ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m == null ? "application/octet-stream" : m;
    }

    private void openExternal(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
    }

    private boolean tryStart(Intent i) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true; } catch (Exception e) { return false; }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() { handleBack(); }

    private void handleBack() {
        if (web == null) { finish(); return; }
        web.evaluateJavascript("(window.appBack && window.appBack()) ? 'y' : 'n'", value -> {
            if (value == null || !value.contains("y")) finish();
        });
    }

    /* ---------- location ---------- */
    private boolean hasLoc() {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean locOn() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        try {
            if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
            return lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) || lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception e) { return false; }
    }

    private void locate(boolean silent) {
        if (locBusy) return;
        locSilent = silent;
        if (!hasLoc()) {
            if (silent) return;
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOC);
            return;
        }
        if (!locOn()) { if (!silent) locDone(false, "off", null); return; }
        locBusy = true;
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException ignored) { }
        if (best != null && System.currentTimeMillis() - best.getTime() < 15 * 60000L) { gotLocation(best); return; }

        final Location fallback = best;
        final boolean[] done = {false};
        final LocationListener[] holder = new LocationListener[1];
        LocationListener ll = new LocationListener() {
            @Override public void onLocationChanged(Location l) {
                if (done[0]) return;
                done[0] = true;
                try { lm.removeUpdates(this); } catch (Exception ignored) { }
                gotLocation(l);
            }
            @Override public void onStatusChanged(String p, int s, Bundle b) { }
            @Override public void onProviderEnabled(String p) { }
            @Override public void onProviderDisabled(String p) { }
        };
        holder[0] = ll;
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, ll, Looper.getMainLooper());
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, ll, Looper.getMainLooper());
        } catch (SecurityException e) {
            locBusy = false;
            locDone(false, "perm", null);
            return;
        }
        main.postDelayed(() -> {
            if (done[0]) return;
            done[0] = true;
            try { lm.removeUpdates(holder[0]); } catch (Exception ignored) { }
            if (fallback != null) gotLocation(fallback);
            else { locBusy = false; locDone(false, "fail", null); }
        }, 30000);
    }

    private void gotLocation(Location l) {
        final double lat = l.getLatitude(), lng = l.getLongitude();
        new Thread(() -> {
            try {
                Store st = new Store(this);
                JSONObject old = st.loc();
                TimeZone.setDefault(null);
                String tz = TimeZone.getDefault().getID();
                // a silent refresh only matters if we actually moved or the time zone changed
                if (locSilent && "auto".equals(old.optString("mode"))
                        && dist(lat, lng, old.optDouble("lat"), old.optDouble("lng")) < 5
                        && tz.equals(old.optString("tz"))) {
                    locBusy = false;
                    return;
                }
                String name = null, cc = null;
                try {
                    if (Geocoder.isPresent()) {
                        List<Address> r = new Geocoder(this, new Locale("ar")).getFromLocation(lat, lng, 1);
                        if (r != null && !r.isEmpty()) {
                            Address a = r.get(0);
                            name = a.getLocality();
                            if (name == null) name = a.getSubAdminArea();
                            if (name == null) name = a.getAdminArea();
                            cc = a.getCountryCode();
                        }
                    }
                } catch (Exception ignored) { }
                JSONObject near = nearestCity(lat, lng);
                if (near != null) {
                    if (cc == null) cc = near.optString("cc");
                    if (name == null) name = near.optDouble("km") < 30 ? near.optString("name") : "قرب " + near.optString("name");
                }
                if (name == null) name = "موقعك";
                JSONObject loc = new JSONObject()
                        .put("name", name).put("cc", cc == null ? "" : cc)
                        .put("lat", lat).put("lng", lng).put("tz", tz).put("mode", "auto").put("at", System.currentTimeMillis());
                st.put("loc", loc);
                new Engine(this).refresh(false, true);
                locBusy = false;
                locDone(true, null, name);
            } catch (Exception e) {
                locBusy = false;
                locDone(false, "fail", null);
            }
        }).start();
    }

    private void locDone(boolean ok, String err, String name) {
        try {
            JSONObject o = new JSONObject().put("ok", ok).put("silent", locSilent);
            if (err != null) o.put("err", err);
            if (name != null) o.put("name", name);
            js("window.onLoc && onLoc(" + o + ")");
        } catch (Exception ignored) { }
    }

    static double dist(double a1, double o1, double a2, double o2) {
        double r = 6371, dLat = Math.toRadians(a2 - a1), dLon = Math.toRadians(o2 - o1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(a1)) * Math.cos(Math.toRadians(a2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(h));
    }

    private JSONObject nearestCity(double lat, double lng) {
        try (InputStream in = getAssets().open("cities.json")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            JSONArray arr = new JSONObject(bo.toString("UTF-8")).getJSONArray("cities");
            JSONArray best = null; double bd = 1e9;
            for (int i = 0; i < arr.length(); i++) {
                JSONArray c = arr.getJSONArray(i);
                double d = dist(lat, lng, c.getDouble(2), c.getDouble(3));
                if (d < bd) { bd = d; best = c; }
            }
            if (best == null) return null;
            return new JSONObject().put("name", best.getString(0)).put("cc", best.getString(1)).put("km", bd);
        } catch (Exception e) { return null; }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_LOC) {
            if (hasLoc()) locate(false);
            else locDone(false, shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION) ? "denied" : "blocked", null);
        } else if (requestCode == REQ_NOTIF) {
            js("window.onChanged && onChanged()");
        }
    }

    /* ---------- permissions status ---------- */
    private JSONObject perms() {
        JSONObject o = new JSONObject();
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            o.put("notif", nm.areNotificationsEnabled());
            boolean exact = true;
            if (Build.VERSION.SDK_INT >= 31) exact = ((AlarmManager) getSystemService(Context.ALARM_SERVICE)).canScheduleExactAlarms();
            o.put("exact", exact);
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            o.put("battery", pm.isIgnoringBatteryOptimizations(getPackageName()));
            o.put("loc", hasLoc()).put("locOn", locOn());
            o.put("xiaomi", Build.MANUFACTURER != null && Build.MANUFACTURER.toLowerCase(Locale.US).matches(".*(xiaomi|redmi|poco).*"));
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("autostart", miuiAutostart());
            android.hardware.SensorManager sm = (android.hardware.SensorManager) getSystemService(Context.SENSOR_SERVICE);
            o.put("compass", sm != null && sm.getDefaultSensor(android.hardware.Sensor.TYPE_MAGNETIC_FIELD) != null);
        } catch (Exception ignored) { }
        return o;
    }

    /** MIUI keeps "autostart" in a private app-op (10008). Returns null when it can't be read. */
    private Object miuiAutostart() {
        try {
            android.app.AppOpsManager ops = (android.app.AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            java.lang.reflect.Method m = android.app.AppOpsManager.class.getMethod("checkOpNoThrow", int.class, int.class, String.class);
            int r = (Integer) m.invoke(ops, 10008, android.os.Process.myUid(), getPackageName());
            if (r == android.app.AppOpsManager.MODE_ALLOWED) return true;
            if (r == android.app.AppOpsManager.MODE_IGNORED || r == android.app.AppOpsManager.MODE_ERRORED) return false;
            return JSONObject.NULL;
        } catch (Throwable t) { return JSONObject.NULL; }
    }

    /* ---------- compass ---------- */
    private android.hardware.SensorManager sensors;
    private android.hardware.SensorEventListener compassL;
    private final float[] grav = new float[3], geo = new float[3], rot = new float[9], ori = new float[3];
    private boolean hasG = false, hasM = false;
    private int magAcc = 3;
    private long lastSend = 0;
    private double smooth = Double.NaN;

    void compassStart(double lat, double lng) {
        compassStop();
        sensors = (android.hardware.SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensors == null) return;
        final float decl = new android.hardware.GeomagneticField((float) lat, (float) lng, 0, System.currentTimeMillis()).getDeclination();
        android.hardware.Sensor rv = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR);
        android.hardware.Sensor acc = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER);
        android.hardware.Sensor mag = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_MAGNETIC_FIELD);
        hasG = hasM = false; smooth = Double.NaN;
        compassL = new android.hardware.SensorEventListener() {
            @Override public void onSensorChanged(android.hardware.SensorEvent e) {
                int t = e.sensor.getType();
                boolean ok = false;
                if (t == android.hardware.Sensor.TYPE_ROTATION_VECTOR) {
                    android.hardware.SensorManager.getRotationMatrixFromVector(rot, e.values); ok = true;
                } else if (t == android.hardware.Sensor.TYPE_ACCELEROMETER) {
                    System.arraycopy(e.values, 0, grav, 0, 3); hasG = true;
                } else if (t == android.hardware.Sensor.TYPE_MAGNETIC_FIELD) {
                    System.arraycopy(e.values, 0, geo, 0, 3); hasM = true;
                }
                if (!ok && rv == null && hasG && hasM) ok = android.hardware.SensorManager.getRotationMatrix(rot, null, grav, geo);
                if (!ok) return;
                android.hardware.SensorManager.getOrientation(rot, ori);
                double az = (Math.toDegrees(ori[0]) + decl + 360) % 360;
                if (Double.isNaN(smooth)) smooth = az;
                else { double d = ((az - smooth + 540) % 360) - 180; smooth = (smooth + d * 0.25 + 360) % 360; }
                long now = System.currentTimeMillis();
                if (now - lastSend < 60) return;
                lastSend = now;
                js("window.onCompass && onCompass(" + String.format(Locale.US, "%.1f", smooth) + "," + magAcc + ")");
            }
            @Override public void onAccuracyChanged(android.hardware.Sensor s, int a) {
                if (s.getType() == android.hardware.Sensor.TYPE_MAGNETIC_FIELD || s.getType() == android.hardware.Sensor.TYPE_ROTATION_VECTOR) magAcc = a;
            }
        };
        if (rv != null) sensors.registerListener(compassL, rv, android.hardware.SensorManager.SENSOR_DELAY_GAME);
        else if (acc != null) sensors.registerListener(compassL, acc, android.hardware.SensorManager.SENSOR_DELAY_GAME);
        if (mag != null) sensors.registerListener(compassL, mag, android.hardware.SensorManager.SENSOR_DELAY_GAME);
    }

    void compassStop() {
        if (sensors != null && compassL != null) sensors.unregisterListener(compassL);
        compassL = null;
    }

    /* ---------- bridge ---------- */
    public class Bridge {
        private String state() { return new Engine(MainActivity.this).stateJson(System.currentTimeMillis()).toString(); }

        @JavascriptInterface public boolean isDark() { return dark(); }

        @JavascriptInterface public String getState() { return state(); }

        @JavascriptInterface public String cities() {
            try (InputStream in = getAssets().open("cities.json")) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return bo.toString("UTF-8");
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface public String mark(String day, String k, boolean done) {
            new Engine(MainActivity.this).mark(day, k, done, System.currentTimeMillis());
            new Engine(MainActivity.this).refresh(false, true);
            return state();
        }

        @JavascriptInterface public String qada(String k, int delta) {
            new Engine(MainActivity.this).qadaAdd(k, delta);
            return state();
        }

        @JavascriptInterface public String setSettings(String json) {
            try {
                Store st = new Store(MainActivity.this);
                JSONObject s = st.settings();
                JSONObject in = new JSONObject(json);
                Iterator<String> it = in.keys();
                while (it.hasNext()) { String k = it.next(); s.put(k, in.get(k)); }
                st.put("set", s);
                if (in.has("remind") || in.has("after") || in.has("every") || in.has("quiet") || in.has("adhan") || in.has("off") || in.has("method") || in.has("asr"))
                    st.putLong("lastFire", 0);
                new Engine(MainActivity.this).refresh(false, true);
            } catch (Exception ignored) { }
            return state();
        }

        @JavascriptInterface public String setCity(String json) {
            try {
                JSONObject c = new JSONObject(json);
                c.put("mode", "city").put("at", System.currentTimeMillis());
                new Store(MainActivity.this).put("loc", c);
                new Engine(MainActivity.this).refresh(false, true);
            } catch (Exception ignored) { }
            return state();
        }

        @JavascriptInterface public void locate(boolean silent) { main.post(() -> MainActivity.this.locate(silent)); }

        @JavascriptInterface public String perms() { return MainActivity.this.perms().toString(); }

        @JavascriptInterface public void askNotif() {
            main.post(() -> {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        && !"asked".equals(new Store(MainActivity.this).getStr("notifAsked", ""))) {
                    new Store(MainActivity.this).putStr("notifAsked", "asked");
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                } else openNotif();
            });
        }

        @JavascriptInterface public void openNotif() {
            Intent i = new Intent();
            if (Build.VERSION.SDK_INT >= 26) {
                i.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            } else {
                i.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:" + getPackageName()));
            }
            if (!tryStart(i)) openAppSettings();
        }

        @JavascriptInterface public void openExact() {
            if (Build.VERSION.SDK_INT >= 31 && tryStart(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName())))) return;
            openAppSettings();
        }

        @JavascriptInterface public void openBattery() {
            if (tryStart(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return;
            openAppSettings();
        }

        @JavascriptInterface public void openAutostart() {
            Intent i = new Intent().setComponent(new ComponentName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"));
            if (tryStart(i)) return;
            openAppSettings();
        }

        @JavascriptInterface public void openLocSettings() {
            if (!tryStart(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))) openAppSettings();
        }

        @JavascriptInterface public void openAppSettings() {
            tryStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        }

        @JavascriptInterface public String testTakbir() {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            int vol = am.getStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION);
            if (am.getRingerMode() != android.media.AudioManager.RINGER_MODE_NORMAL) return "silent";
            if (vol == 0) return "zero";
            final boolean[] ok = {false};
            java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
            main.post(() -> { ok[0] = Engine.playTakbir(MainActivity.this); l.countDown(); });
            try { l.await(3, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) { }
            return ok[0] ? "ok" : "fail";
        }

        @JavascriptInterface public void test() { new Engine(MainActivity.this).test(); }

        @JavascriptInterface public void vibrate(int ms) {
            try {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v == null) return;
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                else v.vibrate(ms);
            } catch (Exception ignored) { }
        }

        @JavascriptInterface public void keepAwake(boolean on) {
            main.post(() -> {
                if (on) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface public void copy(String text) {
            main.post(() -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("صلاتي", text));
            });
        }

        @JavascriptInterface public void compass(boolean on, double lat, double lng) {
            main.post(() -> { if (on) compassStart(lat, lng); else compassStop(); });
        }

        @JavascriptInterface public void audioPlay(String cfg) { AudioService.play(MainActivity.this, cfg); }

        @JavascriptInterface public void audioCmd(String c) { AudioService.command(MainActivity.this, c, 0); }

        @JavascriptInterface public void audioSleep(int minutes) { AudioService.command(MainActivity.this, "sleep", minutes); }

        @JavascriptInterface public String audioState() {
            return AudioService.live == null ? "{\"state\":\"stopped\"}" : AudioService.state;
        }

        @JavascriptInterface public void openUrl(String url) { openExternal(url); }

        @JavascriptInterface public String version() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
        }
    }
}
