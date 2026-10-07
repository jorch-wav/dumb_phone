package dumb_phone.home;

import static dumb_phone.home.Theme.AMBER;
import static dumb_phone.home.Theme.CELL;
import static dumb_phone.home.Theme.DIM;
import static dumb_phone.home.Theme.GREEN;
import static dumb_phone.home.Theme.LIT;
import static dumb_phone.home.Theme.RULE;
import static dumb_phone.home.Theme.SEL;
import static dumb_phone.home.Theme.VOID;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Home screen: a 3x3 grid of tiles in the dumb_phone look (JetBrains Mono,
 * Nerd Font glyphs). D-pad + OK, or 1-4, or touch. # (or the bottom line) = every app.
 */
public class HomeActivity extends Activity {

    static String g(int cp) { return new String(Character.toChars(cp)); }

    /**
     * label, Nerd Font glyph, apps to try in order ("|"-separated packages), then a fallback role that
     * finds whatever the phone has (its browser, SMS app, maps, camera). Edit this list to change the tiles.
     */
    static final String[][] TILES = {
            {"whatsapp", g(0xF05A3), "com.whatsapp|org.thoughtcrime.securesms|org.telegram.messenger", ""},
            {"contacts", g(0xF06CB), "dumb_phone.phone", "contacts"},
            {"messages", g(0xF0369), "org.fossify.messages|com.moez.QKSMS", "sms"},
            {"radio", g(0xF0439), "dumb_phone.radio", ""},
            {"podcasts", g(0xF0994), "dumb_phone.podcasts", ""},
            {"web", g(0xF059F), "mark.via.gp|mark.via", "browser"},
            {"maps", g(0xF034D), "app.organicmaps|net.osmand|net.osmand.plus|com.google.android.apps.maps", "maps"},
            {"notes", g(0xF082E), "com.omgodse.notally|com.philkes.notallyx|org.fossify.notes", ""},
            {"camera", g(0xF0100), "", "camera"},
    };
    /** Where to get a tile's app when the phone has none (shown as a hint). */
    static final String[][] SUGGEST = {
            {"whatsapp", "WhatsApp or Signal"}, {"web", "Via browser (F-Droid)"}, {"messages", "Fossify Messages (F-Droid)"},
            {"maps", "Organic Maps (F-Droid)"}, {"notes", "Notally (F-Droid)"},
    };
    static final int COLS = 3;
    /** Left out of the all-apps list: these only draw the call screen (dumb_phone Contacts does the dialling). */
    static final java.util.Set<String> HIDDEN = new java.util.HashSet<>(java.util.Arrays.asList(
            "org.fossify.phone", "com.simplemobiletools.dialer"));

    /** The phone's own menu (the stock "KaiOS" launcher) has these; they go in the all-apps list. */
    static final String[][] STOCK = {
            {"alarm", "gwin.com.firefox.clock.ClockActivity"},
            {"calculator", "gwin.com.firefox.calculator.CalculatorActivity"},
            {"calendar", "gwin.com.firefox.calendar.CalendarActivity"},
            {"call logs", "gwin.com.firefox.calllog.CallLogActivity"},
            {"camera", "gwin.com.firefox.camera.CameraActivity"},
            {"fm radio", "gwin.com.firefox.fm.FmActivity"},
            {"gallery", "gwin.com.firefox.gallery.GalleryActivity"},
            {"music player", "gwin.com.firefox.music.MusicActivity"},
            {"phone settings", "gwin.com.firefox.setting.SettingsActivity"},
            {"phonebook", "gwin.com.firefox.contact.ContactActivity"},
            {"photo contacts", "gwin.com.firefox.contact.PhotoContactActivity"},
            {"quick settings", "gwin.com.firefox.setting.SpSettingsMainActivity"},
            {"video", "gwin.com.firefox.video.VideoActivity"},
            {"voice memos", "gwin.com.firefox.voice_memo.VoiceMemoActivity"},
    };

    private Typeface mono, bold, icons;
    private final List<TextView> tiles = new ArrayList<>();
    private TextView clock, status, carrier, date, owner, weatherSmall;
    private FrameLayout root;
    private View home;
    private ListView all;

    private String sim = "";
    private int sigLevel = -1;                        // 0-4 bars, -1 = no signal icon (no sim / no service)

    private final android.telephony.PhoneStateListener phone = new android.telephony.PhoneStateListener() {
        private int level = 0;
        @Override public void onSignalStrengthsChanged(android.telephony.SignalStrength ss) { level = ss.getLevel(); show(); }
        @Override public void onServiceStateChanged(android.telephony.ServiceState st) { show(); }
        private void show() { sim = simText(level); updateStatus(); }
    };

    /** "carrier ▂▄▆·" with a SIM in, "no sim" without (shown under wifi). */
    private String simText(int level) {
        android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        int st = tm.getSimState();
        sigLevel = -1;
        if (st == android.telephony.TelephonyManager.SIM_STATE_ABSENT || st == android.telephony.TelephonyManager.SIM_STATE_UNKNOWN) return "no sim";
        if (st != android.telephony.TelephonyManager.SIM_STATE_READY) return "sim locked";
        String op = tm.getNetworkOperatorName();
        if (op == null || op.isEmpty()) return "no service";
        sigLevel = Math.max(0, Math.min(4, level));
        return op.toLowerCase();
    }

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateStatus(); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.load(this);                               // colours of the chosen theme
        drawnTheme = Theme.name;
        drawnBg = Background.stamp(this);
        mono = Typeface.createFromAsset(getAssets(), "JetBrainsMono-Regular.ttf");
        bold = Typeface.createFromAsset(getAssets(), "JetBrainsMono-Bold.ttf");
        icons = Typeface.createFromAsset(getAssets(), "phosphor-icons.ttf");

