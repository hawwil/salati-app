package ps.nexa.salati;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** All persistent state, shared by the screen and the background reminders. */
public final class Store {
    private final SharedPreferences p;

    public Store(Context c) { p = c.getApplicationContext().getSharedPreferences("salati", Context.MODE_PRIVATE); }

    public JSONObject obj(String k) {
        try { return new JSONObject(p.getString(k, "{}")); } catch (Exception e) { return new JSONObject(); }
    }
    public void put(String k, JSONObject o) { p.edit().putString(k, o.toString()).apply(); }
    public long getLong(String k, long d) { return p.getLong(k, d); }
    public void putLong(String k, long v) { p.edit().putLong(k, v).apply(); }
    public String getStr(String k, String d) { return p.getString(k, d); }
    public void putStr(String k, String v) { p.edit().putString(k, v).apply(); }

    /* -------- location -------- */
    public JSONObject loc() {
        JSONObject l = obj("loc");
        if (!l.has("lat")) {
            try {
                l.put("name", "غزة").put("cc", "PS").put("lat", 31.5017).put("lng", 34.4668)
                        .put("tz", "Asia/Gaza").put("mode", "default");
            } catch (Exception ignored) { }
        }
        return l;
    }

    /* -------- settings with defaults -------- */
    public JSONObject settings() {
        JSONObject s = obj("set");
        try {
            if (!s.has("method")) s.put("method", "auto");
            if (!s.has("asr")) s.put("asr", "auto");
            if (!s.has("off")) s.put("off", new JSONObject());
            if (!s.has("remind")) s.put("remind", true);
            if (!s.has("after")) s.put("after", 15);
            if (!s.has("every")) s.put("every", 15);
            if (!s.has("quiet")) s.put("quiet", true);
            if (!s.has("adhan")) s.put("adhan", true);
            if (!s.has("autoLoc")) s.put("autoLoc", true);
            if (!s.has("keepAlive")) s.put("keepAlive", false);
        } catch (Exception ignored) { }
        return s;
    }
}
