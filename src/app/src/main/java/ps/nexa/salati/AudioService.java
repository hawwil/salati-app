package ps.nexa.salati;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Plays Quran recitation (ayah by ayah, streamed) or a list of adhkar recordings,
 * in the background with the screen locked, with lock-screen / notification controls.
 */
public class AudioService extends Service {

    public static final String ACT_PLAY = "ps.nexa.salati.audio.PLAY";
    public static final String ACT_CMD = "ps.nexa.salati.audio.CMD";
    static final String CH = "audio1";
    static final int NID = 7;

    /** latest state as JSON, read by the screen */
    static volatile String state = "{\"state\":\"stopped\"}";
    static volatile AudioService live;

    private final Handler h = new Handler(Looper.getMainLooper());
    private MediaPlayer cur, nxt;
    private boolean nxtReady = false, nxtLinked = false, paused = false, loading = false;
    private int epoch = 0;

    // queue
    private String mode = "list";          // "quran" | "list"
    private String folder = "", rname = "";
    private int[] counts = new int[0];
    private String[] names = new String[0];
    private boolean cont = true;
    private JSONArray items = new JSONArray();
    private long pos = -1;                  // quran: s*1000+a (a=0 → basmala); list: index
    private long nxtPos = -1;
    private long sleepAt = 0;

