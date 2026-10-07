package dumb_phone.home;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

import java.io.File;

/**
 * The ringtone for incoming calls, played by us straight from the sound library file.
 * Android's own ringer opens ringtones through the media service, which this phone (430 MB) kills
 * when a call starts the call screen, so it "rang" in silence. Sounds therefore sets Android's
 * ringtone to none and saves the choice here (prefs sounds/ringtone); VolumeService (never killed)
 * plays it while a call rings, only when the ringer is normal, at the ring volume.
 */
final class CallRing {
    private static MediaPlayer player;
    private static final android.os.Handler check = new android.os.Handler(android.os.Looper.getMainLooper());

    static String file(Context c) { return NotifySound.prefs(c).getString("ringtone", ""); }

    /** Watch calls (from VolumeService). Also moves an older Sounds choice over from Android's setting. */
    static void watch(final VolumeService s) {
        migrate(s);
        TelephonyManager tm = (TelephonyManager) s.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) return;
        tm.listen(new PhoneStateListener() {
            @Override public void onCallStateChanged(int state, String number) {
                android.util.Log.i("dumb_phone-ring", "call state " + state);
                if (state == TelephonyManager.CALL_STATE_RINGING) { s.callRinging(); start(s, "call state"); }
                else stop();
            }
        }, PhoneStateListener.LISTEN_CALL_STATE);
    }

    /** Take over Android's ringtone (and switch Android's off): a library file by name, any other readable
     *  file (the phone's built-in tones in /system/media) by its full path. */
    static void migrate(Context c) {
        if (!file(c).isEmpty()) return;
        try {
            android.net.Uri u = RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_RINGTONE);
            if (u == null) return;
            try (android.database.Cursor q = c.getContentResolver().query(u, new String[]{"_data"}, null, null, null)) {
                if (q == null || !q.moveToFirst() || q.getString(0) == null) return;
                File f = new File(q.getString(0));
                if (f.getPath().startsWith(SoundsActivity.DIR.getPath())) use(c, f.getName());
                else if (f.canRead()) use(c, f.getPath());
            }
        } catch (Exception ignored) { }
    }

    /** Make library file `name` the ringtone. */
    static void use(Context c, String name) {
        NotifySound.prefs(c).edit().putString("ringtone", name).apply();
        RingtoneManager.setActualDefaultRingtoneUri(c, RingtoneManager.TYPE_RINGTONE, null);
    }

    static void start(Context c, String why) {
        String f = file(c);
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        android.util.Log.i("dumb_phone-ring", "start (" + why + "): file=" + f + " playing=" + (player != null) + " ringer=" + (am == null ? -1 : am.getRingerMode()));
        if (f.isEmpty() || player != null) return;
        if (am == null || am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) return;
        if (am.getMode() == AudioManager.MODE_IN_COMMUNICATION) {   // already on a WhatsApp/VoIP call: don't ring over it
            android.util.Log.i("dumb_phone-ring", "in a VoIP call, leaving the ring to call-waiting");
            return;
        }
        File file = f.startsWith("/") ? new File(f) : new File(SoundsActivity.DIR, f);   // full path = a built-in tone
        if (!file.isFile()) file = Clock.fallbackSound(c);
        if (file == null || !file.isFile()) return;
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            player.setDataSource(file.getPath());
            player.setLooping(true);
            player.prepare();
            player.start();
            android.util.Log.i("dumb_phone-ring", "ringing with " + file.getName());
            final TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
            final long began = android.os.SystemClock.uptimeMillis();
            check.removeCallbacksAndMessages(null);
            check.postDelayed(new Runnable() { public void run() {      // never trust only the "call ended" message:
                if (player == null) return;                             // stop as soon as nothing rings any more
                boolean still = tm != null && tm.getCallState() == TelephonyManager.CALL_STATE_RINGING;
                if (!still || android.os.SystemClock.uptimeMillis() - began > 60_000) {
                    android.util.Log.i("dumb_phone-ring", "safety stop (ringing=" + still + ")");
                    stop();
                } else check.postDelayed(this, 1000);
            } }, 1000);
        } catch (Exception e) {
            android.util.Log.w("dumb_phone-ring", "couldn't play " + file, e);
            stop();
        }
    }

    static boolean ringing() { return player != null; }

    static void stop() {
        check.removeCallbacksAndMessages(null);
        if (player == null) return;
        android.util.Log.i("dumb_phone-ring", "stop");
        try { player.stop(); } catch (Exception ignored) { }
        try { player.release(); } catch (Exception ignored) { }
        player = null;
    }
}
