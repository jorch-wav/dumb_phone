package dumb_phone.podcasts;

import static dumb_phone.podcasts.Theme.AMBER;
import static dumb_phone.podcasts.Theme.CELL;
import static dumb_phone.podcasts.Theme.DIM;
import static dumb_phone.podcasts.Theme.GREEN;
import static dumb_phone.podcasts.Theme.LIT;
import static dumb_phone.podcasts.Theme.RULE;
import static dumb_phone.podcasts.Theme.SEL;
import static dumb_phone.podcasts.Theme.VOID;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Podcasts: a list of shows with their covers; OK opens a show on its own page (cover, newest
 * episodes, "load more"). The top bar shows what is playing: cover, title, time and a progress bar.
 * Keys: OK open / play, 5 play-pause, 1 -15 s, 3 +30 s, * played, # refresh, Back up a level,
 * hold a show = remove it.
 */
public class MainActivity extends Activity implements PodService.Listener {
    /** The radio's genre palette (xterm-256 colours), so shows get colours like the radio's genres. */
    static final int[] PALETTE = {84, 215, 80, 211, 141, 227, 111, 203, 43, 155, 219, 117, 179, 120, 147, 209, 86, 229, 177};

    private Typeface mono, bold;
    private Feeds feeds;
    static final String ADD = "+ add a podcast", MORE = "↓ load 10 more", LATEST = "↡ latest episodes", UNSUB = "✕ unsubscribe";
    /** The "remove this show?" row under a held show. */
    static final class Confirm { final Feeds.Show show; Confirm(Feeds.Show s) { show = s; } }
    /** The big cover + title at the top of a show's page. */
    static final class PageHead { }
    private Feeds.Show removeAsk;
    private Feeds.Show page;                 // the show whose page is open, or null for the list of shows
    private boolean latest;                  // the "latest episodes from every show" list
    private android.widget.FrameLayout frame;
    private View search;                     // the add-a-podcast screen, when open
    private android.widget.EditText query;
    private TextView searchStatus;
    private final List<String[]> results = new ArrayList<>();
    private BaseAdapter resultsAdapter;
    private final List<Object> rows = new ArrayList<>();
    private ListView list;
    private TextView title, now, progress, hint;
    private ImageView nowArt;
    private Bar nowBar;
    private final Adapter adapter = new Adapter();
    private final Handler handler = new Handler();
    private final Runnable ticker = new Runnable() {
        @Override public void run() { header(); handler.postDelayed(this, 1000); }
    };

    /** Write any uncaught crash to files/crash.log before the process dies, so a "closed suddenly" is diagnosable. */
    static void logCrashes(final android.content.Context c) {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                try {
                    java.io.StringWriter sw = new java.io.StringWriter();
                    e.printStackTrace(new java.io.PrintWriter(sw));
                    String line = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date())
                            + " [" + t.getName() + "] " + sw + "\n";
                    java.io.File f = new java.io.File(c.getFilesDir(), "crash.log");
                    java.io.FileWriter w = new java.io.FileWriter(f, true); w.write(line); w.close();
                } catch (Throwable ignored) { }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        logCrashes(getApplicationContext());
        Theme.load(this);                               // colours of the chosen theme
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        mono = Typeface.createFromAsset(getAssets(), "JetBrainsMono-Regular.ttf");
        bold = Typeface.createFromAsset(getAssets(), "JetBrainsMono-Bold.ttf");
        feeds = new Feeds(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(VOID);

        // now playing: cover | title, time / show, progress bar
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(px(6), px(4), px(6), px(4));
        nowArt = new ImageView(this);
        nowArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(px(40), px(40));
        ap.setMargins(0, 0, px(6), 0);
        head.addView(nowArt, ap);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        title = text(11, DIM);
        now = text(14, GREEN);
        progress = text(11, DIM);
        for (TextView t : new TextView[]{title, now, progress}) {
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(t);
        }
        nowBar = new Bar(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, px(3));
        bp.setMargins(0, px(2), 0, 0);
        texts.addView(nowBar, bp);
        head.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        head.setFocusable(false);
        head.setOnClickListener(new View.OnClickListener() {      // tap the top = pause / play
            @Override public void onClick(View v) { if (PodService.active) send(PodService.TOGGLE); }
        });
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
                if (o instanceof Feeds.Episode) { togglePlayed((Feeds.Episode) o); return true; }
                if (o instanceof Feeds.Show) { askRemove((Feeds.Show) o); return true; }
                return false;
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        root.addView(rule(), new LinearLayout.LayoutParams(-1, 1));
        root.addView(head);                                // now playing: cover + progress, at the bottom
        root.addView(rule(), new LinearLayout.LayoutParams(-1, 1));
        hint = text(10, DIM);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, px(4), 0, px(4));
        root.addView(hint);

