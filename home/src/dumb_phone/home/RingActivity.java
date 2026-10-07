package dumb_phone.home;

import android.app.Activity;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** A ringing alarm or finished timer: full screen over everything, OK / any key stops, * snoozes (alarms). */
public class RingActivity extends Activity {
    private MediaPlayer player;
    private Vibrator vib;
    private final Handler h = new Handler();
    private boolean alarm;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        Theme.load(this);
        alarm = !"timer".equals(getIntent().getStringExtra("what"));
        Typeface mono = Typeface.createFromAsset(getAssets(), "JetBrainsMono-Regular.ttf");
        Typeface bold = Typeface.createFromAsset(getAssets(), "JetBrainsMono-Bold.ttf");
        Typeface icons = Typeface.createFromAsset(getAssets(), "phosphor-icons.ttf");
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER);
        v.setBackgroundColor(Theme.VOID);
        v.addView(text(new String(Character.toChars(alarm ? 0xF0020 : 0xF13AB)), icons, 44, Theme.AMBER));
        v.addView(text(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()), bold, 48, Theme.GREEN));
        v.addView(text(alarm ? "alarm" : "time's up", mono, 16, Theme.AMBER));
        TextView hint = text(alarm ? "\n[OK] stop   [*] snooze 9 min" : "\n[OK] stop   [*] 1 more minute", mono, 12, Theme.DIM);
        v.addView(hint);
        setContentView(v);
        start();
        h.postDelayed(new Runnable() { public void run() { stop(); } }, 10 * 60_000L);   // never ring forever
    }

    private TextView text(String s, Typeface tf, int sp, int col) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(tf);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(col);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private int oldVol = -1;

    private void start() {
        String k = alarm ? "alarm" : "timer";
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        boolean sound = getIntent().getBooleanExtra("loud", Clock.prefs(this).getBoolean(k + "Loud", true));   // "sound" toggle (default on); ignores the ringer mode
        if (am != null && sound) {                                // its own volume, put back afterwards
            int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM);
            oldVol = am.getStreamVolume(android.media.AudioManager.STREAM_ALARM);
            am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, Math.max(1, Math.round(max * getIntent().getIntExtra("vol", Clock.prefs(this).getInt(k + "Vol", 5)) / 7f)), 0);
        }
        if (sound) try {
            String f = getIntent().getStringExtra("sound");           // this alarm's / the timer's own sound
            java.io.File own = f == null || f.isEmpty() ? null : new java.io.File(SoundsActivity.DIR, f);
            if (own == null || !own.isFile()) own = Clock.fallbackSound(this);   // your Sounds alarm, or a library tone
            Uri u = own != null && own.isFile() ? Uri.fromFile(own) : RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            player.setDataSource(this, u);
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception e) { player = null; }
        vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        // "vibrate" toggle (default on); an alarm vibrates whatever the ringer mode is, or not at all.
        if (vib != null && getIntent().getBooleanExtra("vib", Clock.prefs(this).getBoolean((alarm ? "alarm" : "timer") + "Vib", true))) {
            // Tag it as an ALARM so the phone still vibrates when it's on silent / vibrate / Do Not Disturb
            // (a plain vibrate() is suppressed there). Falls back to the untagged call if that's refused.
            AudioAttributes va = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            try { vib.vibrate(new long[]{0, 600, 600}, 0, va); }
            catch (Throwable t) { vib.vibrate(new long[]{0, 600, 600}, 0); }
        }
    }

    private void stop() {
        h.removeCallbacksAndMessages(null);
        if (player != null) { try { player.stop(); player.release(); } catch (Exception ignored) { } player = null; }
        if (vib != null) vib.cancel();
        restoreVolume();
        finish();
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_STAR) { if (alarm) Clock.snooze(this, getIntent().getStringExtra("sound"), getIntent().getIntExtra("vol", 5)); else Clock.snoozeTimer(this); stop(); return true; }
        stop();
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) stop();
        return true;
    }

    private void restoreVolume() {
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null && oldVol >= 0) { am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, oldVol, 0); oldVol = -1; }
    }

    @Override protected void onDestroy() {
        restoreVolume();
        if (player != null || vib != null) { h.removeCallbacksAndMessages(null); if (player != null) try { player.release(); } catch (Exception ignored) { } if (vib != null) vib.cancel(); }
        super.onDestroy();
    }
}
