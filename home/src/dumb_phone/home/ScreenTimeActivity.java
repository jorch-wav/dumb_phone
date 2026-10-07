package dumb_phone.home;

import android.app.Activity;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Screen time": time spent in apps today and on each of the last 7 days, from Android's own usage
 * records (nothing is collected by us, nothing runs in the background). Needs the usage-access
 * permission, granted once by setup.sh: adb shell appops set dumb_phone.home GET_USAGE_STATS allow
 */
public class ScreenTimeActivity extends Activity {
    private Typeface mono, bold;
    private int dayShown;                                // the "by app" list: 0 = today … 6 = six days ago
    @SuppressWarnings("unchecked")
    private final Map<String, Long>[] perDay = new Map[7];
    private ScrollView sv;
    private boolean resetArmed;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.load(this);
        mono = Typeface.createFromAsset(getAssets(), "JetBrainsMono-Regular.ttf");
        bold = Typeface.createFromAsset(getAssets(), "JetBrainsMono-Bold.ttf");
        build();
    }

    private void build() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(8), dp(6), dp(8), dp(8));
        v.setBackgroundColor(Theme.VOID);
        v.addView(text("screen time & battery", bold, 13, Theme.AMBER));

        long[] days = new long[7];                       // [0] = today, [6] = 6 days ago
        for (int i = 0; i < 7; i++) perDay[i] = new HashMap<>();
        Map<String, Long> today = perDay[0];
        boolean ok = collect(days, today);
        if (!ok) {
            TextView t = text("No permission to read usage yet.\n\nOn a computer, with the phone plugged in:\nadb shell appops set dumb_phone.home GET_USAGE_STATS allow\n\n(setup.sh does this.)", mono, 13, Theme.GREEN);
            t.setPadding(0, dp(10), 0, 0);
            v.addView(t);
            setContentView(v);
            return;
        }

        TextView big = text(span(days[0]), bold, 34, Theme.GREEN);
        big.setPadding(0, dp(4), 0, 0);
        v.addView(big);
        long week = 0;
        int counted = 0;
        for (int i = 0; i < 7; i++) if (days[i] > 0) { week += days[i]; counted++; }
        v.addView(text("today  ·  daily average " + span(counted > 0 ? week / counted : 0), mono, 11, Theme.DIM));
        // pickups (times the screen came on) and the longest stretch, today
        long midnightNow = midnight();
        int pickups = 0; long longest = 0;
        for (long[] iv : on) {
            if (iv[1] <= midnightNow) continue;
            if (iv[0] >= midnightNow) pickups++;
            longest = Math.max(longest, iv[1] - Math.max(iv[0], midnightNow));
        }
        v.addView(text(pickups + (pickups == 1 ? " pickup" : " pickups") + "  ·  longest " + span(longest), mono, 11, Theme.GREEN));
        if (!on.isEmpty()) v.addView(text("screen-on time, counted since " + new SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())
                .format(new java.util.Date(on.get(0)[0])).toLowerCase(Locale.getDefault()), mono, 10, Theme.DIM));

        Week chart = new Week(this, days);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(96));
        cp.setMargins(0, dp(10), 0, dp(6));
        v.addView(chart, cp);
        // the same week as plain lines (easier to read than bars); the chosen day (← →) is lit
        SimpleDateFormat df = new SimpleDateFormat("EEE dd MMM", Locale.getDefault());
        for (int d = 0; d < 7; d++) {
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_YEAR, -d);
            LinearLayout line = new LinearLayout(this);
            line.setPadding(dp(4), dp(1), dp(4), dp(1));
            if (d == dayShown) line.setBackgroundColor(Theme.LIT);
            int col = d == dayShown ? Theme.AMBER : days[d] > 0 ? Theme.GREEN : Theme.DIM;
            TextView name = text((d == 0 ? "today" : d == 1 ? "yesterday" : df.format(cal.getTime()).toLowerCase(Locale.getDefault())), mono, 12, col);
            line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(text(days[d] > 0 ? span(days[d]) : "–", mono, 12, col));
            final int day = d;
            line.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View x) { showDay(day); } });
            v.addView(line, new LinearLayout.LayoutParams(-1, -2));
        }

        View rule = new View(this);
        rule.setBackgroundColor(Theme.RULE);
        v.addView(rule, new LinearLayout.LayoutParams(-1, 1));
        LinearLayout head = new LinearLayout(this);
        head.addView(text(dayName(dayShown) + " by app", bold, 12, Theme.AMBER), new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(text((dayShown < 6 ? "‹ " : "  ") + span(days[dayShown]) + (dayShown > 0 ? " ›" : "  "), mono, 11, Theme.DIM));
        head.setOnTouchListener(new View.OnTouchListener() {   // tap the left half = an older day, right half = newer
            @Override public boolean onTouch(View h, android.view.MotionEvent e) {
                if (e.getAction() == android.view.MotionEvent.ACTION_UP) showDay(dayShown + (e.getX() < h.getWidth() / 2f ? 1 : -1));
                return true;
            }
        });
        LinearLayout.LayoutParams hp = padded(dp(6));
        hp.width = -1;                                   // full width, so the day total sits on the right
        v.addView(head, hp);
        v.addView(text("← → other days", mono, 9, Theme.DIM));

        List<Map.Entry<String, Long>> apps = new ArrayList<>(perDay[dayShown].entrySet());
        Collections.sort(apps, new Comparator<Map.Entry<String, Long>>() {
            @Override public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) { return Long.compare(b.getValue(), a.getValue()); }
        });
        long top = apps.isEmpty() ? 1 : Math.max(1, apps.get(0).getValue());
        int shown = 0;
        for (Map.Entry<String, Long> e : apps) {
            if (e.getValue() < 60_000 || shown++ >= 12) continue;             // skip under a minute
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(4), 0, dp(2));
            LinearLayout line = new LinearLayout(this);
            TextView name = text(label(e.getKey()), mono, 13, Theme.GREEN);
            name.setSingleLine(true);
            line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(text(span(e.getValue()), mono, 13, Theme.DIM));
            row.addView(line);
            Bar bar = new Bar(this, (float) e.getValue() / top);
            row.addView(bar, new LinearLayout.LayoutParams(-1, dp(3)));
            v.addView(row);
        }
        if (shown == 0) v.addView(text("nothing over a minute yet", mono, 12, Theme.DIM));

        addBattery(v);

        addWhatsApp(v);

        // start counting over (needs a second press, so it can't happen by accident)
        View rule2 = new View(this);
        rule2.setBackgroundColor(Theme.RULE);
        LinearLayout.LayoutParams rp2 = new LinearLayout.LayoutParams(-1, 1);
        rp2.setMargins(0, dp(10), 0, 0);
        v.addView(rule2, rp2);
        TextView reset = text(resetArmed ? "[#] again = reset now" : "[#] start counting over", mono, 11, resetArmed ? Theme.AMBER : Theme.DIM);
        reset.setPadding(0, dp(6), 0, dp(6));
        reset.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View x) { pressReset(); } });
        v.addView(reset);

        int y = sv == null ? 0 : sv.getScrollY();
        sv = new ScrollView(this);
        sv.setBackgroundColor(Theme.VOID);
        sv.addView(v);
        setContentView(sv);
        final int keep = y;
        sv.post(new Runnable() { public void run() { sv.scrollTo(0, keep); } });
    }

    /** The background WhatsApp checks today (from files/wa.log): how many, time awake, battery, messages. */
    private void addWhatsApp(LinearLayout v) {
        long mid = midnight();
        int n = 0, drop = 0, msgs = 0, withMsgs = 0; long awake = 0;
        try {
            for (String line : VolumeService.readAll(new java.io.File(getFilesDir(), "wa.log")).split("\n")) {
                String[] p = line.trim().split(" ");
                if (p.length != 5 || Long.parseLong(p[0]) < mid) continue;
                n++;
                awake += Long.parseLong(p[1]) - Long.parseLong(p[0]);
                int l0 = Integer.parseInt(p[2]), l1 = Integer.parseInt(p[3]);
                if (l0 > 0 && l1 > 0 && l0 > l1) drop += l0 - l1;
                int m = Integer.parseInt(p[4]);
                msgs += m; if (m > 0) withMsgs++;
            }
        } catch (Exception ignored) { }
        if (getPackageManager().getLaunchIntentForPackage("com.whatsapp") == null && n == 0) return;
        View rule = new View(this);
        rule.setBackgroundColor(Theme.RULE);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, 1);
        rp.setMargins(0, dp(10), 0, 0);
        v.addView(rule, rp);
        v.addView(text("whatsapp checks", bold, 12, Theme.AMBER), padded(dp(6)));
        if (n == 0) {
            v.addView(text("none yet today (every 15 min while the screen is off)", mono, 11, Theme.DIM));
            return;
        }
        v.addView(text(n + (n == 1 ? " check" : " checks") + "  ·  awake " + secs(awake), mono, 13, Theme.GREEN));
        v.addView(text(msgs + " new " + (msgs == 1 ? "message" : "messages") + " fetched (" + withMsgs + " of the checks found some)", mono, 11, Theme.DIM));
        v.addView(text("battery during checks: " + drop + "%" + (drop == 0 ? " (too small to show)" : ""), mono, 11, Theme.DIM));
    }

    private static String secs(long ms) {
        long s = ms / 1000;
        return s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s";
    }

    private static long midnight() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static String dayName(int d) {
        if (d == 0) return "today";
        if (d == 1) return "yesterday";
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -d);
        return new SimpleDateFormat("EEEE", Locale.getDefault()).format(cal.getTime()).toLowerCase(Locale.getDefault());
    }

    private void showDay(int d) {
        d = Math.max(0, Math.min(6, d));
        if (d == dayShown) return;
        dayShown = d;
        build();
    }

    private void pressReset() {
        if (!resetArmed) { resetArmed = true; build(); return; }
        resetArmed = false;
        if (VolumeService.instance != null) VolumeService.instance.resetScreenTime();
        else { for (String f : new String[]{"screen.log", "battery.log", "wa.log"}) new java.io.File(getFilesDir(), f).delete(); }
        android.widget.Toast.makeText(this, "counting from now", android.widget.Toast.LENGTH_SHORT).show();
        dayShown = 0;
        build();
    }

    @Override public boolean onKeyDown(int code, android.view.KeyEvent e) {
        switch (code) {
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT: showDay(dayShown + 1); return true;
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT: showDay(dayShown - 1); return true;
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN: if (sv != null) sv.smoothScrollBy(0, dp(70)); return true;
            case android.view.KeyEvent.KEYCODE_DPAD_UP: if (sv != null) sv.smoothScrollBy(0, -dp(70)); return true;
            case android.view.KeyEvent.KEYCODE_POUND: pressReset(); return true;
        }
        if (resetArmed && code != android.view.KeyEvent.KEYCODE_BACK) { resetArmed = false; build(); }
        return super.onKeyDown(code, e);
    }

    // ---- battery ----

    /** {time, level, plugged} from files/battery.log (written by the key service). */
    private List<long[]> battery() {
        List<long[]> out = new ArrayList<>();
        try {
            for (String line : VolumeService.readAll(new java.io.File(getFilesDir(), "battery.log")).split("\n")) {
                String[] p = line.trim().split(" ");
                if (p.length == 3) out.add(new long[]{Long.parseLong(p[0]), Long.parseLong(p[1]), Long.parseLong(p[2])});
            }
        } catch (Exception ignored) { }
        return out;
    }

    private double onShare(long a, long b) {           // how much of a..b the screen was on (0..1)
        long sum = 0;
        for (long[] iv : on) { long x = Math.max(a, iv[0]), y = Math.min(b, iv[1]); if (y > x) sum += y - x; }
        return b > a ? (double) sum / (b - a) : 0;
    }

    private void addBattery(LinearLayout v) {
        List<long[]> b = battery();
        View rule = new View(this);
        rule.setBackgroundColor(Theme.RULE);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, 1);
        rp.setMargins(0, dp(10), 0, 0);
        v.addView(rule, rp);
        android.content.Intent now = registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        int level = now == null ? -1 : now.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) * 100 / Math.max(1, now.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100));
        boolean plugged = now != null && now.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0;
        v.addView(text("battery", bold, 12, Theme.AMBER), padded(dp(6)));
        // drain per hour, split by screen on / off, from the unplugged stretches
        double onDrop = 0, onTime = 0, offDrop = 0, offTime = 0;
        for (int i = 1; i < b.size(); i++) {
            long[] x = b.get(i - 1), y = b.get(i);
            if (x[2] == 1 || y[2] == 1 || y[1] > x[1] || y[0] - x[0] > 12 * 3_600_000L) continue;
            double share = onShare(x[0], y[0]), hours = (y[0] - x[0]) / 3_600_000.0, drop = x[1] - y[1];
            onDrop += drop * share; onTime += hours * share;
            offDrop += drop * (1 - share); offTime += hours * (1 - share);
        }
        String big = level < 0 ? "?" : level + "%";
        TextView t = text(big + (plugged ? "  charging" : ""), bold, 22, plugged ? Theme.AMBER : Theme.GREEN);
        v.addView(t);
        double onRate = onTime > 0.2 ? onDrop / onTime : -1, offRate = offTime > 0.5 ? offDrop / offTime : -1;
        if (!plugged && level > 0 && (onRate > 0 || offRate > 0)) {
            // a day like today: the screen on as much as in the last 24 h
            long day = System.currentTimeMillis() - 86_400_000L;
            double share = onShare(day, System.currentTimeMillis());
            double rate = (onRate > 0 ? onRate : 0) * share + (offRate > 0 ? offRate : onRate * 0.2) * (1 - share);
            if (rate > 0.05) v.addView(text("about " + span((long) (level / rate * 3_600_000L)) + " left at today's use", mono, 11, Theme.DIM));
        }
        // this stretch: on battery since … (used n%), or charging since …
        int k = b.size() - 1;
        while (k > 0 && b.get(k - 1)[2] == b.get(k)[2]) k--;
        if (!b.isEmpty() && k > 0) {
            String at = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new java.util.Date(b.get(k)[0]));
            long dur = System.currentTimeMillis() - b.get(k)[0];
            if (plugged) v.addView(text("charging since " + at, mono, 11, Theme.DIM));
            else if (level >= 0) v.addView(text("on battery since " + at + " (" + span(dur) + ")  ·  used " + Math.max(0, b.get(k)[1] - level) + "%", mono, 11, Theme.GREEN));
        }
        v.addView(text("screen on: " + (onRate > 0 ? fmt(onRate) + "%/h" : "measuring…")
                + "   off: " + (offRate > 0 ? fmt(offRate) + "%/h" : "measuring…"), mono, 11, Theme.DIM));
        Line chart = new Line(this, b);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(80));
        cp.setMargins(0, dp(8), 0, dp(2));
        v.addView(chart, cp);
        v.addView(text("last 24 h · shaded = screen on · charging in accent", mono, 9, Theme.DIM));
        if (b.size() < 3) v.addView(text("the graph fills in as the battery changes", mono, 10, Theme.DIM));
    }

    private static String fmt(double d) { return d >= 10 ? String.valueOf(Math.round(d)) : String.format(Locale.US, "%.1f", d); }

    /** Battery level over the last 24 h: 0 / 50 / 100 lines, the level as a line, charging parts in the accent. */
    final class Line extends View {
        private final List<long[]> pts;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Line(Context c, List<long[]> pts) { super(c); this.pts = pts; }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), lab = dp(10);
            long end = System.currentTimeMillis(), start = end - 86_400_000L;
            p.setColor(Theme.LIT);                                   // when the screen was on
            if (on != null) for (long[] iv : on) {
                if (iv[1] < start) continue;
                float x0 = Math.max(0, (iv[0] - start) * w / 86_400_000f), x1 = Math.min(w, (iv[1] - start) * w / 86_400_000f);
                c.drawRect(x0, 0, Math.max(x0 + 1, x1), h - lab, p);
            }
            p.setStrokeWidth(1);
            p.setColor(Theme.RULE);
            for (int k = 0; k <= 2; k++) { float y = (h - lab) * k / 2f; c.drawLine(0, y, w, y, p); }
            p.setTypeface(mono);
            p.setTextSize(dp(8));
            p.setColor(Theme.DIM);
            c.drawText("100", 0, dp(8), p);
            c.drawText("24h ago", 0, h, p);
            p.setTextAlign(Paint.Align.RIGHT);
            c.drawText("now", w, h, p);
            p.setTextAlign(Paint.Align.LEFT);
            p.setStrokeWidth(dp(2));
            float px = -1, py = 0;
            long plug = 0;
            for (long[] q : pts) {
                if (q[0] < start - 3_600_000L) { plug = q[2]; py = (h - lab) * (1 - q[1] / 100f); px = 0; continue; }
                float x = Math.max(0, (q[0] - start) * w / 86_400_000f), y = (h - lab) * (1 - q[1] / 100f);
                if (px >= 0) { p.setColor(plug == 1 ? Theme.AMBER : Theme.GREEN); c.drawLine(px, py, x, py, p); c.drawLine(x, py, x, y, p); }
                px = x; py = y; plug = q[2];
            }
            if (px >= 0) { p.setColor(plug == 1 ? Theme.AMBER : Theme.GREEN); c.drawLine(px, py, w, py, p); }
        }
    }

    /** Screen-on intervals from files/screen.log (written by the key service); empty = nothing logged yet. */
    private List<long[]> screenOn() {
        List<long[]> out = new ArrayList<>();
        java.io.File f = new java.io.File(getFilesDir(), "screen.log");
        long start = -1;
        try {
            for (String line : VolumeService.readAll(f).split("\n")) {
                String[] p = line.trim().split(" ");
                if (p.length != 2) continue;
                long t = Long.parseLong(p[1]);
                if (p[0].equals("1")) { if (start < 0) start = t; }
                else if (start >= 0) { out.add(new long[]{start, t}); start = -1; }
            }
        } catch (Exception ignored) { }
        if (start >= 0) out.add(new long[]{start, System.currentTimeMillis()});
        return out;
    }

    private List<long[]> on;

    /** Foreground time per day (last 7) and per app (today), from the usage event log, counted only while the screen was on. */
    private boolean collect(long[] days, Map<String, Long> today) {
        on = screenOn();
        UsageStatsManager um = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        long midnight = c.getTimeInMillis(), now = System.currentTimeMillis();
        long from = midnight - 6 * 86_400_000L;
        UsageEvents ev;
        try { ev = um.queryEvents(from, now); } catch (Exception e) { return false; }
        if (ev == null) return false;
        UsageEvents.Event e = new UsageEvents.Event();
        String fg = null;
        long since = 0;
        boolean any = false;
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            any = true;
            int t = e.getEventType();
            if (t == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                if (fg != null) add(days, today, fg, since, e.getTimeStamp(), midnight);
                fg = e.getPackageName();
                since = e.getTimeStamp();
            } else if (t == UsageEvents.Event.MOVE_TO_BACKGROUND && fg != null && fg.equals(e.getPackageName())) {
                add(days, today, fg, since, e.getTimeStamp(), midnight);
                fg = null;
            }
        }
        if (fg != null) add(days, today, fg, since, now, midnight);
        return any || hasPermission();
    }

    private void add(long[] days, Map<String, Long> today, String pkg, long a, long b, long midnight) {
        if (b <= a) return;
        for (long[] iv : on) {                              // only the parts while the screen was on
            long x = Math.max(a, iv[0]), y = Math.min(b, iv[1]);
            if (y > x) addSpan(days, today, pkg, x, y, midnight);
        }
    }

    private void addSpan(long[] days, Map<String, Long> today, String pkg, long a, long b, long midnight) {
        if (b <= a || b - a > 6 * 3_600_000L) return;     // ignore broken spans (e.g. missing events)
        while (a < b) {
            int day = a >= midnight ? 0 : (int) ((midnight - a - 1) / 86_400_000L) + 1;
            long dayEnd = midnight - (day - 1) * 86_400_000L;
            long end = Math.min(b, dayEnd);
            if (day >= 0 && day < 7) {
                days[day] += end - a;
                Map<String, Long> m = perDay[day];
                Long x = m.get(pkg); m.put(pkg, (x == null ? 0 : x) + end - a);
            }
            a = end;
        }
    }

    private boolean hasPermission() {
        android.app.AppOpsManager ao = (android.app.AppOpsManager) getSystemService(APP_OPS_SERVICE);
        return ao != null && ao.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName()) == android.app.AppOpsManager.MODE_ALLOWED;
    }

    private String label(String pkg) {
        if (pkg.equals(getPackageName())) return "home";
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().toLowerCase(Locale.getDefault());
        } catch (Exception e) { return pkg.endsWith(".home") ? "home (old)" : pkg; }
    }

    /** Compact for the chart: 45m, 2h5, 11h. */
    static String shortSpan(long ms) {
        long m = ms / 60_000, h = m / 60;
        if (h == 0) return Math.max(1, m) + "m";
        return h >= 10 || m % 60 == 0 ? h + "h" : h + "h" + (m % 60);
    }

    static String span(long ms) {
        long m = ms / 60_000, h = m / 60;
        return h > 0 ? h + "h " + (m % 60) + "m" : m + "m";
    }

    private LinearLayout.LayoutParams padded(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.setMargins(0, top, 0, dp(2));
        return p;
    }

    private TextView text(String s, Typeface tf, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(tf);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    /** Seven bars, oldest left, today right (in the accent). */
    final class Week extends View {
        private final long[] days;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Week(Context c, long[] days) { super(c); this.days = days; }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), label = dp(12), top = dp(12);   // day names below, times above
            long max = 1;
            for (long d : days) max = Math.max(max, d);
            float slot = w / 7f, bw = slot * 0.62f, base = h - label - dp(2), room = base - top;
            p.setTypeface(mono);
            p.setTextSize(dp(9));
            p.setTextAlign(Paint.Align.CENTER);
            SimpleDateFormat f = new SimpleDateFormat("EEE", Locale.getDefault());
            for (int i = 0; i < 7; i++) {
                int day = 6 - i;
                boolean lit = day == dayShown;
                float x = i * slot + (slot - bw) / 2;
                float bh = room * days[day] / max;
                p.setColor(Theme.RULE);
                c.drawRect(x, top, x + bw, base, p);
                p.setColor(lit ? Theme.AMBER : Theme.GREEN);
                c.drawRect(x, base - bh, x + bw, base, p);
                if (days[day] > 0) {                                   // its time, just above the bar
                    p.setColor(lit ? Theme.AMBER : Theme.GREEN);
                    c.drawText(shortSpan(days[day]), x + bw / 2, Math.max(dp(9), base - bh - dp(3)), p);
                }
                Calendar cal = Calendar.getInstance();
                cal.add(Calendar.DAY_OF_YEAR, -day);
                p.setColor(lit ? Theme.AMBER : Theme.DIM);
                c.drawText(f.format(cal.getTime()).substring(0, 2).toLowerCase(Locale.getDefault()), x + bw / 2, h - dp(1), p);
            }
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {   // tap a bar = that day
            if (e.getAction() == android.view.MotionEvent.ACTION_UP) showDay(6 - (int) Math.min(6, Math.max(0, e.getX() / (getWidth() / 7f))));
            return true;
        }
    }

    final class Bar extends View {
        private final float frac;
        private final Paint p = new Paint();
        Bar(Context c, float frac) { super(c); this.frac = frac; }
        @Override protected void onDraw(Canvas c) {
            p.setColor(Theme.RULE);
            c.drawRect(0, 0, getWidth(), getHeight(), p);
            p.setColor(Theme.GREEN);
            c.drawRect(0, 0, getWidth() * frac, getHeight(), p);
        }
    }
}
