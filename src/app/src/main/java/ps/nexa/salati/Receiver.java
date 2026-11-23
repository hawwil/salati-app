package ps.nexa.salati;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

import java.util.TimeZone;

/** Alarm ticks, "صلّيت" taps from the notification, reboot and clock changes. */
public class Receiver extends BroadcastReceiver {
    public static final String TICK = "ps.nexa.salati.TICK";
    public static final String MARK = "ps.nexa.salati.MARK";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (a == null) return;
        try {
            if (MARK.equals(a)) {
                Engine e = new Engine(ctx);
                e.mark(intent.getStringExtra("day"), intent.getStringExtra("k"), true, System.currentTimeMillis());
                new Engine(ctx).refresh(false, true);
                MainActivity.notifyChanged();
                return;
            }
            if (Intent.ACTION_TIMEZONE_CHANGED.equals(a)) {
                Store st = new Store(ctx);
                JSONObject loc = st.loc();
                if ("auto".equals(loc.optString("mode"))) {
                    TimeZone.setDefault(null);
                    loc.put("tz", TimeZone.getDefault().getID());
                    st.put("loc", loc);
                }
            }
            new Engine(ctx).refresh(TICK.equals(a), false);
            MainActivity.notifyChanged();
            if (TICK.equals(a) && Engine.takbirPlaying()) {
                // keep the process alive while the takbir is playing
                final PendingResult pr = goAsync();
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(pr::finish, 18000);
            }
        } catch (Exception ignored) { }
    }
}