    private MediaSession session;
    private android.graphics.Bitmap art;
    private AudioManager am;
    private AudioFocusRequest focusReq;
    private boolean resumeOnFocus = false;
    private WifiManager.WifiLock wifi;
    private boolean noisyReg = false;

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { pause(); }
    };

    private final AudioManager.OnAudioFocusChangeListener focus = f -> {
        if (f == AudioManager.AUDIOFOCUS_LOSS) { resumeOnFocus = false; pause(); }
        else if (f == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) { resumeOnFocus = !paused; pause(); }
        else if (f == AudioManager.AUDIOFOCUS_GAIN && resumeOnFocus) { resumeOnFocus = false; resume(); }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        live = this;
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        try { art = android.graphics.BitmapFactory.decodeResource(getResources(), R.drawable.art); } catch (Exception ignored) { }
        session = new MediaSession(this, "salati");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { skip(1); }
            @Override public void onSkipToPrevious() { skip(-1); }
            @Override public void onStop() { stopAll(); }
        });
        session.setActive(true);
        try {
            wifi = ((WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE))
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "salati:audio");
            wifi.setReferenceCounted(false);
        } catch (Exception ignored) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // must go foreground quickly whenever started as foreground service
        startForeground(NID, buildNotif());
        if (intent == null) return START_NOT_STICKY;
        try {
            if (ACT_PLAY.equals(intent.getAction())) {
                configure(new JSONObject(intent.getStringExtra("cfg")));
            } else if (ACT_CMD.equals(intent.getAction())) {
                String c = intent.getStringExtra("c");
                if ("pause".equals(c)) pause();
                else if ("resume".equals(c)) resume();
                else if ("toggle".equals(c)) { if (paused) resume(); else pause(); }
                else if ("next".equals(c)) skip(1);
                else if ("prev".equals(c)) skip(-1);
                else if ("stop".equals(c)) stopAll();
                else if ("sleep".equals(c)) {
                    int m = intent.getIntExtra("m", 0);
                    sleepAt = m > 0 ? System.currentTimeMillis() + m * 60000L : 0;
                    h.removeCallbacks(sleepTask);
                    if (m > 0) h.postDelayed(sleepTask, m * 60000L);
                    publish();
                }
            }
        } catch (Exception e) {
            fail("cfg");
        }
        return START_NOT_STICKY;
    }

    private final Runnable sleepTask = this::stopAll;

    private void configure(JSONObject c) throws Exception {
        mode = c.optString("mode", "list");
        rname = c.optString("rname", "");
        if ("quran".equals(mode)) {
            folder = c.getString("folder");
            JSONArray ct = c.getJSONArray("counts"), nm = c.getJSONArray("names");
            counts = new int[ct.length()];
            names = new String[nm.length()];
            for (int i = 0; i < counts.length; i++) counts[i] = ct.getInt(i);
            for (int i = 0; i < names.length; i++) names[i] = nm.getString(i);
            cont = c.optBoolean("cont", true);
            int s = c.getInt("s"), a = c.optInt("a", 1);
            if (a <= 1) a = hasBasmala(s) ? 0 : 1;
            pos = s * 1000L + a;
        } else {
            items = c.getJSONArray("items");
            pos = c.optInt("start", 0);
        }
        paused = false;
        if (!requestFocus()) { fail("focus"); return; }
        regNoisy(true);
        startAt(pos);
    }

    static boolean hasBasmala(int s) { return s != 1 && s != 9; }

    /* ---------------- queue ---------------- */
    private long nextOf(long p) {
        if (p < 0) return -1;
        if ("quran".equals(mode)) {
            int s = (int) (p / 1000), a = (int) (p % 1000);
            if (a < counts[s - 1]) return s * 1000L + a + 1;
            if (cont && s < 114) return (s + 1) * 1000L + (hasBasmala(s + 1) ? 0 : 1);
            return -1;
        }
        return p + 1 < items.length() ? p + 1 : -1;
    }

    private long prevOf(long p) {
        if ("quran".equals(mode)) {
            int s = (int) (p / 1000), a = (int) (p % 1000);
            if (a > 1) return s * 1000L + a - 1;
            if (s > 1) return (s - 1) * 1000L + counts[s - 2];
            return s * 1000L + 1;
        }
        return Math.max(0, p - 1);
    }

    private String src(long p) {
        if ("quran".equals(mode)) {
            int s = (int) (p / 1000), a = (int) (p % 1000);
            if (a == 0) { s = 1; a = 1; }
            return String.format(Locale.US, "https://everyayah.com/data/%s/%03d%03d.mp3", folder, s, a);
        }
        return items.optJSONObject((int) p).optString("src");
    }

    private String title(long p) {
        if ("quran".equals(mode)) { int s = (int) (p / 1000); return "سورة " + (s - 1 < names.length ? names[s - 1] : ""); }
        JSONObject it = items.optJSONObject((int) p);
        return it == null ? "" : it.optString("title");
    }

    private String sub(long p) {
        if ("quran".equals(mode)) {
            int a = (int) (p % 1000);
            return (a == 0 ? "البسملة" : "الآية " + a) + (rname.isEmpty() ? "" : " · " + rname);
        }
        JSONObject it = items.optJSONObject((int) p);
        return it == null ? "" : it.optString("sub");
    }

    /* ---------------- playback ---------------- */
    private MediaPlayer make(long p, final int ep, final boolean isNext) {
        MediaPlayer mp = new MediaPlayer();
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        try {
            String s = src(p);
            if (s.startsWith("http")) {
                mp.setDataSource(s);
            } else {
                AssetFileDescriptor fd = getAssets().openFd(s);
                mp.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
                fd.close();
            }
        } catch (Exception e) {
            mp.release();
            return null;
        }
        mp.setOnCompletionListener(m -> { if (ep == epoch) onDone(m); });
        mp.setOnErrorListener((m, w, x) -> {
            if (ep != epoch) return true;
            if (m == nxt) { dropNext(); return true; }
            fail("net");
            return true;
        });
        mp.setOnPreparedListener(m -> {
            if (ep != epoch) return;
            if (isNext) {
                nxtReady = true;
                if (cur != null && cur.isPlaying()) linkNext();
                else if (cur == null && !paused) promote(); // current finished while we were loading
            } else {
                loading = false;
                if (!paused) m.start();
                publish();
                prepareNext();
            }
        });
        return mp;
    }

    private void startAt(long p) {
        epoch++;
        releaseAll();
        pos = p;
        loading = true;
        cur = make(p, epoch, false);
        publish();
        if (cur == null) { fail("src"); return; }
        if (wifi != null && src(p).startsWith("http")) wifi.acquire();
        cur.prepareAsync();
    }

    private void prepareNext() {
        dropNext();
        nxtPos = nextOf(pos);
        if (nxtPos < 0) return;
        nxt = make(nxtPos, epoch, true);
        if (nxt != null) nxt.prepareAsync(); else nxtPos = -1;
    }

    private void linkNext() {
        try { cur.setNextMediaPlayer(nxt); nxtLinked = true; } catch (Exception e) { nxtLinked = false; }
    }

    private void dropNext() {
        if (nxt != null) { try { nxt.release(); } catch (Exception ignored) { } }
        nxt = null; nxtReady = false; nxtLinked = false;
    }

    private void onDone(MediaPlayer m) {
        if (m != cur) return;
        if (nxtPos < 0) { // end of queue
            finished();
            return;
        }
        if (nxtLinked) {           // already started gaplessly
            MediaPlayer old = cur;
            cur = nxt; nxt = null; nxtReady = false; nxtLinked = false;
            pos = nxtPos;
            old.release();
            publish();
            prepareNext();
        } else if (nxtReady) {
            cur.release(); cur = null;
            promote();
        } else {
            // next still loading: wait for its onPrepared (cur == null signals that)
            cur.release(); cur = null;
            pos = nxtPos;
            loading = true;
            publish();
            if (nxt == null) startAt(pos);
        }
    }

    private void promote() {
        cur = nxt; nxt = null; nxtReady = false; nxtLinked = false;
        pos = nxtPos;
        loading = false;
        if (!paused) cur.start();
        publish();
        prepareNext();
    }

    private void skip(int dir) {
        long p = dir > 0 ? nextOf(pos) : prevOf(pos);
        if (p < 0) return;
        paused = false;
        startAt(p);
    }

    private void pause() {
        paused = true;
        try { if (cur != null && cur.isPlaying()) cur.pause(); } catch (Exception ignored) { }
        publish();
    }

    private void resume() {
        if (pos < 0) return;
        if (!requestFocus()) return;
        paused = false;
        if (cur == null && !loading) { startAt(pos); return; }
        try { if (cur != null && !loading) cur.start(); } catch (Exception e) { startAt(pos); return; }
        publish();
    }

    private void finished() {
        String done = "{\"state\":\"ended\",\"mode\":\"" + mode + "\"}";
        teardown();
        state = done;
        MainActivity.audioChanged(state);
    }

    private void fail(String why) {
        try {
            JSONObject o = baseState("error").put("err", why);
            teardown();
            state = o.toString();
            MainActivity.audioChanged(state);
        } catch (Exception ignored) { teardown(); }
    }

    private void stopAll() {
        teardown();
        state = "{\"state\":\"stopped\"}";
        MainActivity.audioChanged(state);
    }

    private void teardown() {
        epoch++;
        releaseAll();
        h.removeCallbacks(sleepTask);
        sleepAt = 0;
        abandonFocus();
        regNoisy(false);
        if (wifi != null && wifi.isHeld()) wifi.release();
        session.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0, 1).build());
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        stopSelf();
    }

    private void releaseAll() {
        if (cur != null) { try { cur.release(); } catch (Exception ignored) { } }
        cur = null;
        dropNext();
        loading = false;
    }

    @Override
    public void onDestroy() {
        epoch++;
        releaseAll();
        abandonFocus();
        regNoisy(false);
        session.release();
        if (live == this) live = null;
        super.onDestroy();
    }

    /* ---------------- focus / noisy ---------------- */
    private boolean requestFocus() {
        int r;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusReq == null) focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener(focus).build();
            r = am.requestAudioFocus(focusReq);
        } else {
            r = am.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        return r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) { if (focusReq != null) am.abandonAudioFocusRequest(focusReq); }
        else am.abandonAudioFocus(focus);
    }

    private void regNoisy(boolean on) {
        try {
            if (on && !noisyReg) { registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)); noisyReg = true; }
            else if (!on && noisyReg) { unregisterReceiver(noisy); noisyReg = false; }
        } catch (Exception ignored) { }
    }

    /* ---------------- state & notification ---------------- */
    private JSONObject baseState(String st) throws Exception {
        JSONObject o = new JSONObject().put("state", st).put("mode", mode).put("rname", rname)
                .put("title", pos >= 0 ? title(pos) : "").put("sub", pos >= 0 ? sub(pos) : "")
                .put("sleepAt", sleepAt).put("cont", cont);
        if ("quran".equals(mode) && pos >= 0) o.put("s", pos / 1000).put("a", pos % 1000).put("folder", folder);
        else o.put("idx", pos);
        if ("list".equals(mode) && pos >= 0 && items.optJSONObject((int) pos) != null)
            o.put("key", items.optJSONObject((int) pos).optString("key"));
        return o;
    }

    private void publish() {
        try {
            String st = loading ? "loading" : paused ? "paused" : "playing";
            state = baseState(st).toString();
            MainActivity.audioChanged(state);
            session.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title(pos))
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, sub(pos))
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, "صلاتي")
                    .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
                    .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, art).build());
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                            | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP)
                    .setState(paused ? PlaybackState.STATE_PAUSED : loading ? PlaybackState.STATE_BUFFERING : PlaybackState.STATE_PLAYING,
                            PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1).build());
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NID, buildNotif());
        } catch (Exception ignored) { }
    }

    private PendingIntent cmd(String c, int req) {
        Intent i = new Intent(this, AudioService.class).setAction(ACT_CMD).putExtra("c", c);
        return PendingIntent.getService(this, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotif() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, "التلاوة والأذكار", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (art == null) {
            try { art = android.graphics.BitmapFactory.decodeResource(getResources(), R.drawable.art); } catch (Exception ignored) { }
        }
        if (Build.VERSION.SDK_INT >= 26) b.setColorized(false);
        b.setSmallIcon(R.drawable.ic_stat)
                .setLargeIcon(art)
                .setContentTitle(pos >= 0 ? title(pos) : "صلاتي")
                .setContentText(pos >= 0 ? (loading ? "جارٍ التحميل… · " : "") + sub(pos) : "")
                .setContentIntent(PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setDeleteIntent(cmd("stop", 24))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(!paused)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous, "السابق", cmd("prev", 21)).build())
                .addAction(new Notification.Action.Builder(paused ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause,
                        paused ? "تشغيل" : "إيقاف مؤقت", cmd("toggle", 22)).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, "التالي", cmd("next", 23)).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "إغلاق", cmd("stop", 24)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    /* ---------------- entry points for the screen ---------------- */
    static void play(Context c, String cfg) {
        Intent i = new Intent(c, AudioService.class).setAction(ACT_PLAY).putExtra("cfg", cfg);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void command(Context c, String cmd, int minutes) {
        if (live == null) { if ("stop".equals(cmd)) { state = "{\"state\":\"stopped\"}"; MainActivity.audioChanged(state); } return; }
        Intent i = new Intent(c, AudioService.class).setAction(ACT_CMD).putExtra("c", cmd).putExtra("m", minutes);
        try { c.startService(i); } catch (Exception e) {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        }
    }
}
