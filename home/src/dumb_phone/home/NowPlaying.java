package dumb_phone.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;

/**
 * What our own audio apps (podcasts, radio) last said they're playing. They broadcast on every state
 * change; VolumeService registers {@link #receiver} once so the pull-down panel can show a play/stop
 * row above the notifications. Anything else making sound (e.g. Auxio) is caught by isMusicActive().
 */
final class NowPlaying {
    static final String ACTION = "dumb_phone.home.NOWPLAYING";
    static String pkg = "", title = "", sub = "";
    static boolean active, paused;
    static long ts;

    static final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            pkg = s(i.getStringExtra("pkg"));
            active = i.getBooleanExtra("active", false);
            paused = i.getBooleanExtra("paused", false);
            title = s(i.getStringExtra("title"));
            sub = s(i.getStringExtra("sub"));
            ts = System.currentTimeMillis();
        }
    };

    static void register(Context c) {
        try { c.registerReceiver(receiver, new IntentFilter(ACTION)); } catch (Exception ignored) { }
    }

    private static String s(String v) { return v == null ? "" : v; }

    /** True when there is something to show a play/stop row for: audio is playing, or a podcast is paused. */
    static boolean showing(Context c) {
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am != null && am.isMusicActive()) return true;
        } catch (Exception ignored) { }
        return active && paused;               // a paused podcast: no sound, but still worth a resume/stop row
    }

    /** Whether to draw the button as "playing" (so the tap pauses) or "paused" (so the tap plays). */
    static boolean playingNow(Context c) {
        if (active && paused) return false;
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am != null && am.isMusicActive()) return true;
        } catch (Exception ignored) { }
        return active;
    }

    /** The label for the row: what our app told us if it's fresh, else a generic "music". */
    static String label(Context c) {
        boolean fresh = active && System.currentTimeMillis() - ts < 12 * 3600_000L;
        if (fresh && !title.isEmpty()) return title;
        return "music";
    }

    static String sublabel(Context c) {
        boolean fresh = active && System.currentTimeMillis() - ts < 12 * 3600_000L;
        if (fresh) {
            if (!sub.isEmpty()) return paused ? sub + " · paused" : sub;
            return paused ? "paused" : "playing";
        }
        return "playing";
    }
}
