package dumb_phone.radio;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/** Plays one stream in the foreground, so it keeps going with the screen off. */
public class RadioService extends Service implements AudioManager.OnAudioFocusChangeListener {
    static final String PLAY = "dumb_phone.radio.PLAY", STOP = "dumb_phone.radio.STOP";

    interface Listener { void onRadioState(); }

    // Read by the activity (same process, main thread).
    static String url, name, song, status = "stopped";
    static boolean playing;
    static Listener listener;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private StreamSource source;
    private WifiManager.WifiLock wifiLock;
    private AudioManager audio;
    private MediaSession session;        // headset play/stop + system media control
    private int generation;          // bumps on every play/stop so stale callbacks are ignored
    private boolean pausedForFocus;
    private int retries;

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { stopRadio(); }   // headphones pulled out
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        wifiLock = ((WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE))
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "dumb_phone-radio");
        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        try {
            session = new MediaSession(this, "dumb_phone-radio");
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            session.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { if (!playing && url != null) { retries = 0; play(url, name); } }
                @Override public void onPause() { stopRadio(); }     // live radio has no pause: the button stops it
                @Override public void onStop() { stopRadio(); }
            });
        } catch (Exception ignored) { }
    }

    /** Keep the media session in step so the headset button and the system know the station. */
    private void updateSession() {
        if (session == null) return;
        try {
            int st = playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_STOPPED;
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP)
                    .setState(st, 0, playing ? 1f : 0f).build());
            session.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, name == null ? "" : name)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, song == null ? "" : song).build());
            session.setActive(playing);
        } catch (Exception ignored) { }
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (i != null && PLAY.equals(i.getAction())) {
            retries = 0;
            play(i.getStringExtra("url"), i.getStringExtra("name"));
        } else {
            stopRadio();
        }
        return START_NOT_STICKY;
    }

    private void play(String u, String n) {
        release();
        url = u; name = n; song = null; playing = true; pausedForFocus = false;
        set("connecting…");
        if (audio.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            set("audio busy");
            return;
        }
        if (!wifiLock.isHeld()) wifiLock.acquire();
        startForeground(1, notification());
        new Thread(new Resolver(u, ++generation)).start();
    }

    /** Looks up the real stream address off the main thread, then starts playing it. */
    private class Resolver implements Runnable {
        final String u;
        final int gen;
        String real;
        Resolver(String u, int gen) { this.u = u; this.gen = gen; }

        @Override public void run() {
            if (real == null) {
                real = resolve(u);
                main.post(this);
            } else if (gen == generation) {
                start(real, gen);
            }
        }
    }

    private void start(String real, final int gen) {
        try {
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer m) {
                    if (gen != generation) return;
                    m.start();
                    retries = 0;
                    set("playing");
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer m, int what, int extra) {
                    if (gen == generation) failed();
                    return true;
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer m) {
                    if (gen == generation) failed();   // a live stream "ending" means the connection dropped
                }
            });
            if (real.toLowerCase().contains(".m3u8")) {
                mp.setDataSource(real);                            // HLS: MediaPlayer fetches the segments itself
            } else {
                source = new StreamSource(real, new StreamSource.TitleListener() {
                    @Override public void onTitle(final String t) {
                        main.post(new Runnable() {
                            @Override public void run() { if (gen == generation) { song = t.isEmpty() ? null : t; set(status); } }
                        });
                    }
                });
                mp.setDataSource(source);
            }
            mp.prepareAsync();
        } catch (Exception e) {
            failed();
        }
    }

    /** Reconnect a couple of times (Wi-Fi blips), then give up. */
    private void failed() {
        if (playing && retries < 2) {
            retries++;
            final String u = url, n = name;
            set("reconnecting…");
            release();
            main.postDelayed(new Runnable() {
                @Override public void run() { if (playing && u.equals(url)) play(u, n); }
            }, 2000);
        } else {
            release();
            playing = false;
            set("can't play this station");
            finish();
        }
    }

    private void stopRadio() {
        generation++;
        release();
        playing = false;
        audio.abandonAudioFocus(this);
        set("stopped");
        finish();
    }

    private void finish() {
        if (wifiLock.isHeld()) wifiLock.release();
        stopForeground(true);
    }

    private void release() {
        if (player != null) {
            try { player.reset(); player.release(); } catch (Exception ignored) { }
            player = null;
        }
        if (source != null) { source.close(); source = null; }
    }

    @Override public void onAudioFocusChange(int change) {
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            stopRadio();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {          // a call: drop the stream
            if (playing) { pausedForFocus = true; generation++; release(); set("paused"); }
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if (player != null) player.setVolume(0.2f, 0.2f);
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if (player != null) player.setVolume(1f, 1f);
            if (pausedForFocus && playing) play(url, name);                     // live radio: reconnect
        }
    }

    private void set(String s) {
        status = s;
        if (playing) {
            try { startForeground(1, notification()); } catch (Exception ignored) { }
        }
        updateSession();
        tellHome();
        if (listener != null) listener.onRadioState();
    }

    /** Tell the home screen's panel what's playing, so it can show play/stop above the notifications. */
    private void tellHome() {
        try {
            sendBroadcast(new Intent("dumb_phone.home.NOWPLAYING").setPackage("dumb_phone.home")
                    .putExtra("pkg", getPackageName())
                    .putExtra("active", playing).putExtra("paused", false)
                    .putExtra("title", name == null ? "" : name)
                    .putExtra("sub", song == null ? "" : song));
        } catch (Exception ignored) { }
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, RadioService.class).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(name)
                .setContentText(song != null && "playing".equals(status) ? song : status)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(android.R.drawable.ic_media_pause, "stop", stop)
                .build();
    }

    @Override public void onDestroy() {
        stopRadio();
        if (session != null) { try { session.release(); } catch (Exception ignored) { } session = null; }
        unregisterReceiver(noisy);
    }

    /** Follow redirects and open .m3u / .pls playlists to get the real stream address. HLS (.m3u8) is left to MediaPlayer. */
    static String resolve(String u) {
        try {
            for (int hop = 0; hop < 6; hop++) {
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("User-Agent", "dumb_phone-radio");
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                    u = new URL(new URL(u), c.getHeaderField("Location")).toString();
                    c.disconnect();
                    continue;
                }
                String type = String.valueOf(c.getContentType()).toLowerCase();
                String path = new URL(u).getPath().toLowerCase();
                boolean playlist = type.contains("mpegurl") || type.contains("scpls") || type.contains("x-pls")
                        || path.endsWith(".m3u") || path.endsWith(".pls") || path.endsWith(".m3u8");
                if (!playlist) { c.disconnect(); return u; }
                StringBuilder body = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                    String line;
                    for (int n = 0; n < 200 && (line = r.readLine()) != null; n++) body.append(line).append('\n');
                }
                c.disconnect();
                if (body.indexOf("#EXT-X-") >= 0) return u;                    // HLS
                String next = null;
                for (String line : body.toString().split("\n")) {
                    line = line.trim();
                    if (line.matches("(?i)file\\d+=.*")) line = line.substring(line.indexOf('=') + 1).trim();
                    if (line.startsWith("http")) { next = line; break; }
                }
                if (next == null) return u;
                u = next;
            }
        } catch (Exception e) {
            android.util.Log.w("dumb_phone-radio", "resolve " + u + ": " + e);
        }
        return u;
    }
}
