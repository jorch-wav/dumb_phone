package dumb_phone.podcasts;

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

/** Plays one episode in the foreground; remembers the position every 10 s and on pause. */
public class PodService extends Service implements AudioManager.OnAudioFocusChangeListener {
    static final String PLAY = "dumb_phone.podcasts.PLAY", TOGGLE = "dumb_phone.podcasts.TOGGLE",
            SEEK = "dumb_phone.podcasts.SEEK", STOP = "dumb_phone.podcasts.STOP";

    interface Listener { void onPodState(); }

    // Read by the activity (same process, main thread).
    static String url, title, show, status = "stopped";
    static boolean active, paused;          // active = an episode is loaded (playing or paused)
    static Listener listener;
    private static PodService self;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private RangeSource source;
    private Feeds feeds;
    private AudioManager audio;
    private MediaSession session;           // lets the headset buttons (play/pause/fwd/rew) reach us
    private WifiManager.WifiLock wifiLock;
    private int generation;
    private boolean pausedForFocus;
    private long knownDuration;
    private int retries;                    // reopenings after a mid-episode error (reset by a new episode)

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { if (active && !paused) pause(); }   // headphones out
    };

    private final Runnable saver = new Runnable() {
        @Override public void run() {
            save();
            if (active && !paused) main.postDelayed(this, 10000);
        }
    };

    static long position() {
        try { return self != null && self.player != null && self.prepared ? self.player.getCurrentPosition() : 0; } catch (Exception e) { return 0; }
    }

    static long duration() {
        try {
            if (self == null) return 0;
            if (self.player != null && self.prepared && self.player.getDuration() > 0) return self.player.getDuration();
            return self.knownDuration;
        } catch (Exception e) { return 0; }
    }

    private boolean prepared;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        self = this;
        MainActivity.logCrashes(getApplicationContext());
        feeds = new Feeds(this);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        wifiLock = ((WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE))
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "dumb_phone-podcasts");
        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        try {
            session = new MediaSession(this, "dumb_phone-podcasts");
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            session.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { if (active) { if (paused) resume(); } else if (url != null) play(url, title, show, knownDuration); }
                @Override public void onPause() { if (active && !paused) pause(); }
                @Override public void onStop() { stopAll(); }
                @Override public void onSkipToNext() { seek(30000); }       // headset forward = +30 s
                @Override public void onSkipToPrevious() { seek(-15000); }   // headset back = -15 s
                @Override public void onFastForward() { seek(30000); }
                @Override public void onRewind() { seek(-15000); }
                @Override public void onSeekTo(long pos) { if (player != null && prepared) { try { player.seekTo((int) pos); save(); } catch (Exception ignored) { } updateSession(); } }
            });
        } catch (Exception ignored) { }
    }

    /** Keep the media session in step so the headset buttons and the system know what's playing. */
    private void updateSession() {
        if (session == null) return;
        try {
            int st = !active ? PlaybackState.STATE_STOPPED : paused ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_PLAYING;
            long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_STOP | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND
                    | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(actions).setState(st, position(), paused ? 0f : 1f).build());
            session.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title == null ? "" : title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, show == null ? "" : show)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, duration()).build());
            session.setActive(active);
        } catch (Exception ignored) { }
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        String a = i == null ? null : i.getAction();
        if (PLAY.equals(a)) {
            String u = i.getStringExtra("url");
            if (active && u != null && u.equals(url)) { if (paused) resume(); }
            else play(u, i.getStringExtra("title"), i.getStringExtra("show"), i.getLongExtra("duration", 0));
        } else if (TOGGLE.equals(a)) {
            if (!active) return START_NOT_STICKY;
            if (paused) resume(); else pause();
        } else if (SEEK.equals(a)) {
            seek(i.getLongExtra("by", 0));
        } else {
            stopAll();
        }
        return START_NOT_STICKY;
    }

    private void play(final String u, String t, String s, long dur) {
        save();
        release();
        if (u != null && !u.equals(url)) retries = 0;
        url = u; title = t; show = s; knownDuration = dur;
        active = true; paused = false; pausedForFocus = false;
        set("loading…");
        if (audio.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { set("audio busy"); return; }
        if (!wifiLock.isHeld()) wifiLock.acquire();
        startForeground(1, notification());
        new Thread(new Opener(u, ++generation)).start();
    }

    /** Opens the file off the main thread, then starts MediaPlayer on it. */
    private class Opener implements Runnable {
        final String u; final int gen;
        RangeSource src; String error;
        Opener(String u, int gen) { this.u = u; this.gen = gen; }

        @Override public void run() {
            if (src == null && error == null) {
                try { src = new RangeSource(u); } catch (Exception e) { error = e.getMessage(); }
                main.post(this);
                return;
            }
            if (gen != generation) { if (src != null) src.close(); return; }
            if (error != null) { failed("can't open: " + error); return; }
            start(src, gen);
        }
    }

    private void start(RangeSource src, final int gen) {
        source = src;
        prepared = false;
        try {
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer m) {
                    if (gen != generation) return;
                    prepared = true;
                    long at = feeds.position(url);
                    if (at > 0 && (m.getDuration() <= 0 || at < m.getDuration() - 5000)) m.seekTo((int) at);
                    m.start();
                    set("playing");
                    main.removeCallbacks(saver);
                    main.postDelayed(saver, 10000);
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer m) {
                    if (gen != generation) return;
                    long d = m.getDuration(), at = m.getCurrentPosition();
                    if (d > 0 && at > 0 && d - at > 60000) {          // stopped early: the stream broke, carry on from here
                        feeds.savePosition(url, at, d);
                        play(url, title, show, knownDuration);
                        return;
                    }
                    feeds.setPlayed(url, true);
                    stopAll();
                    set("finished");
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer m, int what, int extra) {
                    if (gen != generation) return true;
                    if (prepared && retries++ < 3) {                  // mid-episode: reopen at the same spot
                        save();
                        play(url, title, show, knownDuration);
                    } else failed("can't play this episode");
                    return true;
                }
            });
            mp.setDataSource(src);
            mp.prepareAsync();
        } catch (Exception e) {
            failed("can't play this episode");
        }
    }

    private void pause() {
        if (player == null || !prepared) return;
        try { player.pause(); } catch (Exception ignored) { }
        paused = true;
        save();
        main.removeCallbacks(saver);
        if (wifiLock.isHeld()) wifiLock.release();   // paused: don't keep the Wi-Fi radio in high-perf
        set("paused");
    }

    private void resume() {
        if (player == null || !prepared) { play(url, title, show, knownDuration); return; }
        audio.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        if (!wifiLock.isHeld()) wifiLock.acquire();
        try { player.start(); } catch (Exception e) { failed("can't resume"); return; }
        paused = false; pausedForFocus = false;
        main.removeCallbacks(saver);
        main.postDelayed(saver, 10000);
        set("playing");
    }

    private void seek(long by) {
        if (player == null || !prepared) return;
        try {
            long to = Math.max(0, player.getCurrentPosition() + by);
            if (player.getDuration() > 0) to = Math.min(to, player.getDuration() - 1000);
            player.seekTo((int) to);
            save();
        } catch (Exception ignored) { }
        updateSession();
        if (listener != null) listener.onPodState();
    }

    private void save() {
        if (url != null && player != null && prepared) {
            try { feeds.savePosition(url, player.getCurrentPosition(), player.getDuration()); } catch (Exception ignored) { }
        }
    }

    private void failed(String why) {
        release();
        active = false; paused = false;
        set(why);
        finishForeground();
    }

    private void stopAll() {
        save();
        generation++;
        release();
        active = false; paused = false;
        audio.abandonAudioFocus(this);
        set("stopped");
        finishForeground();
    }

    private void finishForeground() {
        main.removeCallbacks(saver);
        if (wifiLock.isHeld()) wifiLock.release();
        stopForeground(true);
    }

    private void release() {
        prepared = false;
        if (player != null) { try { player.reset(); player.release(); } catch (Exception ignored) { } player = null; }
        if (source != null) { source.close(); source = null; }
    }

    @Override public void onAudioFocusChange(int change) {
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            if (active && !paused) pause();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {          // a call
            if (active && !paused) { pause(); pausedForFocus = true; }
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if (player != null) player.setVolume(0.2f, 0.2f);
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if (player != null) player.setVolume(1f, 1f);
            if (pausedForFocus && active && paused) resume();
        }
    }

    private void set(String s) {
        status = s;
        if (active) { try { startForeground(1, notification()); } catch (Exception ignored) { } }
        updateSession();
        tellHome();
        if (listener != null) listener.onPodState();
    }

    /** Tell the home screen's panel what's playing, so it can show play/stop above the notifications. */
    private void tellHome() {
        try {
            sendBroadcast(new Intent("dumb_phone.home.NOWPLAYING").setPackage("dumb_phone.home")
                    .putExtra("pkg", getPackageName())
                    .putExtra("active", active).putExtra("paused", paused)
                    .putExtra("title", title == null ? "" : title)
                    .putExtra("sub", show == null ? "" : show));
        } catch (Exception ignored) { }
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent toggle = PendingIntent.getService(this, 1, new Intent(this, PodService.class).setAction(TOGGLE), PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this)
                .setSmallIcon(paused ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(show + (status.equals("playing") ? "" : " · " + status))
                .setContentIntent(open)
                .setOngoing(!paused)
                .setShowWhen(false)
                .addAction(paused ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause, paused ? "play" : "pause", toggle)
                .build();
    }

    @Override public void onDestroy() {
        stopAll();
        if (session != null) { try { session.release(); } catch (Exception ignored) { } session = null; }
        unregisterReceiver(noisy);
        self = null;
    }
}
