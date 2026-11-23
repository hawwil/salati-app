package ps.nexa.salati;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;

/** Home-screen widget: the five prayer times, the next one highlighted with a live countdown. */
public class Widget extends AppWidgetProvider {
    private static final int[] CELL = {R.id.c0, R.id.c1, R.id.c2, R.id.c3, R.id.c4};
    private static final int[] NAME = {R.id.n0, R.id.n1, R.id.n2, R.id.n3, R.id.n4};
    private static final int[] TIME = {R.id.t0, R.id.t1, R.id.t2, R.id.t3, R.id.t4};
    private static final int[] LINE = {R.id.u0, R.id.u1, R.id.u2, R.id.u3, R.id.u4};
    private static final String[] HM = {"محرم", "صفر", "ربيع الأول", "ربيع الآخر", "جمادى الأولى", "جمادى الآخرة",
            "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة"};
    private static final int GOLD = 0xFFE0B04E, WHITE = 0xFFFFFFFF, DIM = 0xB3FFFFFF, NAVY = 0xFF13294B;

    static boolean any(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            return m != null && (m.getAppWidgetIds(new ComponentName(c, Widget.class)).length > 0
                    || m.getAppWidgetIds(new ComponentName(c, WidgetSmall.class)).length > 0);
        } catch (Throwable e) { return false; }
    }

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        try { new Engine(c).refresh(false, false); } catch (Throwable e) { update(c, null); }
    }

    @Override public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle o) {
        update(c, null);
    }

    @Override public void onEnabled(Context c) {
        try { new Engine(c).refresh(false, false); } catch (Throwable ignored) { }
    }

    static void update(Context c, Engine e) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, Widget.class));
            int[] small = m.getAppWidgetIds(new ComponentName(c, WidgetSmall.class));
            if (ids.length == 0 && small.length == 0) return;
            if (e == null) e = new Engine(c);
            for (int id : ids) m.updateAppWidget(id, build(c, e, m.getAppWidgetOptions(id), false));
            for (int id : small) m.updateAppWidget(id, build(c, e, null, true));
        } catch (Throwable ignored) { }
    }

    private static RemoteViews build(Context c, Engine e, Bundle opts, boolean small) {
        RemoteViews v = new RemoteViews(c.getPackageName(), small ? R.layout.widget_small : R.layout.widget);
        long now = System.currentTimeMillis();
        long[] np = e.nextPrayer(now);
        int ni = (int) np[0];
        long day = ni == 0 ? e.localDay(np[1]) : e.prayerDay(now);
        long[] t = e.times(day);
        int[] idx = {0, 2, 3, 4, 5};
        for (int i = 0; i < 5; i++) {
            boolean on = i == ni;
            v.setTextViewText(NAME[i], Engine.AR[i]);
            v.setTextViewText(TIME[i], shortClock(e, t[idx[i]]));
            v.setViewVisibility(LINE[i], on ? View.VISIBLE : View.INVISIBLE);
            v.setTextColor(NAME[i], on ? GOLD : DIM);
            v.setTextColor(TIME[i], on ? GOLD : WHITE);
        }
        v.setTextViewText(R.id.wNext, Engine.AR[ni] + " بعد");
        v.setChronometer(R.id.wCount, SystemClock.elapsedRealtime() + Math.max(0, np[1] - now), null, true);
        v.setChronometerCountDown(R.id.wCount, true);
        if (small) return click(c, v);
        v.setTextViewText(R.id.wCity, e.loc.optString("name", ""));
        v.setTextViewText(R.id.wDate, hijri(now, e));

        int h = opts == null ? 0 : opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
        v.setViewVisibility(R.id.wGrid, h > 0 && h < 90 ? View.GONE : View.VISIBLE);
        return click(c, v);
    }

    private static RemoteViews click(Context c, RemoteViews v) {

        Intent open = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        v.setOnClickPendingIntent(R.id.wRoot, PendingIntent.getActivity(c, 7, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return v;
    }

    private static String shortClock(Engine e, long ms) {
        java.util.Calendar c = java.util.Calendar.getInstance(e.tz);
        c.setTimeInMillis(ms);
        int h = c.get(java.util.Calendar.HOUR_OF_DAY) % 12;
        return String.format(java.util.Locale.US, "%d:%02d", h == 0 ? 12 : h, c.get(java.util.Calendar.MINUTE));
    }

    private static String hijri(long now, Engine e) {
        try {
            android.icu.util.IslamicCalendar cal = new android.icu.util.IslamicCalendar(
                    android.icu.util.TimeZone.getTimeZone(e.tz.getID()));
            cal.setCalculationType(android.icu.util.IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
            cal.setTimeInMillis(now);
            return cal.get(android.icu.util.Calendar.DAY_OF_MONTH) + " " + HM[cal.get(android.icu.util.Calendar.MONTH)]
                    + " " + cal.get(android.icu.util.Calendar.YEAR) + "هـ";
        } catch (Throwable t) { return ""; }
    }
}
