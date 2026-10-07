package dumb_phone.dumbtrains;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
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

import static dumb_phone.dumbtrains.Theme.AMBER;
import static dumb_phone.dumbtrains.Theme.DIM;
import static dumb_phone.dumbtrains.Theme.GREEN;
import static dumb_phone.dumbtrains.Theme.RULE;
import static dumb_phone.dumbtrains.Theme.SEL;
import static dumb_phone.dumbtrains.Theme.VOID;

/**
 * Trains: from, to, when (now or a set time), the next direct trains. Fetches only when opened or when
 * you ask (#); remembers the two stations; * swaps them.
 */
public class MainActivity extends Activity {
    private static final int STATE = 3, FROM = 0, TO = 1, WHEN = 2;
    private static final String[] ST_CODE = {"", "vic", "nsw", "qld", "wa", "sa", "tas", "act", "nt"};
    private static final String[] ST_NAME = {"anywhere", "Victoria", "New South Wales", "Queensland",
            "Western Australia", "South Australia", "Tasmania", "ACT", "Northern Territory"};
    private int stateIx;
    private boolean stateSet;
    private Typeface mono, bold;
    private ListView list;
    private TextView status;
    private final List<Object> rows = new ArrayList<>();
    private final List<Transit.Trip> trips = new ArrayList<>();
    private Transit.Stop from, to;
    private long when;                                  // 0 = now, else the chosen time (ms)
    private boolean arrive;                             // the time is "arrive by" (else "leave")
    private long fetched;                               // when the trips were last fetched
    private String fetchedFor = "";
    private boolean loading;
    private String error;
    private int gen;
    private final Handler main = new Handler();

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public boolean isEnabled(int i) { return !(rows.get(i) instanceof String); }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public View getView(int i, View v, ViewGroup p) { return row(rows.get(i)); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.load(this);
        mono = Typeface.createFromAsset(getAssets(), "JetBrainsMono-Regular.ttf");
        bold = Typeface.createFromAsset(getAssets(), "JetBrainsMono-Bold.ttf");
        from = load("from");
        to = load("to");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(VOID);
        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(SEL));
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> a, View v, int pos, long id) { choose(rows.get(pos)); }
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        View rule = new View(this);
        rule.setBackgroundColor(RULE);
        root.addView(rule, new LinearLayout.LayoutParams(-1, 1));
        status = text("", mono, 10, DIM);
        status.setPadding(px(6), px(3), px(6), px(4));
        root.addView(status);
        setContentView(root);
        loadState();
        rebuild();
        list.requestFocus();
        list.setSelection(from == null ? 0 : to == null ? 1 : 3);   // start where there's something to do
    }

    @Override protected void onResume() {
        super.onResume();
        if (Theme.changed(this)) { recreate(); return; }
        // only now: open = fresh times (if the last fetch is older than a minute or the stations changed)
        if (System.currentTimeMillis() - fetched > 60_000 || !fetchedFor.equals(trip())) fetch();
    }

    private SharedPreferences prefs() { return getSharedPreferences("trains", MODE_PRIVATE); }

    private Transit.Stop load(String k) {
        String s = prefs().getString(k, null);
        if (s == null) return null;
        String[] p = s.split("\t");
        if (p.length < 2) return null;
        Transit.Stop st = new Transit.Stop();
        st.id = p[0]; st.name = p[1];
        return st;
    }

    private void save() {
        SharedPreferences.Editor e = prefs().edit();
        e.putString("from", from == null ? null : from.id + "\t" + from.name);
        e.putString("to", to == null ? null : to.id + "\t" + to.name);
        e.apply();
    }

    private String trip() { return (from == null ? "" : from.id) + ">" + (to == null ? "" : to.id) + "@" + when + (arrive ? "a" : "l"); }

    // ---- the list: from, to, when, then the trains ----

    private void loadState() {
        String code = prefs().getString("state", null);
        stateSet = code != null;
        stateIx = 0;
        for (int i = 0; i < ST_CODE.length; i++) if (ST_CODE[i].equals(code)) stateIx = i;
        Transit.state = ST_CODE[stateIx];
    }

    private void setState(int ix) {
        stateIx = (ix % ST_CODE.length + ST_CODE.length) % ST_CODE.length;
        stateSet = true;
        Transit.state = ST_CODE[stateIx];
        prefs().edit().putString("state", ST_CODE[stateIx]).apply();
        rebuild();
    }

    private void rebuild() {
        rows.clear();
        rows.add(STATE); rows.add(FROM); rows.add(TO); rows.add(WHEN);
        if (!stateSet) rows.add("set your state above (← →) so your local stations show first");
        if (from == null || to == null) rows.add("pick both stations (OK on a line above)");
        else if (loading && trips.isEmpty()) rows.add("looking…");
        else if (error != null) rows.add(error);
        else if (trips.isEmpty()) rows.add(arrive && when > 0 ? "no train gets there by then (→ = a later time)" : "no train found (try another time with →)");
        else rows.addAll(trips);
        adapter.notifyDataSetChanged();
        status.setText("← → time · OK leave/arrive · 0 now\n* swap · # refresh" + (fetched == 0 ? "" : " · updated " + hm(fetched)));
    }

    private View row(Object o) {
        TextView t = text("", mono, 14, GREEN);
        t.setPadding(px(8), px(6), px(8), px(6));
        SpannableStringBuilder s = new SpannableStringBuilder();
        if (o instanceof Integer) {
            int k = (Integer) o;
            String label = k == STATE ? "state " : k == FROM ? "from  " : k == TO ? "to    " : "when  ";
            String val = k == STATE ? ST_NAME[stateIx]
                    : k == FROM ? (from == null ? "pick a station" : from.name)
                    : k == TO ? (to == null ? "pick a station" : to.name)
                    : (when == 0 ? "now" : (arrive ? "arrive by " : "leave ") + day(when) + hm(when));
            add(s, label, DIM, 0.85f);
            add(s, val, (k == WHEN && when != 0) || (k == STATE && stateIx != 0) ? AMBER : GREEN, 1f);
            t.setTypeface(bold);
        } else if (o instanceof Transit.Trip) {
            Transit.Trip tr = (Transit.Trip) o;
            long mins = (tr.leave - System.currentTimeMillis()) / 60_000;
            add(s, hm(tr.leave) + " → " + hm(tr.arrive), GREEN, 1f);
            long early = (when - tr.arrive) / 60_000;
            add(s, "   " + (arrive && when > 0 ? (early <= 0 ? "on time" : early + " min early")
                    : mins <= 0 ? "now" : mins < 60 ? "in " + mins + " min" : ""), AMBER, 0.85f);
            add(s, "\n" + tr.line + (tr.platform.isEmpty() ? "" : " · platform " + tr.platform)
                    + " · " + ((tr.arrive - tr.leave) / 60_000) + " min"
                    + (tr.changes == 0 ? " · direct" : " · " + tr.changes + " change" + (tr.changes > 1 ? "s" : ""))
                    + (tr.live ? " · live" : ""), DIM, 0.75f);
        } else {
            add(s, String.valueOf(o), DIM, 0.85f);
        }
        t.setText(s);
        return t;
    }

    private static void add(SpannableStringBuilder s, String text, int col, float size) {
        int at = s.length();
        s.append(text);
        s.setSpan(new ForegroundColorSpan(col), at, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (size != 1f) s.setSpan(new RelativeSizeSpan(size), at, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void choose(Object o) {
        if (!(o instanceof Integer)) { fetch(); return; }          // OK on a train = refresh
        int k = (Integer) o;
        if (k == STATE) { setState(stateIx + 1); return; }
        if (k == WHEN) {                                       // OK on "when" = leave <-> arrive by (same time)
            if (when == 0) { when = round(System.currentTimeMillis()) + 30 * 60_000L; arrive = true; }
            else arrive = !arrive;
            rebuild(); fetch(); return;
        }
        startActivityForResult(new Intent(this, PickActivity.class).putExtra("title", k == FROM ? "from" : "to"), k);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (res != RESULT_OK || data == null) return;
        Transit.Stop s = new Transit.Stop();
        s.id = data.getStringExtra("id"); s.name = data.getStringExtra("name"); s.area = data.getStringExtra("area");
        if (req == FROM) from = s; else to = s;
        save();
        trips.clear();
        rebuild();
        fetch();
    }

    private void swap() {
        Transit.Stop t = from; from = to; to = t;
        save();
        trips.clear();
        rebuild();
        fetch();
    }

    private void fetch() {
        if (from == null || to == null) { rebuild(); return; }
        final Transit.Stop a = from, b = to;
        final long w = when;
        final boolean arr = arrive && when > 0;
        final int g = ++gen;
        loading = true; error = null;
        rebuild();
        new Thread(new Runnable() { public void run() {
            List<Transit.Trip> got = null; String err = null;
            try { got = Transit.trips(a, b, w, 5, arr); }
            catch (java.net.UnknownHostException e) { err = "no internet"; }
            catch (Exception e) { err = "couldn't reach the trains service (" + e.getClass().getSimpleName() + ")"; }
            final List<Transit.Trip> fg = got; final String fe = err;
            main.post(new Runnable() { public void run() {
                if (g != gen) return;
                loading = false;
                error = fe;
                trips.clear();
                if (fg != null) trips.addAll(fg);
                fetched = System.currentTimeMillis();
                fetchedFor = trip();
                rebuild();
            } });
        } }).start();
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        int pos = list.getSelectedItemPosition();
        boolean onWhen = pos >= 0 && pos < rows.size() && Integer.valueOf(WHEN).equals(rows.get(pos));
        boolean onState = pos >= 0 && pos < rows.size() && Integer.valueOf(STATE).equals(rows.get(pos));
        switch (code) {
            case KeyEvent.KEYCODE_STAR: swap(); return true;
            case KeyEvent.KEYCODE_POUND: fetch(); return true;
            case KeyEvent.KEYCODE_0: when = 0; arrive = false; rebuild(); fetch(); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (onState) { setState(stateIx + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1)); list.setSelection(0); return true; }
                if (onWhen) {                                         // ± 10 minutes, from now
                    long base = when == 0 ? round(System.currentTimeMillis()) : when;
                    when = Math.max(round(System.currentTimeMillis()), base + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 600_000 : -600_000));
                    if (when <= round(System.currentTimeMillis()) && !arrive) when = 0;   // back to "now" (arrive-by keeps a time)
                    if (arrive && when <= System.currentTimeMillis()) when = round(System.currentTimeMillis()) + 600_000;
                    rebuild();
                    list.setSelection(2);
                    main.removeCallbacks(refetch);
                    main.postDelayed(refetch, 900);                   // look up once you stop pressing
                    return true;
                }
                break;
        }
        return super.onKeyDown(code, e);
    }

    private final Runnable refetch = new Runnable() { public void run() { fetch(); } };

    private static long round(long ms) { return ms - ms % 600_000; }

    private static String hm(long ms) { return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(ms)); }

    private static String day(long ms) {
        java.util.Calendar a = java.util.Calendar.getInstance(), b = java.util.Calendar.getInstance();
        b.setTimeInMillis(ms);
        return a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR) ? ""
                : new SimpleDateFormat("EEE ", Locale.getDefault()).format(new Date(ms)).toLowerCase(Locale.getDefault());
    }

    private TextView text(String s, Typeface tf, int sp, int col) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(tf);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(col);
        return t;
    }

    private int px(int dp) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics())); }
}
