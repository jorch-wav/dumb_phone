package dumb_phone.home;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.List;

/**
 * "Alarms" and "Timer" (two app entries, one screen each) in the dumb_phone look.
 * Alarms: ↑ ↓ pick, OK edit, * on / off, hold OK delete; the editor: ← → hour / minute, ↑ ↓ change,
 * 1-7 toggle mon…sun, OK save, back cancel.
 * Timer: type the minutes (or ↑ ↓), OK start / pause, # reset.
 */
public class ClockActivity extends Activity {
    private Typeface mono, bold;
    private Board board;
    private int tab;                                  // 0 alarms, 1 timer
    private List<Clock.Alarm> alarms;
    private int sel;                                  // highlighted row (alarms; last = "+ new alarm")
    private Clock.Alarm editing;                      // the alarm being edited, or null
    private boolean editHour = true;
    private int timerMin = 5;                         // the timer's set length
    private long typedAt;                             // digits typed within 1.5 s add up (e.g. 1 5 = 15 min)
    private final Handler h = new Handler();
    // ---- sound picker (an alarm's sound, or the timer's) ----
    private java.util.List<String[]> lib;            // {file, title, credit}; row 0 = the phone's alarm sound
    private int pick = -1;                            // highlighted row while picking, -1 = not picking
    private android.media.MediaPlayer taste;

    private void openPicker(String current) {
        lib = SoundsActivity.library();
        pick = 0;
        for (int i = 0; i < lib.size(); i++) if (lib.get(i)[0].equals(current)) pick = i + 1;
        board.invalidate();
    }

