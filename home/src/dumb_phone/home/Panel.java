package dumb_phone.home;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The pull-down panel in our theme (the phone's own grey one can't be themed): notifications first,
 * then quick settings and the weather. Opened from the home screen with up from the top row.
 * OK opens a notification, * clears it, Back closes the panel.
 */
final class Panel {
    interface Row { }
    static final class Header implements Row { final String text; Header(String t) { text = t; } }
    static final class Note implements Row { final Notifications.Item sbn; Note(Notifications.Item s) { sbn = s; } }
    static final class Action implements Row {
        final String text, sub; final Runnable run;
        Action(String t, String s, Runnable r) { text = t; sub = s; run = r; }
    }
    /** An icon, text and an optional right-hand value (weather). */
    static final class Line implements Row {
        final int glyph; final String text, right; final boolean big;
        Line(int g, String t, String r, boolean big) { glyph = g; text = t; right = r; this.big = big; }
    }
    /** One line of round toggle buttons (Wi-Fi, hotspot, Bluetooth, ringer): ← → picks, OK / tap switches. */
    static final class Toggles implements Row {
        static final class T {
            final String label; final Adjust.Getter glyph; final java.util.concurrent.Callable<Boolean> on; final Runnable flip;
            Runnable more;                              // long press: open its settings screen
            T(String l, Adjust.Getter g, java.util.concurrent.Callable<Boolean> o, Runnable f) { label = l; glyph = g; on = o; flip = f; }
            boolean isOn() { try { return on.call(); } catch (Exception e) { return false; } }
        }
        final List<T> items = new ArrayList<>();
        int focus;
    }
    /** Hours side by side (time / icon / temperature), iPhone-style: ← → scroll, OK or tap opens more. */
    static final class Strip implements Row {
        final List<String[]> cols = new ArrayList<>();        // {time, glyph code point, temp}
        int offset;                                           // first column shown
        Runnable open;
    }
    /** A line that only shows information (OK does nothing). */
    static final class Info implements Row { final String text; Info(String t) { text = t; } }
    /** What's playing (podcast / radio / music): OK or the ▶/❚❚ = play/pause, * or ■ = stop. */
    static final class Media implements Row {
        final String title, sub; final boolean playing;
        Media(String t, String s, boolean p) { title = t; sub = s; playing = p; }
    }

    final ListView view;
    private final HomeActivity home;
    private final Typeface mono, bold, icons;
    private final List<Row> rows = new ArrayList<>();
    private final BaseAdapter adapter;
    /** Extra rows (quick settings, weather) added after the notifications. */
    final List<RowSource> sources = new ArrayList<>();

    interface RowSource { void addRows(List<Row> out); }

