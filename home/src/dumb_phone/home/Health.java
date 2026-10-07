package dumb_phone.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import java.io.File;

/**
 * A tiny health check for the home screen, so a broken app shows up at once instead of when you need it:
 * apps that can't open any more, and apps that crashed recently ("X has stopped", noted by the key
 * service from the screen messages it already receives). Costs nothing in the background: it only runs
 * when home is shown, at most every 10 minutes, and is a few package lookups.
 */
final class Health {
    private static long last;
    private static String cached = "";

    /** Apps worth watching: the texting app, WhatsApp and our own. */
    private static final String[][] WATCH = {
            {"whatsapp", "com.whatsapp"}, {"contacts", "dumb_phone.phone"}, {"radio", "dumb_phone.radio"},
            {"podcasts", "dumb_phone.podcasts"}, {"notes", "com.omgodse.notally"}, {"maps", "app.organicmaps"}};

    /** One short line of problems, "" when all is well. */
    static String problems(Context c) {
        long now = System.currentTimeMillis();
        if (now - last < 10 * 60_000L) return cached;
        last = now;
        StringBuilder b = new StringBuilder();
        PackageManager pm = c.getPackageManager();
        String sms = android.provider.Telephony.Sms.getDefaultSmsPackage(c);
        if (sms == null) add(b, "no texting app set");
        else if (installed(pm, sms) && pm.getLaunchIntentForPackage(sms) == null) add(b, "messages can't open");
        for (String[] w : WATCH)
            if (installed(pm, w[1]) && pm.getLaunchIntentForPackage(w[1]) == null) add(b, w[0] + " can't open");
        String crash = recentCrash(c, now);
        if (!crash.isEmpty()) add(b, crash);
        cached = b.length() == 0 ? "" : b.toString();
        return cached;
    }

    /** Ask again on the next home visit (after an app was installed or opened by its back door). */
    static void recheck() { last = 0; }

    private static void add(StringBuilder b, String s) { if (b.length() > 0) b.append(" · "); b.append(s); }

    private static boolean installed(PackageManager pm, String pkg) {
        try { return pm.getApplicationInfo(pkg, 0).enabled; } catch (Exception e) { return false; }
    }

    /** The newest "X has stopped" of the last 24 h, as "x crashed". */
    private static String recentCrash(Context c, long now) {
        File f = new File(c.getFilesDir(), "crash.log");
        if (!f.isFile()) return "";
        String[] lines = VolumeService.readAll(f).split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            int sp = lines[i].indexOf(' ');
            if (sp < 0) continue;
            try {
                if (now - Long.parseLong(lines[i].substring(0, sp)) < 86_400_000L) return lines[i].substring(sp + 1) + " crashed";
            } catch (NumberFormatException ignored) { }
            break;
        }
        return "";
    }

    /** From the key service: the screen says "<app> has stopped" / "keeps stopping". */
    static void noteCrash(Context c, CharSequence text) {
        String t = String.valueOf(text).replace("[", "").replace("]", "").trim();
        int k = t.indexOf(" has stopped");
        if (k < 0) k = t.indexOf(" keeps stopping");
        if (k <= 0) return;
        String app = t.substring(0, k).trim().toLowerCase(java.util.Locale.getDefault());
        File f = new File(c.getFilesDir(), "crash.log");
        try {
            if (f.length() > 8_000) f.delete();                   // tiny: the last few crashes are enough
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f, true)) {
                o.write((System.currentTimeMillis() + " " + app + "\n").getBytes("UTF-8"));
            }
        } catch (Exception ignored) { }
        recheck();
        android.util.Log.w("dumb_phone-health", "crash seen: " + app);
    }

    /** A way into an app whose launcher entry is gone (Fossify apps switch theirs off when confused). */
    static Intent backDoor(PackageManager pm, String pkg) {
        Intent i = new Intent(Intent.ACTION_MAIN).setClassName(pkg, pkg + ".activities.MainActivity");
        return i.resolveActivity(pm) != null ? i : null;
    }
}