    private void tastePick() {
        stopTaste();
        if (pick <= 0) return;
        try {
            h.removeCallbacks(levelBack);
            levelOn(curVol());                                 // preview at the alarm's / timer's own volume
            taste = new android.media.MediaPlayer();
            taste.setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build());
            taste.setDataSource(new java.io.File(SoundsActivity.DIR, lib.get(pick - 1)[0]).getPath());
            taste.prepare();
            taste.start();
        } catch (Exception e) { taste = null; }
    }

    private void stopTaste() { if (taste != null) { try { taste.stop(); taste.release(); } catch (Exception ignored) { } taste = null; } levelOff(); }

    @Override protected void onStop() { super.onStop(); stopTaste(); levelOff(); }

    private void choosePick() {
        String f = pick <= 0 ? "" : lib.get(pick - 1)[0];
        if (editing != null) editing.sound = f;
        else Clock.prefs(this).edit().putString("timerSound", f).apply();
        stopTaste();
        pick = -1;
        board.invalidate();
    }

    private boolean pickKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: if (pick > 0) { pick--; tastePick(); } break;
            case KeyEvent.KEYCODE_DPAD_DOWN: if (pick < lib.size()) { pick++; tastePick(); } break;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: choosePick(); break;
            case KeyEvent.KEYCODE_BACK: stopTaste(); pick = -1; break;
            default: return true;
        }
        board.invalidate();
        return true;
    }
    private final Runnable tick = new Runnable() { public void run() { board.invalidate(); h.postDelayed(this, 500); } };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.load(this);
        mono = Typeface.createFromAsset(getAssets(), "JetBrainsMono-Regular.ttf");
        bold = Typeface.createFromAsset(getAssets(), "JetBrainsMono-Bold.ttf");
        alarms = Clock.load(this);
        timerMin = Clock.prefs(this).getInt("timerMin", 5);
        Intent in = getIntent();
        String act = in.getAction() == null ? "" : in.getAction();
        boolean timerApp = (in.getComponent() != null && in.getComponent().getClassName().endsWith("TimerActivity"))
                || act.equals("android.intent.action.SET_TIMER") || act.equals("android.intent.action.SHOW_TIMERS") || in.getBooleanExtra("timer", false);
        tab = timerApp ? 1 : 0;                       // Alarms and Timer are separate apps (two entries, one screen each)
        if (act.equals(android.provider.AlarmClock.ACTION_SET_ALARM) && in.hasExtra(android.provider.AlarmClock.EXTRA_HOUR)) {
            Clock.Alarm al = new Clock.Alarm();         // another app (or a voice command) asked for an alarm
            al.id = (int) (System.currentTimeMillis() % 1_000_000);
            al.hour = in.getIntExtra(android.provider.AlarmClock.EXTRA_HOUR, 7);
            al.minute = in.getIntExtra(android.provider.AlarmClock.EXTRA_MINUTES, 0);
            java.util.ArrayList<Integer> d = in.getIntegerArrayListExtra(android.provider.AlarmClock.EXTRA_DAYS);
            if (d != null) for (int cal : d) al.days |= 1 << ((cal + 5) % 7);
            alarms.add(al);
            Clock.save(this, alarms);
            android.widget.Toast.makeText(this, String.format("alarm set for %02d:%02d", al.hour, al.minute), android.widget.Toast.LENGTH_SHORT).show();
            if (in.getBooleanExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)) { finish(); return; }
        }
        if (act.equals("android.intent.action.SET_TIMER") && in.hasExtra("android.intent.extra.alarm.LENGTH")) {
            int sec = in.getIntExtra("android.intent.extra.alarm.LENGTH", 300);
            Clock.prefs(this).edit().putLong("timerEnd", System.currentTimeMillis() + sec * 1000L).remove("timerLeft").apply();
            Clock.schedule(this);
            if (in.getBooleanExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)) { finish(); return; }
        }
        board = new Board(this);
        board.setFocusable(true);
        board.setFocusableInTouchMode(true);
        setContentView(board);
        board.requestFocus();
    }

    @Override protected void onResume() { super.onResume(); alarms = Clock.load(this); h.post(tick); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(tick); }

    private float dp(float v) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    // ---- volume + "ring on silent", separately for alarms and the timer (7 steps; default 5, on) ----
    private String k() { return tab == 0 ? "alarm" : "timer"; }
    private int vol() { return Clock.prefs(this).getInt(k() + "Vol", 5); }
    private boolean loud() { return Clock.prefs(this).getBoolean(k() + "Loud", true); }
    /** The volume being set: the alarm in the editor, or the timer's. */
    private int curVol() { return editing != null ? editing.vol : vol(); }

    private void storeVol(int v) {
        v = Math.max(1, Math.min(7, v));
        if (editing != null) editing.vol = v; else Clock.prefs(this).edit().putInt(k() + "Vol", v).apply();
    }

    // Tests play at the real level: the alarm stream is set to it for the test, then put back.
    private int oldLevel = -1;
    private void levelOn(int v) {
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        if (oldLevel < 0) oldLevel = am.getStreamVolume(android.media.AudioManager.STREAM_ALARM);
        int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM);
        am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, Math.max(1, Math.round(max * v / 7f)), 0);
    }
    private final Runnable levelBack = new Runnable() { public void run() { levelOff(); } };
    private void levelOff() {
        if (taste != null) return;
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null && oldLevel >= 0) am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, oldLevel, 0);
        oldLevel = -1;
    }

    private void setVol(int v) {
        storeVol(v);
        try {                                                  // a short beep at exactly that volume
            levelOn(curVol());
            final android.media.ToneGenerator tg = new android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100);
            tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150);
            h.postDelayed(new Runnable() { public void run() { tg.release(); } }, 400);
            h.removeCallbacks(levelBack);
            h.postDelayed(levelBack, 700);
        } catch (Exception ignored) { }
    }
    private void toggleLoud() { Clock.prefs(this).edit().putBoolean(k() + "Loud", !loud()).apply(); }
    private boolean vibe() { return Clock.prefs(this).getBoolean(k() + "Vib", true); }
    private void toggleVibe() { Clock.prefs(this).edit().putBoolean(k() + "Vib", !vibe()).apply(); }
    private static String blocks(int v) { StringBuilder b = new StringBuilder(); for (int i = 1; i <= 7; i++) b.append(i <= v ? '█' : '░'); return b.toString(); }

    // ---- timer state (kept in prefs so it survives the app closing) ----
    private long timerEnd() { return Clock.prefs(this).getLong("timerEnd", 0); }
    private long timerLeft() { return Clock.prefs(this).getLong("timerLeft", 0); }   // paused time left (ms)

    private void timerOk() {
        long end = timerEnd(), now = System.currentTimeMillis();
        if (end > now) Clock.prefs(this).edit().putLong("timerLeft", end - now).remove("timerEnd").apply();          // pause
        else {
            long left = timerLeft() > 0 ? timerLeft() : timerMin * 60_000L;
            Clock.prefs(this).edit().putLong("timerEnd", now + left).remove("timerLeft").putInt("timerMin", timerMin).apply();
        }
        Clock.schedule(this);
    }

    private void timerReset() {
        Clock.prefs(this).edit().remove("timerEnd").remove("timerLeft").apply();
        Clock.schedule(this);
    }

    private static String mmss(long ms) {
        long s = Math.max(0, (ms + 999) / 1000);
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%02d:%02d", s / 60, s % 60);
    }

    // ---- keys ----
    private boolean held, pressInList;                // OK went down in the list (not in the editor)

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (pick >= 0) return pickKey(code);
        if (editing != null) return editKey(code, e);
        if ((code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) && tab == 1) {   // timer: ← → volume
            setVol(vol() + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1)); board.invalidate(); return true;
        }
        if (code == KeyEvent.KEYCODE_STAR && tab == 1) { toggleLoud(); board.invalidate(); return true; }
        if (tab == 0) {
            int rows = alarms.size() + 1;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP: sel = Math.max(0, sel - 1); break;
                case KeyEvent.KEYCODE_DPAD_DOWN: sel = Math.min(rows - 1, sel + 1); break;
                case KeyEvent.KEYCODE_STAR: if (sel < alarms.size()) { alarms.get(sel).on = !alarms.get(sel).on; Clock.save(this, alarms); } break;
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                    if (e.getRepeatCount() == 0) { e.startTracking(); pressInList = true; }
                    else if (e.getRepeatCount() == 8 && sel < alarms.size()) { held = true; alarms.remove(sel); Clock.save(this, alarms); sel = Math.max(0, sel - 1); android.widget.Toast.makeText(this, "alarm deleted", android.widget.Toast.LENGTH_SHORT).show(); }
                    return true;
                default: return super.onKeyDown(code, e);
            }
            board.invalidate();
            return true;
        }
        // timer
        if (code == KeyEvent.KEYCODE_0 && (timerEnd() > 0 || System.currentTimeMillis() - typedAt >= 1500)) { toggleVibe(); board.invalidate(); return true; }
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9 && timerEnd() == 0) {
            int d = code - KeyEvent.KEYCODE_0;
            long now = System.currentTimeMillis();
            timerMin = now - typedAt < 1500 ? Math.min(999, timerMin * 10 + d) : d;
            typedAt = now;
            Clock.prefs(this).edit().remove("timerLeft").apply();
        } else if (code == KeyEvent.KEYCODE_DPAD_UP && timerEnd() == 0) { timerMin = Math.min(999, timerMin + 1); Clock.prefs(this).edit().remove("timerLeft").apply(); }
        else if (code == KeyEvent.KEYCODE_DPAD_DOWN && timerEnd() == 0) { timerMin = Math.max(1, timerMin - 1); Clock.prefs(this).edit().remove("timerLeft").apply(); }
        else if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) timerOk();
        else if (code == KeyEvent.KEYCODE_POUND) { if (timerEnd() == 0 && timerLeft() == 0) openPicker(Clock.prefs(this).getString("timerSound", "")); else timerReset(); }
        else return super.onKeyDown(code, e);
        board.invalidate();
        return true;
    }

    @Override public boolean onKeyUp(int code, KeyEvent e) {
        if (pick >= 0) return true;
        if (editing == null && tab == 0 && pressInList && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
            pressInList = false;
            if (held) { held = false; board.invalidate(); return true; }
            openRow(sel);
            return true;
        }
        return super.onKeyUp(code, e);
    }

    private void openRow(int i) {

        if (i < alarms.size()) editing = copy(alarms.get(i));
        else {                                         // a new alarm: 07:00, once
            editing = new Clock.Alarm();
            editing.id = (int) (System.currentTimeMillis() % 1_000_000);
            editing.hour = 7;
        }
        editHour = true;
        onTouched = false;
        erow = 0; dayCur = 0; actCur = 0; typed = "";
        board.invalidate();
    }

    private static Clock.Alarm copy(Clock.Alarm a) {
        Clock.Alarm c = new Clock.Alarm();
        c.id = a.id; c.hour = a.hour; c.minute = a.minute; c.days = a.days; c.on = a.on; c.sound = a.sound; c.vol = a.vol; c.loud = a.loud; c.vib = a.vib;
        return c;
    }

    // ---- the alarm editor: rows you move through with ↑ ↓ ----
    // 0 time, 1 days, 2 tone, 3 volume, 4 sound on/off, 5 vibrate on/off, 6 on/off + delete
    private int erow, dayCur, actCur;
    private String typed = "";                        // digits typed for the time (HHMM)

    private boolean editKey(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {               // back = save and close (or cancel a pending delete)
            if (delArmed) delArmed = false; else saveEdit();
            board.invalidate();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP) { erow = Math.max(0, erow - 1); typed = ""; }
        else if (code == KeyEvent.KEYCODE_DPAD_DOWN) { erow = Math.min(6, erow + 1); typed = ""; }
        else if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int d = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
            if (erow == 0) stepMinutes(d);
            else if (erow == 1) dayCur = (dayCur + d + 7) % 7;
            else if (erow == 3) setVol(editing.vol + d);
            else if (erow == 6) actCur = d > 0 ? 1 : 0;
        } else if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            activate(erow);
            return true;
        } else if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            int dg = code - KeyEvent.KEYCODE_0;
            if (erow == 0) typeTime(dg);
            else if (erow == 1 && dg >= 1 && dg <= 7) { dayCur = dg - 1; editing.days ^= 1 << dayCur; }
            else return super.onKeyDown(code, e);
        } else return super.onKeyDown(code, e);
        board.invalidate();
        return true;
    }

    /** OK (or a tap) on an editor row. */
    private void activate(int row) {
        erow = row;
        if (row == 0) erow = 1;                            // done with the time: on to the days
        else if (row == 1) editing.days ^= 1 << dayCur;
        else if (row == 2) { openPicker(editing.sound); return; }
        else if (row == 4) editing.loud = !editing.loud;
        else if (row == 5) editing.vib = !editing.vib;
        else if (row == 6) {
            if (actCur == 0) { editing.on = !editing.on; onTouched = true; }
            else { deleteEdit(); return; }
        }
        board.invalidate();
    }

    private void stepMinutes(int d) {
        int t = (editing.hour * 60 + editing.minute + d + 1440) % 1440;
        editing.hour = t / 60; editing.minute = t % 60;
    }

    /** Type the time as 4 digits (0 7 3 0 = 07:30); it shows as you go. */
    private void typeTime(int dg) {
        typed = (typed.length() >= 4 ? "" : typed) + dg;
        String t = (typed + "0000").substring(0, 4);
        int h = Integer.parseInt(t.substring(0, 2)), m = Integer.parseInt(t.substring(2));
        if (h < 24 && m < 60) { editing.hour = h; editing.minute = m; }
        else typed = typed.substring(0, typed.length() - 1);
    }

    private void step(int d) {
        if (editHour) editing.hour = (editing.hour + d + 24) % 24;
        else editing.minute = (editing.minute + d + 60) % 60;
    }

    private boolean delArmed;                         // 9 / delete pressed once: press again to delete

    private void deleteEdit() {
        if (!delArmed) { delArmed = true; board.invalidate(); return; }
        for (int i = alarms.size() - 1; i >= 0; i--) if (alarms.get(i).id == editing.id) alarms.remove(i);
        Clock.save(this, alarms);
        alarms = Clock.load(this);
        sel = Math.min(sel, alarms.size());
        editing = null;
        delArmed = false;
        android.widget.Toast.makeText(this, "alarm deleted", android.widget.Toast.LENGTH_SHORT).show();
        board.invalidate();
    }

    private boolean onTouched;                        // on/off changed in this edit (otherwise saving turns it on)

    private void saveEdit() {
        if (!onTouched) editing.on = true;
        onTouched = false;
        boolean found = false;
        for (int i = 0; i < alarms.size(); i++) if (alarms.get(i).id == editing.id) { alarms.set(i, editing); found = true; }
        if (!found) alarms.add(editing);
        Clock.save(this, alarms);
        alarms = Clock.load(this);
        long in = Clock.next(editing, System.currentTimeMillis()) - System.currentTimeMillis();
        android.widget.Toast.makeText(this, editing.on ? "rings in " + (in / 3_600_000) + " h " + (in / 60_000 % 60) + " min" : "saved (off)", android.widget.Toast.LENGTH_SHORT).show();
        editing = null;
        delArmed = false;
    }

    /** Everything is drawn here (tabs, alarm rows, the editor, the timer). */
    final class Board extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Board(Context c) { super(c); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), hh = getHeight();
            c.drawColor(Theme.VOID);
            p.setTypeface(bold);
            p.setTextSize(dp(15));
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(Theme.AMBER);
            c.drawText(tab == 0 ? "alarms" : "timer", w / 2, dp(20), p);
            p.setColor(Theme.RULE);
            c.drawRect(0, dp(31), w, dp(32), p);
            volTop = -1;
            if (pick >= 0) { drawPicker(c, w, hh); return; }
            if (editing != null) drawEditor(c, w, hh);
            else if (tab == 0) drawAlarms(c, w, hh);
            else drawTimer(c, w, hh);
        }

        private void hint(Canvas c, float w, float hh, String s) {
            p.setTypeface(mono);
            p.setTextSize(dp(10));
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(Theme.DIM);
            c.drawText(s, w / 2, hh - dp(6), p);
        }

        private void drawAlarms(Canvas c, float w, float hh) {
            float rowH = dp(40), top = dp(36);
            int first = Math.max(0, sel - (int) ((hh - top - dp(24)) / rowH) + 1);
            for (int i = first; i <= alarms.size(); i++) {
                float y = top + (i - first) * rowH;
                if (y + rowH > hh - dp(20)) break;
                if (i == sel) { p.setColor(Theme.LIT); c.drawRect(dp(4), y, w - dp(4), y + rowH - dp(4), p); }
                p.setTextAlign(Paint.Align.LEFT);
                if (i == alarms.size()) {
                    p.setTypeface(mono); p.setTextSize(dp(15));
                    p.setColor(i == sel ? Theme.AMBER : Theme.GREEN);
                    c.drawText("+ new alarm", dp(10), y + dp(24), p);
                    continue;
                }
                Clock.Alarm a = alarms.get(i);
                int col = !a.on ? Theme.DIM : i == sel ? Theme.AMBER : Theme.GREEN;
                p.setTypeface(bold); p.setTextSize(dp(22)); p.setColor(col);
                c.drawText(String.format("%02d:%02d", a.hour, a.minute), dp(10), y + dp(26), p);
                p.setTypeface(mono); p.setTextSize(dp(11)); p.setColor(a.on ? Theme.DIM : Theme.RULE);
                c.drawText(Clock.days(a.days), dp(92), y + dp(25), p);
                p.setTextAlign(Paint.Align.RIGHT);
                p.setColor(a.on ? col : Theme.DIM);
                // a switch: a pill with a knob on the right (on) or left (off)
                float sx = w - dp(46), sy = y + dp(12);
                p.setColor(a.on ? Theme.AMBER : Theme.RULE);
                c.drawRoundRect(new android.graphics.RectF(sx, sy, sx + dp(34), sy + dp(16)), dp(8), dp(8), p);
                p.setColor(a.on ? Theme.VOID : Theme.DIM);
                c.drawCircle(a.on ? sx + dp(26) : sx + dp(8), sy + dp(8), dp(6), p);
            }
            hint(c, w, hh, alarms.isEmpty() ? "[OK] new alarm" : "[OK] edit  [*] or tap switch: on/off");
        }

        float[] rowTop = new float[8];                    // editor rows (for touch), rowTop[7] = bottom

        private void rowBg(Canvas c, int row, float y0, float y1, float w) {
            rowTop[row] = y0; rowTop[row + 1] = y1;
            if (erow == row) { p.setColor(Theme.LIT); c.drawRect(dp(3), y0, w - dp(3), y1 - dp(2), p); }
        }

        private void label(Canvas c, String s, float x, float y, int row, Paint.Align a) {
            p.setTypeface(erow == row ? bold : mono); p.setTextSize(dp(13)); p.setTextAlign(a);
            p.setColor(erow == row ? Theme.AMBER : Theme.GREEN);
            c.drawText(s, x, y, p);
        }

        /** A little on/off switch. */
        private void pill(Canvas c, float right, float cy, boolean on) {
            float sx = right - dp(36);
            p.setColor(on ? Theme.AMBER : Theme.RULE);
            c.drawRoundRect(new android.graphics.RectF(sx, cy - dp(8), sx + dp(34), cy + dp(8)), dp(8), dp(8), p);
            p.setColor(on ? Theme.VOID : Theme.DIM);
            c.drawCircle(on ? sx + dp(26) : sx + dp(8), cy, dp(6), p);
        }

        private void drawEditor(Canvas c, float w, float hh) {
            float y = dp(34);
            // 0 time
            float h0 = dp(62);
            rowBg(c, 0, y, y + h0, w);
            p.setTypeface(bold); p.setTextSize(dp(44)); p.setTextAlign(Paint.Align.CENTER);
            p.setColor(erow == 0 ? Theme.AMBER : Theme.GREEN);
            c.drawText(String.format("%02d:%02d", editing.hour, editing.minute), w / 2, y + dp(46), p);
            if (erow == 0) { p.setTypeface(mono); p.setTextSize(dp(9)); p.setColor(Theme.DIM); c.drawText(typed.isEmpty() ? "type it (0730) or ← →" : "typing " + typed, w / 2, y + dp(58), p); }
            y += h0;
            // 1 days: big circles
            float d = (w - dp(16)) / 7f, rad = d / 2 - dp(3), h1 = d + dp(8);
            rowBg(c, 1, y, y + h1, w);
            String[] n = {"M", "T", "W", "T", "F", "S", "S"};
            for (int i = 0; i < 7; i++) {
                float cx = dp(8) + d * i + d / 2, cy = y + h1 / 2;
                boolean on = (editing.days & (1 << i)) != 0;
                p.setStyle(Paint.Style.FILL);
                p.setColor(on ? Theme.AMBER : Theme.CELL);
                c.drawCircle(cx, cy, rad, p);
                if (erow == 1 && i == dayCur) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(2)); p.setColor(Theme.GREEN); c.drawCircle(cx, cy, rad + dp(1), p); p.setStyle(Paint.Style.FILL); }
                p.setTypeface(bold); p.setTextSize(dp(13)); p.setTextAlign(Paint.Align.CENTER);
                p.setColor(on ? Theme.VOID : Theme.DIM);
                c.drawText(n[i], cx, cy + dp(5), p);
            }
            y += h1;
            float hr = dp(34);
            // 2 tone (which sound plays)
            rowBg(c, 2, y, y + hr, w);
            label(c, "tone", dp(10), y + dp(22), 2, Paint.Align.LEFT);
            String st = SoundsActivity.titleOfFile(editing.sound);
            p.setTextSize(dp(11)); p.setTypeface(mono); p.setTextAlign(Paint.Align.RIGHT); p.setColor(Theme.DIM);
            while (p.measureText(st + " ›") > w - dp(80) && st.length() > 4) st = st.substring(0, st.length() - 2);
            c.drawText(st + " ›", w - dp(10), y + dp(22), p);
            y += hr;
            // 3 volume: bar across + label
            float h3 = dp(40);
            rowBg(c, 3, y, y + h3, w);
            volTop = y + dp(6);
            volBar(c, dp(10), volTop, w - dp(20));
            label(c, "volume", dp(10), y + dp(36), 3, Paint.Align.LEFT);
            y += h3;
            // 4 / 5 sound + vibrate on/off (ignore the ringer mode)
            rowBg(c, 4, y, y + hr, w);
            label(c, "sound", dp(10), y + dp(22), 4, Paint.Align.LEFT);
            pill(c, w - dp(10), y + dp(17), editing.loud);
            y += hr;
            rowBg(c, 5, y, y + hr, w);
            label(c, "vibrate", dp(10), y + dp(22), 5, Paint.Align.LEFT);
            pill(c, w - dp(10), y + dp(17), editing.vib);
            y += hr;
            // 6 on/off + delete
            float h6 = dp(38);
            rowBg(c, 6, y, y + h6, w);
            float bw = (w - dp(20)) / 2;
            String[] lb = {editing.on ? "alarm on" : "alarm off", delArmed ? "sure? OK" : "delete"};
            for (int i = 0; i < 2; i++) {
                float x0 = dp(10) + i * bw;
                boolean f = erow == 6 && actCur == i;
                p.setStyle(Paint.Style.FILL);
                p.setColor(i == 1 && delArmed ? Theme.AMBER : f ? Theme.SEL : Theme.CELL);
                c.drawRect(x0 + dp(2), y + dp(5), x0 + bw - dp(2), y + h6 - dp(5), p);
                p.setTypeface(f ? bold : mono); p.setTextSize(dp(12)); p.setTextAlign(Paint.Align.CENTER);
                p.setColor(i == 1 && delArmed ? Theme.VOID : f ? Theme.AMBER : i == 0 && !editing.on ? Theme.DIM : Theme.GREEN);
                c.drawText(lb[i], x0 + bw / 2, y + dp(24), p);
            }
            y += h6;
            p.setTextAlign(Paint.Align.CENTER);
            hint(c, w, hh, erow == 1 ? "[←→] day [OK] or [1-7] on/off  [back] save" : "[↑↓] move  [OK] choose  [back] save");
        }

        float volTop = -1;                                // where the volume bar was drawn (for touch)
        float dayY, soundY, btnY;                         // the editor's rows (for touch)

        /** 7 blocks across the given width: the volume (tap or slide along it to set it). */
        private void volBar(Canvas c, float x, float y, float width) {
            float gap = dp(3), bw = (width - gap * 6) / 7;
            for (int i = 0; i < 7; i++) {
                p.setColor(i < curVol() ? Theme.AMBER : Theme.RULE);
                c.drawRect(x + i * (bw + gap), y, x + i * (bw + gap) + bw, y + dp(14), p);
            }
        }

        private void drawPicker(Canvas c, float w, float hh) {
            p.setTextAlign(Paint.Align.CENTER); p.setTypeface(bold); p.setTextSize(dp(13)); p.setColor(Theme.AMBER);
            c.drawText(editing != null ? "alarm sound" : "timer sound", w / 2, dp(48), p);
            float rowH = dp(40), top = dp(56);
            int rows = lib.size() + 1, fits = (int) ((hh - top - dp(20)) / rowH);
            int first = Math.max(0, Math.min(pick - fits / 2, rows - fits));
            p.setTextAlign(Paint.Align.LEFT);
            for (int i = first; i < rows && i < first + fits; i++) {
                float y = top + (i - first) * rowH;
                if (i == pick) { p.setColor(Theme.LIT); c.drawRect(dp(4), y, w - dp(4), y + rowH - dp(3), p); }
                p.setTypeface(i == pick ? bold : mono); p.setTextSize(dp(14)); p.setColor(i == pick ? Theme.AMBER : Theme.GREEN);
                c.drawText(i == 0 ? "your alarm sound (Sounds)" : lib.get(i - 1)[1], dp(10), y + dp(18), p);
                p.setTypeface(mono); p.setTextSize(dp(9)); p.setColor(Theme.DIM);
                String cr = i == 0 ? "the alarm picked in Sounds" : lib.get(i - 1)[2];
                while (p.measureText(cr) > w - dp(20) && cr.length() > 4) cr = cr.substring(0, cr.length() - 2);
                c.drawText(cr, dp(10), y + dp(32), p);
            }
            hint(c, w, hh, "[↑↓] listen  [OK] use  [back] cancel");
        }

        private void drawTimer(Canvas c, float w, float hh) {
            long end = timerEnd(), now = System.currentTimeMillis();
            boolean running = end > now;
            long show = running ? end - now : timerLeft() > 0 ? timerLeft() : timerMin * 60_000L;
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(bold); p.setTextSize(dp(show >= 3_600_000 ? 40 : 54));
            p.setColor(running ? Theme.AMBER : Theme.GREEN);
            c.drawText(mmss(show), w / 2, hh * 0.47f, p);
            p.setTypeface(mono); p.setTextSize(dp(12)); p.setColor(Theme.DIM);
            c.drawText(running ? "running" : timerLeft() > 0 ? "paused" : "type the minutes", w / 2, hh * 0.47f + dp(26), p);
            p.setTextSize(dp(12)); p.setColor(Theme.GREEN);
            volTop = hh * 0.47f + dp(40);
            volBar(c, dp(10), volTop, w - dp(20));
            p.setTextAlign(Paint.Align.LEFT); p.setColor(Theme.GREEN); p.setTextSize(dp(11));
            c.drawText("timer volume", dp(10), hh * 0.47f + dp(68), p);
            p.setTextAlign(Paint.Align.RIGHT);
            c.drawText("sound " + (loud() ? "✓" : "✗") + " [*]", w - dp(10), hh * 0.47f + dp(68), p);
            c.drawText("vibrate " + (vibe() ? "✓" : "✗") + " [0]", w - dp(10), hh * 0.47f + dp(86), p);
            p.setTextAlign(Paint.Align.CENTER); p.setTextSize(dp(12));
            c.drawText("sound: " + SoundsActivity.titleOfFile(Clock.prefs(ClockActivity.this).getString("timerSound", "")) + (running || timerLeft() > 0 ? "" : "  [#]"), w / 2, hh * 0.47f + dp(108), p);
            hint(c, w, hh, running ? "[OK] pause [#] reset [←→] volume" : "[1-9↑↓] min [OK] start [←→] volume");
        }

        private boolean dragging;

        @Override public boolean onTouchEvent(MotionEvent e) {
            boolean onBar = pick < 0 && volTop >= 0 && e.getY() > volTop - dp(10) && e.getY() < volTop + dp(26);
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) dragging = onBar;
            if (dragging) {                                   // slide along the bar
                int v = Math.max(1, Math.min(7, (int) ((e.getX() - dp(10)) / ((getWidth() - dp(20)) / 7f)) + 1));
                if (v != curVol() && e.getActionMasked() != MotionEvent.ACTION_UP) { storeVol(v); invalidate(); }
                if (e.getActionMasked() == MotionEvent.ACTION_UP) { dragging = false; setVol(curVol()); }   // a beep at the new level
                return true;
            }
            if (e.getAction() != MotionEvent.ACTION_UP) return true;
            float x = e.getX(), y = e.getY(), w = getWidth(), hh = getHeight();
            if (y < dp(32)) return true;
            if (pick >= 0) {
                float rowH = dp(40), top = dp(56);
                int rows = lib.size() + 1, fits = (int) ((hh - top - dp(20)) / rowH);
                int first = Math.max(0, Math.min(pick - fits / 2, rows - fits));
                int row = first + (int) ((y - top) / rowH);
                if (row >= 0 && row < rows) { if (row == pick) choosePick(); else { pick = row; tastePick(); } }
                invalidate();
                return true;
            }
            if (editing == null && tab == 1 && y > hh * 0.47f + dp(56) && y < hh * 0.47f + dp(74)) { toggleLoud(); invalidate(); return true; }
            if (editing == null && tab == 1 && y > hh * 0.47f + dp(74) && y < hh * 0.47f + dp(92)) { toggleVibe(); invalidate(); return true; }
            if (editing == null && tab == 1 && y > hh * 0.47f + dp(94) && y < hh * 0.47f + dp(116)) { openPicker(Clock.prefs(ClockActivity.this).getString("timerSound", "")); return true; }
            if (editing != null) {                       // tap a row: it gets the focus and does its thing
                int row = -1;
                for (int r = 0; r < 7; r++) if (y >= rowTop[r] && y < rowTop[r + 1]) row = r;
                if (row < 0) return true;
                erow = row;
                if (row == 0) stepMinutes(x < w / 2 ? -1 : 1);            // left half earlier, right half later
                else if (row == 1) { int d = (int) ((x - dp(8)) / ((w - dp(16)) / 7f)); if (d >= 0 && d < 7) { dayCur = d; editing.days ^= 1 << d; } }
                else if (row == 3) { }                                   // the bar handles itself (drag)
                else if (row == 6) { actCur = x < w / 2 ? 0 : 1; activate(6); return true; }
                else { activate(row); return true; }
                invalidate();
                return true;
            }
            if (tab == 0) {
                int row = (int) ((y - dp(36)) / dp(40));
                if (row >= 0 && row < alarms.size() && x > w - dp(56)) { alarms.get(row).on = !alarms.get(row).on; Clock.save(ClockActivity.this, alarms); sel = row; }
                else if (row >= 0 && row <= alarms.size()) { if (row == sel || row == alarms.size()) openRow(row); else sel = row; }
            } else timerOk();
            invalidate();
            return true;
        }
    }
}
