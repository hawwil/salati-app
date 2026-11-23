package ps.nexa.salati;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TimeZone;

/**
 * The reminder brain: prayer times for the saved location, what is pending,
 * when to remind next, and the notifications themselves.
 */
public final class Engine {

    public static final String[] K = {"fajr", "dhuhr", "asr", "maghrib", "isha"};
    public static final String[] AR = {"الفجر", "الظهر", "العصر", "المغرب", "العشاء"};
    private static final int[] IDX = {0, 2, 3, 4, 5};      // index into the 6-slot times array
    private static final String[] OFF = {"fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha"};
    static final long DAY = 86400000L, MIN = 60000L;

    static final int N_REMIND = 1, N_ADHAN = 2, N_TEST = 3;
    static final String CH_REMIND = "remind2", CH_ADHAN = "adhan2", CH_ADHAN_T = "adhan_takbir";
    static final String TAKBIR = "takbir.mp3";

    static boolean hasTakbir(Context c) {
        try { c.getAssets().openFd(TAKBIR).close(); return true; } catch (Exception e) { return false; }
    }

    /** kept in a field: a MediaPlayer with no reference can be garbage-collected mid-play and fall silent */
    private static android.media.MediaPlayer takbirPlayer;

    static synchronized boolean takbirPlaying() { return takbirPlayer != null; }

