package dumb_phone.home;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;

/**
 * The Wi-Fi hotspot switches itself off after 10 minutes with no device connected (Android 8.1 has no
 * such option), so a forgotten hotspot doesn't drain the battery. Only runs while the hotspot is on:
 * a check every 5 minutes. Connected devices = entries in the ARP table on the hotspot's interface.
 */
public class HotspotIdle extends BroadcastReceiver {
    static final long EVERY = 5 * 60_000L, IDLE = 10 * 60_000L;

    static boolean on(Context c) {
        return Quick.hotspotOn((WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE));
    }

    /** The hotspot came on (or the service started with it on): start watching. */
    static void start(Context c) {
        if (!on(c)) { stop(c); return; }
        Clock.prefs(c).edit().putLong("apSeen", System.currentTimeMillis()).apply();   // the idle count starts now
        arm(c);
    }

    static void stop(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pending(c));
    }

    private static PendingIntent pending(Context c) {
        return PendingIntent.getBroadcast(c, 9, new Intent(c, HotspotIdle.class), PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void arm(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, android.os.SystemClock.elapsedRealtime() + EVERY, pending(c));
    }

    @Override public void onReceive(Context c, Intent i) {
        if (!on(c)) return;                                   // switched off meanwhile: stop checking
        long now = System.currentTimeMillis();
        int n = clients();
        if (n > 0) Clock.prefs(c).edit().putLong("apSeen", now).apply();
        long idle = now - Clock.prefs(c).getLong("apSeen", now);
        android.util.Log.i("dumb_phone-hotspot", "check: " + n + " connected, idle " + idle / 60_000 + " min");
        if (n == 0 && idle >= IDLE) { off(c); return; }
        arm(c);
    }

    /** Devices on the hotspot (ARP entries that aren't on the phone's own Wi-Fi link). */
    static int clients() {
        int n = 0;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/net/arp"))) {
            r.readLine();                                     // header
            for (String l; (l = r.readLine()) != null; ) {
                String[] p = l.trim().split("\\s+");
                if (p.length >= 6 && !p[5].equals("wlan0") && !p[2].equals("0x0") && !p[3].equals("00:00:00:00:00:00")) n++;
            }
        } catch (Exception ignored) { }
        return n;
    }

    /** Switch the hotspot off without any screen (allowed with WRITE_SETTINGS). */
    static boolean off(Context c) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.ConnectivityManager.class.getMethod("stopTethering", int.class).invoke(cm, 0);
            android.util.Log.i("dumb_phone-hotspot", "switched off (no devices)");
            stop(c);
            return true;
        } catch (Throwable t) {
            android.util.Log.w("dumb_phone-hotspot", "couldn't switch off: " + t);
            return false;
        }
    }
}
