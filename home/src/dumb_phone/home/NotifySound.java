package dumb_phone.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Vibrator;

import java.io.File;

/**
 * Notification sounds per app. Once you pick a message sound in Sounds, Android's own notification sound
 * is set to silent and this plays instead: the app's own sound if you gave it one, else the message sound.
 * Follows the ringer (normal = sound, vibrate = a buzz, silent = nothing), at most once per 2 s per app.
 */
final class NotifySound {
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("sounds", Context.MODE_PRIVATE); }
    static boolean active(Context c) { return prefs(c).contains("message"); }

    /** The sound file for an app ("" = the message sound). */
    static String forApp(Context c, String pkg) { return prefs(c).getString("app:" + pkg, ""); }

    private static long last;
    private static String lastPkg = "";
    private static MediaPlayer player;

    static void play(Context c, String pkg) {
        if (!active(c) || pkg.equals(c.getPackageName())) return;
        long now = System.currentTimeMillis();
        if (pkg.equals(lastPkg) && now - last < 2000) return;
        last = now; lastPkg = pkg;
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        int mode = am == null ? AudioManager.RINGER_MODE_NORMAL : am.getRingerMode();
        if (mode == AudioManager.RINGER_MODE_SILENT || forApp(c, pkg).equals("-")) return;   // "-" = silent for this app (no buzz either)
        if (mode == AudioManager.RINGER_MODE_VIBRATE) {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(new long[]{0, 180, 120, 180}, -1);
            return;
        }
        String f = forApp(c, pkg);
        if (f.isEmpty()) f = prefs(c).getString("message", "");
        if (f.isEmpty() || f.equals("-")) return;                  // "-" = silent for this app
        File file = new File(SoundsActivity.DIR, f);
        if (!file.isFile()) return;
        try {
            if (player != null) { player.release(); player = null; }
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            player.setDataSource(file.getPath());
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) { mp.release(); if (player == mp) player = null; }
            });
            player.prepare();
            player.start();
        } catch (Exception e) { player = null; }
    }
}
