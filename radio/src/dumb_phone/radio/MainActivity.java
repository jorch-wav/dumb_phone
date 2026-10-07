package dumb_phone.radio;

import static dumb_phone.radio.Theme.AMBER;
import static dumb_phone.radio.Theme.CELL;
import static dumb_phone.radio.Theme.DIM;
import static dumb_phone.radio.Theme.GREEN;
import static dumb_phone.radio.Theme.LIT;
import static dumb_phone.radio.Theme.RULE;
import static dumb_phone.radio.Theme.SEL;
import static dumb_phone.radio.Theme.VOID;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * One list of genres that fold open (accordions), each genre with its own colour.
 * Keys: OK open/fold genre or play/stop, ← fold, → open, * star, # all/favourites, 0 random, Back folds then exits.
 * The top row "+ add a station" searches radio-browser.info; holding a station asks to remove it.
 */
public class MainActivity extends Activity implements RadioService.Listener {
    /** Genre palette (xterm-256 colour numbers). */
    static final int[] PALETTE = {84, 215, 80, 211, 141, 227, 111, 203, 43, 155, 219, 117, 179, 120, 147, 209, 86, 229, 177};

    private Typeface mono, bold;            // JetBrains Mono
    private Stations stations;
    private boolean favsOnly;
    private Set<String> expanded;            // genres that are open
    private final List<Object> rows = new ArrayList<>();
    private ListView list;
    private TextView title, now, songLine, favKey;
    private final Adapter adapter = new Adapter();
    private final Random random = new Random();
    static final String ADD = "+ add a station";
    /** The "remove this station?" row under a held station. */
    static final class Confirm { final Stations.Station station; Confirm(Stations.Station s) { station = s; } }
    private Stations.Station removeAsk;
    private android.widget.FrameLayout frame;
    private LinearLayout search;                 // the add-a-station screen, when open
    private android.widget.EditText query;
    private TextView searchStatus;
    private ListView resultsList;
    private BaseAdapter resultsAdapter;
    private final List<String[]> results = new ArrayList<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.load(this);                               // colours of the chosen theme
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        mono = Typeface.createFromAsset(getAssets(), "JetBrainsMono-Regular.ttf");
        bold = Typeface.createFromAsset(getAssets(), "JetBrainsMono-Bold.ttf");
        stations = new Stations(this);
        SharedPreferences p = prefs();
        favsOnly = p.getBoolean("favsOnly", false);
        expanded = new HashSet<>(p.getStringSet("expanded", new HashSet<String>()));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(VOID);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(px(6), px(3), px(6), px(4));
        title = text(12, DIM);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        now = text(15, GREEN);
        now.setSingleLine(true);
        now.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(title);
        head.addView(now);
        songLine = text(13, GREEN);
        songLine.setSingleLine(true);
        songLine.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        songLine.setMarqueeRepeatLimit(-1);
        songLine.setSelected(true);                                // lets a long song title scroll
        songLine.setVisibility(View.GONE);
        head.addView(songLine);
        head.setFocusable(false);
        head.setOnClickListener(new View.OnClickListener() {     // tap the now-playing bar = stop
            @Override public void onClick(View v) { if (RadioService.playing) stop(); }
        });
        root.addView(head);
        root.addView(rule(), new LinearLayout.LayoutParams(-1, 1));

        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(SEL));
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) { choose(pos); }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                Object o = rows.get(pos);
                if (o instanceof Stations.Station) { askRemove((Stations.Station) o); return true; }
                return false;
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout foot = new LinearLayout(this);
        root.addView(rule(), new LinearLayout.LayoutParams(-1, 1));
        favKey = footKey("", new Runnable() { public void run() { switchFavs(); } });
        foot.addView(favKey, new LinearLayout.LayoutParams(0, -2, 1));
        foot.addView(footKey("0 random", new Runnable() { public void run() { playRandom(); } }), new LinearLayout.LayoutParams(0, -2, 1));
        foot.addView(footKey("* star", new Runnable() { public void run() { starSelected(); } }), new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(foot);

        frame = new android.widget.FrameLayout(this);
        frame.setBackgroundColor(VOID);
        frame.addView(root);
        setContentView(frame);
        rebuild();
        selectPlaying();
        handle(getIntent());
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handle(i);
    }

    /** From the Pi: adb shell am start -n dumb_phone.radio/.MainActivity --es play URL | --ez stop true */
    private void handle(Intent i) {
        if (i != null && i.getBooleanExtra("stop", false)) { stop(); return; }
        String u = i == null ? null : i.getStringExtra("play");
        if (u == null) return;
        Stations.Station s = stations.byUrl(u);
        play(s != null ? s : new Stations.Station(u, u, ""));
    }

    @Override protected void onResume() {
        super.onResume();
        if (Theme.changed(this)) { recreate(); return; }   // theme picked in the home app
        hideKeyboard();
        RadioService.listener = this;
        if (stations.stale()) {
            stations = new Stations(this);
            rebuild();
        }
        onRadioState();
    }

    @Override protected void onPause() {
        super.onPause();
        if (RadioService.listener == this) RadioService.listener = null;
    }

    private SharedPreferences prefs() { return getSharedPreferences("radio", MODE_PRIVATE); }

    // ---- the list ----

    /** Rebuild the rows: each genre header, followed by its stations when it is open. */
    private void rebuild() {
        rows.clear();
        if (!favsOnly) rows.add(ADD);
        for (Stations.Group g : stations.view(favsOnly)) {
            rows.add(g);
            if (!favsOnly && !expanded.contains(g.name)) continue;      // favourites: every genre open (few stations)
            for (Stations.Station st : g.stations) {
                rows.add(st);
                if (st == removeAsk) rows.add(new Confirm(st));
            }
        }
        header();
        favKey.setText(favsOnly ? "# all" : "# favs");
        adapter.notifyDataSetChanged();
    }

    private void select(Object o) {
        int i = rows.indexOf(o);
        if (i < 0) return;
        list.setSelection(i);
    }

    private Stations.Group groupOf(Stations.Station s) {
        for (Stations.Group g : stations.view(favsOnly)) if (g.name.equals(s.genre)) return g;
        return null;
    }

    private Stations.Group groupAt(int pos) {
        for (int i = Math.min(pos, rows.size() - 1); i >= 0; i--)
            if (rows.get(i) instanceof Stations.Group) return (Stations.Group) rows.get(i);
            else if (rows.get(i) == ADD) return null;
        return null;
    }

    private void setOpen(Stations.Group g, boolean open) {
        if (g == null || expanded.contains(g.name) == open) return;
        if (open) expanded.add(g.name); else expanded.remove(g.name);
        prefs().edit().putStringSet("expanded", new HashSet<>(expanded)).apply();
        rebuild();
        select(g);
    }

    private void selectPlaying() {
        Stations.Station s = RadioService.playing ? stations.byUrl(RadioService.url) : null;
        if (s == null) { if (!rows.isEmpty()) list.setSelection(0); return; }
        Stations.Group g = groupOf(s);
        if (g != null && !expanded.contains(g.name)) setOpen(g, true);
        for (Object o : rows) if (o instanceof Stations.Station && ((Stations.Station) o).url.equals(s.url)) { select(o); return; }
        if (g != null) select(g);
    }

    private void choose(int pos) {
        Object o = rows.get(pos);
        if (o == ADD) { showSearch(); return; }
        if (o instanceof Confirm) { removeStation(((Confirm) o).station); return; }
        if (removeAsk != null) { removeAsk = null; rebuild(); pos = rows.indexOf(o); if (pos < 0) return; }
        if (o instanceof Stations.Group) {
            Stations.Group g = (Stations.Group) o;
            setOpen(g, !expanded.contains(g.name));
        } else {
            Stations.Station s = (Stations.Station) o;
            if (RadioService.playing && s.url.equals(RadioService.url)) stop();
            else play(s);
        }
    }

    private void switchFavs() {
        favsOnly = !favsOnly;
        prefs().edit().putBoolean("favsOnly", favsOnly).apply();
        rebuild();
        selectPlaying();
        list.requestFocus();                            // the D-pad must work straight away in either view
        if (list.getSelectedItemPosition() < 0 && !rows.isEmpty()) list.setSelection(favsOnly ? 0 : Math.min(1, rows.size() - 1));
    }

    // ---- actions ----

    private void play(Stations.Station s) {
        startService(new Intent(this, RadioService.class).setAction(RadioService.PLAY)
                .putExtra("url", s.url).putExtra("name", s.name));
    }

    private void stop() {
        startService(new Intent(this, RadioService.class).setAction(RadioService.STOP));
    }

    private void playRandom() {
        List<Stations.Station> pool = new ArrayList<>();
        for (Stations.Group g : stations.view(favsOnly)) pool.addAll(g.stations);
        if (pool.isEmpty()) return;
        Stations.Station s = pool.get(random.nextInt(pool.size()));
        setOpen(groupOf(s), true);
        for (Object o : rows) if (o instanceof Stations.Station && ((Stations.Station) o).url.equals(s.url)) select(o);
        play(s);
    }

    private void starSelected() {
        Object o = list.getSelectedItem();
        if (o instanceof Stations.Station) star((Stations.Station) o);
        else if (RadioService.url != null && stations.byUrl(RadioService.url) != null) star(stations.byUrl(RadioService.url));
    }

    private void star(Stations.Station s) {
        stations.toggleFav(s);
        int pos = list.getSelectedItemPosition();
        if (favsOnly) {       // un-starring in the favourites view removes the line
            rebuild();
            if (!rows.isEmpty()) list.setSelection(Math.max(0, Math.min(pos, rows.size() - 1)));
        } else {
            adapter.notifyDataSetChanged();
        }
    }

    @Override public void onRadioState() {
        header();
        adapter.notifyDataSetChanged();
    }

    private void header() {
        String st = RadioService.status;
        title.setText(favsOnly ? "FAVOURITES" : "ALL STATIONS");
        if (RadioService.playing) now.setText("▶ " + RadioService.name + ("playing".equals(st) ? "" : " · " + st));
        else now.setText("■ " + st);
        String song = RadioService.playing ? RadioService.song : null;
        if (song != null && !("♪ " + song).equals(songLine.getText().toString())) songLine.setText("♪ " + song);
        songLine.setVisibility(song != null ? View.VISIBLE : View.GONE);
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (search != null && code != KeyEvent.KEYCODE_BACK) return super.onKeyDown(code, e);
        int pos = list.getSelectedItemPosition();
        Object sel = pos >= 0 && pos < rows.size() ? rows.get(pos) : null;
        switch (code) {
            case KeyEvent.KEYCODE_STAR: starSelected(); return true;
            case KeyEvent.KEYCODE_POUND: switchFavs(); return true;
            case KeyEvent.KEYCODE_0: playRandom(); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (sel != null) setOpen(groupAt(pos), false);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (sel instanceof Stations.Group) setOpen((Stations.Group) sel, true);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (search != null) { closeSearch(); return true; }
                if (removeAsk != null) { removeAsk = null; rebuild(); return true; }
                // Back on a station folds its genre; on a genre it leaves (the radio keeps playing).
                if (sel instanceof Stations.Station) { setOpen(groupAt(pos), false); return true; }
                break;
        }
        return super.onKeyDown(code, e);
    }

    // ---- views ----

    /** xterm-256 colour number to ARGB (the 6x6x6 cube and the grey ramp). */
    static int xterm(int n) {
        if (n >= 232) { int v = 8 + (n - 232) * 10; return Color.rgb(v, v, v); }
        int[] lv = {0, 95, 135, 175, 215, 255};
        n -= 16;
        return Color.rgb(lv[n / 36], lv[(n / 6) % 6], lv[n % 6]);
    }

    /** The sum of the genre name's characters picks the colour. */
    static int genreColour(String name) {
        int sum = 0;
        for (int i = 0; i < name.length(); i++) sum += name.charAt(i);
        return xterm(PALETTE[sum % PALETTE.length]);
    }

    private int px(int dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics()));
    }

    private TextView text(int size, int color) {
        TextView t = new TextView(this);
        t.setTypeface(mono);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(color);
        return t;
    }

    private View rule() {
        View v = new View(this);
        v.setBackgroundColor(RULE);
        return v;
    }

    private TextView footKey(String label, final Runnable action) {
        TextView t = text(11, DIM);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, px(5), 0, px(5));
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        return t;
    }

    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View v, ViewGroup parent) {
            LinearLayout row = (LinearLayout) v;
            if (row == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                LinearLayout line = new LinearLayout(MainActivity.this);
                TextView name = text(15, GREEN);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                line.addView(text(15, GREEN));
                row.addView(line);
                row.addView(new View(MainActivity.this), new LinearLayout.LayoutParams(-1, px(1)));
            }
            LinearLayout line = (LinearLayout) row.getChildAt(0);
            TextView name = (TextView) line.getChildAt(0), mark = (TextView) line.getChildAt(1);
            View under = row.getChildAt(1);
            Object o = rows.get(i);
            if (o == ADD || o instanceof Confirm) {
                row.setPadding(px(6), px(o == ADD ? 6 : 2), px(6), px(6));
                line.setPadding(0, 0, 0, 0);
                name.setText(o == ADD ? ADD : "  remove it?  OK = yes · back = no");
                name.setTypeface(o == ADD ? bold : mono);
                name.setTextColor(AMBER);
                mark.setText("");
                under.setVisibility(View.GONE);
                return row;
            }
            if (o instanceof Stations.Group) {
                Stations.Group g = (Stations.Group) o;
                int c = genreColour(g.name);
                boolean open = favsOnly || expanded.contains(g.name);
                boolean here = RadioService.playing && g.name.equals(genreOfPlaying());
                row.setPadding(px(6), px(i == 0 ? 6 : 12), px(6), 0);
                line.setPadding(0, 0, 0, px(4));
                name.setText(g.name);
                name.setTypeface(bold);
                name.setTextColor(c);
                mark.setText((here && !open ? "▶ " : "") + (open ? "−" : "+" + g.stations.size()));
                mark.setTypeface(mono);
                mark.setTextColor(c);
                under.setBackgroundColor(c);
                under.setVisibility(View.VISIBLE);
            } else {
                Stations.Station s = (Stations.Station) o;
                boolean here = RadioService.playing && s.url.equals(RadioService.url);
                row.setPadding(px(6), px(6), px(6), px(6));
                line.setPadding(0, 0, 0, 0);
                name.setText(s.name);
                name.setTypeface(mono);
                name.setTextColor(here ? AMBER : GREEN);
                mark.setText((stations.isFav(s) ? " ★" : "") + (here ? " ▶" : ""));
                mark.setTypeface(mono);
                mark.setTextColor(here ? AMBER : GREEN);
                under.setVisibility(View.GONE);
            }
            return row;
        }
    }

    // ---- removing a station ----

    private void askRemove(Stations.Station s) {
        removeAsk = s;
        rebuild();
        for (int i = 0; i < rows.size(); i++) if (rows.get(i) instanceof Confirm) list.setSelection(i);
    }

    private void removeStation(Stations.Station s) {
        removeAsk = null;
        int pos = list.getSelectedItemPosition();
        stations.remove(s);
        rebuild();
        if (!rows.isEmpty()) list.setSelection(Math.max(0, Math.min(pos - 1, rows.size() - 1)));
        android.widget.Toast.makeText(this, "removed " + s.name, android.widget.Toast.LENGTH_SHORT).show();
    }

    // ---- adding a station (search radio-browser.info) ----

    private void showSearch() {
        if (search != null) return;
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackgroundColor(VOID);
        v.setPadding(px(6), px(4), px(6), px(2));
        TextView t = text(14, AMBER);
        t.setTypeface(bold);
        t.setText("add a station");
        v.addView(t);
        query = new android.widget.EditText(this);
        query.setTypeface(mono);
        query.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        query.setTextColor(GREEN);
        query.setHintTextColor(DIM);
        query.setHint("station name");
        query.setSingleLine(true);
        query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        android.graphics.drawable.GradientDrawable box = new android.graphics.drawable.GradientDrawable();
        box.setColor(CELL);
        box.setStroke(px(1), GREEN);
        query.setBackground(box);
        query.setPadding(px(6), px(5), px(6), px(5));
        query.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView tv, int action, KeyEvent ev) { runSearch(); return true; }
        });
        query.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View view, int code, KeyEvent ev) {
                if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER) { runSearch(); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_DOWN && !results.isEmpty()) { resultsList.requestFocus(); resultsList.setSelection(0); return true; }
                return false;
            }
        });
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(-1, -2);
        qp.setMargins(0, px(4), 0, px(2));
        v.addView(query, qp);
        searchStatus = text(11, DIM);
        searchStatus.setText("type a name, then OK to search  ·  back = close");
        v.addView(searchStatus);
        resultsList = new ListView(this);
        resultsList.setDivider(null);
        resultsList.setSelector(new ColorDrawable(SEL));
        resultsAdapter = new BaseAdapter() {
            @Override public int getCount() { return results.size(); }
            @Override public Object getItem(int i) { return results.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View cv, ViewGroup parent) {
                LinearLayout r = (LinearLayout) cv;
                if (r == null) {
                    r = new LinearLayout(MainActivity.this);
                    r.setOrientation(LinearLayout.VERTICAL);
                    r.setPadding(px(2), px(5), px(2), px(5));
                    TextView a = text(14, GREEN); a.setSingleLine(true); a.setEllipsize(TextUtils.TruncateAt.END);
                    TextView b2 = text(11, DIM); b2.setSingleLine(true); b2.setEllipsize(TextUtils.TruncateAt.END);
                    r.addView(a); r.addView(b2);
                }
                String[] x = results.get(i);
                boolean have = stations.has(x[1]);
                ((TextView) r.getChildAt(0)).setText(x[0]);
                ((TextView) r.getChildAt(0)).setTextColor(have ? DIM : GREEN);
                ((TextView) r.getChildAt(1)).setText((have ? "✓ added  " : "") + x[3]);
                return r;
            }
        };
        resultsList.setAdapter(resultsAdapter);
        resultsList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View view, int i, long id) { addStation(results.get(i)); }
        });
        v.addView(resultsList, new LinearLayout.LayoutParams(-1, 0, 1));
        search = v;
        results.clear();
        frame.addView(search);
        query.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(query, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
    }

    private void closeSearch() {
        if (search == null) return;
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        frame.removeView(search);
        search = null;
        list.requestFocus();
    }

    private void runSearch() {
        final String term = query.getText().toString().trim();
        if (term.isEmpty()) return;
        searchStatus.setText("searching for " + term + "…");
        results.clear();
        resultsAdapter.notifyDataSetChanged();
        new Thread(new Runnable() {
            @Override public void run() {
                List<String[]> got = null;
                String err = null;
                try { got = Stations.search(term); } catch (Throwable e) { err = e.getMessage() == null ? "no connection" : e.getMessage(); }
                final List<String[]> r = got;
                final String error = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (search == null) return;
                        if (r == null) { searchStatus.setText("couldn't search: " + error); return; }
                        results.addAll(r);
                        resultsAdapter.notifyDataSetChanged();
                        searchStatus.setText(r.isEmpty() ? "nothing found for " + term : r.size() + " found  ·  ↓ then OK to add");
                        if (!r.isEmpty()) { resultsList.requestFocus(); resultsList.setSelection(0); }
                    }
                });
            }
        }).start();
    }

    private void addStation(String[] r) {
        Stations.Station s = stations.add(r[0], r[1], stations.genreFor(r[2]));
        closeSearch();
        expanded.add(s.genre);
        prefs().edit().putStringSet("expanded", new HashSet<>(expanded)).apply();
        rebuild();
        select(s);
        play(s);
        android.widget.Toast.makeText(this, "added to " + s.genre, android.widget.Toast.LENGTH_SHORT).show();
    }

    private String genreOfPlaying() {
        Stations.Station s = stations.byUrl(RadioService.url);
        return s == null ? null : s.genre;
    }

    /** Close the keyboard if another app left it open (it would sit over this screen). */
    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
    }

}
