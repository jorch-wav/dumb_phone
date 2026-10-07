package dumb_phone.home;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Alarms and the timer. Alarms are kept in prefs ("clock") and handed to Android's own alarm clock
 * (setAlarmClock: exact, wakes the phone, shown as "next alarm"), one at a time: the next one due.
 * Also the receiver that fires them (and re-arms everything after a restart).
 */
public class Clock extends BroadcastReceiver {
    static final class Alarm {
        int id, hour, minute, days;       // days: bit 0 = monday … bit 6 = sunday; 0 = once
        boolean on = true;
        String sound = "";
        int vol = 5;                       // its own volume, 1-7
        boolean loud = true, vib = true;   // ring / vibrate even when the phone is on silent                 // a file from the sound library, "" = the phone's alarm sound
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("clock", Context.MODE_PRIVATE); }

    static List<Alarm> load(Context c) {
        List<Alarm> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("alarms", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Alarm al = new Alarm();
                al.id = o.getInt("id"); al.hour = o.getInt("h"); al.minute = o.getInt("m");
                al.days = o.optInt("d", 0); al.on = o.optBoolean("on", true); al.sound = o.optString("s", ""); al.vol = o.optInt("v", 5); al.loud = o.optBoolean("l", true); al.vib = o.optBoolean("b", true);
                out.add(al);
            }
        } catch (Exception ignored) { }
        java.util.Collections.sort(out, new java.util.Comparator<Alarm>() {
            @Override public int compare(Alarm a, Alarm b) { return (a.hour * 60 + a.minute) - (b.hour * 60 + b.minute); }
        });
        return out;
    }

    static void save(Context c, List<Alarm> list) {
        JSONArray a = new JSONArray();
        try {
            for (Alarm al : list) a.put(new JSONObject().put("id", al.id).put("h", al.hour).put("m", al.minute).put("d", al.days).put("on", al.on).put("s", al.sound).put("v", al.vol).put("l", al.loud).put("b", al.vib));
        } catch (Exception ignored) { }
        prefs(c).edit().putString("alarms", a.toString()).apply();
        android.util.Log.i("dumb_phone-clock", "saved " + a);
        schedule(c);
    }

    /** When alarm al next rings, from now (ms). */
    static long next(Alarm al, long now) {
        Calendar k = Calendar.getInstance();
        k.setTimeInMillis(now);
        k.set(Calendar.SECOND, 0); k.set(Calendar.MILLISECOND, 0);
        k.set(Calendar.HOUR_OF_DAY, al.hour); k.set(Calendar.MINUTE, al.minute);
        for (int i = 0; i < 8; i++) {
            if (k.getTimeInMillis() > now) {
                int dow = (k.get(Calendar.DAY_OF_WEEK) + 5) % 7;      // monday = 0
                if (al.days == 0 || (al.days & (1 << dow)) != 0) return k.getTimeInMillis();
            }
            k.add(Calendar.DAY_OF_MONTH, 1);
        }
        return Long.MAX_VALUE;
    }

    /** The next alarm due, or null. */
    static Alarm nextAlarm(Context c) {
        long now = System.currentTimeMillis(), best = Long.MAX_VALUE;
        Alarm pick = null;
        for (Alarm al : load(c)) if (al.on && next(al, now) < best) { best = next(al, now); pick = al; }
        return pick;
    }

    /** Arm the next alarm and the timer with Android (call after any change, at boot, after ringing). */
    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent show = PendingIntent.getActivity(c, 1, new Intent(c, ClockActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent ring = PendingIntent.getBroadcast(c, 2, new Intent(c, Clock.class).setAction("alarm"), PendingIntent.FLAG_UPDATE_CURRENT);
        Alarm al = nextAlarm(c);
        if (al == null) am.cancel(ring);
        else {
            Intent i = new Intent(c, Clock.class).setAction("alarm").putExtra("id", al.id);
            ring = PendingIntent.getBroadcast(c, 2, i, PendingIntent.FLAG_UPDATE_CURRENT);
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(next(al, System.currentTimeMillis()), show), ring);
        }
        PendingIntent timer = PendingIntent.getBroadcast(c, 3, new Intent(c, Clock.class).setAction("timer"), PendingIntent.FLAG_UPDATE_CURRENT);
        long end = prefs(c).getLong("timerEnd", 0);
        if (end > System.currentTimeMillis()) am.setAlarmClock(new AlarmManager.AlarmClockInfo(end, show), timer);
        else am.cancel(timer);
    }

    static String days(int d) {
        if (d == 0) return "once";
        if (d == 0x7F) return "every day";
        if (d == 0x1F) return "mon–fri";
        if (d == 0x60) return "weekends";
        String[] n = {"mo", "tu", "we", "th", "fr", "sa", "su"};
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 7; i++) if ((d & (1 << i)) != 0) b.append(b.length() > 0 ? " " : "").append(n[i]);
        return b.toString();
    }

    @Override public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || "android.intent.action.TIME_SET".equals(a) || Intent.ACTION_TIMEZONE_CHANGED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            schedule(c);
            return;
        }
        String what = "alarm", sound = "";
        int vol = 5;
        boolean loud = prefs(c).getBoolean("timerLoud", true), vib = prefs(c).getBoolean("timerVib", true);
        if ("timer".equals(a)) {
            prefs(c).edit().remove("timerEnd").apply();
            what = "timer";
            sound = prefs(c).getString("timerSound", "");
            vol = prefs(c).getInt("timerVol", 5);
        } else if ("alarm".equals(a)) {
            int id = i.getIntExtra("id", -1);
            List<Alarm> list = load(c);
            for (Alarm al : list) if (al.id == id) { sound = al.sound; vol = al.vol; loud = al.loud; vib = al.vib; if (al.days == 0) al.on = false; }   // a one-off alarm is done
            save(c, list);
        } else if ("snooze".equals(a)) {
            what = "alarm";
            sound = prefs(c).getString("snoozeSound", "");
            vol = prefs(c).getInt("snoozeVol", 5);
        } else return;
        schedule(c);
        c.startActivity(new Intent(c, RingActivity.class).putExtra("what", what).putExtra("sound", sound).putExtra("vol", vol).putExtra("loud", loud).putExtra("vib", vib)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION));
    }

    /** The sound when an alarm / the timer has none of its own: the alarm chosen in Sounds if it is from the
     *  library, else "Alarm Gentle" (or the first library sound). Never the phone's stock tone. */
    static java.io.File fallbackSound(Context c) {
        try {
            android.net.Uri u = android.media.RingtoneManager.getActualDefaultRingtoneUri(c, android.media.RingtoneManager.TYPE_ALARM);
            if (u != null) try (android.database.Cursor q = c.getContentResolver().query(u, new String[]{"_data"}, null, null, null)) {
                if (q != null && q.moveToFirst() && q.getString(0) != null && q.getString(0).startsWith(SoundsActivity.DIR.getPath())) return new java.io.File(q.getString(0));
            }
        } catch (Exception ignored) { }
        java.io.File g = new java.io.File(SoundsActivity.DIR, "material-alarm.ogg");
        if (g.isFile()) return g;
        java.util.List<String[]> lib = SoundsActivity.library();
        return lib.isEmpty() ? null : new java.io.File(SoundsActivity.DIR, lib.get(0)[0]);
    }

    /** The timer once more: 1 minute. */
    static void snoozeTimer(Context c) {
        prefs(c).edit().putLong("timerEnd", System.currentTimeMillis() + 60_000L).remove("timerLeft").apply();
        schedule(c);
    }

    /** Ring again in 9 minutes. */
    static void snooze(Context c, String sound, int vol) {
        prefs(c).edit().putString("snoozeSound", sound == null ? "" : sound).putInt("snoozeVol", vol).apply();
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent show = PendingIntent.getActivity(c, 1, new Intent(c, ClockActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent p = PendingIntent.getBroadcast(c, 4, new Intent(c, Clock.class).setAction("snooze"), PendingIntent.FLAG_UPDATE_CURRENT);
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 9 * 60_000L, show), p);
    }
}