    /** Plays the short takbir unless the phone is on silent / vibrate. Returns false if it could not play. */
    static synchronized boolean playTakbir(Context c) {
        try {
            android.media.AudioManager am = (android.media.AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am.getRingerMode() != android.media.AudioManager.RINGER_MODE_NORMAL) return false;
            if (takbirPlayer != null) { try { takbirPlayer.release(); } catch (Exception ignored) { } takbirPlayer = null; }
            android.media.MediaPlayer mp = new android.media.MediaPlayer();
            mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build());
            android.content.res.AssetFileDescriptor fd = c.getApplicationContext().getAssets().openFd(TAKBIR);
            mp.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            fd.close();
            mp.setWakeMode(c.getApplicationContext(), android.os.PowerManager.PARTIAL_WAKE_LOCK);
            mp.setOnCompletionListener(m -> { synchronized (Engine.class) { m.release(); if (takbirPlayer == m) takbirPlayer = null; } });
            mp.setOnErrorListener((m, a, b) -> { synchronized (Engine.class) { m.release(); if (takbirPlayer == m) takbirPlayer = null; } return true; });
            mp.prepare();
            mp.start();
            takbirPlayer = mp;
            return true;
        } catch (Exception e) { return false; }
    }

    /** set by MainActivity while it is on screen */
    public static volatile boolean screenOn = false;

    final Context c;
    final Store st;
    final JSONObject loc, set;
    final TimeZone tz;
    final PrayCalc.Method method;
    final boolean hanafi;
    final long after, every;
    final JSONObject marks;
    long install;

    static String autoMethod(JSONObject loc) {
        if (PrayCalc.inGaza(loc.optDouble("lat", 0), loc.optDouble("lng", 0))) return "gaza";
        return PrayCalc.methodForCountry(loc.optString("cc"));
    }

    public Engine(Context ctx) {
        c = ctx.getApplicationContext();
        st = new Store(c);
        loc = st.loc();
        set = st.settings();
        tz = TimeZone.getTimeZone(loc.optString("tz", "Asia/Gaza"));
        String mid = set.optString("method", "auto");
        method = PrayCalc.method("auto".equals(mid) ? autoMethod(loc) : mid);
        String asr = set.optString("asr", "auto");
        hanafi = "auto".equals(asr) ? PrayCalc.hanafiForCountry(loc.optString("cc")) : "hanafi".equals(asr);
        after = set.optLong("after", 15) * MIN;
        every = Math.max(5, set.optLong("every", 15)) * MIN;
        marks = st.obj("marks");
        install = st.getLong("install", 0);
        if (install == 0) { install = System.currentTimeMillis(); st.putLong("install", install); }
    }

    /* ---------------- dates ---------------- */
    static long civilToDays(int y, int m, int d) {
        y -= m <= 2 ? 1 : 0;
        long era = (y >= 0 ? y : y - 399) / 400;
        long yoe = y - era * 400;
        long doy = (153L * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }
    static int[] daysToCivil(long z) {
        z += 719468;
        long era = (z >= 0 ? z : z - 146096) / 146097;
        long doe = z - era * 146097;
        long yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        long y = yoe + era * 400;
        long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        long mp = (5 * doy + 2) / 153;
        long d = doy - (153 * mp + 2) / 5 + 1;
        long m = mp + (mp < 10 ? 3 : -9);
        return new int[]{(int) (y + (m <= 2 ? 1 : 0)), (int) m, (int) d};
    }
    static String dayStr(long day) { int[] a = daysToCivil(day); return String.format(java.util.Locale.US, "%04d-%02d-%02d", a[0], a[1], a[2]); }
    static long parseDay(String s) {
        String[] p = s.split("-");
        return civilToDays(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
    }
    long localDay(long ms) { return Math.floorDiv(ms + tz.getOffset(ms), DAY); }

    /* ---------------- times ---------------- */
    private final java.util.HashMap<Long, long[]> cache = new java.util.HashMap<>();

    /** fajr, sunrise, dhuhr, asr, maghrib, isha as epoch ms */
    public long[] times(long day) {
        long[] hit = cache.get(day);
        if (hit != null) return hit;
        int[] ymd = daysToCivil(day);
        long noonGuess = day * DAY + 12 * 3600000L - tz.getRawOffset();
        double tzH = tz.getOffset(noonGuess) / 3600000.0;
        double ishaOverride = 0;
        if (method.ishaMin > 0 && isRamadan(noonGuess)) ishaOverride = 120;
        boolean gaza = "gaza".equals(method.id);
        double[] h = gaza
                ? PrayCalc.compute(ymd[0], ymd[1], ymd[2], PrayCalc.GAZA_LAT, PrayCalc.GAZA_LNG, tzH, method, hanafi, 0)
                : PrayCalc.compute(ymd[0], ymd[1], ymd[2], loc.optDouble("lat"), loc.optDouble("lng"), tzH,
                method, hanafi, ishaOverride);
        JSONObject off = set.optJSONObject("off");
        long[] r = new long[6];
        for (int i = 0; i < 6; i++) {
            long mins = gaza ? (long) Math.floor(h[i] * 60 + PrayCalc.GAZA_SEC[i] / 60.0) : Math.round(h[i] * 60);
            if (off != null) mins += off.optInt(OFF[i], 0);
            r[i] = day * DAY + mins * MIN - Math.round(tzH * 3600000);
        }
        cache.put(day, r);
        return r;
    }

    private static boolean isRamadan(long ms) {
        try {
            android.icu.util.IslamicCalendar cal = new android.icu.util.IslamicCalendar();
            cal.setCalculationType(android.icu.util.IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
            cal.setTimeInMillis(ms);
            return cal.get(android.icu.util.Calendar.MONTH) == 8;
        } catch (Throwable t) { return false; }
    }

    /* ---------------- state ---------------- */
    static final class Item {
        long day; int i; long abs; boolean done; boolean old; boolean today;
        String id() { return dayStr(day) + ":" + K[i]; }
        String name() { return AR[i] + (today ? "" : " (الأمس)"); }
    }

    long prayerDay(long now) {
        long today = localDay(now);
        return now < times(today)[0] ? today - 1 : today;
    }

    long markOf(long day, int i) {
        JSONObject d = marks.optJSONObject(dayStr(day));
        return d == null ? 0 : d.optLong(K[i], 0);
    }

    List<Item> items(long P) {
        List<Item> out = new ArrayList<>();
        for (long day = P - 1; day <= P; day++) {
            long[] t = times(day);
            for (int i = 0; i < 5; i++) {
                Item it = new Item();
                it.day = day; it.i = i; it.abs = t[IDX[i]];
                it.done = markOf(day, i) != 0;
                it.old = it.abs < install;
                it.today = day == P;
                out.add(it);
            }
        }
        return out;
    }

    Item current(List<Item> items, long P, long now) {
        Item cur = null;
        for (Item it : items) if (it.today && it.abs <= now) cur = it;
        if (cur != null && cur.i == 0 && now >= times(P)[1]) cur = null;   // after sunrise Fajr is make-up
        return cur;
    }

    List<Item> pending(List<Item> items, long now) {
        List<Item> p = new ArrayList<>();
        for (Item it : items) if (!it.done && !it.old && it.abs + after <= now) p.add(it);
        return p;
    }

    boolean quiet(long now) {
        if (!set.optBoolean("quiet", true)) return false;
        long today = localDay(now);
        return now < times(today)[0] + after;   // from midnight until Fajr + delay
    }

    /** Move unprayed prayers that left the two-day window into the make-up counter. */
    void roll(long P) {
        String rolledS = st.getStr("rolled", "");
        long target = P - 2;
        if (rolledS.isEmpty()) { st.putStr("rolled", dayStr(target)); return; }
        long rolled = parseDay(rolledS);
        if (rolled >= target) return;
        JSONObject q = st.obj("qada");
        long from = Math.max(rolled + 1, target - 400);
        try {
            for (long d = from; d <= target; d++) {
                long[] t = times(d);
                for (int i = 0; i < 5; i++) {
                    if (t[IDX[i]] < install) continue;
                    if (markOf(d, i) != 0) continue;
                    q.put(K[i], q.optInt(K[i], 0) + 1);
                }
            }
            // forget marks older than 60 days
            Iterator<String> it = marks.keys();
            List<String> drop = new ArrayList<>();
            while (it.hasNext()) { String k = it.next(); try { if (parseDay(k) < P - 60) drop.add(k); } catch (Exception e) { drop.add(k); } }
            for (String k : drop) marks.remove(k);
        } catch (Exception ignored) { }
        st.put("qada", q);
        st.put("marks", marks);
        st.putStr("rolled", dayStr(target));
    }

    /* ---------------- actions ---------------- */
    public void mark(String day, String k, boolean done, long now) {
        try {
            JSONObject d = marks.optJSONObject(day);
            if (d == null) { d = new JSONObject(); marks.put(day, d); }
            if (done) d.put(k, now); else d.remove(k);
            st.put("marks", marks);
        } catch (Exception ignored) { }
    }

    public void qadaAdd(String k, int delta) {
        JSONObject q = st.obj("qada");
        try { q.put(k, Math.max(0, q.optInt(k, 0) + delta)); } catch (Exception ignored) { }
        st.put("qada", q);
    }

    /** next adhan after now: {prayer index, time} */
    long[] nextPrayer(long now) {
        long P = prayerDay(now);
        for (long d = P; d <= P + 1; d++) {
            long[] t = times(d);
            for (int i = 0; i < 5; i++) if (t[IDX[i]] > now) return new long[]{i, t[IDX[i]]};
        }
        long[] t = times(P + 2);
        return new long[]{0, t[0]};
    }

    String clock(long ms) {
        java.util.Calendar c = java.util.Calendar.getInstance(tz);
        c.setTimeInMillis(ms);
        int h = c.get(java.util.Calendar.HOUR_OF_DAY), m = c.get(java.util.Calendar.MINUTE);
        String ap = h < 12 ? "ص" : "م";
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return String.format(java.util.Locale.US, "%d:%02d %s", h12, m, ap);
    }

    /* ---------------- JSON for the screen ---------------- */
    public JSONObject stateJson(long now) {
        JSONObject o = new JSONObject();
        try {
            long P = prayerDay(now);
            roll(P);
            o.put("now", now).put("tzOff", tz.getOffset(now)).put("tz", tz.getID());
            o.put("P", dayStr(P)).put("install", install);
            o.put("loc", loc).put("set", set).put("method", method.id).put("methodName", method.name).put("hanafi", hanafi);
            o.put("after", after / MIN);
            JSONObject days = new JSONObject();
            for (long d = P - 1; d <= P + 1; d++) {
                long[] t = times(d);
                JSONObject x = new JSONObject();
                for (int i = 0; i < 6; i++) x.put(OFF[i], t[i]);
                days.put(dayStr(d), x);
            }
            o.put("days", days);
            o.put("marks", marks);
            o.put("qada", st.obj("qada"));
            JSONArray ms = new JSONArray();
            for (PrayCalc.Method m : PrayCalc.METHODS) ms.put(new JSONObject().put("id", m.id).put("name", m.name));
            o.put("methods", ms);
            o.put("autoMethod", autoMethod(loc));
            o.put("autoHanafi", PrayCalc.hanafiForCountry(loc.optString("cc")));
            o.put("next", st.getLong("nextAt", 0));
        } catch (Exception ignored) { }
        return o;
    }

    /* ---------------- reminders ---------------- */
    static String message(List<Item> pend, Item cur, String[] outTitle) {
        List<Item> qada = new ArrayList<>();
        Item now = null;
        for (Item it : pend) {
            if (cur != null && it.day == cur.day && it.i == cur.i) now = it; else qada.add(it);
        }
        if (qada.isEmpty() && now != null) {
            outTitle[0] = "هل صلّيتَ " + AR[now.i] + "؟ 🕌";
            return "إذا صلّيتَ فاضغط «صلّيتُ» في الأسفل";
        }
        StringBuilder b = new StringBuilder("اقضِ ");
        for (int i = 0; i < qada.size(); i++) { if (i > 0) b.append(" و"); b.append(qada.get(i).name()); }
        if (now != null) b.append(" وصلِّ ").append(AR[now.i]);
        outTitle[0] = "هل صلّيتَ؟ 🕌";
        return b.toString();
    }

    /**
     * Re-evaluate: post / update / clear the reminder and set the next alarm.
     * @param fromAlarm true when called by the alarm (may make a sound)
     * @param silentUpdate update a visible reminder without sound (after marking)
     */
    public void refresh(boolean fromAlarm, boolean silentUpdate) {
        long now = System.currentTimeMillis();
        long P = prayerDay(now);
        roll(P);
        List<Item> items = items(P);
        Item cur = current(items, P, now);
        List<Item> pend = pending(items, now);
        boolean remind = set.optBoolean("remind", true);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        channels(nm);

        StringBuilder sig = new StringBuilder();
        for (Item it : pend) sig.append(it.id()).append(',');
        String prevSig = st.getStr("lastSig", "");
        long lastFire = st.getLong("lastFire", 0);

        if (!remind || pend.isEmpty()) {
            nm.cancel(N_REMIND);
            st.putStr("lastSig", "");
        } else if (!quiet(now)) {
            boolean newOne = false;
            for (Item it : pend) if (!prevSig.contains(it.id() + ",")) { newOne = true; break; }
            boolean due = newOne || now - lastFire >= every - 30000;
            if (fromAlarm && due) {
                // with the app open the screen already shows it; count it as delivered
                if (!screenOn) post(nm, pend, cur, false);
                st.putLong("lastFire", now);
                st.putStr("lastSig", sig.toString());
            } else if (silentUpdate && isShowing(nm)) {
                post(nm, pend, cur, true);
                st.putStr("lastSig", sig.toString());
            }
        }

        if (fromAlarm && set.optBoolean("adhan", false)) adhanCheck(nm, P, now);

        long nf = nextFire(now, P, pend, remind);
        if (set.optBoolean("keepAlive", false)) nf = Math.min(nf, nextPrayer(now)[1] + 1000);
        if (Widget.any(c)) {
            nf = Math.min(nf, nextPrayer(now)[1] + 1000);
            nf = Math.min(nf, (localDay(now) + 1) * DAY - tz.getOffset(now) + 5000);   // new Hijri date
            Widget.update(c, this);
        }
        schedule(nf);
        KeepAliveService.sync(c, this);
    }

    private void adhanCheck(NotificationManager nm, long P, long now) {
        for (long d = P; d <= P + 1; d++) {
            long[] t = times(d);
            for (int i = 0; i < 5; i++) {
                long a = t[IDX[i]];
                String id = dayStr(d) + ":" + K[i];
                if (now >= a - 30000 && now < a + 10 * MIN && !id.equals(st.getStr("lastAdhan", ""))) {
                    st.putStr("lastAdhan", id);
                    boolean takbir = hasTakbir(c);
                    if (takbir && Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH_ADHAN_T) == null) {
                        NotificationChannel ch = new NotificationChannel(CH_ADHAN_T, "دخول وقت الصلاة (تكبير)", NotificationManager.IMPORTANCE_HIGH);
                        ch.setDescription("إشعار عند دخول وقت الصلاة بصوت التكبير");
                        ch.setSound(null, null);
                        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                        nm.createNotificationChannel(ch);
                    }
                    if (takbir) playTakbir(c);
                    Notification.Builder b = builder(takbir ? CH_ADHAN_T : CH_ADHAN)
                            .setContentTitle("حان الآن وقت " + AR[i] + " 🕌")
                            .setContentText("حسب توقيت " + loc.optString("name", ""))
                            .setAutoCancel(true)
                            .setContentIntent(openApp());
                    nm.notify(N_ADHAN, b.build());
                    return;
                }
            }
        }
    }

    long nextFire(long now, long P, List<Item> pend, boolean remind) {
        long best = now + 6 * 3600000L;
        if (remind) {
            for (long d = P - 1; d <= P + 1; d++) {
                long[] t = times(d);
                for (int i = 0; i < 5; i++) {
                    long a = t[IDX[i]];
                    if (a < install || markOf(d, i) != 0) continue;
                    long f = a + after;
                    if (f > now + 1000 && f < best) best = f;
                }
            }
            if (!pend.isEmpty()) {
                long f = Math.max(st.getLong("lastFire", 0) + every, now + 30000);
                if (quiet(now)) f = Math.max(f, times(localDay(now))[0] + after);
                if (f < best) best = f;
            }
        }
        if (set.optBoolean("adhan", false)) {
            for (long d = P; d <= P + 1; d++) {
                long[] t = times(d);
                for (int i = 0; i < 5; i++) { long a = t[IDX[i]]; if (a > now + 1000 && a < best) best = a; }
            }
        }
        return best;
    }

    void schedule(long at) {
        st.putLong("nextAt", at);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(c, 1,
                new Intent(c, Receiver.class).setAction(Receiver.TICK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    /* ---------------- notifications ---------------- */
    static void channels(NotificationManager nm) {
        if (Build.VERSION.SDK_INT < 26) return;
        if (nm.getNotificationChannel(CH_REMIND) == null) {
            NotificationChannel ch = new NotificationChannel(CH_REMIND, "تذكير الصلاة", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("بعد الأذان، ويتكرر حتى تضغط «صلّيتُ»");
            ch.enableVibration(true);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CH_ADHAN) == null) {
            NotificationChannel ch = new NotificationChannel(CH_ADHAN, "دخول وقت الصلاة", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("إشعار عند دخول وقت الصلاة");
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
    }

    Notification.Builder builder(String ch) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, ch) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_stat).setColor(Color.parseColor("#13294B")).setShowWhen(true);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
        b.setCategory(Notification.CATEGORY_REMINDER);
        b.setVisibility(Notification.VISIBILITY_PUBLIC);
        return b;
    }

    PendingIntent openApp() {
        Intent i = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent markIntent(Item it, int req) {
        Intent i = new Intent(c, Receiver.class).setAction(Receiver.MARK)
                .putExtra("day", dayStr(it.day)).putExtra("k", K[it.i]);
        return PendingIntent.getBroadcast(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    void post(NotificationManager nm, List<Item> pend, Item cur, boolean silent) {
        String[] title = new String[1];
        String body = message(pend, cur, title);
        Notification.Builder b = builder(CH_REMIND)
                .setContentTitle(title[0])
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(openApp())
                .setOnlyAlertOnce(silent)
                .setAutoCancel(false);
        Item now = null, firstQ = null;
        for (Item it : pend) {
            if (cur != null && it.day == cur.day && it.i == cur.i) now = it;
            else if (firstQ == null) firstQ = it;
        }
        if (now != null) b.addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "✅ صلّيتُ " + AR[now.i], markIntent(now, 10)).build());
        if (firstQ != null) b.addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "✅ قضيتُ " + firstQ.name(), markIntent(firstQ, 11)).build());
        nm.notify(N_REMIND, b.build());
    }

    boolean isShowing(NotificationManager nm) {
        try {
            for (StatusBarNotification s : nm.getActiveNotifications()) if (s.getId() == N_REMIND) return true;
        } catch (Exception ignored) { }
        return false;
    }

    public void test() {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        channels(nm);
        Notification.Builder b = builder(CH_REMIND)
                .setContentTitle("هل صلّيتَ؟ 🕌")
                .setContentText("هكذا سيصلك التذكير بعد الأذان")
                .setContentIntent(openApp())
                .setAutoCancel(true);
        nm.notify(N_TEST, b.build());
    }
}
