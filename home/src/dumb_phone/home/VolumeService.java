package dumb_phone.home;

import static dumb_phone.home.Theme.AMBER;
import static dumb_phone.home.Theme.CELL;
import static dumb_phone.home.Theme.DIM;
import static dumb_phone.home.Theme.GREEN;
import static dumb_phone.home.Theme.LIT;
import static dumb_phone.home.Theme.RULE;
import static dumb_phone.home.Theme.SEL;
import static dumb_phone.home.Theme.VOID;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.telephony.TelephonyManager;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Takes the volume buttons so the stock grey volume bar never shows: changes the right volume
 * itself (call, music or ringer) and shows a small themed bar at the top for 1.5 s.
 * While a call is ringing the buttons are left to the system (they silence the ringer).
 */
public class VolumeService extends AccessibilityService {

    private final Handler handler = new Handler();
    private WindowManager wm;
    private LinearLayout bar;
    private TextView icon, label, value;
    private Meter meter;
    private Typeface mono, icons;
    private final Runnable hide = new Runnable() {
        @Override public void run() { if (bar != null) bar.setVisibility(View.GONE); }
    };

    static String g(int cp) { return new String(Character.toChars(cp)); }

    static VolumeService instance;
    private boolean learning;

    /** Default push-to-talk key: F9 (the side/back button on many Android keypad phones). */
    int voiceKey() { return getSharedPreferences("keys", MODE_PRIVATE).getInt("voice", KeyEvent.KEYCODE_F9); }

    static String keyName(int code) {
        return KeyEvent.keyCodeToString(code).replace("KEYCODE_", "").replace('_', ' ').toLowerCase(java.util.Locale.US);
    }

