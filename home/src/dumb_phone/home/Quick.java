package dumb_phone.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.provider.Settings;

import java.util.List;

/**
 * Quick settings rows for the panel: brightness (← →), Wi-Fi, hotspot, ringer.
 * Brightness needs "modify system settings" (setup.sh: appops set dumb_phone.home WRITE_SETTINGS allow).
 * The hotspot can't be switched by normal apps on Android 8, so OK opens Android's hotspot screen and
 * the key service flips its switch, then comes back here.
 */
final class Quick {
    private static Panel.Toggles toggles;              // keeps which circle is picked across redraws
    static String g(int cp) { return new String(Character.toChars(cp)); }
    static void addTo(final HomeActivity h, final Panel p) {
        if (toggles != null) toggles.focus = 0;         // every time the panel opens: start at the first button
        if (toggles2 != null) toggles2.focus = 0;
        // ringer changed elsewhere (volume keys: last step -> vibrate -> silent): redraw its circle while open
        final android.content.BroadcastReceiver ring = new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { if (p.view.isAttachedToWindow()) p.refresh(); }
        };
        p.view.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(android.view.View v) {
                h.registerReceiver(ring, new android.content.IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION));
            }
            @Override public void onViewDetachedFromWindow(android.view.View v) {
                try { h.unregisterReceiver(ring); } catch (IllegalArgumentException ignored) { }
            }
        });
        // the next alarm and the timer (live), under the notifications; OK / tap opens them
        p.sources.add(new Panel.RowSource() {
            @Override public void addRows(List<Panel.Row> out) {
                Clock.Alarm next = Clock.nextAlarm(h);
                long end = Clock.prefs(h).getLong("timerEnd", 0), left = Clock.prefs(h).getLong("timerLeft", 0), now = System.currentTimeMillis();
                if (next == null && end <= now && left <= 0) return;
                out.add(new Panel.Header("alarms & timer"));
                if (next != null) {
                    long in = Clock.next(next, now) - now;
                    out.add(new Panel.Action(String.format("alarm  %02d:%02d", next.hour, next.minute),
                            "in " + (in / 3_600_000) + " h " + (in / 60_000 % 60) + " min · " + Clock.days(next.days),
                            new Runnable() { public void run() { h.startActivity(new Intent(h, ClockActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } }));
                }
                if (end > now || left > 0) {
                    long ms = end > now ? end - now : left, sec = (ms + 999) / 1000;
                    out.add(new Panel.Action(String.format("timer  %02d:%02d", sec / 60, sec % 60), end > now ? "running" : "paused",
                            new Runnable() { public void run() { h.startActivity(new Intent(h, TimerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } }));
                    tickWhileOpen(p);
                }
            }
        });
        Weather.addTo(h, p);                            // weather first, right under the notifications
        p.sources.add(new Panel.RowSource() {
            @Override public void addRows(List<Panel.Row> out) {
                out.add(new Panel.Header("quick settings"));
                final WifiManager wm = (WifiManager) h.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                final AudioManager am = (AudioManager) h.getSystemService(Context.AUDIO_SERVICE);
                final android.bluetooth.BluetoothAdapter bt = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
                if (toggles == null) toggles = new Panel.Toggles();
                toggles.items.clear();
                toggles.items.add(new Panel.Toggles.T("wi-fi",
                        new Panel.Adjust.Getter() { public String value() { return g(wm != null && wm.isWifiEnabled() ? 0xF05A9 : 0xF05AA); } },
                        new java.util.concurrent.Callable<Boolean>() { public Boolean call() { return wm != null && wm.isWifiEnabled(); } },
                        new Runnable() { public void run() { if (wm != null) wm.setWifiEnabled(!wm.isWifiEnabled()); } }));
                toggles.items.add(new Panel.Toggles.T("hotspot",
                        new Panel.Adjust.Getter() { public String value() { return g(0xF0003); } },
                        new java.util.concurrent.Callable<Boolean>() { public Boolean call() { return hotspotOn(wm); } },
                        new Runnable() { public void run() { toggleHotspot(h, !hotspotOn(wm)); } }));
                toggles.items.add(new Panel.Toggles.T("bluetooth",
                        new Panel.Adjust.Getter() { public String value() { return g(bt != null && bt.isEnabled() ? 0xF00AF : 0xF00B2); } },
                        new java.util.concurrent.Callable<Boolean>() { public Boolean call() { return bt != null && bt.isEnabled(); } },
                        new Runnable() { public void run() { if (bt != null) { if (bt.isEnabled()) bt.disable(); else bt.enable(); } } }));
                toggles.items.add(new Panel.Toggles.T(ringer(am),
                        new Panel.Adjust.Getter() { public String value() {
                            int m = am == null ? AudioManager.RINGER_MODE_NORMAL : am.getRingerMode();
                            return g(m == AudioManager.RINGER_MODE_VIBRATE ? 0xF0566 : m == AudioManager.RINGER_MODE_SILENT ? 0xF0581 : 0xF057E); } },
                        new java.util.concurrent.Callable<Boolean>() { public Boolean call() { return am != null && am.getRingerMode() == AudioManager.RINGER_MODE_NORMAL; } },
                        new Runnable() { public void run() { nextRinger(am); } }));
                toggles.items.get(0).more = open(h, new Intent(Settings.ACTION_WIFI_SETTINGS));
                toggles.items.get(1).more = open(h, new Intent().setComponent(new ComponentName("com.android.settings",
                        "com.android.settings.Settings$TetherWifiSettingsActivity")));
                toggles.items.get(2).more = open(h, new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                toggles.items.get(3).more = open(h, new Intent(Settings.ACTION_SOUND_SETTINGS));
                out.add(toggles);
                // second row: alarms & timer (lit when one is set; label = the next alarm), flashlight
                if (toggles2 == null) toggles2 = new Panel.Toggles();
                toggles2.items.clear();
                final Clock.Alarm next = Clock.nextAlarm(h);
                final long end = Clock.prefs(h).getLong("timerEnd", 0);
                Panel.Toggles.T al = new Panel.Toggles.T(next != null ? String.format("%02d:%02d", next.hour, next.minute) : "alarms",
                        new Panel.Adjust.Getter() { public String value() { return g(0xF0020); } },
                        new java.util.concurrent.Callable<Boolean>() { public Boolean call() { return next != null; } },
                        new Runnable() { public void run() { h.startActivity(new Intent(h, ClockActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } });
                al.more = al.flip;
                toggles2.items.add(al);
                long left = end - System.currentTimeMillis();
                Panel.Toggles.T tm = new Panel.Toggles.T(left > 0 ? (left / 60_000 + 1) + " min" : "timer",
                        new Panel.Adjust.Getter() { public String value() { return g(0xF13AB); } },
                        new java.util.concurrent.Callable<Boolean>() { public Boolean call() { return end > System.currentTimeMillis(); } },
                        new Runnable() { public void run() { h.startActivity(new Intent().setClassName(h, "dumb_phone.home.TimerActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } });
                tm.more = tm.flip;
                toggles2.items.add(tm);
                Panel.Toggles.T fl = new Panel.Toggles.T("torch",
                        new Panel.Adjust.Getter() { public String value() { return g(torch ? 0xF0241 : 0xF0243); } },
                        new java.util.concurrent.Callable<Boolean>() { public Boolean call() { return torch; } },
                        new Runnable() { public void run() { setTorch(h, !torch); } });
                toggles2.items.add(fl);
                out.add(toggles2);
                out.add(new Panel.Adjust("brightness",
                        new Panel.Adjust.Getter() { public String value() { return bar(brightness(h)); } },
                        new Panel.Adjust.Stepper() { public void by(int d) { setBrightness(h, brightness(h) + d); } }));
            }
        });
        p.refresh();
    }

    /** A long-press action that opens a settings screen (falls back to the main settings). */
    static Runnable open(final HomeActivity h, final Intent i) {
        return new Runnable() { public void run() {
            try { h.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception e) { h.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        } };
    }

    /** Redraw the panel every second while it is open and a timer runs (the countdown row). */
    private static final android.os.Handler tick = new android.os.Handler();
    private static void tickWhileOpen(final Panel p) {
        tick.removeCallbacksAndMessages(null);
        tick.postDelayed(new Runnable() { public void run() {
            if (p.view.isAttachedToWindow()) p.refresh();
        } }, 1000);
    }

    // ---- flashlight ----

    private static Panel.Toggles toggles2;
    static boolean torch;
    private static boolean torchWatch;

    static void setTorch(Context c, boolean on) {
        try {
            android.hardware.camera2.CameraManager cm = (android.hardware.camera2.CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
            if (!torchWatch) {                          // follow the torch's real state (other apps can switch it too)
                torchWatch = true;
                cm.registerTorchCallback(new android.hardware.camera2.CameraManager.TorchCallback() {
                    @Override public void onTorchModeChanged(String id, boolean enabled) { torch = enabled; }
                }, null);
            }
            for (String id : cm.getCameraIdList()) {
                Boolean flash = cm.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (flash != null && flash) { cm.setTorchMode(id, on); torch = on; return; }
            }
            android.widget.Toast.makeText(c, "no flashlight on this phone", android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            android.widget.Toast.makeText(c, "flashlight is busy (camera open?)", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    // ---- brightness: 6 steps ----

    static final int[] LEVELS = {8, 30, 70, 120, 180, 255};

    static int brightness(Context c) {
        int v = Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 120);
        int best = 0;
        for (int i = 0; i < LEVELS.length; i++) if (Math.abs(LEVELS[i] - v) < Math.abs(LEVELS[best] - v)) best = i;
        return best;
    }

    static void setBrightness(Context c, int step) {
        step = Math.max(0, Math.min(LEVELS.length - 1, step));
        try {
            Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, LEVELS[step]);
        } catch (Exception e) {
            android.widget.Toast.makeText(c, "brightness: run setup.sh once more", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    static String bar(int step) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < LEVELS.length; i++) b.append(i <= step ? '█' : '░');
        return b.toString();
    }

    // ---- hotspot ----

    static boolean hotspotOn(WifiManager wm) {
        try { return (Boolean) WifiManager.class.getMethod("isWifiApEnabled").invoke(wm); }
        catch (Exception e) { return false; }
    }

    static void toggleHotspot(HomeActivity h, boolean on) {
        if (!on && HotspotIdle.off(h)) return;          // off: quietly, no settings screen
        if (VolumeService.instance != null) VolumeService.instance.flipHotspotThenReturn(on);
        try {
            h.startActivity(new Intent().setComponent(new ComponentName("com.android.settings",
                    "com.android.settings.Settings$TetherWifiSettingsActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        } catch (Exception e) {
            h.startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    // ---- ringer ----

    static String ringer(AudioManager am) {
        if (am == null) return "?";
        switch (am.getRingerMode()) {
            case AudioManager.RINGER_MODE_VIBRATE: return "vibrate";
            case AudioManager.RINGER_MODE_SILENT: return "silent";
            default: return "ring";
        }
    }

    static void nextRinger(AudioManager am) {
        if (am == null) return;
        int m = am.getRingerMode();
        int next = m == AudioManager.RINGER_MODE_NORMAL ? AudioManager.RINGER_MODE_VIBRATE
                : m == AudioManager.RINGER_MODE_VIBRATE ? AudioManager.RINGER_MODE_SILENT : AudioManager.RINGER_MODE_NORMAL;
        try { am.setRingerMode(next); } catch (SecurityException e) { am.setRingerMode(AudioManager.RINGER_MODE_NORMAL); }
    }
}
