package ps.nexa.salati;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/** Optional ongoing notification ("next prayer …") that keeps صلاتي alive on aggressive phones. */
public class KeepAliveService extends Service {
    static final int NID = 9;
    static final String CH = "keep1";

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            Engine e = new Engine(this);
            startForeground(NID, build(this, e));   // always first: required after startForegroundService
            if (!e.set.optBoolean("keepAlive", false)) { stopSelfNow(); return START_NOT_STICKY; }
        } catch (Exception ex) { stopSelfNow(); return START_NOT_STICKY; }
        return START_STICKY;
    }

    private void stopSelfNow() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        stopSelf();
    }

    static Notification build(Context c, Engine e) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, "الصلاة التالية (دائم)", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("إشعار ثابت يُبقي صلاتي يعمل ويعرض موعد الصلاة التالية");
            ch.setShowBadge(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
        long now = System.currentTimeMillis();
        long[] np = e.nextPrayer(now);          // {index, time}
        int i = (int) np[0];
        String title = "الصلاة التالية: " + Engine.AR[i] + " · " + e.clock(np[1]);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH) : new Notification.Builder(c);
        Intent open = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText("حسب توقيت " + e.loc.optString("name", ""))
                .setContentIntent(PendingIntent.getActivity(c, 3, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .setShowWhen(true)
                .setWhen(np[1])
                .setUsesChronometer(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_STATUS);
        if (Build.VERSION.SDK_INT >= 24) b.setChronometerCountDown(true);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        return b.build();
    }

    /** start / refresh / stop according to the setting */
    static void sync(Context c, Engine e) {
        try {
            boolean on = e.set.optBoolean("keepAlive", false);
            Intent i = new Intent(c, KeepAliveService.class);
            if (on) {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
            } else {
                c.stopService(i);
            }
        } catch (Exception ignored) {
            // background-start limits: the notification is refreshed next time the app or an alarm runs
        }
    }
}
