package dumb_phone.home;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * "Sounds": the ringtone, the alarm, the message sound and a sound per app, from the sound library
 * (/sdcard/Ringtones/dumb_phone: the .ogg files + sounds.json with each one's title and credit).
 * ← → tabs, ↑ ↓ move (sounds play as you go), OK use, back leaves an app's list.
 * The ringtone and the alarm can't be the same sound. Messages and per-app sounds are played by the key
 * service (NotifySound); Android's own notification sound is then set to silent so nothing plays twice.
 */
public class SoundsActivity extends Activity {
    static final File DIR = new File("/sdcard/Ringtones/dumb_phone");
    static final String[] TABS = {"ringtone", "alarm", "message", "apps"};

    private static final class Sound { String file, title, credit; double seconds; }
    private final List<Sound> sounds = new ArrayList<>();
    private final List<String[]> apps = new ArrayList<>();      // {label, package}
    private Typeface mono, bold;
    private int tab, sel;
    private String appPick;                                     // the app whose sound is being picked, or null
    private int appSel;
    private MediaPlayer player;
    private Board board;
    private final String[] current = new String[3];             // file in use for ringtone / alarm / message

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.load(this);
        mono = Typeface.createFromAsset(getAssets(), "JetBrainsMono-Regular.ttf");
        bold = Typeface.createFromAsset(getAssets(), "JetBrainsMono-Bold.ttf");
        try {
            JSONArray a = new JSONArray(VolumeService.readAll(new File(DIR, "sounds.json")));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Sound s = new Sound();
                s.file = o.getString("file"); s.title = o.getString("title"); s.credit = o.optString("credit", ""); s.seconds = o.optDouble("seconds", 0);
                if (new File(DIR, s.file).isFile()) sounds.add(s);
            }
        } catch (Exception ignored) { }
        PackageManager pm = getPackageManager();
        for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            boolean dup = false;
            for (String[] x : apps) if (x[1].equals(pkg)) dup = true;
            if (!dup) apps.add(new String[]{r.loadLabel(pm).toString().toLowerCase(Locale.getDefault()), pkg});
        }
        Collections.sort(apps, new Comparator<String[]>() { @Override public int compare(String[] x, String[] y) { return x[0].compareTo(y[0]); } });
        current[0] = !CallRing.file(this).isEmpty() ? CallRing.file(this) : systemFile(RingtoneManager.TYPE_RINGTONE);
        current[1] = systemFile(RingtoneManager.TYPE_ALARM);
        current[2] = NotifySound.active(this) ? NotifySound.prefs(this).getString("message", null) : systemFile(RingtoneManager.TYPE_NOTIFICATION);
        selectCurrent();
        board = new Board(this);
        board.setFocusable(true);
        board.setFocusableInTouchMode(true);
        setContentView(board);
        board.requestFocus();
    }

    /** The sound library as {file, title, credit} (for the alarm and timer sound pickers). */
    static java.util.List<String[]> library() {
        java.util.List<String[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(VolumeService.readAll(new File(DIR, "sounds.json")));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (new File(DIR, o.getString("file")).isFile()) out.add(new String[]{o.getString("file"), o.getString("title"),
                        o.optString("credit", "") + "  · " + Math.round(o.optDouble("seconds", 0)) + " s", String.valueOf(o.optDouble("seconds", 0))});
            }
        } catch (Exception ignored) { }
        Collections.sort(out, new Comparator<String[]>() {           // long ones first (alarms, ringtones)
            @Override public int compare(String[] x, String[] y) { return Double.compare(Double.parseDouble(y[3]), Double.parseDouble(x[3])); }
        });
        return out;
    }

    static String titleOfFile(String file) {
        if (file == null || file.isEmpty()) return "your alarm sound (Sounds)";
        for (String[] s : library()) if (s[0].equals(file)) return s[1];
        return file;
    }

    @Override protected void onPause() { super.onPause(); stopPreview(); }

    /** The library file Android currently uses for a type, or null if it's something else. */
    private String systemFile(int type) {
        Uri u = RingtoneManager.getActualDefaultRingtoneUri(this, type);
        if (u == null) return null;
        try (Cursor c = getContentResolver().query(u, new String[]{MediaStore.Audio.Media.DATA}, null, null, null)) {
            if (c != null && c.moveToFirst()) { String p = c.getString(0); return p == null ? null : new File(p).getName(); }
        } catch (Exception ignored) { }
        return null;
    }

    /** Ringtone and alarm: long sounds first; message and apps: short ones first. */
    private void orderForTab() {
        final boolean longFirst = tab < 2;
        Collections.sort(sounds, new Comparator<Sound>() {
            @Override public int compare(Sound x, Sound y) { return longFirst ? Double.compare(y.seconds, x.seconds) : Double.compare(x.seconds, y.seconds); }
        });
    }

    private void selectCurrent() {
        orderForTab();
        sel = 0;
        if (tab < 3) for (int i = 0; i < sounds.size(); i++) if (sounds.get(i).file.equals(current[tab])) sel = i;
    }

    // ---- what the list shows ----
    /** Rows when picking a sound for an app: "message sound", "silent", then the sounds. */
    private int rows() {
        if (tab < 3) return sounds.size();
        return appPick == null ? apps.size() : sounds.size() + 2;
    }

    private Sound soundAt(int row) {
        if (tab < 3) return row < sounds.size() ? sounds.get(row) : null;
        if (appPick != null && row >= 2) return sounds.get(row - 2);
        return null;
    }

    private String titleOf(String file) {
        if (file == null || file.isEmpty()) return "message sound";
        if (file.equals("-")) return "silent";
        for (Sound s : sounds) if (s.file.equals(file)) return s.title;
        return file;
    }

    // ---- sound ----
    private void preview() {
        stopPreview();
        Sound s = soundAt(sel);
        if (s == null) return;
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(tab == 1 ? AudioAttributes.USAGE_ALARM
                    : AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build());
            player.setDataSource(new File(DIR, s.file).getPath());
            player.prepare();
            player.start();
        } catch (Exception e) { player = null; }
    }

    private void stopPreview() {
        if (player != null) { try { player.stop(); player.release(); } catch (Exception ignored) { } player = null; }
    }

    /** Android's media entry for a library file (added if missing), offered for every kind of sound. */
    private Uri mediaUri(Sound s) {
        String path = new File(DIR, s.file).getPath();
        Uri uri = null;
        try (Cursor c = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, new String[]{MediaStore.Audio.Media._ID},
                MediaStore.Audio.Media.DATA + "=?", new String[]{path}, null)) {
            if (c != null && c.moveToFirst()) uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getString(0));
        } catch (Exception ignored) { }
        if (uri == null) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DATA, path);
            v.put(MediaStore.MediaColumns.TITLE, s.title);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "audio/ogg");
            uri = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
        }
        ContentValues f = new ContentValues();
        f.put(MediaStore.Audio.Media.IS_RINGTONE, true);
        f.put(MediaStore.Audio.Media.IS_ALARM, true);
        f.put(MediaStore.Audio.Media.IS_NOTIFICATION, true);
        f.put(MediaStore.MediaColumns.TITLE, s.title);
        getContentResolver().update(uri, f, null, null);
        return uri;
    }

    private void use() {
        try {
            if (tab == 3) { useForApp(); return; }
            Sound s = soundAt(sel);
            if (s == null) return;
            int other = tab == 0 ? 1 : tab == 1 ? 0 : -1;
            if (other >= 0 && s.file.equals(current[other])) {
                Toast.makeText(this, "that's your " + TABS[other] + ": pick a different one", Toast.LENGTH_SHORT).show();
                return;
            }
            if (tab == 2) {                                     // messages: our player; Android's own sound off
                NotifySound.prefs(this).edit().putString("message", s.file).apply();
                RingtoneManager.setActualDefaultRingtoneUri(this, RingtoneManager.TYPE_NOTIFICATION, null);
            } else if (tab == 0) {                              // ringtone: our player (Android's ringer goes silent on this phone)
                CallRing.use(this, s.file);
            } else {
                RingtoneManager.setActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM, mediaUri(s));
            }
            current[tab] = s.file;
            Toast.makeText(this, s.title + " is your " + TABS[tab], Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "couldn't set it (run setup.sh once more)", Toast.LENGTH_SHORT).show();
        }
        board.invalidate();
    }

    private void useForApp() {
        if (appPick == null) {                                  // open this app's sound list
            appPick = apps.get(sel)[1];
            appSel = sel;
            String f = NotifySound.forApp(this, appPick);
            sel = f.isEmpty() ? 0 : f.equals("-") ? 1 : 0;
            for (int i = 0; i < sounds.size(); i++) if (sounds.get(i).file.equals(f)) sel = i + 2;
        } else {
            String v = sel == 0 ? "" : sel == 1 ? "-" : sounds.get(sel - 2).file;
            if (v.isEmpty()) NotifySound.prefs(this).edit().remove("app:" + appPick).apply();
            else NotifySound.prefs(this).edit().putString("app:" + appPick, v).apply();
            if (!NotifySound.active(this))
                Toast.makeText(this, "also pick a message sound, so app sounds can play", Toast.LENGTH_LONG).show();
            stopPreview();
            appPick = null;
            sel = appSel;
        }
        board.invalidate();
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (appPick != null) return true;
                tab = (tab + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 3)) % 4; stopPreview(); selectCurrent(); break;
            case KeyEvent.KEYCODE_DPAD_UP: if (sel > 0) { sel--; preview(); } break;
            case KeyEvent.KEYCODE_DPAD_DOWN: if (sel < rows() - 1) { sel++; preview(); } break;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: use(); break;
            case KeyEvent.KEYCODE_5: preview(); break;
            case KeyEvent.KEYCODE_BACK: if (appPick != null) { stopPreview(); appPick = null; sel = appSel; break; } return super.onKeyDown(code, e);
            default: return super.onKeyDown(code, e);
        }
        board.invalidate();
        return true;
    }

    private float dp(float v) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    final class Board extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Board(Context c) { super(c); }

        private int first(float h) {
            int fits = (int) ((h - dp(36) - dp(20)) / dp(42));
            return Math.max(0, Math.min(sel - fits / 2, rows() - fits));
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            c.drawColor(Theme.VOID);
            p.setTypeface(bold); p.setTextSize(dp(12)); p.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < 4; i++) {
                float x = w / 8 + i * w / 4;
                p.setColor(i == tab ? Theme.AMBER : Theme.DIM);
                c.drawText(TABS[i], x, dp(20), p);
                if (i == tab) c.drawRect(x - dp(20), dp(26), x + dp(20), dp(28), p);
            }
            p.setColor(Theme.RULE);
            c.drawRect(0, dp(31), w, dp(32), p);
            p.setTextAlign(Paint.Align.LEFT);
            if (sounds.isEmpty()) {
                p.setTypeface(mono); p.setTextSize(dp(12)); p.setColor(Theme.DIM);
                c.drawText("no sound library on this phone", dp(10), dp(56), p);
                c.drawText("(setup.sh copies it to Ringtones/dumb_phone)", dp(10), dp(74), p);
                return;
            }
            float rowH = dp(42), top = dp(36);
            int fits = (int) ((h - top - dp(20)) / rowH), first = first(h);
            for (int i = first; i < rows() && i < first + fits; i++) {
                float y = top + (i - first) * rowH;
                String title, sub;
                boolean mine;
                if (tab == 3 && appPick == null) {
                    String[] a = apps.get(i);
                    title = a[0]; sub = titleOf(NotifySound.forApp(SoundsActivity.this, a[1]));
                    mine = !NotifySound.forApp(SoundsActivity.this, a[1]).isEmpty();
                } else if (tab == 3) {
                    String f = NotifySound.forApp(SoundsActivity.this, appPick);
                    if (i == 0) { title = "message sound"; sub = "the same as other messages"; mine = f.isEmpty(); }
                    else if (i == 1) { title = "silent"; sub = "no sound for this app"; mine = f.equals("-"); }
                    else { Sound s = sounds.get(i - 2); title = s.title; sub = s.credit + "  · " + Math.round(s.seconds) + " s"; mine = s.file.equals(f); }
                } else {
                    Sound s = sounds.get(i);
                    title = s.title; sub = s.credit + (s.seconds > 0 ? "  · " + Math.round(s.seconds) + " s" : ""); mine = s.file.equals(current[tab]);
                }
                if (i == sel) { p.setColor(Theme.LIT); c.drawRect(dp(4), y, w - dp(4), y + rowH - dp(3), p); }
                p.setTypeface(i == sel || mine ? bold : mono); p.setTextSize(dp(14));
                p.setColor(i == sel ? Theme.AMBER : Theme.GREEN);
                c.drawText(title + (mine && !(tab == 3 && appPick == null) ? "  ✓" : ""), dp(10), y + dp(18), p);
                p.setTypeface(mono); p.setTextSize(dp(9)); p.setColor(mine && tab == 3 && appPick == null ? Theme.AMBER : Theme.DIM);
                while (p.measureText(sub) > w - dp(20) && sub.length() > 4) sub = sub.substring(0, sub.length() - 2);
                c.drawText(sub, dp(10), y + dp(33), p);
            }
            p.setTextAlign(Paint.Align.CENTER); p.setTextSize(dp(10)); p.setColor(Theme.DIM);
            String hint = tab == 3 && appPick == null ? "[OK] choose this app's sound   [←→] tabs"
                    : tab == 3 ? "for " + appName(appPick) + ":  [↑↓] listen [OK] use [back]" : "[↑↓] listen  [OK] use  [←→] tabs";
            c.drawText(hint, w / 2, h - dp(6), p);
        }

        private String appName(String pkg) { for (String[] a : apps) if (a[1].equals(pkg)) return a[0]; return pkg; }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() != MotionEvent.ACTION_UP) return true;
            float y = e.getY(), w = getWidth(), h = getHeight();
            if (y < dp(32)) { if (appPick == null) { tab = Math.min(3, (int) (e.getX() / (w / 4))); stopPreview(); selectCurrent(); invalidate(); } return true; }
            int row = first(h) + (int) ((y - dp(36)) / dp(42));
            if (row >= 0 && row < rows()) { if (row == sel) use(); else { sel = row; preview(); } }
            invalidate();
            return true;
        }
    }
}