        root = new FrameLayout(this);
        android.graphics.drawable.Drawable photo = Background.drawable(this);
        if (photo != null) root.setBackground(photo); else root.setBackgroundColor(VOID);
        home = buildHome();
        Background.shadows(this, home);
        root.addView(home);
        setContentView(root);
        registerReceiver(screenOff, new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF));
        if (sHidden && Background.on(this) && grid != null) { grid.setVisibility(View.INVISIBLE); photoMode = true; }
        if (wantsAll(getIntent())) showAll();
    }

    private View buildHome() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(8), dp(6), dp(8), dp(4));

        // a thin dim status strip (carrier + signal left; wifi, ringer, battery right), a rule,
        // then the big green clock with the date in amber on its baseline
        LinearLayout strip = new LinearLayout(this);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(2), 0, dp(2), dp(3));
        carrier = text(mono, 11, DIM);
        carrier.setSingleLine(true);
        carrier.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status = text(mono, 11, DIM);
        status.setSingleLine(true);
        strip.addView(carrier, new LinearLayout.LayoutParams(0, -2, 1));
        strip.addView(status);
        v.addView(strip);
        strip.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View x) { showPanel(); } });
        strip.setFocusable(false);                      // tap only: the D-pad goes up from the tiles into the panel
        View rule = new View(this);
        rule.setBackgroundColor(RULE);
        v.addView(rule, new LinearLayout.LayoutParams(-1, 1));

        LinearLayout bar = new LinearLayout(this);
        bar.setBaselineAligned(true);
        bar.setPadding(dp(2), dp(4), dp(2), dp(1));
        clock = text(bold, 34, GREEN);
        clock.setIncludeFontPadding(false);
        date = text(mono, 22, AMBER);
        date.setIncludeFontPadding(false);
        date.setSingleLine(true);
        date.setGravity(Gravity.RIGHT);
        bar.addView(clock, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(date);
        v.addView(bar);
        // the phone's own name (Settings > About phone > Device name) + version under the clock
        owner = text(mono, 11, DIM);
        owner.setMaxLines(2);                           // a long name (e.g. FRANK'S DUMB PHONE 1.7) wraps instead of running into the weather
        owner.setPadding(0, 0, dp(6), 0);
        LinearLayout ownerLine = new LinearLayout(this);
        ownerLine.setGravity(Gravity.CENTER_VERTICAL);
        ownerLine.setPadding(dp(3), 0, dp(2), dp(6));
        ownerLine.addView(owner, new LinearLayout.LayoutParams(0, -2, 1));
        weatherSmall = text(mono, 11, DIM);                // a small forecast on the right: icon + temperature
        weatherSmall.setSingleLine(true);
        ownerLine.addView(weatherSmall);
        v.addView(ownerLine);

        LinearLayout grid = new LinearLayout(this);
        this.grid = grid;
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int r = 0; r < TILES.length / COLS; r++) {
            LinearLayout row = new LinearLayout(this);
            for (int c = 0; c < COLS; c++) {
                final int i = r * COLS + c;
                TextView t = tile(TILES[i][0], TILES[i][1]);
                t.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View x) { open(i); }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
                lp.setMargins(dp(2), dp(2), dp(2), dp(2));
                row.addView(t, lp);
                tiles.add(t);
            }
            grid.addView(row, new LinearLayout.LayoutParams(-1, tileSize() + dp(4)));
        }
        v.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));      // spare space: half above the tiles,
        v.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        v.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));      // half below

        // D-pad moves between tiles; up from the top row and down from the bottom row stop there.
        int n = tiles.size();
        for (int i = 0; i < n; i++) {
            TextView t = tiles.get(i);
            t.setId(100 + i);
            t.setNextFocusLeftId(100 + (i % COLS == 0 ? i : i - 1));
            t.setNextFocusRightId(100 + (i % COLS == COLS - 1 ? i : i + 1));
            t.setNextFocusUpId(100 + (i < COLS ? i : i - COLS));
            if (i < COLS) t.setOnKeyListener(new View.OnKeyListener() {       // up from the top row = the panel
                @Override public boolean onKey(View v, int code, KeyEvent ev) {
                    if (code != KeyEvent.KEYCODE_DPAD_UP) return false;
                    if (ev.getAction() == KeyEvent.ACTION_DOWN) showPanel();
                    return true;
                }
            });
            t.setNextFocusDownId(100 + (i + COLS >= n ? i : i + COLS));
        }
        return v;
    }

    private TextView tile(String label, String glyph) {
        final TextView t = new TextView(this);
        SpannableStringBuilder s = new SpannableStringBuilder(glyph + "\n" + label);
        s.setSpan(new IconSpan(icons, sp(28)), 0, glyph.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        t.setText(s);
        t.setTypeface(mono);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(dp(4), 1f);
        t.setFocusable(true);
        t.setFocusableInTouchMode(false);
        t.setClickable(true);
        if (android.os.Build.VERSION.SDK_INT >= 26) t.setDefaultFocusHighlightEnabled(false);   // we draw our own
        style(t, false);
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) { style(t, has); }
        });
        return t;
    }

    /** cell, or the drawer's selected look: lit cell, green border, amber text. */
    private void style(TextView t, boolean selected) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Background.panel(this, selected ? LIT : CELL, selected));    // see-through over a photo
        d.setStroke(dp(1), selected ? GREEN : Background.panel(this, CELL, false));
        t.setBackground(d);
        t.setTextColor(selected ? AMBER : GREEN);
    }

    @Override protected void onResume() {
        super.onResume();
        shown = this;
        if (!Theme.chosen(this).equals(drawnTheme) || Background.stamp(this) != drawnBg) {
            if (!Theme.chosen(this).equals(drawnTheme)) Background.tellFossify(this);
            recreate(); return; }   // a new theme was picked in Themes
        hideKeyboard();
        Guard.check(this);
        Clock.schedule(this);                            // alarms are always armed (cheap: just the next one)
        Weather.refresh(this, new Runnable() { public void run() { updateStatus(); } });
        if (wantPanel || getIntent().getBooleanExtra("panel", false)) {
            wantPanel = false;
            getIntent().removeExtra("panel");
            showPanel();
        }
        Notifications.onChange = new Runnable() { @Override public void run() {
            runOnUiThread(new Runnable() { @Override public void run() { updateStatus(); if (panel != null) panel.refresh(); } });
        } };                              // keep the key helpers + T9 switched on
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_BATTERY_CHANGED);
        f.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        f.addAction(android.net.wifi.WifiManager.RSSI_CHANGED_ACTION);
        f.addAction(android.media.AudioManager.RINGER_MODE_CHANGED_ACTION);
        f.addAction("android.media.VOLUME_CHANGED_ACTION");
        registerReceiver(tick, f);
        sim = simText(0);
        ((android.telephony.TelephonyManager) getSystemService(TELEPHONY_SERVICE)).listen(phone,
                android.telephony.PhoneStateListener.LISTEN_SIGNAL_STRENGTHS | android.telephony.PhoneStateListener.LISTEN_SERVICE_STATE);
        updateStatus();
        if (all == null && (getCurrentFocus() == null || !tiles.contains(getCurrentFocus()))) tiles.get(0).requestFocus();
    }

    // ---- photo mode: with a background photo, the screen wakes showing just the photo (and the clock) ----
    private View grid;
    private boolean photoMode;
    // kept across screens: on this phone the stock system app wipes and recreates the home screen on every
    // home press ("clear-task"), so the photo-mode state must survive a new HomeActivity
    private static boolean sHidden, sByScreen;          // tiles hidden; hidden because the screen went off
    private static long sLastRed;
    private int swallowUp;                              // key whose release belongs to a key we already used
    static HomeActivity shown;                          // this screen while it's in front (for the red key)

    private final android.content.BroadcastReceiver screenOff = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, Intent i) { hideTiles(); if (photoMode) sByScreen = true; }
    };

    void hideTiles() {
        if (!Background.on(this) || grid == null || panel != null || all != null) return;
        grid.setVisibility(View.INVISIBLE);
        photoMode = true;
        sHidden = true;
        sByScreen = false;
    }

    void showTiles() {
        if (grid == null) return;
        grid.setVisibility(View.VISIBLE);
        photoMode = false;
        sHidden = sByScreen = false;
        tiles.get(0).requestFocus();
    }

    /** The red key on the home screen: tiles off (see the photo) / on. False = not ours to handle. */
    /** Would the red key hide / show the tiles now (a photo, no panel or app list open)? */
    boolean canToggleTiles() { return Background.on(this) && panel == null && all == null; }

    boolean toggleTiles() {
        if (!Background.on(this) || panel != null || all != null) return false;
        if (photoMode) showTiles(); else hideTiles();
        return true;
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(screenOff); } catch (IllegalArgumentException ignored) { }
    }

    @Override protected void onPause() {
        super.onPause();
        if (shown == this) shown = null;
        // onResume may have returned early (theme changed -> recreate) before registering: never crash here
        try { unregisterReceiver(tick); } catch (IllegalArgumentException ignored) { }
        ((android.telephony.TelephonyManager) getSystemService(TELEPHONY_SERVICE)).listen(phone, android.telephony.PhoneStateListener.LISTEN_NONE);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i.getBooleanExtra("panel", false)) { wantPanel = true; }
        setIntent(i);
        if (wantsAll(i)) { showAll(); return; }
        boolean closed = hideAll();   // the home button always comes back to the tiles
        // red key while this screen was already in front (Android turns it into "home", sent twice):
        // with a photo, it hides / shows the tiles. Not when coming back from an app or opening the flip.
        long t = android.os.SystemClock.uptimeMillis();
        if (!closed && visible && i.getBooleanExtra("panel", false) == false && Background.on(this)) {
            if (t - sLastRed > 700) { sLastRed = t; if (photoMode) showTiles(); else hideTiles(); }
            return;
        }
        if (photoMode && !visible && !sByScreen) showTiles();   // back from an app: the tiles, not the bare photo
        if (!photoMode) tiles.get(0).requestFocus();
    }

    private boolean visible;                           // on screen (between onStart and onStop)
    boolean focused;                                   // has the keys (false while the grey shade is down)

    /** The grey shade took the focus from home (Android doesn't always say so another way): swap it for ours. */
    @Override public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        focused = f;
        if (!f && shown == this && VolumeService.instance != null) VolumeService.instance.shadeMaybe();
    }

    @Override protected void onStart() { super.onStart(); visible = false; getWindow().getDecorView().post(new Runnable() {
        @Override public void run() { visible = true; } }); }

    @Override protected void onStop() { super.onStop(); visible = false; }

    /** Opened through the "All apps" entry (the menu button). */
    private static boolean wantsAll(Intent i) {
        return i != null && i.getComponent() != null && i.getComponent().getClassName().endsWith(".AllApps");
    }

    /** Ringer as a speaker icon (+ ring volume), a vibrate icon, or a crossed-out speaker. */
    private String sound() {
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return "";
        int mode = am.getRingerMode();
        if (mode == android.media.AudioManager.RINGER_MODE_VIBRATE) return g(0xF0566);
        int v = am.getStreamVolume(android.media.AudioManager.STREAM_RING);
        if (mode == android.media.AudioManager.RINGER_MODE_SILENT || v == 0) return g(0xF0581);
        return g(0xF057E) + v;
    }

    private void updateStatus() {
        Date now = new Date();
        clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat("EEE dd MMM", Locale.getDefault()).format(now).toLowerCase());
        date.post(fitDate);
        SpannableStringBuilder cs = new SpannableStringBuilder();
        if (sigLevel >= 0) icon(cs, new int[]{0xF08BF, 0xF08BC, 0xF08BD, 0xF08BE, 0xF08BE}[sigLevel]);   // outline, 1-3 bars
        cs.append(sim);
        carrier.setText(cs);
        String dn = android.provider.Settings.Global.getString(getContentResolver(), "device_name");
        boolean named = dn != null && !dn.trim().isEmpty() && !dn.replace('_', ' ').equalsIgnoreCase(android.os.Build.MODEL.replace('_', ' '));
        String ver = "";
        try { ver = " " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        SpannableStringBuilder ow = new SpannableStringBuilder(((named ? dn.trim() : "dumb_phone") + ver).toUpperCase(Locale.getDefault()));   // e.g. FRANK'S DUMB PHONE 1.0
        String sick = Health.problems(this);                // an app that can't open / crashed: say so, in amber
        if (!sick.isEmpty()) {
            int at = ow.length();
            ow.append("\n").append(sick).append(" · plug into pocket");
            ow.setSpan(new android.text.style.ForegroundColorSpan(AMBER), at, ow.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        owner.setMaxLines(sick.isEmpty() ? 2 : 4);
        owner.setText(ow);
        smallWeather();

        // right side: notifications, wifi, ringer, battery, as icons with a little text
        SpannableStringBuilder st = new SpannableStringBuilder();
        int unread = Notifications.count();
        if (unread > 0) {
            int from = st.length();
            icon(st, 0xF009E);
            st.append(String.valueOf(unread)).append("  ");
            st.setSpan(new android.text.style.ForegroundColorSpan(AMBER), from, st.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        NetworkInfo n = ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).getActiveNetworkInfo();
        if (n == null || !n.isConnected()) icon(st, 0xF092E);
        else if (n.getType() == ConnectivityManager.TYPE_WIFI) {
            int lv = 4;
            try {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                lv = android.net.wifi.WifiManager.calculateSignalLevel(wm.getConnectionInfo().getRssi(), 4);
            } catch (Exception ignored) { }
            icon(st, new int[]{0xF091F, 0xF0922, 0xF0925, 0xF0928}[Math.max(0, Math.min(3, lv))]);
        } else st.append("data");
        st.append("  ");
        String snd = sound();
        if (!snd.isEmpty()) {
            icon(st, snd.codePointAt(0));
            st.append(snd.substring(Character.charCount(snd.codePointAt(0))));
            st.append("  ");
        }
        Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) {
            int lvl = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int pct = lvl * 100 / Math.max(1, scale);
            boolean chg = bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            int g = chg ? 0xF0084 : pct >= 95 ? 0xF0079 : pct < 10 ? 0xF0083 : 0xF007A + Math.min(8, pct / 10 - 1);
            int from = st.length();
            icon(st, g);
            st.append(pct + "%");
            if (pct <= 15 && !chg)                            // low battery: the only thing that turns amber
                st.setSpan(new android.text.style.ForegroundColorSpan(AMBER), from, st.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        status.setText(st);
    }

    /** The date at full size when it fits; a long one ("wed 30 sep") shrinks to fit the row. */
    private final Runnable fitDate = new Runnable() {
        @Override public void run() {
            View row = (View) date.getParent();
            // the clock row is "clock (stretches) + date", so measure the clock's text, not its view
            int room = row.getWidth() - row.getPaddingLeft() - row.getPaddingRight()
                    - (int) Math.ceil(clock.getPaint().measureText(clock.getText().toString())) - dp(8);
            if (room <= 0) return;
            float size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 22, getResources().getDisplayMetrics());
            android.graphics.Paint pt = new android.graphics.Paint(date.getPaint());
            pt.setTextSize(size);
            float w = pt.measureText(date.getText().toString());
            if (w > room) size = size * room / w;
            date.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        }
    };

    /** "☁ 14°" from the saved forecast, next to the phone's name. */
    private void smallWeather() {
        org.json.JSONObject o = Weather.saved(this);
        if (o == null) { weatherSmall.setText(""); return; }
        try {
            org.json.JSONObject cur = o.getJSONObject("current");
            SpannableStringBuilder w = new SpannableStringBuilder();
            icon(w, Weather.glyph(cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1));
            w.append(Math.round(cur.getDouble("temperature_2m")) + "° " + Weather.words(cur.getInt("weather_code")));
            String loc = Weather.shortPlace(this);
            if (!loc.isEmpty()) {
                int at = w.length();
                w.append(" ").append(loc);
                w.setSpan(new android.text.style.ForegroundColorSpan(Theme.DIM), at, w.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            weatherSmall.setText(w);
        } catch (Exception e) { weatherSmall.setText(""); }
    }

    /** Append one icon glyph (drawn with the icon font) to the status text. */
    private void icon(SpannableStringBuilder st, int cp) {
        int at = st.length();
        st.append(g(cp));
        st.setSpan(new IconSpan(icons, sp(12)), at, st.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        st.append(" ");
    }

    private void open(int i) {
        Intent it = tileIntent(i);
        if (it != null) {
            try { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)); return; }
            catch (Exception ignored) { }
        }
        String tip = "no app for this yet";
        for (String[] sg : SUGGEST) if (sg[0].equals(TILES[i][0])) tip = "install " + sg[1];
        android.widget.Toast.makeText(this, tip, android.widget.Toast.LENGTH_LONG).show();
    }

    /** The first listed app that is installed, else the phone's own app for the role. */
    private Intent tileIntent(int i) {
        PackageManager pm = getPackageManager();
        for (String pkg : TILES[i][2].split("\\|")) {
            if (pkg.isEmpty()) continue;
            Intent l = pm.getLaunchIntentForPackage(pkg);
            if (l == null) l = Health.backDoor(pm, pkg);   // its launcher entry is gone: still open it
            if (l != null) return l;
        }
        Intent r = null;
        switch (TILES[i][3]) {
            case "browser": r = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER); break;
            case "maps": r = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MAPS); break;
            case "contacts": r = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS); break;
            case "camera": r = new Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA); break;
            case "sms":
                String sms = android.provider.Telephony.Sms.getDefaultSmsPackage(this);
                if (sms != null) r = pm.getLaunchIntentForPackage(sms);
                if (r == null && sms != null) r = Health.backDoor(pm, sms);
                if (r == null) r = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING);
                break;
        }
        if (r != null && r.resolveActivity(pm) == null) r = null;
        return r;
    }

    private void dialFrom(String first) {
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName("dumb_phone.phone", "dumb_phone.phone.PhoneActivity"))
                    .putExtra("dial", first).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) { }
    }

    /** The green call key: recent calls. */
    private void openPhone() {
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName("dumb_phone.phone", "dumb_phone.phone.PhoneActivity"))
                    .putExtra("tab", "recents").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) { }
    }

    // ---- every app, as a plain list ----

    /**
     * Built-in apps that can't be uninstalled but are covered by something better: left out of the list.
     * Each only when its replacement is actually there, so other phones keep what they need.
     */
    private boolean redundant(String pkg, String cls) {
        if (cls.equals("gwin.com.firefox.LauncherActivity")) return true;           // the old launcher: would come back on top
        if (cls.equals("gwin.com.firefox.clock.ClockActivity")) return true;       // our Clock does alarms + timer
        if (pkg.equals("com.android.browser")) return has("mark.via.gp") || has("mark.via") || has("org.mozilla.fennec_fdroid");
        if (cls.endsWith(".calllog.CallLogActivity") || cls.endsWith(".contact.ContactActivity")
                || cls.endsWith(".contact.PhotoContactActivity")) return has("dumb_phone.phone");   // our Contacts
        if (cls.endsWith(".music.MusicActivity")) return has("org.oxycblt.auxio");
        if (cls.endsWith(".fm.FmActivity")) return !enabled("gwin.com.firefox.fm");     // FM switched off
        if (cls.endsWith(".voice_memo.VoiceMemoActivity")) return has("com.android.soundrecorder");
        return false;
    }

    /** What an app is, for names that don't say it (shown dim after the name in the list). */
    static final java.util.Map<String, String> WHAT = new java.util.HashMap<>();
    static {
        String[][] w = {
                {"com.omgodse.notally", "notes"}, {"com.philkes.notallyx", "notes"}, {"io.ente.auth", "2fa codes"},
                {"mark.via.gp", "browser"}, {"mark.via", "browser"}, {"org.oxycblt.auxio", "music"},
                {"app.organicmaps", "maps"}, {"at.bitfire.davdroid", "contacts sync"}, {"org.fdroid.fdroid", "app store"},
                {"org.fossify.messages", "messages"}, {"org.fossify.phone", "calls"}, {"io.github.sspanak.tt9", "keyboard settings"},
                {"com.mediatek.filemanager", "files"}, {"com.android.stk", "sim menu"}, {"com.gwin.sos", "emergency"},
                {"com.android.soundrecorder", "voice recorder"}, {"org.thoughtcrime.securesms", "messages"},
                {"de.danoeh.antennapod", "podcasts"}, {"com.aurora.store", "app store"}, {"org.mozilla.fennec_fdroid", "browser"},
                {"net.osmand", "maps"}, {"net.osmand.plus", "maps"}, {"com.google.android.apps.maps", "maps"},
        };
        for (String[] x : w) WHAT.put(x[0], x[1]);
    }

    private boolean has(String pkg) {
        try { getPackageManager().getPackageInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }

    private boolean enabled(String pkg) {
        try { return getPackageManager().getApplicationInfo(pkg, 0).enabled; } catch (Exception e) { return false; }
    }

    private final List<String[]> allEntries = new ArrayList<>();   // label, package, class (every app)
    private final List<String[]> shownEntries = new ArrayList<>();
    private String find = "";                                       // T9 digits typed in the app list
    private TextView allHead;
    private BaseAdapter allAdapter;

    private SharedPreferences pins() { return getSharedPreferences("pins", MODE_PRIVATE); }
    private static String key(String[] e) { return e[1] + "/" + e[2]; }

    /** The keypad digit for a letter (T9), or the character itself. */
    private static char t9(char c) {
        String keys = "22233344455566677778889999";
        c = Character.toLowerCase(c);
        return c >= 'a' && c <= 'z' ? keys.charAt(c - 'a') : c;
    }

    private static boolean matches(String label, String digits) {
        if (digits.isEmpty()) return true;
        StringBuilder all = new StringBuilder();
        for (String w : label.split("[^a-zA-Z0-9]+")) {
            StringBuilder d = new StringBuilder();
            for (char ch : w.toCharArray()) d.append(t9(ch));
            if (d.toString().startsWith(digits)) return true;        // a word starts with it
            all.append(d);
        }
        return all.toString().contains(digits);
    }

    private static boolean startsWord(String label, String digits) {
        if (digits.isEmpty()) return true;
        for (String w : label.split("[^a-zA-Z0-9]+")) {
            StringBuilder d = new StringBuilder();
            for (char ch : w.toCharArray()) d.append(t9(ch));
            if (d.toString().startsWith(digits)) return true;
        }
        return false;
    }

    /** The letters of label that the typed digits matched (e.g. 72 in "radio" -> "ra"), or null. */
    private static String matchedLetters(String label, String digits) {
        StringBuilder all = new StringBuilder(), letters = new StringBuilder();
        for (String w : label.split("[^a-zA-Z0-9]+")) {
            StringBuilder d = new StringBuilder();
            for (char ch : w.toCharArray()) d.append(t9(ch));
            if (d.toString().startsWith(digits)) return w.substring(0, digits.length()).toLowerCase(Locale.getDefault());
            all.append(d);
            letters.append(w);
        }
        int at = all.indexOf(digits);
        return at < 0 ? null : letters.substring(at, at + digits.length()).toLowerCase(Locale.getDefault());
    }

    /** Pinned apps first (★), then the rest A-Z, filtered by the typed digits. */
    private void refilter() {
        shownEntries.clear();
        java.util.Set<String> pinned = pins().getAll().keySet();
        for (int pass = 0; pass < 4; pass++)                       // pinned first; within each, word starts before the rest
            for (String[] e : allEntries) {
                if (pinned.contains(key(e)) != (pass < 2) || !matches(e[0], find)) continue;
                boolean start = startsWord(e[0], find);
                if (start == (pass % 2 == 0)) shownEntries.add(e);
            }
        String typed = shownEntries.isEmpty() ? null : matchedLetters(shownEntries.get(0)[0], find);
        allHead.setText(find.isEmpty() ? "all apps   [0-9] find  [*] pin"
                : "find " + (typed != null ? typed : find + " (no match)") + "_   [back] delete");
        allAdapter.notifyDataSetChanged();
        if (all != null && !shownEntries.isEmpty()) all.setSelection(1);
    }

    private void togglePin(String[] e) {
        SharedPreferences.Editor ed = pins().edit();
        if (pins().contains(key(e))) ed.remove(key(e)); else ed.putBoolean(key(e), true);
        ed.apply();
        refilter();
        int at = shownEntries.indexOf(e);
        if (at >= 0) all.setSelection(at + 1);
    }

    private void showAll() {
        if (all != null) return;
        PackageManager pm = getPackageManager();
        final List<ResolveInfo> apps = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        for (int i = apps.size() - 1; i >= 0; i--) {
            String pkg = apps.get(i).activityInfo.packageName;
            String cls = apps.get(i).activityInfo.name;
            boolean self = pkg.equals(getPackageName()) && !cls.endsWith(".ThemeActivity") && !cls.endsWith(".ScreenTimeActivity") && !cls.endsWith(".WeatherActivity") && !cls.endsWith(".BackgroundActivity") && !cls.endsWith(".ClockActivity") && !cls.endsWith(".TimerActivity") && !cls.endsWith(".SoundsActivity");
            if (self || HIDDEN.contains(pkg) || redundant(pkg, cls)) apps.remove(i);   // our home entry, duplicates
        }
        allEntries.clear();
        for (ResolveInfo r : apps) {
            // what it does first, its name second: "music (auxio)", "maps (organic maps)"
            String name = r.loadLabel(pm).toString().toLowerCase(), what = WHAT.get(r.activityInfo.packageName);
            String label = what == null || name.equals(what) || name.startsWith(what + " ") ? name : what + " (" + name + ")";
            allEntries.add(new String[]{label, r.activityInfo.packageName, r.activityInfo.name});
        }
        boolean kaios = false;                        // the stock menu of KaiOS-style Android flip phones
        try { pm.getPackageInfo("gwin.com.firefox", 0); kaios = true; } catch (Exception ignored) { }
        if (kaios) for (String[] st : STOCK) {
            boolean have = false;
            for (String[] e : allEntries) if (e[0].equals(st[0]) || e[2].equals(st[1])) have = true;
            if (!have && !redundant("gwin.com.firefox", st[1])) allEntries.add(new String[]{st[0], "gwin.com.firefox", st[1]});
        }
        Collections.sort(allEntries, new Comparator<String[]>() {
            @Override public int compare(String[] a, String[] b) { return a[0].compareTo(b[0]); }
        });
        find = "";

        all = new ListView(this);
        all.setBackgroundColor(VOID);
        all.setDivider(null);
        all.setSelector(new ColorDrawable(LIT));
        all.setPadding(dp(8), dp(6), dp(8), dp(6));
        allHead = text(bold, 12, AMBER);
        allHead.setPadding(dp(2), 0, 0, dp(6));
        all.addHeaderView(allHead, null, false);
        allAdapter = new BaseAdapter() {
            @Override public int getCount() { return shownEntries.size(); }
            @Override public Object getItem(int i) { return shownEntries.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup p) {
                TextView t = v != null ? (TextView) v : text(mono, 15, GREEN);
                t.setPadding(dp(4), dp(7), dp(4), dp(7));
                String[] e = shownEntries.get(i);
                boolean pin = pins().contains(key(e));
                SpannableStringBuilder sb = new SpannableStringBuilder((pin ? "★ " : "") + e[0]);
                int br = sb.toString().indexOf(" (");              // the app's own name, in brackets: dimmer
                if (br > 0) {
                    sb.setSpan(new android.text.style.ForegroundColorSpan(DIM), br, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new android.text.style.RelativeSizeSpan(0.8f), br, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                t.setText(sb);
                t.setTextColor(pin ? AMBER : GREEN);
                return t;
            }
        };
        all.setAdapter(allAdapter);
        all.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                String[] e = (String[]) p.getItemAtPosition(pos);
                try {
                    startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                            .setComponent(new ComponentName(e[1], e[2]))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
                } catch (Exception ex) {
                    android.widget.Toast.makeText(HomeActivity.this, "can't open that", android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        });
        all.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                togglePin((String[]) p.getItemAtPosition(pos));
                return true;
            }
        });
        all.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int code, KeyEvent ev) {
                if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
                    find += (char) ('0' + code - KeyEvent.KEYCODE_0);
                    refilter();
                    return true;
                }
                if (code == KeyEvent.KEYCODE_STAR) {
                    Object o = all.getSelectedItem();
                    if (o instanceof String[]) togglePin((String[]) o);
                    return true;
                }
                if ((code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DEL) && !find.isEmpty()) {
                    find = find.substring(0, find.length() - 1);
                    refilter();
                    return true;
                }
                return false;
            }
        });
        root.addView(all);
        home.setVisibility(View.GONE);
        refilter();
        all.requestFocus();
        all.setSelection(1);                         // the first app
    }

    private String drawnTheme;
    private long drawnBg;
    static final ComponentName THEME_ROW = new ComponentName("dumb_phone.home", "#theme");
    private ListView themes;

    /** Every theme, each row drawn in its own colours; OK picks it and every dumb_phone app follows. */
    private void showThemes() {
        themes = new ListView(this);
        themes.setBackgroundColor(VOID);
        themes.setDivider(null);
        themes.setPadding(dp(8), dp(6), dp(8), dp(6));
        TextView head = text(bold, 13, AMBER);
        head.setText("theme");
        head.setPadding(dp(2), 0, 0, dp(6));
        themes.addHeaderView(head, null, false);
        themes.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return Theme.NAMES.length; }
            @Override public Object getItem(int i) { return Theme.NAMES[i]; }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup p) {
                TextView t = v != null ? (TextView) v : text(mono, 15, GREEN);
                int[] c = Theme.P[i];
                SpannableStringBuilder sb = new SpannableStringBuilder(Theme.NAMES[i] + (Theme.NAMES[i].equals(Theme.name) ? "  ✓" : "") + "   ");
                int at = sb.length();
                sb.append("■■");                          // accent + dim swatches
                sb.setSpan(new android.text.style.ForegroundColorSpan(c[6]), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new android.text.style.ForegroundColorSpan(c[4]), at + 1, at + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                t.setText(sb);
                t.setTextColor(c[3]);
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(c[1]);
                bg.setStroke(dp(1), c[0]);
                t.setBackground(bg);
                t.setPadding(dp(8), dp(9), dp(8), dp(9));
                return t;
            }
        });
        themes.setSelector(new ColorDrawable(0x00000000));
        themes.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {   // highlight = outline in the accent
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                for (int k = 0; k < p.getChildCount(); k++) {
                    View ch = p.getChildAt(k);
                    if (ch.getBackground() instanceof android.graphics.drawable.GradientDrawable) {
                        int idx = p.getFirstVisiblePosition() + k - 1;
                        if (idx < 0 || idx >= Theme.NAMES.length) continue;
                        ((android.graphics.drawable.GradientDrawable) ch.getBackground()).setStroke(dp(k == pos - p.getFirstVisiblePosition() ? 2 : 1),
                                k == pos - p.getFirstVisiblePosition() ? Theme.P[idx][6] : Theme.P[idx][0]);
                    }
                }
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        themes.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                String n = (String) p.getItemAtPosition(pos);
                getSharedPreferences("theme", MODE_PRIVATE).edit().putString("name", n).commit();
                recreate();                              // redraw home in the new colours; the other apps follow when opened
            }
        });
        root.addView(themes);
        themes.requestFocus();
        themes.setSelection(Theme.index(Theme.name) + 1);
    }

    @Override public boolean onKeyLongPress(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_POUND && all == null) {      // hidden: hold # on home = choose the voice key
            if (VolumeService.instance != null) VolumeService.instance.learnKey();
            return true;
        }
        return super.onKeyLongPress(code, e);
    }

    @Override public boolean onKeyUp(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_POUND && all == null && (e.getFlags() & KeyEvent.FLAG_CANCELED_LONG_PRESS) == 0
                && e.isTracking()) { showAll(); return true; }
        return super.onKeyUp(code, e);
    }

    private Panel panel;
    private boolean wantPanel;                          // opened by the key service (grey pull-down replaced)
    private float downY = -1;

    /** A swipe down anywhere on the home screen opens our panel (like the phone's pull-down). */
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (photoMode) {                                 // a tap on the photo brings the tiles back
            if (ev.getAction() == android.view.MotionEvent.ACTION_UP) showTiles();
            return true;
        }
        if (panel == null && all == null) {
            if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN) downY = ev.getY();
            else if (ev.getAction() == android.view.MotionEvent.ACTION_MOVE && downY >= 0 && ev.getY() - downY > dp(60)) {
                downY = -1;
                showPanel();
                return true;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    void showPanel() {
        if (panel != null) return;
        hideAll();
        panel = new Panel(this, mono, bold);
        Quick.addTo(this, panel);                       // quick settings + weather rows (no-op if absent)
        root.addView(panel.view);
        home.setVisibility(View.GONE);
        ((ViewGroup) home).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);   // keys must not reach the hidden tiles
        panel.view.setFocusableInTouchMode(true);       // so the D-pad works at once
        panel.view.requestFocus();
        panel.view.setSelection(panel.firstEnabled());
        panel.view.post(new Runnable() { @Override public void run() {
            if (panel != null) { panel.view.requestFocus(); panel.view.setSelection(panel.firstEnabled()); }
        } });
    }

    void closePanel() {
        if (panel == null) return;
        root.removeView(panel.view);
        panel = null;
        ((ViewGroup) home).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        home.setVisibility(View.VISIBLE);
        tiles.get(0).requestFocus();
    }

    private boolean hideAll() {
        if (panel != null) { closePanel(); return true; }
        if (themes != null) { root.removeView(themes); themes = null; if (all != null) all.requestFocus(); return true; }
        if (all == null) return false;
        root.removeView(all);
        all = null;
        home.setVisibility(View.VISIBLE);
        return true;
    }

    /** The first arrow press after coming home lands on the first tile, not wherever Android guesses. */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int c = e.getKeyCode();
        if (photoMode && e.getAction() == KeyEvent.ACTION_DOWN) { showTiles(); swallowUp = c; return true; }   // any key: tiles back
        if (swallowUp == c) { if (e.getAction() == KeyEvent.ACTION_UP) swallowUp = 0; return true; }   // its repeats + release
        boolean arrow = c == KeyEvent.KEYCODE_DPAD_UP || c == KeyEvent.KEYCODE_DPAD_DOWN
                || c == KeyEvent.KEYCODE_DPAD_LEFT || c == KeyEvent.KEYCODE_DPAD_RIGHT || c == KeyEvent.KEYCODE_DPAD_CENTER;
        if (all == null && panel == null && arrow && !tiles.contains(getCurrentFocus())) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                tiles.get(0).requestFocus();
                if (c == KeyEvent.KEYCODE_DPAD_UP) showPanel();         // up always opens the panel
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (all == null) {
            // number keys start dialling (like any phone); * too, for codes like *100#
            if ((code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) || code == KeyEvent.KEYCODE_STAR) {
                dialFrom(code == KeyEvent.KEYCODE_STAR ? "*" : String.valueOf((char) ('0' + code - KeyEvent.KEYCODE_0)));
                return true;
            }
            if (code == KeyEvent.KEYCODE_CALL) { openPhone(); return true; }
            if (code == KeyEvent.KEYCODE_MENU) { showAll(); return true; }
            if (code == KeyEvent.KEYCODE_POUND) { e.startTracking(); return true; }   // tap = all apps, hold = voice key
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (hideAll()) tiles.get(0).requestFocus();
            return true;              // home has nowhere to go back to
        }
        return super.onKeyDown(code, e);
    }

    // ---- helpers ----

    private TextView text(Typeface tf, int size, int color) {
        TextView t = new TextView(this);
        t.setTypeface(tf);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(color);
        return t;
    }

    /** Square tiles: three across the screen width (minus the side padding and tile margins). */
    private int tileSize() {
        int w = getResources().getDisplayMetrics().widthPixels;
        return (w - dp(16)) / COLS - dp(4);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private int sp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics()));
    }

    /** Close the keyboard if another app left it open (it would sit over this screen). */
    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
    }

}