    Panel(HomeActivity h, Typeface mono, Typeface bold) {
        home = h;
        this.mono = mono;
        this.bold = bold;
        icons = Typeface.createFromAsset(h.getAssets(), "phosphor-icons.ttf");
        view = new ListView(h);
        view.setBackgroundColor(Theme.VOID);
        view.setDivider(null);
        view.setSelector(new ColorDrawable(Theme.LIT));
        int p = dp(8);
        view.setPadding(p, dp(6), p, dp(6));
        adapter = new BaseAdapter() {
            @Override public int getCount() { return rows.size(); }
            @Override public Object getItem(int i) { return rows.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public boolean isEnabled(int i) { Row r = rows.get(i); return !(r instanceof Header) && !(r instanceof Info) && !(r instanceof Line); }
            @Override public boolean areAllItemsEnabled() { return false; }
            @Override public View getView(int i, View v, ViewGroup parent) { return draw(rows.get(i)); }
        };
        view.setAdapter(adapter);
        view.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> a, View v, int pos, long id) { choose(rows.get(pos)); }
        });
        view.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> a, View v, int pos, long id) { adapter.notifyDataSetChanged(); }
            @Override public void onNothingSelected(AdapterView<?> a) { }
        });
        view.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> a, View v, int pos, long id) {
                if (rows.get(pos) instanceof Note) { Notifications.dismiss(((Note) rows.get(pos)).sbn); return true; }
                if (rows.get(pos) instanceof Toggles) {          // hold OK on a circle = its settings
                    Toggles t = (Toggles) rows.get(pos);
                    Runnable m = t.items.get(t.focus).more;
                    if (m != null) m.run();
                    return true;
                }
                return false;
            }
        });
        view.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int code, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                Object o = view.getSelectedItem();
                if (code == KeyEvent.KEYCODE_STAR && o instanceof Note) { Notifications.dismiss(((Note) o).sbn); return true; }
                if (code == KeyEvent.KEYCODE_STAR && o instanceof Media) { mediaStop(); return true; }
                if ((code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) && o instanceof Strip) {
                    Strip st = (Strip) o;
                    st.offset = Math.max(0, Math.min(Math.max(0, st.cols.size() - 5), st.offset + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1)));
                    adapter.notifyDataSetChanged();
                    return true;
                }
                if ((code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) && o instanceof Toggles) {
                    Toggles t = (Toggles) o;
                    t.focus = Math.max(0, Math.min(t.items.size() - 1, t.focus + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1)));
                    adapter.notifyDataSetChanged();
                    return true;
                }
                if ((code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) && o instanceof Adjust) {
                    ((Adjust) o).step(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    refresh();
                    return true;
                }
                return false;
            }
        });
        refresh();
    }

    /** A row changed with ← → (e.g. brightness). */
    static final class Adjust implements Row {
        final String label; final Getter get; final Stepper step;
        Getter glyph;                                   // optional icon (as a code point string) left of the value
        interface Getter { String value(); }
        interface Stepper { void by(int d); }
        Adjust(String l, Getter g, Stepper s) { label = l; get = g; step = s; }
        void step(int d) { step.by(d); }
    }

    void refresh() {
        int sel = view.getSelectedItemPosition();
        rows.clear();
        if (NowPlaying.showing(home)) {                  // what's playing, above the notifications
            rows.add(new Header("playing"));
            rows.add(new Media(NowPlaying.label(home), NowPlaying.sublabel(home), NowPlaying.playingNow(home)));
        }
        List<Notifications.Item> notes = Notifications.list();
        rows.add(new Header(notes.isEmpty() ? "notifications" : "notifications · " + notes.size()));
        if (notes.isEmpty()) rows.add(new Info("nothing new"));
        else {
            for (Notifications.Item s : notes) rows.add(new Note(s));
            rows.add(new Action("clear all", null, new Runnable() { public void run() { Notifications.dismissAll(); } }));
        }
        for (RowSource src : sources) src.addRows(rows);
        adapter.notifyDataSetChanged();
        if (sel >= 0 && sel < rows.size()) view.setSelection(sel);
        else view.setSelection(firstEnabled());
    }

    int firstEnabled() {
        for (int i = 0; i < rows.size(); i++) if (adapter.isEnabled(i)) return i;
        return 0;
    }

    private void choose(Row r) {
        if (r instanceof Note) {
            Notifications.Item it = ((Note) r).sbn;
            try {
                if (it.open != null && !it.pkg.equals(Notifications.TELECOM)) it.open.send();
                else {                                       // missed calls: our recents; restored after a restart: the app itself
                    android.content.Intent i = it.pkg.equals(Notifications.TELECOM)
                            ? new android.content.Intent().setClassName("dumb_phone.phone", "dumb_phone.phone.PhoneActivity").putExtra("tab", "recents")
                            : home.getPackageManager().getLaunchIntentForPackage(it.pkg);
                    if (i != null) home.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                }
            } catch (Exception ignored) { }
            if (it.autoCancel || Notifications.instance == null) Notifications.dismiss(it);
            home.closePanel();
        } else if (r instanceof Action) {
            ((Action) r).run.run();
            refresh();
        } else if (r instanceof Adjust) {
            ((Adjust) r).step(1);
            refresh();
        } else if (r instanceof Strip) {
            if (((Strip) r).open != null) ((Strip) r).open.run();
        } else if (r instanceof Toggles) {
            Toggles t = (Toggles) r;
            t.items.get(t.focus).flip.run();
            delayedRefresh();
        } else if (r instanceof Media) {
            mediaPlayPause();
        }
    }

    private boolean ours(String pkg) { return NowPlaying.active && pkg.equals(NowPlaying.pkg); }

    /** Play/pause what's showing: our own apps via their service (reliable), anything else via a media button. */
    void mediaPlayPause() {
        if (ours("dumb_phone.podcasts")) svc("dumb_phone.podcasts", "dumb_phone.podcasts.PodService", "dumb_phone.podcasts.TOGGLE");
        else if (ours("dumb_phone.radio")) svc("dumb_phone.radio", "dumb_phone.radio.RadioService", "dumb_phone.radio.STOP");   // live radio: pause = stop
        else mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        afterControl();
    }

    /** Stop what's showing. */
    void mediaStop() {
        if (ours("dumb_phone.podcasts")) svc("dumb_phone.podcasts", "dumb_phone.podcasts.PodService", "dumb_phone.podcasts.STOP");
        else if (ours("dumb_phone.radio")) svc("dumb_phone.radio", "dumb_phone.radio.RadioService", "dumb_phone.radio.STOP");
        else mediaKey(KeyEvent.KEYCODE_MEDIA_STOP);
        afterControl();
    }

    private void svc(String pkg, String cls, String action) {
        try { home.startService(new Intent(action).setClassName(pkg, cls)); }
        catch (Exception e) { mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); }
    }

    private void mediaKey(int keycode) {
        try {
            AudioManager am = (AudioManager) home.getSystemService(Context.AUDIO_SERVICE);
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keycode));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keycode));
        } catch (Exception ignored) { }
    }

    private void afterControl() {
        view.postDelayed(new Runnable() { public void run() { refresh(); } }, 400);
        view.postDelayed(new Runnable() { public void run() { refresh(); } }, 1200);
    }

    /** Wi-Fi / Bluetooth take a moment to change state: redraw now and again shortly. */
    void delayedRefresh() {
        refresh();
        view.postDelayed(new Runnable() { public void run() { refresh(); } }, 1200);
        view.postDelayed(new Runnable() { public void run() { refresh(); } }, 3000);
    }

    private View draw(Row r) {
        LinearLayout row = new LinearLayout(home);
        row.setOrientation(LinearLayout.VERTICAL);
        if (r instanceof Header) {
            row.setPadding(dp(2), dp(8), dp(2), dp(3));
            row.addView(text(((Header) r).text, bold, 12, Theme.AMBER));
            View rule = new View(home);
            rule.setBackgroundColor(Theme.RULE);
            row.addView(rule, new LinearLayout.LayoutParams(-1, 1));
            return row;
        }
        row.setPadding(dp(4), dp(6), dp(4), dp(6));
        if (r instanceof Strip) {
            final Strip st = (Strip) r;
            final android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(home);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setFocusable(false);
            LinearLayout line = new LinearLayout(home);
            final int colW = dp(44);
            for (String[] c : st.cols) {
                LinearLayout col = new LinearLayout(home);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
                boolean now = c[0].equals("now");
                col.addView(text(c[0], now ? bold : mono, 10, now ? Theme.AMBER : Theme.DIM));
                col.addView(text(new String(Character.toChars(Integer.parseInt(c[1]))), icons, 18, now ? Theme.AMBER : Theme.GREEN));
                col.addView(text(c[2], now ? bold : mono, 12, now ? Theme.AMBER : Theme.GREEN));
                line.addView(col, new LinearLayout.LayoutParams(colW, -2));
            }
            hs.addView(line);
            hs.post(new Runnable() { public void run() { hs.scrollTo(st.offset * colW, 0); } });
            hs.setOnTouchListener(new View.OnTouchListener() {      // a tap (not a drag) opens the weather app
                float x0;
                public boolean onTouch(View v, android.view.MotionEvent ev) {
                    if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN) x0 = ev.getX();
                    if (ev.getAction() == android.view.MotionEvent.ACTION_UP && Math.abs(ev.getX() - x0) < dp(8) && st.open != null) st.open.run();
                    return false;
                }
            });
            row.setPadding(dp(2), dp(4), dp(2), dp(4));
            row.addView(hs);
            return row;
        }
        if (r instanceof Toggles) {
            final Toggles tg = (Toggles) r;
            boolean rowSelected = view.getSelectedItem() == r;
            LinearLayout line = new LinearLayout(home);
            line.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            for (int k = 0; k < tg.items.size(); k++) {
                final int idx = k;
                Toggles.T t = tg.items.get(k);
                boolean on = t.isOn(), focused = rowSelected && k == tg.focus;
                LinearLayout cell = new LinearLayout(home);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
                TextView circle = text(t.glyph.value(), icons, 20, on ? Theme.VOID : Theme.DIM);
                circle.setGravity(android.view.Gravity.CENTER);
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                bg.setColor(on ? Theme.GREEN : Theme.CELL);
                bg.setStroke(dp(focused ? 2 : 1), focused ? Theme.AMBER : on ? Theme.GREEN : Theme.RULE);
                circle.setBackground(bg);
                cell.addView(circle, new LinearLayout.LayoutParams(dp(42), dp(42)));
                TextView lab = text(t.label, mono, 10, focused ? Theme.AMBER : on ? Theme.GREEN : Theme.DIM);
                lab.setGravity(android.view.Gravity.CENTER);
                cell.addView(lab);
                cell.setOnTouchListener(new View.OnTouchListener() {   // touch, not click: clickable children would
                    boolean held;                                         // stop the D-pad selecting this row
                    final Runnable hold = new Runnable() { public void run() {
                        held = true;
                        tg.focus = idx;
                        Runnable m = tg.items.get(idx).more;
                        if (m != null) m.run();
                    } };
                    public boolean onTouch(View x, android.view.MotionEvent ev) {
                        int a = ev.getActionMasked();
                        if (a == android.view.MotionEvent.ACTION_DOWN) { held = false; x.postDelayed(hold, 550); }
                        else if (a == android.view.MotionEvent.ACTION_CANCEL) x.removeCallbacks(hold);
                        else if (a == android.view.MotionEvent.ACTION_UP) {
                            x.removeCallbacks(hold);
                            if (!held) {
                                tg.focus = idx;
                                tg.items.get(idx).flip.run();
                                delayedRefresh();
                            }
                        }
                        return true;
                    }
                });
                line.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
            }
            for (int k = tg.items.size(); k < 4; k++) line.addView(new View(home), new LinearLayout.LayoutParams(0, 1, 1));   // keep circles in a 4-wide grid
            row.setPadding(dp(2), dp(8), dp(2), dp(6));
            row.addView(line);
            return row;
        }
        if (r instanceof Line) {
            Line l = (Line) r;
            LinearLayout line = new LinearLayout(home);
            line.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView g = text(new String(Character.toChars(l.glyph)), icons, l.big ? 22 : 15, l.big ? Theme.AMBER : Theme.GREEN);
            g.setPadding(0, 0, dp(8), 0);
            line.addView(g);
            TextView t = text(l.text, l.big ? bold : mono, l.big ? 15 : 12, l.big ? Theme.AMBER : Theme.GREEN);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            line.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            if (l.right != null) line.addView(text(l.right, mono, 12, Theme.DIM));
            row.setPadding(dp(4), dp(l.big ? 5 : 3), dp(4), dp(l.big ? 5 : 3));
            row.addView(line);
        } else if (r instanceof Media) {
            Media m = (Media) r;
            LinearLayout line = new LinearLayout(home);
            line.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView pp = text(m.playing ? "❚❚" : "▶", bold, 18, Theme.AMBER);
            pp.setPadding(dp(2), 0, dp(10), 0);
            line.addView(pp);
            LinearLayout col = new LinearLayout(home);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView t = text(m.title, bold, 14, Theme.GREEN);
            t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(t);
            if (m.sub != null && !m.sub.isEmpty()) {
                TextView sub = text(m.sub, mono, 11, Theme.DIM);
                sub.setSingleLine(true); sub.setEllipsize(TextUtils.TruncateAt.END);
                col.addView(sub);
            }
            line.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            TextView stop = text("■", bold, 17, Theme.GREEN);   // tap = stop (so you can kill forgotten radio/podcasts)
            stop.setGravity(android.view.Gravity.CENTER);
            stop.setPadding(dp(14), dp(6), dp(10), dp(6));
            stop.setFocusable(false);
            stop.setOnTouchListener(new View.OnTouchListener() {
                public boolean onTouch(View x, android.view.MotionEvent ev) {
                    if (ev.getActionMasked() == android.view.MotionEvent.ACTION_UP) mediaStop();
                    return true;   // don't let the row's tap (play/pause) also fire
                }
            });
            line.addView(stop);
            row.addView(line);
        } else if (r instanceof Info) {
            row.addView(text(((Info) r).text, mono, 13, Theme.DIM));
        } else if (r instanceof Action) {
            row.addView(text(((Action) r).text, mono, 14, Theme.GREEN));
            if (((Action) r).sub != null) row.addView(text(((Action) r).sub, mono, 11, Theme.DIM));
        } else if (r instanceof Adjust) {
            final Adjust ad = (Adjust) r;                       // a full-width block bar, its name underneath
            View bar = new View(home) {
                final android.graphics.Paint p = new android.graphics.Paint();
                String v = ad.get.value();
                @Override public boolean onTouchEvent(android.view.MotionEvent ev) {   // tap or drag sets the level
                    int act = ev.getActionMasked();
                    if (act != android.view.MotionEvent.ACTION_DOWN && act != android.view.MotionEvent.ACTION_MOVE) return true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    int n = Math.max(1, v.length());
                    int want = Math.max(0, Math.min(n - 1, (int) (ev.getX() / getWidth() * n)));
                    int have = v.lastIndexOf('█');
                    if (want != have) { ad.step(want - have); v = ad.get.value(); invalidate(); }
                    return true;
                }
                @Override protected void onDraw(android.graphics.Canvas c) {
                    int n = Math.max(1, v.length()), gap = dp(3);
                    float w = (getWidth() - gap * (n - 1f)) / n;
                    for (int i = 0; i < n; i++) {
                        p.setColor(v.charAt(i) == '█' ? Theme.AMBER : Theme.RULE);
                        c.drawRect(i * (w + gap), getHeight() - dp(12), i * (w + gap) + w, getHeight(), p);   // the space above is extra touch room
                    }
                }
            };
            row.addView(bar, new LinearLayout.LayoutParams(-1, dp(22)));
            LinearLayout line = new LinearLayout(home);
            line.setPadding(0, dp(3), 0, 0);
            if (ad.glyph != null) {
                TextView g = text(ad.glyph.value(), icons, 13, Theme.DIM);
                g.setPadding(0, 0, dp(4), 0);
                line.addView(g);
            }
            line.addView(text(ad.label, mono, 12, Theme.GREEN), new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(text("‹ ›", mono, 12, Theme.DIM));
            row.addView(line);
        } else if (r instanceof Note) {
            Notifications.Item s = ((Note) r).sbn;
            String title = s.title, body = s.text;
            String when = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(s.time));
            row.addView(text(appName(s.pkg) + " · " + when, mono, 10, Theme.DIM));
            TextView t = text(title, bold, 14, Theme.GREEN);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(t);
            if (body != null && body.length() > 0) {
                TextView b = text(body, mono, 12, Theme.GREEN);
                b.setMaxLines(2);
                b.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(b);
            }
        }
        return row;
    }

    private String appName(String pkg) {
        try {
            PackageManager pm = home.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().toLowerCase(Locale.getDefault());
        } catch (Exception e) { return pkg; }
    }

    private TextView text(String s, Typeface tf, int sp, int color) {
        TextView t = new TextView(home);
        t.setText(s);
        t.setTypeface(tf);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, home.getResources().getDisplayMetrics()));
    }

}