    /** Keys that can't be the voice key: the ones the phone needs for everything else. */
    private static boolean learnable(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN: case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_HOME: case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_ENDCALL:
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                return false;
        }
        return true;
    }

    /** Called from the home screen's "voice key" row: the next key pressed becomes the voice key. */
    void learnKey() {
        learning = true;
        say(0xF036C, "voice", "press the key to use for voice…", 0);
        handler.postDelayed(new Runnable() { @Override public void run() {
            if (learning) { learning = false; say(0xF036D, "voice", "no key pressed, still " + keyName(voiceKey()), 2000); }
        } }, 10000);
    }

    @Override protected void onServiceConnected() {
        instance = this;
        // our own charging chime (the stock one is switched off by the Guard): never on vibrate / silent
        android.content.IntentFilter pf = new android.content.IntentFilter(android.content.Intent.ACTION_POWER_CONNECTED);
        pf.addAction(android.content.Intent.ACTION_BATTERY_LOW);
        pf.addAction(android.content.Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, android.content.Intent i) {
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                if (am == null || am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) return;
                TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
                if (tm != null && tm.getCallState() != TelephonyManager.CALL_STATE_IDLE) return;
                if (android.content.Intent.ACTION_BATTERY_LOW.equals(i.getAction())) Chime.low();
                else if (android.content.Intent.ACTION_POWER_DISCONNECTED.equals(i.getAction())) Chime.unplugged();
                else Chime.plugged();
            }
        }, pf);
        Clock.schedule(this);                            // re-arm alarms + timer (an app update cancels them)
        Notifications.init(this);                        // the panel's notifications from before a restart
        addTopStrip();
        WhatsAppCheck.schedule(this);                    // WhatsApp fetches messages every 15 min while idle
        HotspotIdle.start(this);                         // a hotspot left on with nothing connected switches off
        NowPlaying.register(this);                       // what podcasts/radio are playing, for the panel's play/stop row
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, android.content.Intent i) {
                int st = i.getIntExtra("wifi_state", 0);
                if (st == 13) HotspotIdle.start(c); else if (st == 11) HotspotIdle.stop(c);   // 13 on, 11 off
            }
        }, new android.content.IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED"));                                   // the grey shade can't be pulled down: our panel instead
        CallRing.watch(this);                            // we play the ringtone ourselves (see CallRing)
                // battery level over time for "Screen time" (the system sends this anyway; we only note changes)
        android.content.Intent bat = registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, android.content.Intent i) { batteryLog(i); }
        }, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) batteryLog(bat);
        // screen on / off times for "Screen time" (it only counts app time while the screen was on, like the iPhone)
        android.os.PowerManager pmgr = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        screenLog(pmgr != null && pmgr.isInteractive());
        android.content.IntentFilter sf = new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_ON);
        sf.addAction(android.content.Intent.ACTION_SCREEN_OFF);
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, android.content.Intent i) {
                boolean on = android.content.Intent.ACTION_SCREEN_ON.equals(i.getAction());
                screenLog(on);
                if (!on) reloadVoiceIfRoom();
            }
        }, sf);
        android.content.IntentFilter lf = new android.content.IntentFilter("HALL_OFF");
        lf.addAction("HALL_ON");
        registerReceiver(lid, lf);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        mono = Typeface.createFromAsset(getAssets(), "JetBrainsMono-Bold.ttf");
        icons = Typeface.createFromAsset(getAssets(), "phosphor-icons.ttf");
        // test hook for adb (injected keys skip this service): am broadcast -a dumb_phone.home.DICTATE --es cmd start|stop
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, android.content.Intent i) {
                if ("stop".equals(i.getStringExtra("cmd"))) { stopDictation(); return; }
                if ("chime".equals(i.getStringExtra("cmd"))) { Chime.plugged(); return; }       // test: hear the chime
                if ("chime-low".equals(i.getStringExtra("cmd"))) { Chime.low(); return; }
                if ("chime-bye".equals(i.getStringExtra("cmd"))) { Chime.unplugged(); return; }
                if ("free".equals(i.getStringExtra("cmd"))) { if (voice != null) voice.free(); return; }
                if ("ring".equals(i.getStringExtra("cmd"))) { CallRing.migrate(c); CallRing.start(c, "test"); return; }
                if ("ringstop".equals(i.getStringExtra("cmd"))) { CallRing.stop(); return; }
                if ("setring".equals(i.getStringExtra("cmd")) && i.getStringExtra("file") != null) { CallRing.use(c, i.getStringExtra("file")); return; }   // set the ringtone from the computer
                if ("shade".equals(i.getStringExtra("cmd"))) { swapShade(); return; }
                if ("resetscreentime".equals(i.getStringExtra("cmd"))) { resetScreenTime(); return; }
                if ("btname".equals(i.getStringExtra("cmd"))) {            // rename Bluetooth (needs it on for a moment)
                    final android.bluetooth.BluetoothAdapter bt = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
                    if (bt == null) return;
                    final boolean wasOn = bt.isEnabled();
                    final String nm = i.getStringExtra("name") == null ? "dumb_phone" : i.getStringExtra("name");
                    if (!wasOn) bt.enable();
                    handler.postDelayed(new Runnable() { public void run() {
                        boolean ok = bt.setName(nm);
                        android.util.Log.i("dumb_phone-keys", "bluetooth name -> " + ok + " " + bt.getName());
                        handler.postDelayed(new Runnable() { public void run() { if (!wasOn) bt.disable(); } }, 2000);
                    } }, 4000);
                    return;
                }
                if ("apname".equals(i.getStringExtra("cmd"))) {            // rename the hotspot (network name)
                    android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                    try {
                        android.net.wifi.WifiConfiguration cf = (android.net.wifi.WifiConfiguration) android.net.wifi.WifiManager.class.getMethod("getWifiApConfiguration").invoke(wm);
                        android.util.Log.i("dumb_phone-hotspot", "hotspot name was " + cf.SSID);
                        cf.SSID = i.getStringExtra("name") == null ? "dumb_phone" : i.getStringExtra("name");
                        Object r = android.net.wifi.WifiManager.class.getMethod("setWifiApConfiguration", android.net.wifi.WifiConfiguration.class).invoke(wm, cf);
                        android.util.Log.i("dumb_phone-hotspot", "rename -> " + r + ", now " + ((android.net.wifi.WifiConfiguration) android.net.wifi.WifiManager.class.getMethod("getWifiApConfiguration").invoke(wm)).SSID);
                    } catch (Throwable t) { android.util.Log.i("dumb_phone-hotspot", "rename: " + (t.getCause() != null ? t.getCause() : t)); }
                    return;
                }
                if ("apcheck".equals(i.getStringExtra("cmd"))) { new HotspotIdle().onReceive(c, i); return; }   // test: run a hotspot check now
                if ("apoff".equals(i.getStringExtra("cmd"))) {             // test: can we stop the hotspot without the screen?
                    android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                    try { Object r = android.net.wifi.WifiManager.class.getMethod("setWifiApEnabled", android.net.wifi.WifiConfiguration.class, boolean.class).invoke(wm, null, false); android.util.Log.i("dumb_phone-hotspot", "setWifiApEnabled -> " + r); }
                    catch (Throwable t) { android.util.Log.i("dumb_phone-hotspot", "setWifiApEnabled: " + (t.getCause() != null ? t.getCause() : t)); }
                    try { Object r = android.net.wifi.WifiManager.class.getMethod("stopSoftAp").invoke(wm); android.util.Log.i("dumb_phone-hotspot", "stopSoftAp -> " + r); }
                    catch (Throwable t) { android.util.Log.i("dumb_phone-hotspot", "stopSoftAp: " + (t.getCause() != null ? t.getCause() : t)); }
                    try { android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                        android.net.ConnectivityManager.class.getMethod("stopTethering", int.class).invoke(cm, 0); android.util.Log.i("dumb_phone-hotspot", "stopTethering ok"); }
                    catch (Throwable t) { android.util.Log.i("dumb_phone-hotspot", "stopTethering: " + (t.getCause() != null ? t.getCause() : t)); }
                    return;
                }
                if ("fakecall".equals(i.getStringExtra("cmd"))) {          // test: take audio focus like a ringing call (8 s)
                    final AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                    final AudioManager.OnAudioFocusChangeListener none = new AudioManager.OnAudioFocusChangeListener() { public void onAudioFocusChange(int f) { } };
                    int r = am.requestAudioFocus(none, AudioManager.STREAM_RING, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                    android.util.Log.i("dumb_phone-keys", "fake call: focus " + r + ", music active " + am.isMusicActive());
                    handler.postDelayed(new Runnable() { public void run() {
                        android.util.Log.i("dumb_phone-keys", "fake call over, music active " + am.isMusicActive());
                        am.abandonAudioFocus(none);
                    } }, 8000);
                    return;
                }
                if ("focusring".equals(i.getStringExtra("cmd"))) { FocusRing.afterKey(VolumeService.this); return; }   // test: as after an arrow key
                if ("keypaddel".equals(i.getStringExtra("cmd"))) { keypadDelete(i.getStringExtra("hold")); return; }
                if ("wadel".equals(i.getStringExtra("cmd"))) { android.view.accessibility.AccessibilityNodeInfo b = typingBox(); android.util.Log.i("dumb_phone-keys", "wadel box=" + (b != null) + " deleted=" + (b != null && deleteBefore(b))); return; }
                if ("waleft".equals(i.getStringExtra("cmd"))) { android.view.accessibility.AccessibilityNodeInfo b = typingBox(); android.util.Log.i("dumb_phone-keys", "waleft " + (b != null && b.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY, granularity()))); return; }
                if ("rowok".equals(i.getStringExtra("cmd"))) { rowKey(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)); rowKey(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)); return; }
                if ("intolist".equals(i.getStringExtra("cmd"))) { android.util.Log.i("dumb_phone-keys", "intoList " + intoList()); return; }
                if ("wa".equals(i.getStringExtra("cmd"))) { WhatsAppCheck.check(c, "test"); return; }   // test: as if the 15 min passed      // test: as if the grey shade came down
                if ("load".equals(i.getStringExtra("cmd"))) { voice().load(); return; }
                if ("notify".equals(i.getStringExtra("cmd"))) {                                 // test: a sample notification
                    android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    nm.notify(4242, new android.app.Notification.Builder(VolumeService.this)
                            .setSmallIcon(android.R.drawable.stat_notify_chat).setAutoCancel(true)
                            .setContentTitle("Lucy").setContentText("are we still on for dinner tonight?").build());
                    return;
                }
                if (dictating) return;
                android.view.accessibility.AccessibilityNodeInfo f = findTextBox();
                android.util.Log.w("dumb_phone-voice", "test start, box=" + (f == null ? null : f.getClassName()));
                if (f != null) { target = f; startDictation(); }
            }
        }, new android.content.IntentFilter("dumb_phone.home.DICTATE"), "android.permission.DUMP", null);   // only adb can send it
        voice().load();                                     // once; it stays in memory so listening starts at once
    }

    /** The keypad's contacts key (F8) opens favourite contacts; the envelope key (F7) opens WhatsApp. */
    private boolean appKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() > 0) return true;   // the release never arrives here
        if (e.getKeyCode() == KeyEvent.KEYCODE_F8) { openFavs(); return true; }
        Intent i;
        {
            i = getPackageManager().getLaunchIntentForPackage("com.whatsapp");
            if (i == null) i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING);
        }
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
        return true;
    }

    /** Our favourite contacts (the contacts key; the system opens its photo contacts for it, we swap them for this). */
    private void openFavs() {
        try {
            startActivity(new Intent().setClassName("dumb_phone.phone", "dumb_phone.phone.PhoneActivity")
                    .putExtra("tab", "favs").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (Exception ignored) { }
    }

    @Override protected boolean onKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (learning && learnable(code)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) return true;
            learning = false;
            getSharedPreferences("keys", MODE_PRIVATE).edit().putInt("voice", code).apply();
            say(0xF036C, "voice", "voice key = " + keyName(code) + " (hold it in a text box)", 3000);
            return true;
        }
        if (code == voiceKey()) return dictationKey(e);
        if ((code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)
                && editKey(e)) return true;
        if (code == KeyEvent.KEYCODE_DPAD_DOWN && e.getAction() == KeyEvent.ACTION_DOWN && intoList()) return true;
        if ((code == KeyEvent.KEYCODE_4 || code == KeyEvent.KEYCODE_5 || code == KeyEvent.KEYCODE_6) && musicKey(e)) return true;
        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && rowKey(e)) return true;
        if (e.getAction() == KeyEvent.ACTION_DOWN && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT))
            FocusRing.afterKey(this);   // passes the key on
        if (code == KeyEvent.KEYCODE_ENDCALL) {
            // hold red ~0.7 s = the power menu (our red-key uses below would otherwise swallow the long press)
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                powerFired = false;
                handler.removeCallbacks(powerMenu);
                handler.postDelayed(powerMenu, 700);
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(powerMenu);
                if (powerFired) {                         // held: the power menu is up; skip the short-press action
                    boolean ours = redSwallow || swallowEndUp;  // only eat the key-up if we ate the key-down too
                    powerFired = false; redSwallow = false; swallowEndUp = false;
                    if (ours) return true;
                }
            }
        }
        if (code == KeyEvent.KEYCODE_ENDCALL && redOnHome(e)) return true;
        if (code == KeyEvent.KEYCODE_ENDCALL && saveFirst(e)) return true;
        if (code == KeyEvent.KEYCODE_CALL) return callKey(e);
        if (code == KeyEvent.KEYCODE_F8 || code == KeyEvent.KEYCODE_F7) return appKey(e);
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        if (tm != null && tm.getCallState() == TelephonyManager.CALL_STATE_RINGING) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) CallRing.stop();   // a volume key silences the ringtone, as usual
            return false;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() > 0 && held == code) return true;     // our own repeat handles holding
            held = code;
            step(code == KeyEvent.KEYCODE_VOLUME_UP);
            handler.removeCallbacks(repeat);
            handler.postDelayed(repeat, 400);                             // hold: keep stepping until released
        } else if (e.getAction() == KeyEvent.ACTION_UP) {
            held = 0;
            handler.removeCallbacks(repeat);
        }
        return true;                                    // eat down and up, so the stock bar never appears
    }

    private int held;                               // the volume key being held, 0 = none
    private final Runnable repeat = new Runnable() {
        @Override public void run() {
            if (held == 0) return;
            step(held == KeyEvent.KEYCODE_VOLUME_UP);
            handler.postDelayed(this, 110);
        }
    };

    private void step(boolean up) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        int stream = stream(am, tm);
        if (stream == AudioManager.STREAM_RING) {           // ring: below the last step comes vibrate, then silent (and back up)
            int mode = am.getRingerMode(), v = am.getStreamVolume(stream);
            try {
                if (!up && mode == AudioManager.RINGER_MODE_NORMAL && v <= 1) am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
                else if (!up && mode == AudioManager.RINGER_MODE_VIBRATE) am.setRingerMode(AudioManager.RINGER_MODE_SILENT);
                else if (up && mode == AudioManager.RINGER_MODE_SILENT) am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
                else if (up && mode == AudioManager.RINGER_MODE_VIBRATE) { am.setRingerMode(AudioManager.RINGER_MODE_NORMAL); am.setStreamVolume(stream, 1, 0); }
                else if (mode == AudioManager.RINGER_MODE_NORMAL) am.adjustStreamVolume(stream, up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
            } catch (SecurityException e) { am.adjustStreamVolume(stream, up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0); }
        } else am.adjustStreamVolume(stream, up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
        show(am, stream);
    }

    /** Which volume the buttons should change right now. */
    private static int stream(AudioManager am, TelephonyManager tm) {
        if (tm != null && tm.getCallState() == TelephonyManager.CALL_STATE_OFFHOOK) return AudioManager.STREAM_VOICE_CALL;
        if (am.isMusicActive()) return AudioManager.STREAM_MUSIC;
        return AudioManager.STREAM_RING;
    }

    private void show(AudioManager am, int stream) {
        if (wm == null) return;
        ensureBar();
        int v = am.getStreamVolume(stream), max = Math.max(1, am.getStreamMaxVolume(stream));
        String name = stream == AudioManager.STREAM_VOICE_CALL ? "call" : stream == AudioManager.STREAM_MUSIC ? "media" : "ring";
        int glyph;
        if (stream == AudioManager.STREAM_RING && am.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE) glyph = 0xF0566;
        else if (stream == AudioManager.STREAM_RING && am.getRingerMode() == AudioManager.RINGER_MODE_SILENT) glyph = 0xF0581;
        else if (v == 0) glyph = 0xF0581;
        else if (stream == AudioManager.STREAM_VOICE_CALL) glyph = 0xF03F6;
        else glyph = v * 3 > max * 2 ? 0xF057E : v * 3 > max ? 0xF0580 : 0xF057F;
        icon.setText(g(glyph));
        boolean ring = stream == AudioManager.STREAM_RING;
        String state = ring && am.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE ? "vibrate"
                : ring && am.getRingerMode() == AudioManager.RINGER_MODE_SILENT ? "silent" : v == 0 ? "mute" : String.valueOf(v);
        label.setText(name);
        meter.setVisibility(View.VISIBLE);
        meter.set(v, max);
        value.setGravity(Gravity.END);
        value.setEllipsize(null);
        value.setText(state);
        bar.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hide);
        handler.postDelayed(hide, 1500);
    }

    private String barTheme;

    /** Build the bar, or rebuild it if the colour theme changed since. */
    private void ensureBar() {
        Theme.load(this);
        if (bar != null && !Theme.name.equals(barTheme)) {
            try { wm.removeView(bar); } catch (Exception ignored) { }
            bar = null;
        }
        if (bar == null) { build(); barTheme = Theme.name; }
    }

    private void build() {
        float d = getResources().getDisplayMetrics().density;
        bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding((int) (8 * d), (int) (6 * d), (int) (8 * d), (int) (6 * d));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(VOID);
        bg.setStroke(Math.max(1, (int) d), GREEN);
        bar.setBackground(bg);
        icon = new TextView(this);
        icon.setTypeface(icons);
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        icon.setTextColor(AMBER);
        icon.setPadding(0, 0, (int) (8 * d), 0);
        label = new TextView(this);
        label.setTypeface(mono);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTextColor(GREEN);
        label.setSingleLine(true);
        label.setPadding(0, 0, (int) (8 * d), 0);
        meter = new Meter(this);
        value = new TextView(this);
        value.setTypeface(mono);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        value.setTextColor(GREEN);
        value.setSingleLine(true);
        value.setPadding((int) (8 * d), 0, 0, 0);
        bar.addView(icon);
        bar.addView(label);
        bar.addView(meter, new LinearLayout.LayoutParams(0, (int) (10 * d), 3));   // fills most of the width
        bar.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP;
        lp.y = (int) (4 * d);
        lp.x = 0;
        bar.setVisibility(View.GONE);
        wm.addView(bar, lp);
    }

    /** One block per volume step, spread across all the space it is given. */
    static final class Meter extends View {
        private final android.graphics.Paint on = new android.graphics.Paint(), off = new android.graphics.Paint();
        private int level, max = 1;
        Meter(Context c) { super(c); on.setColor(GREEN); off.setColor(DIM); }
        void set(int level, int max) { this.level = level; this.max = Math.max(1, max); invalidate(); }
        @Override protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), h = getHeight(), gap = Math.max(1f, w / max * 0.18f);
            float step = (w + gap) / max;
            for (int i = 0; i < max; i++) {
                float x = i * step;
                c.drawRect(x, 0, x + step - gap, h, i < level ? on : off);
            }
        }
    }

    // ---- hotspot: Android 8 doesn't let apps switch it, so flip the switch on Android's own screen ----

    private boolean hotspotWanted, hotspotPending;
    private long hotspotUntil;

    /** Called just before the panel opens the Wi-Fi hotspot screen. */
    void flipHotspotThenReturn(boolean on) {
        hotspotWanted = on;
        hotspotPending = true;
        hotspotUntil = android.os.SystemClock.uptimeMillis() + 8000;
        for (int ms : new int[]{900, 1700, 2600, 3600}) handler.postDelayed(new Runnable() { public void run() { hotspotTry(); } }, ms);
    }

    private void hotspotScreen() { handler.postDelayed(new Runnable() { public void run() { hotspotTry(); } }, 500); }

    /** If the hotspot screen is showing: flip its switch to the wanted state, then go home. */
    private void hotspotTry() {
        if (!hotspotPending || android.os.SystemClock.uptimeMillis() > hotspotUntil) { hotspotPending = false; return; }
        {
            android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
            android.util.Log.w("dumb_phone-hotspot", "try: root=" + (root == null ? null : root.getPackageName()) + " want=" + hotspotWanted);
            if (root == null || root.getPackageName() == null || !"com.android.settings".equals(root.getPackageName().toString())) return;
            android.view.accessibility.AccessibilityNodeInfo sw = null, bar = null;
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> ids = root.findAccessibilityNodeInfosByViewId("com.android.settings:id/switch_widget");
            if (ids != null && !ids.isEmpty()) sw = ids.get(0);
            if (sw == null) sw = firstCheckable(root);
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> bars = root.findAccessibilityNodeInfosByViewId("com.android.settings:id/switch_bar");
            if (bars != null && !bars.isEmpty()) bar = bars.get(0);
            else if (sw != null) bar = sw.getParent();
            android.util.Log.w("dumb_phone-hotspot", "switch=" + (sw == null ? null : sw.getViewIdResourceName() + " checked=" + sw.isChecked()));
            if (sw == null) return;
            hotspotPending = false;
            if (sw.isChecked() != hotspotWanted) {
                // tap the whole switch bar, like a finger does (the bar toggles its switch and starts the hotspot)
                android.view.accessibility.AccessibilityNodeInfo n = bar != null && bar.isClickable() ? bar : sw;
                while (n != null && !n.isClickable()) n = n.getParent();
                boolean ok = n != null && n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
                android.util.Log.w("dumb_phone-hotspot", "clicked " + (n == null ? null : n.getClassName()) + " ok=" + ok);
            }
            handler.postDelayed(new Runnable() { @Override public void run() { performGlobalAction(GLOBAL_ACTION_HOME); } }, 900);
        }
    }

    private static android.view.accessibility.AccessibilityNodeInfo firstCheckable(android.view.accessibility.AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isCheckable()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo f = firstCheckable(n.getChild(i));
            if (f != null) return f;
        }
        return null;
    }

    // ---- red key in a notes app: Back first (that is when Notally saves), then home ----

    static final java.util.Set<String> SAVE_ON_BACK = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.omgodse.notally", "com.philkes.notallyx"));
    private boolean swallowEndUp;

    private boolean redSwallow;

    /** Red key on the home screen with a background photo: hide / show the tiles instead of going home. */
    private boolean redOnHome(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_UP) {        // a short press: hide / show the tiles now
            if (!redSwallow) return false;
            redSwallow = false;
            HomeActivity h = HomeActivity.shown;
            if (h != null) h.toggleTiles();
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return redSwallow;
        if (e.getRepeatCount() > 0) return redSwallow;
        final HomeActivity h = HomeActivity.shown;
        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        if (h == null || !Background.on(this) || (tm != null && tm.getCallState() != TelephonyManager.CALL_STATE_IDLE)) return false;
        if (!h.canToggleTiles()) return false;           // panel / app list open: the red key works as usual
        redSwallow = true;
        return true;
    }

    // ---- typing in apps whose box ignores the keys (WhatsApp): back = delete, ← → = move the cursor ----

    static final java.util.Set<String> EDIT_APPS = new java.util.HashSet<>(java.util.Arrays.asList("com.whatsapp"));
    private int editDown;                                     // the key whose press we handled
    private android.view.accessibility.AccessibilityNodeInfo editBox;
    private final Runnable editRepeat = new Runnable() { @Override public void run() {
        if (editDown != KeyEvent.KEYCODE_BACK && editDown != KeyEvent.KEYCODE_DEL) return;
        if (editViaKeypad) { keypadDelete(); handler.postDelayed(this, 90); }
        else if (editBox != null && deleteBefore(editBox)) handler.postDelayed(this, 90);    // held: keep deleting
    } };
    private boolean editViaKeypad;

    /** Our keyboard does the deleting (it knows the word being typed); WhatsApp takes back before any
     *  keyboard sees it, so it's passed on from here. */
    private void keypadDelete() { keypadDelete(null); }

    /** hold = "down" (the keyboard deletes, then keeps going while held) / "up" (stop), null = one character. */
    private void keypadDelete(String hold) {
        Intent i = new Intent("dumb_phone.keypad.DELETE").setPackage("dumb_phone.keypad");
        if (hold != null) i.putExtra("hold", hold);
        sendBroadcast(i);
    }

    private boolean editKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DEL;
        if (editDown == 0 && e.getAction() == KeyEvent.ACTION_DOWN) {
            editViaKeypad = ourKeyboard();
            if (editViaKeypad && !back) return false;            // our keyboard moves the cursor itself
        }
        if (e.getAction() == KeyEvent.ACTION_UP) {
            if (editDown != code) return false;
            handler.removeCallbacks(editRepeat);
            if (editViaKeypad && (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DEL)) keypadDelete("up");
            editDown = 0; editBox = null;
            return true;
        }
        if (e.getRepeatCount() > 0) return editDown == code;
        android.view.accessibility.AccessibilityNodeInfo box = typingBox();
        if (box == null) return false;
        boolean done;
        if (back && editViaKeypad) { keypadDelete("down"); done = true; }
        else if (back) done = deleteBefore(box);                    // empty box: back leaves the chat as usual
        else done = box.performAction(code == KeyEvent.KEYCODE_DPAD_LEFT
                ? android.view.accessibility.AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
                : android.view.accessibility.AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, granularity());
        if (!done) return false;
        editDown = code; editBox = box;
        if (back && !editViaKeypad) handler.postDelayed(editRepeat, 450);   // our keyboard times the hold itself
        return true;
    }

    private static Bundle granularity() {
        Bundle b = new Bundle();
        b.putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                android.view.accessibility.AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER);
        b.putBoolean(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, false);
        return b;
    }

    /** True while the dumb_phone keyboard is the phone's keyboard. */
    private boolean ourKeyboard() {
        String m = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);
        return m != null && m.startsWith("dumb_phone.keypad/");
    }

    /** The focused text box with text in it, in one of EDIT_APPS; else null. */
    private android.view.accessibility.AccessibilityNodeInfo typingBox() {
        try {
            android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || root.getPackageName() == null || !EDIT_APPS.contains(root.getPackageName().toString())) return null;
            android.view.accessibility.AccessibilityNodeInfo f = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT);
            if (f == null || !f.isEditable() || f.isShowingHintText()) return null;     // the grey "Message" hint isn't text
            CharSequence t = f.getText();
            return t == null || t.length() == 0 ? null : f;
        } catch (Exception ex) { return null; }
    }

    /** Delete the character before the cursor (a whole emoji when it is one). False when there's nothing left. */
    private boolean deleteBefore(android.view.accessibility.AccessibilityNodeInfo box) {
        try {
            box.refresh();
            if (box.isShowingHintText()) return false;
            CharSequence t = box.getText();
            if (t == null || t.length() == 0) return false;
            int end = box.getTextSelectionEnd(), start = box.getTextSelectionStart();
            if (end < 0 || end > t.length()) end = t.length();
            if (start < 0 || start > end) start = end;
            String s = t.toString();
            if (start == end) {                                // no selection: one character back
                if (end == 0) return true;                     // cursor at the start: nothing to delete, stay in the chat
                start = s.offsetByCodePoints(end, -1);
            }
            String out = s.substring(0, start) + s.substring(end);
            Bundle a = new Bundle();
            a.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, out);
            if (!box.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, a)) return false;
            Bundle sel = new Bundle();
            sel.putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start);
            sel.putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, start);
            box.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
            return true;
        } catch (Exception ex) { return false; }
    }

    /** Fossify Messages: ↓ from the search bar doesn't reach the conversation list (its layout breaks the
     *  focus search). If the focus is in its top bar, put it on the first conversation instead. */
    private boolean intoList() {
        try {
            android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || root.getPackageName() == null || !"org.fossify.messages".contentEquals(root.getPackageName())) return false;
            android.view.accessibility.AccessibilityNodeInfo f = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT);
            String id = f == null ? null : f.getViewIdResourceName();
            if (id == null || !id.contains("toolbar")) return false;
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> rows =
                    root.findAccessibilityNodeInfosByViewId("org.fossify.messages:id/conversation_frame");
            if (rows == null || rows.isEmpty()) return false;
            return rows.get(0).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS);
        } catch (Exception e) { return false; }
    }

    /** In Auxio, like pocket's player: 5 play / pause, 4 previous, 6 next (not while typing in its search). */
    private int musicDown;                                   // the digit whose press we turned into a media key

    private boolean musicKey(KeyEvent e) {
        int code = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_UP) { if (musicDown != code) return false; musicDown = 0; return true; }
        if (e.getRepeatCount() > 0) return musicDown == code;
        try {
            android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || root.getPackageName() == null || !"org.oxycblt.auxio".contentEquals(root.getPackageName())) return false;
            android.view.accessibility.AccessibilityNodeInfo f = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT);
            if (f != null && f.isEditable()) return false;     // typing a search
        } catch (Exception ex) { return false; }
        int media = code == KeyEvent.KEYCODE_5 ? KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE : code == KeyEvent.KEYCODE_4 ? KeyEvent.KEYCODE_MEDIA_PREVIOUS : KeyEvent.KEYCODE_MEDIA_NEXT;
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, media));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, media));
        musicDown = code;
        return true;
    }

    /** Auxio: OK on a row's ⋯ button opens the row itself; holding OK opens the ⋯ menu. */
    private android.view.accessibility.AccessibilityNodeInfo rowMenu;
    private boolean rowHeld;
    private final Runnable rowLong = new Runnable() { @Override public void run() {
        rowHeld = true;
        if (rowMenu != null) rowMenu.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
    } };

    private boolean rowKey(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_UP) {
            if (rowMenu == null) return false;
            handler.removeCallbacks(rowLong);
            if (!rowHeld && rowMenu.getParent() != null) rowMenu.getParent().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
            rowMenu = null; rowHeld = false;
            return true;
        }
        if (e.getRepeatCount() > 0) return rowMenu != null;
        try {
            android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || root.getPackageName() == null || !"org.oxycblt.auxio".contentEquals(root.getPackageName())) return false;
            android.view.accessibility.AccessibilityNodeInfo f = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT);
            String id = f == null ? null : f.getViewIdResourceName();
            if (id == null || !id.endsWith("_menu") || f.getParent() == null || !f.getParent().isClickable()) return false;
            rowMenu = f; rowHeld = false;
            handler.postDelayed(rowLong, 500);
            return true;
        } catch (Exception ex) { return false; }
    }

    private boolean powerFired;
    private final Runnable powerMenu = new Runnable() { @Override public void run() {
        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        if (tm != null && tm.getCallState() != TelephonyManager.CALL_STATE_IDLE) return;   // on a call red is hang-up
        powerFired = true;
        performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
        android.util.Log.i("dumb_phone-keys", "red held: power menu");
    } };

    private boolean saveFirst(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_UP) {
            if (!swallowEndUp) return false;
            swallowEndUp = false;
            performGlobalAction(GLOBAL_ACTION_BACK);
            handler.postDelayed(new Runnable() { @Override public void run() { performGlobalAction(GLOBAL_ACTION_HOME); } }, 350);
            return true;
        }
        if (e.getRepeatCount() > 0) return swallowEndUp;
        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        if (tm != null && tm.getCallState() != TelephonyManager.CALL_STATE_IDLE) return false;   // calls: hang up as usual
        android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
        String pkg = root == null || root.getPackageName() == null ? "" : root.getPackageName().toString();
        if (!SAVE_ON_BACK.contains(pkg)) return false;
        swallowEndUp = true;
        return true;
    }

    // ---- green key: our contacts app instead of the old dialer ----

    private boolean swallowCallUp;

    private boolean callKey(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_UP) {
            if (swallowCallUp) { swallowCallUp = false; return true; }
            return false;
        }
        if (e.getRepeatCount() > 0) return swallowCallUp;
        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        if (tm != null && tm.getCallState() != TelephonyManager.CALL_STATE_IDLE) return false;   // calls keep the key
        android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
        String pkg = root == null || root.getPackageName() == null ? "" : root.getPackageName().toString();
        if (pkg.equals("dumb_phone.phone") || pkg.equals(getPackageName())) return false;     // they use green themselves
        // the phone's own dial / call screens: green = CALL. Emergency numbers (000, 112) always end up on
        // the stock dial screen (apps may not call them directly), so this must never be taken over.
        if (pkg.equals("gwin.com.firefox") || pkg.equals("com.android.dialer") || pkg.equals("com.android.phone")
                || pkg.equals("com.android.server.telecom")) return false;
        try {
            startActivity(new android.content.Intent().setClassName("dumb_phone.phone", "dumb_phone.phone.PhoneActivity")
                    .putExtra("tab", "recents").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ex) { return false; }
        swallowCallUp = true;
        return true;
    }

    // ---- speak to type: hold the back button (F9) in a text box, let go to type it ----

    private static Voice voice;                             // one model per process, even if the service is re-created
    private android.view.accessibility.AccessibilityNodeInfo target;
    private boolean dictating, pressStarted;
    private long downAt;

    private boolean dictationKey(KeyEvent e) {
        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        if (tm != null && tm.getCallState() != TelephonyManager.CALL_STATE_IDLE) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) {
            // held = push to talk, so letting go sends; a quick tap keeps listening until the next tap
            if (pressStarted && dictating && e.getEventTime() - downAt >= 400) stopDictation();
            pressStarted = false;
            return true;
        }
        if (e.getRepeatCount() > 0) return true;
        if (dictating) { stopDictation(); return true; }                                         // second tap = send
        android.view.accessibility.AccessibilityNodeInfo f = findTextBox();
        if (f == null) { say(0xF036D, "voice", "open a text box first", 1500); return true; }
        target = f;
        downAt = e.getEventTime();
        pressStarted = true;
        startDictation();
        return true;
    }

    /** The box being typed in: the input focus, else a focused editable node, else the only/first editable one. */
    private android.view.accessibility.AccessibilityNodeInfo findTextBox() {
        android.view.accessibility.AccessibilityNodeInfo f = findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT);
        if (f != null && f.isEditable()) return f;
        android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        android.view.accessibility.AccessibilityNodeInfo first = null;
        java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> q = new java.util.ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            android.view.accessibility.AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            if (n.isEditable() && n.isVisibleToUser()) {
                if (n.isFocused()) return n;
                if (first == null) first = n;
            }
            for (int i = 0; i < n.getChildCount(); i++) q.add(n.getChild(i));
        }
        return first;
    }

    /** Apps that start much faster (8-9 s -> 5-6 s for WhatsApp) without the voice model in memory. */
    static final java.util.Set<String> HEAVY = new java.util.HashSet<>(java.util.Arrays.asList("com.whatsapp", "app.organicmaps"));

    /** Memory is tight (e.g. WhatsApp starting): let the voice model go; it comes back when the screen is off. */
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW && voice != null) {
            android.util.Log.w("dumb_phone-voice", "trim level " + level);
            voice.free();
        }
    }

    /** Screen off: reload the voice model so it's instant again, but only if there's room for it. */
    private void reloadVoiceIfRoom() {
        if (voice == null || voice.loaded()) return;
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        if (mi.availMem > 160L * 1024 * 1024) voice.load();
        else android.util.Log.w("dumb_phone-voice", "no room to reload (" + mi.availMem / 1048576 + " MB free)");
    }

    private Voice voice() {
        if (voice == null) {
            java.io.File dir = getExternalFilesDir(null);
            voice = new Voice(handler, new java.io.File(dir, "vosk-model").getAbsolutePath());
        }
        return voice;
    }

    private void startDictation() {
        if (voice().busy()) { say(0xF036C, "voice", "writing…", 0); return; }
        dictating = true;
        say(0xF036C, "speak", voice().loaded() ? "listening…" : "listening… (words in a few s)", 0);
        voice().start(new Voice.Listener() {
            @Override public void partial(String text) { if (dictating && !text.isEmpty()) say(0xF036C, "speak", text, 0); }
            @Override public void done(String text) {
                dictating = false;
                if (text == null || text.isEmpty()) say(0xF036D, "voice", "didn't catch that", 1500);
                else insert(text);
            }
            @Override public void failed(String why) { dictating = false; say(0xF036D, "voice", why, 2500); }
        });
    }

    private void stopDictation() {
        if (!dictating) return;
        say(0xF036C, "voice", "writing…", 0);
        voice().stop();
    }

    /**
     * Put the words in like typing: paste them at the cursor (the clipboard is put back after), so the
     * box's own text and its grey placeholder are left alone. Boxes that refuse paste get the words
     * appended with SET_TEXT instead.
     */
    private void insert(String text) {
        final android.view.accessibility.AccessibilityNodeInfo n = target;
        if (n == null) return;
        n.refresh();
        boolean o = android.os.Build.VERSION.SDK_INT >= 26;
        CharSequence cur = o && n.isShowingHintText() ? "" : n.getText();
        String base = cur == null ? "" : cur.toString();
        // Some apps (WhatsApp) report their grey placeholder ("Message") as the text of an empty box.
        CharSequence hint = o ? n.getHintText() : null;
        if (hint != null && base.equals(hint.toString())) base = "";
        int at = n.getTextSelectionStart();
        if (at < 0 || at > base.length()) at = base.length();
        boolean space = at > 0 && !Character.isWhitespace(base.charAt(at - 1));

        final android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        final android.content.ClipData old = cm.getPrimaryClip();
        cm.setPrimaryClip(android.content.ClipData.newPlainText("voice", (space ? " " : "") + text));
        boolean ok = n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE);
        handler.postDelayed(new Runnable() { @Override public void run() {      // give the copied thing back
            if (old != null) cm.setPrimaryClip(old);
        } }, 800);
        if (!ok) {
            String all = base.isEmpty() || base.endsWith(" ") ? base + text : base + " " + text;
            android.os.Bundle args = new android.os.Bundle();
            args.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, all);
            ok = n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            if (ok) {
                android.os.Bundle sel = new android.os.Bundle();
                sel.putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, all.length());
                sel.putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, all.length());
                n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
            }
        }
        say(ok ? 0xF036C : 0xF036D, "voice", ok ? text : "couldn't type into this box", ok ? 1200 : 2500);
    }

    /** The top bar as a message line (mic icon, label, text); 0 ms = stays until replaced. */
    private void say(int glyph, String name, String text, int ms) {
        if (wm == null) return;
        ensureBar();
        icon.setText(g(glyph));
        label.setText(name);
        meter.setVisibility(View.GONE);
        value.setText(text);
        value.setGravity(Gravity.START);
        value.setSingleLine(true);
        value.setEllipsize(android.text.TextUtils.TruncateAt.START);
        bar.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hide);
        if (ms > 0) handler.postDelayed(hide, ms);
    }

    /**
     * If the phone's old home screen ever comes to the front (e.g. the stock launcher left running from
     * boot, which some phones bring back on the home / end-call key), switch straight back to ours.
     */
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_FOCUSED) { FocusRing.focused(this, event); return; }
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) FocusRing.windowChanged(this);
        if (event.getPackageName() != null && "com.android.dialer".contentEquals(event.getPackageName()) && !CallRing.ringing()) {
            TelephonyManager t = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);   // the call screen is up:
            if (t != null && t.getCallState() == TelephonyManager.CALL_STATE_RINGING) { callRinging(); CallRing.start(this, "call screen"); }   // backup trigger
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {      // Android Go: no listener allowed
            android.os.Parcelable data = event.getParcelableData();
            if (data instanceof android.app.Notification && event.getPackageName() != null)
                if (Notifications.posted(event.getPackageName().toString(), (android.app.Notification) data)) {
                    NotifySound.play(this, event.getPackageName().toString());
                    WhatsAppCheck.posted(event.getPackageName().toString());
                }
            return;
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getPackageName() != null
                && HEAVY.contains(event.getPackageName().toString()) && voice != null && voice.loaded()) {
            voice.free();                                    // a big app is starting: give it the voice model's memory
        }
        // the phone's grey pull-down (can't be themed or switched off): close it and open our panel instead
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getPackageName() != null
                && "com.android.systemui".equals(event.getPackageName().toString())) {
            String t = String.valueOf(event.getText()).toLowerCase(java.util.Locale.US);
            String cls = String.valueOf(event.getClassName());
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            boolean call = tm != null && tm.getCallState() != TelephonyManager.CALL_STATE_IDLE;
            android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            boolean locked = km != null && km.isKeyguardLocked();
            // its name isn't always sent: also any SystemUI pull-down frame that isn't the volume or recents screen
            boolean shade = t.contains("shade") || t.contains("quick settings") || t.contains("notification")
                    || (cls.endsWith("FrameLayout") && !t.contains("volume") && !t.contains("recent"));
            android.util.Log.i("dumb_phone-shade", "systemui " + cls + " " + t + " -> " + (shade && !call && !locked));
            if (shade && !call && !locked && !shadePending) swapShade();
            if (shade && !call && !locked) return;
        }

        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (event.getPackageName() != null && "android".contentEquals(event.getPackageName())) {   // "X has stopped" dialog
            String t = String.valueOf(event.getText());
            if (t.contains(" has stopped") || t.contains(" keeps stopping")) Health.noteCrash(this, t);
        }
        if (event.getPackageName() != null) Notifications.opened(event.getPackageName().toString());
        if (event.getClassName() != null && event.getClassName().toString().endsWith("TetherWifiSettingsActivity")) hotspotScreen();
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getClassName() != null
                && "gwin.com.firefox.contact.PhotoContactShow".equals(event.getClassName().toString())) {
            performGlobalAction(GLOBAL_ACTION_BACK);              // the contacts key opened the stock photo contacts
            openFavs();
            return;
        }
        CharSequence pkg = event.getPackageName(), cls = event.getClassName();
        if (pkg == null || cls == null || pkg.toString().equals(getPackageName())) return;
        if (!otherHomes().contains(pkg + "/" + cls)) return;
        if (!flipOpen()) return;                  // shut: HALL_ON brings ours up when it opens
        android.util.Log.w("dumb_phone-home", "old home screen came up (" + cls + "), back to ours");
        showHome();
    }

    /** Append "1 time" (screen on) or "0 time" (off) to files/screen.log; keeps the last 8 days. */
    private int lastLevel = -1, lastPlug = -1;

    /** Battery level log for "Screen time": one line "time level plugged" whenever the level or the plug changes. */
    private void batteryLog(android.content.Intent i) {
        int level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
        int plug = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0 ? 1 : 0;
        if (level < 0) return;
        level = level * 100 / Math.max(1, scale);
        if (level == lastLevel && plug == lastPlug) return;
        lastLevel = level; lastPlug = plug;
        java.io.File f = new java.io.File(getFilesDir(), "battery.log");
        try {
            if (f.length() > 64_000) {                              // keep the last 8 days
                long cut = System.currentTimeMillis() - 8 * 86_400_000L;
                StringBuilder keep = new StringBuilder();
                for (String line : readAll(f).split("\n")) {
                    String[] p = line.trim().split(" ");
                    if (p.length == 3 && Long.parseLong(p[0]) >= cut) keep.append(line).append('\n');
                }
                try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(keep.toString().getBytes("UTF-8")); }
            }
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f, true)) {
                o.write((System.currentTimeMillis() + " " + level + " " + plug + "\n").getBytes("UTF-8"));
            }
        } catch (Exception ignored) { }
    }

    /** Start Screen time over from now (both logs), with the current screen state and battery level. */
    void resetScreenTime() {
        new java.io.File(getFilesDir(), "screen.log").delete();
        new java.io.File(getFilesDir(), "battery.log").delete();
        new java.io.File(getFilesDir(), "wa.log").delete();
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && pm.isInteractive()) screenLog(true);
        lastLevel = -1; lastPlug = -1;
        android.content.Intent b = registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        if (b != null) batteryLog(b);
        android.util.Log.i("dumb_phone-home", "screen time reset");
    }

    private void screenLog(boolean on) {
        java.io.File f = new java.io.File(getFilesDir(), "screen.log");
        try {
            if (f.length() > 64_000) {                              // trim: keep lines from the last 8 days
                long cut = System.currentTimeMillis() - 8 * 86_400_000L;
                StringBuilder keep = new StringBuilder();
                for (String line : readAll(f).split("\n")) {
                    String[] p = line.trim().split(" ");
                    if (p.length == 2 && Long.parseLong(p[1]) >= cut) keep.append(line).append('\n');
                }
                try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(keep.toString().getBytes("UTF-8")); }
            }
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f, true)) {
                o.write(((on ? "1 " : "0 ") + System.currentTimeMillis() + "\n").getBytes("UTF-8"));
            }
        } catch (Exception ignored) { }
    }

    /** Whole file as text ("" if missing); works on every Android version. */
    static String readAll(java.io.File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        } catch (Exception e) { return ""; }
    }

    private long lastHome;

    /**
     * Back to our home screen. A normal startActivity from an app is held for ~5 s after a home/end key
     * press (Android's "app switch" protection), which is exactly when the stock launcher jumps in, so
     * press Home as the system instead (performGlobalAction): Home resolves to us, with no delay.
     */
    private void showHome() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastHome < 1200) return;               // never loop
        lastHome = now;
        if (!performGlobalAction(GLOBAL_ACTION_HOME)) {
            try {
                startActivity(new android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_HOME)
                        .setPackage(getPackageName())
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) { }
        }
    }

    private boolean lidShut;
    private boolean flipOpen() { return !lidShut; }

    /**
     * Flip phones with a hall sensor (e.g. Opel TouchFlip) broadcast HALL_OFF when shut and HALL_ON when
     * opened, and the stock launcher brings itself to the front on them. Answer both: when the flip
     * opens, our home is already up before the screen shows anything.
     */
    private final android.content.BroadcastReceiver lid = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, android.content.Intent i) {
            boolean shut = "HALL_OFF".equals(i.getAction());
            lidShut = shut;
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null && tm.getCallState() != TelephonyManager.CALL_STATE_IDLE) return;   // never during calls
            if (!shut) { showHome(); return; }
            handler.postDelayed(new Runnable() { @Override public void run() { if (lidShut) showHome(); } }, 700);
        }
    };

    private java.util.Set<String> homes;

    /** Every home-screen activity on the phone except ours ("package/class"). */
    private java.util.Set<String> otherHomes() {
        if (homes != null) return homes;
        homes = new java.util.HashSet<>();
        for (android.content.pm.ResolveInfo r : getPackageManager().queryIntentActivities(
                new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0)) {
            String p = r.activityInfo.packageName;
            if (p.equals(getPackageName()) || p.equals("com.android.settings")) continue;    // settings' fallback home is harmless
            homes.add(p + "/" + r.activityInfo.name);
        }
        return homes;
    }

    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        if (bar != null && wm != null) { try { wm.removeView(bar); } catch (Exception ignored) { } }
        super.onDestroy();
    }


    /** The grey shade came down: once the swipe is over, fold it away and show our panel instead. */
    private void swapShade() {
                // wait until the finger is off the screen: closing the shade mid-swipe crashes this phone's
        // System UI (PhoneStatusBarView.onTouchEvent IndexOutOfBounds, "System UI has stopped")
        shadePending = true;
        handler.postDelayed(new Runnable() { public void run() {
            shadePending = false;
            final HomeActivity h = HomeActivity.shown;
            android.util.Log.i("dumb_phone-shade", "swap now, on home=" + (h != null));
            if (h != null && h.focused) return;      // home has the keys again: no shade (a dialog came and went)
            if (h != null) {                         // on home: BACK folds the shade away, then our panel right here
                // (not CLOSE_SYSTEM_DIALOGS: from an app it doesn't fold it here and the stock launcher answers it
                // with a HOME press, which rebuilds home and drops our panel)
                boolean back = performGlobalAction(GLOBAL_ACTION_BACK);
                android.util.Log.i("dumb_phone-shade", "swap on home, back=" + back);
                handler.postDelayed(new Runnable() { public void run() {
                    HomeActivity now = HomeActivity.shown;
                    android.util.Log.i("dumb_phone-shade", "show panel, home=" + (now != null));
                    if (now != null) now.showPanel();
                } }, 120);
                return;
            }
            sendBroadcast(new android.content.Intent(android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS));   // collapses it
            performGlobalAction(GLOBAL_ACTION_BACK);
            handler.postDelayed(new Runnable() { public void run() {
                try {
                    startActivity(new android.content.Intent(VolumeService.this, HomeActivity.class)
                            .putExtra("panel", true).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) { }
            } }, 150);
        } }, 450);
    }

    /**
     * An invisible strip over the status bar (accessibility overlays sit above it). A swipe down from the
     * top lands here instead of on the phone's grey shade, which then never opens: we open our panel.
     * (Closing the grey shade after it opened was unreliable on this phone and could crash System UI.)
     */
    private void addTopStrip() {
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            float d = getResources().getDisplayMetrics().density;
            int h = Math.max(id > 0 ? getResources().getDimensionPixelSize(id) : 0, (int) (24 * d));
            View strip = new View(this);
            strip.setOnTouchListener(new View.OnTouchListener() {
                float y0; boolean done;
                @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                    if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) { y0 = e.getRawY(); done = false; }
                    else if (e.getAction() == android.view.MotionEvent.ACTION_MOVE && !done && e.getRawY() - y0 > 6 * getResources().getDisplayMetrics().density) {
                        done = true;
                        openPanel();
                    }
                    return true;
                }
            });
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, h,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP;
            wm.addView(strip, lp);
        } catch (Exception e) { android.util.Log.w("dumb_phone-shade", "no top strip", e); }
    }

    /** Our panel: on home right there, else home opens with it. */
    private void openPanel() {
        android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km != null && km.isKeyguardLocked()) return;
        HomeActivity h = HomeActivity.shown;
        if (h != null) { h.showPanel(); return; }
        try {
            startActivity(new android.content.Intent(this, HomeActivity.class)
                    .putExtra("panel", true).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) { }
    }

    /** Home lost focus: if it's the grey shade (it has the focus a moment later), swap it. */
    void shadeMaybe() {
        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if ((tm != null && tm.getCallState() != TelephonyManager.CALL_STATE_IDLE) || (km != null && km.isKeyguardLocked())) return;
        android.util.Log.i("dumb_phone-shade", "home lost focus");
        if (!shadePending) swapShade();
    }

    private boolean shadePending;                           // the grey shade will be closed once the swipe is over

    /** A call is ringing: give the call screen the voice model's memory (it reloads later, screen off). */
    void callRinging() {
        if (voice != null) try { voice.free(); } catch (Exception ignored) { }
    }
}
