package dumb_phone.home;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Locale;

/** "Weather": now, the next 24 hours and 7 days, from the same forecast the panel uses. */
public class WeatherActivity extends Activity {
    private Typeface mono, bold, icons;
    private ScrollView sv;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.load(this);
        mono = Typeface.createFromAsset(getAssets(), "JetBrainsMono-Regular.ttf");
        bold = Typeface.createFromAsset(getAssets(), "JetBrainsMono-Bold.ttf");
        icons = Typeface.createFromAsset(getAssets(), "phosphor-icons.ttf");
        sv = new ScrollView(this);
        sv.setBackgroundColor(Theme.VOID);
        setContentView(sv);
        draw();
        Weather.refresh(this, new Runnable() { public void run() { draw(); } });
    }

    private void draw() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(8), dp(6), dp(8), dp(10));
        v.addView(text("weather", bold, 13, Theme.AMBER));
        TextView place = text("place: " + Weather.place(this) + "  ›", mono, 12, Theme.GREEN);
        place.setPadding(0, dp(3), 0, dp(3));
        place.setFocusable(true);
        place.setClickable(true);
        place.setBackground(focusBg());
        place.setOnClickListener(new View.OnClickListener() { public void onClick(View x) { pickPlace(); } });
        v.addView(place);
        JSONObject o = Weather.saved(this);
        if (o == null) {
            v.addView(text("no forecast yet (needs internet)", mono, 13, Theme.DIM));
            sv.removeAllViews();
            sv.addView(v);
            return;
        }
        try {
            JSONObject cur = o.getJSONObject("current"), d = o.getJSONObject("daily");
            int code = cur.getInt("weather_code");
            LinearLayout now = new LinearLayout(this);
            now.setGravity(Gravity.CENTER_VERTICAL);
            now.setPadding(0, dp(6), 0, dp(2));
            TextView g = text(glyph(Weather.glyph(code, cur.optInt("is_day", 1) == 1)), icons, 40, Theme.AMBER);
            g.setPadding(0, 0, dp(10), 0);
            now.addView(g);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(text(Math.round(cur.getDouble("temperature_2m")) + "°", bold, 34, Theme.GREEN));
            col.addView(text(Weather.words(code) + "  ·  " + Math.round(d.getJSONArray("temperature_2m_max").getDouble(0)) + "° / "
                    + Math.round(d.getJSONArray("temperature_2m_min").getDouble(0)) + "°", mono, 12, Theme.GREEN));
            now.addView(col);
            v.addView(now);
            String rise = d.optJSONArray("sunrise") != null ? d.getJSONArray("sunrise").getString(0).substring(11) : null;
            String set = d.optJSONArray("sunset") != null ? d.getJSONArray("sunset").getString(0).substring(11) : null;
            if (rise != null) v.addView(text("sun " + rise + " – " + set, mono, 11, Theme.DIM));

            section(v, "next 24 hours");
            if (o.has("hourly")) {
                JSONObject hr = o.getJSONObject("hourly");
                int start = Weather.nowIndex(o);
                JSONArray t = hr.getJSONArray("time");
                for (int i = start; i < Math.min(t.length(), start + 24); i += (i - start < 12 ? 1 : 3)) {   // hourly, then every 3 h
                    int rain = hr.getJSONArray("precipitation_probability").optInt(i, 0);
                    row(v, Weather.glyph(hr.getJSONArray("weather_code").getInt(i), hr.getJSONArray("is_day").optInt(i, 1) == 1),
                            t.getString(i).substring(11, 16) + "   " + Math.round(hr.getJSONArray("temperature_2m").getDouble(i)) + "°",
                            rain >= 10 ? rain + "% rain" : "");
                }
            }

            section(v, "next 7 days");
            JSONArray dates = d.getJSONArray("time"), codes = d.getJSONArray("weather_code");
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US), out = new SimpleDateFormat("EEE dd", Locale.getDefault());
            JSONArray rainMax = d.optJSONArray("precipitation_probability_max");
            for (int i = 0; i < dates.length(); i++) {
                String name = i == 0 ? "today" : out.format(in.parse(dates.getString(i))).toLowerCase(Locale.getDefault());
                int rain = rainMax == null ? 0 : rainMax.optInt(i, 0);
                row(v, Weather.glyph(codes.getInt(i), true), String.format(Locale.US, "%-7s", name) + Weather.words(codes.getInt(i)),
                        Math.round(d.getJSONArray("temperature_2m_max").getDouble(i)) + "°/" + Math.round(d.getJSONArray("temperature_2m_min").getDouble(i)) + "°"
                                + (rain >= 20 ? "  " + rain + "%" : ""));
            }
            long min = (System.currentTimeMillis() - Weather.savedAt(this)) / 60000;
            TextView up = text("updated " + (min < 2 ? "just now" : min < 60 ? min + " min ago" : (min / 60) + " h ago") + " · open-meteo.com", mono, 10, Theme.DIM);
            up.setPadding(0, dp(10), 0, 0);
            v.addView(up);
        } catch (Exception e) {
            v.addView(text("couldn't read the forecast", mono, 13, Theme.DIM));
        }
        sv.removeAllViews();
        sv.addView(v);
    }

    /** Highlight when the D-pad is on it. */
    private android.graphics.drawable.StateListDrawable focusBg() {
        android.graphics.drawable.StateListDrawable d = new android.graphics.drawable.StateListDrawable();
        d.addState(new int[]{android.R.attr.state_focused}, new android.graphics.drawable.ColorDrawable(Theme.LIT));
        d.addState(new int[]{android.R.attr.state_pressed}, new android.graphics.drawable.ColorDrawable(Theme.LIT));
        d.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(0));
        return d;
    }

    /** Type a town (T9 works), OK searches, pick one; "automatic" goes back to the rough location. */
    private void pickPlace() {
        final LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(8), dp(6), dp(8), dp(8));
        v.setBackgroundColor(Theme.VOID);
        v.addView(text("weather place", bold, 13, Theme.AMBER));
        final android.widget.EditText q = new android.widget.EditText(this);
        q.setTypeface(mono);
        q.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        q.setTextColor(Theme.GREEN);
        q.setHintTextColor(Theme.DIM);
        q.setHint("town or city");
        q.setSingleLine(true);
        android.graphics.drawable.GradientDrawable box = new android.graphics.drawable.GradientDrawable();
        box.setColor(Theme.CELL);
        box.setStroke(dp(1), Theme.GREEN);
        q.setBackground(box);
        q.setPadding(dp(6), dp(5), dp(6), dp(5));
        v.addView(q);
        final TextView status = text("type a name, then OK  ·  back = cancel", mono, 11, Theme.DIM);
        v.addView(status);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        v.addView(list);
        addChoice(list, "automatic (rough location)", null, 0, 0);
        q.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View x, int code, android.view.KeyEvent e) {
                if (e.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                if (code != android.view.KeyEvent.KEYCODE_ENTER && code != android.view.KeyEvent.KEYCODE_DPAD_CENTER) return false;
                final String name = q.getText().toString().trim();
                if (name.isEmpty()) return true;
                status.setText("searching…");
                new Thread(new Runnable() { public void run() {
                    java.util.List<String[]> got = null;
                    try { got = Weather.search(name); } catch (Throwable ignored) { }
                    final java.util.List<String[]> r = got;
                    runOnUiThread(new Runnable() { public void run() {
                        list.removeAllViews();
                        addChoice(list, "automatic (rough location)", null, 0, 0);
                        if (r == null) { status.setText("couldn't search (internet?)"); return; }
                        status.setText(r.isEmpty() ? "nothing found" : r.size() + " found  ·  ↓ then OK");
                        for (String[] p : r) addChoice(list, p[0], p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2]));
                    } });
                } }).start();
                return true;
            }
        });
        ScrollView s2 = new ScrollView(this);
        s2.setBackgroundColor(Theme.VOID);
        s2.addView(v);
        setContentView(s2);
        picking = true;
        q.requestFocus();
    }

    private boolean picking;

    private void addChoice(LinearLayout list, String label, final String name, final double lat, final double lon) {
        TextView t = text(label, mono, 14, name == null ? Theme.AMBER : Theme.GREEN);
        t.setPadding(dp(2), dp(7), dp(2), dp(7));
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(focusBg());
        t.setOnClickListener(new View.OnClickListener() { public void onClick(View x) {
            Weather.setPlace(WeatherActivity.this, name, lat, lon);
            picking = false;
            setContentView(sv);
            draw();
            Weather.refresh(WeatherActivity.this, new Runnable() { public void run() { draw(); } });
        } });
        list.addView(t);
    }

    @Override public void onBackPressed() {
        if (picking) { picking = false; setContentView(sv); draw(); return; }
        super.onBackPressed();
    }

    private void section(LinearLayout v, String title) {
        TextView t = text(title, bold, 12, Theme.AMBER);
        t.setPadding(0, dp(10), 0, dp(2));
        v.addView(t);
        View rule = new View(this);
        rule.setBackgroundColor(Theme.RULE);
        v.addView(rule, new LinearLayout.LayoutParams(-1, 1));
    }

    private void row(LinearLayout v, int glyph, String left, String right) {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(3), 0, dp(3));
        TextView g = text(glyph(glyph), icons, 15, Theme.GREEN);
        g.setPadding(0, 0, dp(8), 0);
        line.addView(g);
        line.addView(text(left, mono, 13, Theme.GREEN), new LinearLayout.LayoutParams(0, -2, 1));
        line.addView(text(right, mono, 12, Theme.DIM));
        v.addView(line);
    }

    private static String glyph(int cp) { return new String(Character.toChars(cp)); }

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
}
