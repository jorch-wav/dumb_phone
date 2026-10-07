package dumb_phone.home;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.telephony.TelephonyManager;

/**
 * Without Google services nothing wakes WhatsApp when a message arrives: it only gets messages while it
 * runs, and this phone (430 MB) closes it soon after. So every 15 minutes, while the screen is off, we
 * open WhatsApp in the background for a few seconds (it connects and posts the new messages), then go
 * back home. 15 min because idle Android 8 lets an app wake the phone only about every 9 minutes.
 */
public class WhatsAppCheck extends BroadcastReceiver {
    static final String WA = "com.whatsapp";
    static final long EVERY = 15 * 60_000L;

    /** Arm the next check (from VolumeService at start and after every check). */
    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent p = PendingIntent.getBroadcast(c, 7, new Intent(c, WhatsAppCheck.class), PendingIntent.FLAG_UPDATE_CURRENT);
        if (am == null) return;
        if (c.getPackageManager().getLaunchIntentForPackage(WA) == null) { am.cancel(p); return; }
        am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, android.os.SystemClock.elapsedRealtime() + EVERY, p);
    }

    @Override public void onReceive(Context c, Intent i) {
        schedule(c);
        check(c, "timer");
    }

    static void check(final Context c, String why) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
        boolean screenOn = pm != null && pm.isInteractive();
        boolean call = tm != null && tm.getCallState() != TelephonyManager.CALL_STATE_IDLE;
        Intent wa = c.getPackageManager().getLaunchIntentForPackage(WA);
        android.util.Log.i("dumb_phone-wa", "check (" + why + "): screenOn=" + screenOn + " call=" + call);
        if (screenOn || call || wa == null) return;            // you're using the phone, or on a call
        final long began = System.currentTimeMillis();
        final int lvl0 = level(c);
        fetched = 0; checking = true;                          // counts WhatsApp's new notifications meanwhile
        final PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dumb_phone:whatsapp");
        wl.acquire(30_000);
        try {
            c.startActivity(wa.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_NO_USER_ACTION));
        } catch (Exception e) { wl.release(); checking = false; return; }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() { public void run() {
            // back home (so the screen doesn't wake up on WhatsApp); it keeps running in the background a while
            VolumeService v = VolumeService.instance;
            if (v != null) v.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
            else c.startActivity(new Intent(c, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            android.util.Log.i("dumb_phone-wa", "back home, " + fetched + " new");
            checking = false;
            log(c, began, System.currentTimeMillis(), lvl0, level(c), fetched);
            if (wl.isHeld()) wl.release();
        } }, 15_000);
    }

    static boolean checking;                                   // a check is running now
    static int fetched;                                        // new WhatsApp notifications during it

    /** From VolumeService: a fresh notification arrived. */
    static void posted(String pkg) { if (checking && WA.equals(pkg)) fetched++; }

    static int level(Context c) {
        Intent b = c.registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return -1;
        return b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) * 100 / Math.max(1, b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100));
    }

    /** files/wa.log: one line per check "start end level-before level-after new-messages" (last 8 days kept). */
    static void log(Context c, long a, long b, int l0, int l1, int n) {
        java.io.File f = new java.io.File(c.getFilesDir(), "wa.log");
        try {
            if (f.length() > 32_000) {
                long cut = System.currentTimeMillis() - 8 * 86_400_000L;
                StringBuilder keep = new StringBuilder();
                for (String line : VolumeService.readAll(f).split("\n")) {
                    String[] p = line.trim().split(" ");
                    if (p.length == 5 && Long.parseLong(p[0]) >= cut) keep.append(line).append('\n');
                }
                try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(keep.toString().getBytes("UTF-8")); }
            }
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f, true)) {
                o.write((a + " " + b + " " + l0 + " " + l1 + " " + n + "\n").getBytes("UTF-8"));
            }
        } catch (Exception ignored) { }
    }
}