        frame = new android.widget.FrameLayout(this);
        frame.setBackgroundColor(VOID);
        frame.addView(root);
        setContentView(frame);
        rebuild();
        // shows without a cover yet (never opened): fetch them quietly, one every 2 s
        int k = 0;
        for (final Feeds.Show s : feeds.shows) {
            if (s.image != null) continue;
            handler.postDelayed(new Runnable() { @Override public void run() { if (!isFinishing()) load(s, true); } }, 1500 + 2000L * k++);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (Theme.changed(this)) { recreate(); return; }   // theme picked in the Themes app
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && search == null) imm.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
        PodService.listener = this;
        onPodState();
        handler.post(ticker);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
        if (PodService.listener == this) PodService.listener = null;
    }

    private SharedPreferences prefs() { return getSharedPreferences("pods", MODE_PRIVATE); }

    // ---- list of shows / a show's page ----

    private void rebuild() {
        int pos = list.getSelectedItemPosition();
        Object keep = pos >= 0 && pos < rows.size() ? rows.get(pos) : null;
        rows.clear();
        if (latest) {
            for (Feeds.Episode e : latestEpisodes()) rows.add(e);
            if (rows.isEmpty()) rows.add(MORE);            // still loading the shows
            hint.setText("[OK] play  [5] ▶/❚❚  [1] -15s  [3] +30s\n[*] played  [←] back");
        } else if (page == null) {
            rows.add(LATEST);
            rows.add(ADD);
            for (Feeds.Show s : feeds.shows) {
                rows.add(s);
                if (s == removeAsk) rows.add(new Confirm(s));
            }
            hint.setText(PodService.active ? "[OK] open  [5] ▶/❚❚  [hold] remove\n[1] -15s  [3] +30s" : "[OK] open  [hold] remove");
        } else {
            rows.add(new PageHead());
            rows.addAll(page.episodes);
            if (page.more || page.loading) rows.add(MORE);
            rows.add(UNSUB);                               // a visible way to unsubscribe, no long-press needed
            if (removeAsk == page) rows.add(new Confirm(page));
            hint.setText("[OK] play  [5] ▶/❚❚  [1] -15s  [3] +30s\n[*] played  [#] refresh  [←] back");
        }
        adapter.notifyDataSetChanged();
        if (keep != null && rows.indexOf(keep) >= 0) list.setSelection(rows.indexOf(keep));
        header();
    }

    /** The newest episodes across every show, soonest first (only the ones already fetched/cached). */
    private List<Feeds.Episode> latestEpisodes() {
        List<Feeds.Episode> all = new ArrayList<>();
        for (Feeds.Show s : feeds.shows) all.addAll(s.episodes);
        java.util.Collections.sort(all, new java.util.Comparator<Feeds.Episode>() {
            @Override public int compare(Feeds.Episode a, Feeds.Episode b) { return Long.compare(b.date, a.date); }
        });
        return all.size() > 40 ? all.subList(0, 40) : all;
    }

    private void openLatest() {
        latest = true; page = null; removeAsk = null;
        rebuild();
        list.requestFocus();
        list.setSelection(0);
        int k = 0;                                          // load the shows we don't have yet, newest first as they arrive
        for (final Feeds.Show s : feeds.shows) {
            if (!s.episodes.isEmpty() && !feeds.stale(s)) continue;
            handler.postDelayed(new Runnable() { @Override public void run() { if (!isFinishing() && latest) load(s, false); } }, 300L * k++);
        }
    }

    private void closeLatest() {
        latest = false;
        rebuild();
        list.requestFocus();
        list.setSelection(0);
    }

    private void openPage(Feeds.Show s) {
        latest = false;
        page = s;
        removeAsk = null;
        rebuild();
        list.requestFocus();
        list.setSelection(rows.size() > 1 ? 1 : 0);
        if (s.episodes.isEmpty() || feeds.stale(s) || s.image == null) load(s, s.image == null);
    }

    private void closePage() {
        Feeds.Show s = page;
        page = null;
        rebuild();
        list.requestFocus();
        if (s != null && rows.indexOf(s) >= 0) list.setSelection(rows.indexOf(s));
    }

    private void loadMore() {
        if (page == null || page.loading) return;
        page.limit += 10;
        load(page, true);
    }

    /** Fetch a show's newest episodes off the main thread. */
    private void load(final Feeds.Show s, final boolean force) {
        if (s.loading) return;
        if (!force && !s.episodes.isEmpty() && !feeds.stale(s)) return;
        s.loading = true;
        s.error = null;
        final int token = ++s.attempt;
        rebuild();
        handler.postDelayed(new Runnable() {                // never spin forever
            @Override public void run() {
                if (s.loading && s.attempt == token) { s.loading = false; s.error = "too slow"; s.attempt++; rebuild(); }
            }
        }, 40000);
        new Thread(new Runnable() {
            @Override public void run() {
                List<Feeds.Episode> got = null;
                String err = null;
                try { got = feeds.fetch(s); }
                catch (Throwable e) {
                    android.util.Log.w("dumb_phone-podcasts", "fetch " + s.url, e);
                    err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                final List<Feeds.Episode> eps = got;
                final String error = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (s.attempt != token) return;            // timed out meanwhile
                        s.loading = false;
                        if (eps != null) { s.episodes.clear(); s.episodes.addAll(eps); s.fetched = System.currentTimeMillis(); }
                        else s.error = error;
                        if (!isFinishing()) rebuild();
                    }
                });
            }
        }).start();
    }

    private void choose(int pos) {
        Object o = rows.get(pos);
        if (o == ADD) { showSearch(); return; }
        if (o == LATEST) { openLatest(); return; }
        if (o == MORE) { if (latest) return; loadMore(); return; }
        if (o == UNSUB) { if (page != null) askRemove(page); return; }
        if (o instanceof PageHead) return;
        if (o instanceof Confirm) { removeShow(((Confirm) o).show); return; }
        if (removeAsk != null) { removeAsk = null; rebuild(); pos = rows.indexOf(o); if (pos < 0) return; }
        if (o instanceof Feeds.Show) { openPage((Feeds.Show) o); return; }
        Feeds.Episode e = (Feeds.Episode) o;
        if (PodService.active && e.url.equals(PodService.url)) send(PodService.TOGGLE);
        else play(e);
    }

    private void play(Feeds.Episode e) {
        String img = e.image != null ? e.image : page != null ? page.image : null;
        prefs().edit().putString("nowImage", img).apply();
        startService(new Intent(this, PodService.class).setAction(PodService.PLAY)
                .putExtra("url", e.url).putExtra("title", e.title).putExtra("show", e.show).putExtra("duration", e.duration));
    }

    // ---- removing a show ----

    private void askRemove(Feeds.Show s) {
        removeAsk = s;
        rebuild();
        for (int i = 0; i < rows.size(); i++) if (rows.get(i) instanceof Confirm) list.setSelection(i);
    }

    private void removeShow(Feeds.Show s) {
        removeAsk = null;
        feeds.remove(s);
        if (page == s) closePage();      // we were on that show's page: go back to the list
        rebuild();
        android.widget.Toast.makeText(this, "unsubscribed from " + s.title, android.widget.Toast.LENGTH_SHORT).show();
    }

    // ---- adding a podcast (search Apple's podcast directory) ----

    private void showSearch() {
        if (search != null) return;
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackgroundColor(VOID);
        v.setPadding(px(6), px(4), px(6), px(2));
        TextView t = text(14, AMBER);
        t.setTypeface(bold);
        t.setText("add a podcast");
        v.addView(t);
        query = new android.widget.EditText(this);
        query.setTypeface(mono);
        query.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        query.setTextColor(GREEN);
        query.setHintTextColor(DIM);
        query.setHint("name of the show");
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
                boolean have = feeds.has(x[2]);
                ((TextView) r.getChildAt(0)).setText(x[0]);
                ((TextView) r.getChildAt(0)).setTextColor(have ? DIM : GREEN);
                ((TextView) r.getChildAt(1)).setText((have ? "✓ subscribed  " : "") + x[1]);
                return r;
            }
        };
        resultsList.setAdapter(resultsAdapter);
        resultsList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View view, int i, long id) { subscribe(results.get(i)); }
        });
        v.addView(resultsList, new LinearLayout.LayoutParams(-1, 0, 1));
        search = v;
        results.clear();
        frame.addView(search);
        query.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(query, InputMethodManager.SHOW_IMPLICIT);
    }

    private ListView resultsList;

    private void closeSearch() {
        if (search == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
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
                try { got = Feeds.search(term); } catch (Exception e) { err = e.getMessage() == null ? "no connection" : e.getMessage(); }
                final List<String[]> r = got;
                final String error = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (search == null) return;
                        if (r == null) { searchStatus.setText("couldn't search: " + error); return; }
                        results.addAll(r);
                        resultsAdapter.notifyDataSetChanged();
                        searchStatus.setText(r.isEmpty() ? "nothing found for " + term : r.size() + " found  ·  ↓ then OK to subscribe");
                        if (!r.isEmpty()) { resultsList.requestFocus(); resultsList.setSelection(0); }
                    }
                });
            }
        }).start();
    }

    private void subscribe(String[] r) {
        Feeds.Show s = feeds.add(r[0], r[2]);
        closeSearch();
        openPage(s);
        android.widget.Toast.makeText(this, "subscribed to " + s.title, android.widget.Toast.LENGTH_SHORT).show();
    }


    private void togglePlayed(Feeds.Episode e) {
        feeds.setPlayed(e.url, !feeds.played(e.url));
        adapter.notifyDataSetChanged();
    }

    private void send(String action) { startService(new Intent(this, PodService.class).setAction(action)); }

    private void seek(long ms) {
        if (PodService.active) startService(new Intent(this, PodService.class).setAction(PodService.SEEK).putExtra("by", ms));
    }

    @Override public void onPodState() {
        header();
        adapter.notifyDataSetChanged();
    }

    private void header() {
        // the now-playing label shows the SHOW being played; only when nothing plays does it show where you are
        if (PodService.active && PodService.show != null && !PodService.show.isEmpty())
            title.setText(PodService.show.toUpperCase(Locale.getDefault()));
        else
            title.setText(latest ? "PODCASTS · LATEST" : page == null ? "PODCASTS" : "PODCASTS · " + page.title.toUpperCase(Locale.getDefault()));
        if (PodService.active) {
            now.setText((PodService.paused ? "❚❚ " : "▶ ") + PodService.title);
            now.setTextColor(AMBER);
            long p = PodService.position(), d = PodService.duration();
            String st = PodService.status;
            // the show name is now on the top label, so the progress line is just the time (or the state)
            progress.setText("playing".equals(st) || "paused".equals(st) ? clock(p) + (d > 0 ? " / " + clock(d) : "") : st);
            nowBar.set(d > 0 ? (float) p / d : 0, false);
            nowBar.setVisibility(View.VISIBLE);
            nowArt.setVisibility(View.VISIBLE);
            Art.into(this, prefs().getString("nowImage", null), nowArt);
        } else {
            now.setText("■ " + ("stopped".equals(PodService.status) ? "pick an episode" : PodService.status));
            now.setTextColor(GREEN);
            progress.setText(feeds.shows.size() + " shows");
            nowBar.setVisibility(View.GONE);
            nowArt.setVisibility(View.GONE);
        }
    }

    static String clock(long ms) {
        long s = ms / 1000, h = s / 3600, m = (s / 60) % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s % 60) : String.format(Locale.US, "%d:%02d", m, s % 60);
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (search != null) {
            if (code == KeyEvent.KEYCODE_BACK) { closeSearch(); return true; }
            return super.onKeyDown(code, e);
        }
        if (removeAsk != null && code == KeyEvent.KEYCODE_BACK) { removeAsk = null; rebuild(); return true; }
        int pos = list.getSelectedItemPosition();
        Object sel = pos >= 0 && pos < rows.size() ? rows.get(pos) : null;
        switch (code) {
            case KeyEvent.KEYCODE_5: if (PodService.active) send(PodService.TOGGLE); return true;
            case KeyEvent.KEYCODE_1: seek(-15000); return true;
            case KeyEvent.KEYCODE_3: seek(30000); return true;
            case KeyEvent.KEYCODE_STAR: if (sel instanceof Feeds.Episode) togglePlayed((Feeds.Episode) sel); return true;
            case KeyEvent.KEYCODE_POUND: if (page != null) load(page, true); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: if (latest) { closeLatest(); return true; } if (page != null) { closePage(); return true; } break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (sel instanceof Feeds.Show) { openPage((Feeds.Show) sel); return true; } break;
            case KeyEvent.KEYCODE_BACK: if (latest) { closeLatest(); return true; } if (page != null) { closePage(); return true; } break;
        }
        return super.onKeyDown(code, e);
    }

    // ---- views ----

    static int xterm(int n) {
        int[] lv = {0, 95, 135, 175, 215, 255};
        n -= 16;
        return Color.rgb(lv[n / 36], lv[(n / 6) % 6], lv[n % 6]);
    }

    static int colour(String name) {
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

    private String meta(Feeds.Episode e) {
        StringBuilder s = new StringBuilder();
        if (e.date > 0) s.append(new SimpleDateFormat("d MMM", Locale.getDefault()).format(new Date(e.date)).toLowerCase());
        if (e.duration > 0) s.append(s.length() > 0 ? " · " : "").append(e.duration / 60000).append(" min");
        long p = feeds.position(e.url);
        if (feeds.played(e.url)) s.append("  ✓ played");
        else if (p > 0) s.append("  ").append(clock(p)).append(" in");
        return s.toString();
    }

    /** A thin progress bar: filled part in the accent (or dim when played), rest in the rule colour. */
    final class Bar extends View {
        private float frac;
        private boolean done;
        private final android.graphics.Paint p = new android.graphics.Paint();
        Bar(android.content.Context c) { super(c); }
        void set(float f, boolean played) { frac = Math.max(0, Math.min(1, f)); done = played; invalidate(); }
        @Override protected void onDraw(android.graphics.Canvas c) {
            p.setColor(RULE);
            c.drawRect(0, 0, getWidth(), getHeight(), p);
            p.setColor(done ? DIM : AMBER);
            c.drawRect(0, 0, getWidth() * (done ? 1f : frac), getHeight(), p);
        }
    }

    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int i) { return rows.get(i) instanceof PageHead ? 1 : 0; }

        @Override public View getView(int i, View v, ViewGroup parent) {
            Object o = rows.get(i);
            if (o instanceof PageHead) return pageHead(v);
            LinearLayout row = (LinearLayout) v;
            if (row == null) {
                row = new LinearLayout(MainActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                ImageView art = new ImageView(MainActivity.this);
                art.setScaleType(ImageView.ScaleType.CENTER_CROP);
                LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(px(30), px(30));
                ap.setMargins(0, 0, px(7), 0);
                row.addView(art, ap);
                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                LinearLayout line = new LinearLayout(MainActivity.this);
                TextView name = text(14, GREEN);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                line.addView(text(13, GREEN));
                col.addView(line);
                TextView sub = text(11, DIM);
                sub.setSingleLine(true);
                sub.setEllipsize(TextUtils.TruncateAt.END);
                col.addView(sub);
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, px(2));
                bp.setMargins(0, px(3), 0, 0);
                col.addView(new Bar(MainActivity.this), bp);
                row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            }
            ImageView art = (ImageView) row.getChildAt(0);
            LinearLayout col = (LinearLayout) row.getChildAt(1);
            LinearLayout line = (LinearLayout) col.getChildAt(0);
            TextView name = (TextView) line.getChildAt(0), mark = (TextView) line.getChildAt(1);
            TextView sub = (TextView) col.getChildAt(1);
            Bar bar = (Bar) col.getChildAt(2);
            row.setPadding(px(6), px(6), px(6), px(6));
            if (o == ADD || o == MORE || o == LATEST || o == UNSUB || o instanceof Confirm) {
                art.setVisibility(View.GONE);
                bar.setVisibility(View.GONE);
                boolean add = o == ADD, more = o == MORE, lat = o == LATEST, uns = o == UNSUB;
                name.setText(add ? ADD : lat ? LATEST : uns ? UNSUB : more ? (latest ? "loading episodes…" : page != null && page.loading ? "loading…" : MORE) : "✕ remove " + ((Confirm) o).show.title + "?");
                name.setTypeface(bold);
                name.setTextColor(add || more || lat ? GREEN : AMBER);
                mark.setText("");
                sub.setText(add ? "search by name" : lat ? "newest from every show" : uns ? "stop following this show" : more ? "older episodes" : "OK = yes  ·  back = no");
                return row;
            }
            if (o instanceof Feeds.Show) {
                Feeds.Show s = (Feeds.Show) o;
                int c = colour(s.title);
                art.setVisibility(View.VISIBLE);
                Art.into(MainActivity.this, s.image, art);
                bar.setVisibility(View.GONE);
                name.setText(s.title);
                name.setTypeface(bold);
                name.setTextColor(c);
                mark.setTypeface(mono);
                mark.setTextColor(c);
                mark.setText(s.loading ? " …" : " ›");
                boolean playing = PodService.active && s.title.equals(PodService.show);
                sub.setText(playing ? (PodService.paused ? "❚❚ " : "▶ ") + PodService.title
                        : s.episodes.isEmpty() ? "open to load episodes" : "newest: " + s.episodes.get(0).title);
                return row;
            }
            Feeds.Episode e = (Feeds.Episode) o;
            boolean here = PodService.active && e.url.equals(PodService.url);
            boolean done = feeds.played(e.url);
            art.setVisibility(View.GONE);
            name.setText(e.title);
            name.setTypeface(here ? bold : mono);
            name.setTextColor(here ? AMBER : done ? DIM : GREEN);
            mark.setTypeface(mono);
            mark.setTextColor(AMBER);
            mark.setText(here ? (PodService.paused ? " ❚❚" : " ▶") : done ? " ✓" : "");
            sub.setText(latest ? e.show + " · " + meta(e) : meta(e));
            long p = here ? PodService.position() : feeds.position(e.url);
            long d = here && PodService.duration() > 0 ? PodService.duration() : e.duration;
            bar.setVisibility(done || p > 0 ? View.VISIBLE : View.GONE);
            bar.set(d > 0 ? (float) p / d : 0, done);
            return row;
        }

        /** Cover, title and episode count at the top of a show's page. */
        private View pageHead(View v) {
            LinearLayout h = (LinearLayout) v;
            if (h == null) {
                h = new LinearLayout(MainActivity.this);
                h.setGravity(Gravity.CENTER_VERTICAL);
                h.setPadding(px(6), px(8), px(6), px(8));
                ImageView art = new ImageView(MainActivity.this);
                art.setScaleType(ImageView.ScaleType.CENTER_CROP);
                LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(px(72), px(72));
                ap.setMargins(0, 0, px(8), 0);
                h.addView(art, ap);
                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                TextView t = text(15, GREEN);
                t.setTypeface(bold);
                t.setMaxLines(3);
                t.setEllipsize(TextUtils.TruncateAt.END);
                col.addView(t);
                col.addView(text(11, DIM));
                h.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            }
            if (page == null) return h;
            ImageView art = (ImageView) h.getChildAt(0);
            LinearLayout col = (LinearLayout) h.getChildAt(1);
            ((TextView) col.getChildAt(0)).setText(page.title);
            ((TextView) col.getChildAt(0)).setTextColor(colour(page.title));
            String info = page.loading && page.episodes.isEmpty() ? "loading the newest episodes…"
                    : page.error != null ? "couldn't load: " + page.error + "  [#] try again"
                    : page.episodes.size() + (page.episodes.size() == 1 ? " episode" : " episodes") + (page.more ? " · more below" : "") + "  · [#] refresh";
            ((TextView) col.getChildAt(1)).setText(info);
            Art.into(MainActivity.this, page.image, art);
            return h;
        }
    }
}
