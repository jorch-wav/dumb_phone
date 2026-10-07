package dumb_phone.home;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * "Background": pick a photo for the home screen, then place it: drag or the D-pad moves it, pinch
 * or 1 / 3 zooms, OK saves, Back cancels. A light sketch of the clock and tiles shows on top while
 * placing. Opened with a photo already set, it asks first: new photo, move this one, or remove it.
 */
public class BackgroundActivity extends Activity {
    private static final int PICK = 1;
    private Typeface mono, bold;
    private Bitmap photo;                      // the picked photo (shrunk to at most ~1600 px)
    private float scale = 1, minScale = 1, dx, dy;
    private Editor editor;
    private int menuSel;                        // no background, the pictures, add one, move / zoom
    private Bitmap peek;                        // the highlighted built-in picture, shown behind the list
    private int peekFor = -1;
    private int B;                              // pictures in the list: the built-in ones, then the user's own
    private java.io.File[] mine = new java.io.File[0];

    private String key(int i) { return i < Background.BUILT.length ? Background.BUILT[i][0] : "my:" + mine[i - Background.BUILT.length].getName(); }
    private String label(int i) { return i < Background.BUILT.length ? Background.BUILT[i][1] : "my photo " + (i - Background.BUILT.length + 1); }
    private Bitmap open(int i) {
        if (i >= Background.BUILT.length) return BitmapFactory.decodeFile(mine[i - Background.BUILT.length].getPath());
        try (InputStream in = getAssets().open("backgrounds/" + Background.BUILT[i][0] + ".jpg")) { return BitmapFactory.decodeStream(in); }
        catch (Exception e) { return null; }
    }
    private boolean menu, fromPhone;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.load(this);
        mono = Typeface.createFromAsset(getAssets(), "JetBrainsMono-Regular.ttf");
        bold = Typeface.createFromAsset(getAssets(), "JetBrainsMono-Bold.ttf");
        editor = new Editor();
        setContentView(editor);
        editor.setFocusable(true);
        editor.setFocusableInTouchMode(true);
        editor.requestFocus();
        if (Background.on(this)) {
            menu = true;
            String cur = Background.prefs(this).getString("builtin", "");
            mine = Background.mine(this);
            B = Background.BUILT.length + mine.length;
            for (int i = 0; i < B; i++) if (key(i).equals(cur)) menuSel = i + 1;
        } else pick();
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(i, PICK); }
        catch (Exception e) {
            try { startActivityForResult(new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI), PICK); }
            catch (Exception e2) { toast("no photo picker on this phone"); finish(); }
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req != PICK) return;
        if (res != RESULT_OK || data == null || data.getData() == null) { if (photo == null && !Background.on(this)) finish(); return; }
        Bitmap b = load(data.getData());
        if (b == null) { toast("couldn't open that photo"); if (!Background.on(this)) finish(); return; }
        menu = false;
        fromPhone = true;
        setPhoto(b);
    }

    /** Decode a photo small enough for this phone's memory (the screen is tiny; 4x zoom still looks fine). */
    private Bitmap load(Uri u) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(u)) { BitmapFactory.decodeStream(in, null, o); }
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 1600) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b;
            try (InputStream in = getContentResolver().openInputStream(u)) { b = BitmapFactory.decodeStream(in, null, o); }
            if (b == null) return null;
            int rot = 0;                                           // photos from cameras are often stored sideways
            try (InputStream in = getContentResolver().openInputStream(u)) {
                int or = new android.media.ExifInterface(in).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
                rot = or == 6 ? 90 : or == 3 ? 180 : or == 8 ? 270 : 0;
            } catch (Throwable ignored) { }
            if (rot != 0) {
                Matrix m = new Matrix();
                m.postRotate(rot);
                b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            }
            return b;
        } catch (Throwable e) { return null; }
    }

    private void setPhoto(Bitmap b) {
        photo = b;
        editor.post(new Runnable() { @Override public void run() {
            int w = editor.getWidth(), h = editor.getHeight();
            minScale = Math.max((float) w / photo.getWidth(), (float) h / photo.getHeight());   // always covers the screen
            scale = minScale;
            dx = (w - photo.getWidth() * scale) / 2;
            dy = (h - photo.getHeight() * scale) / 2;
            editor.invalidate();
        } });
    }

    /** Keep the photo covering the whole screen. */
    private void clamp() {
        int w = editor.getWidth(), h = editor.getHeight();
        scale = Math.max(minScale, Math.min(minScale * 5, scale));
        dx = Math.min(0, Math.max(w - photo.getWidth() * scale, dx));
        dy = Math.min(0, Math.max(h - photo.getHeight() * scale, dy));
    }

    private void zoom(float f, float cx, float cy) {
        float old = scale;
        scale *= f;
        clamp();
        float k = scale / old;
        dx = cx - (cx - dx) * k;
        dy = cy - (cy - dy) * k;
        clamp();
        editor.invalidate();
    }

    private void save() {
        int w = editor.getWidth(), h = editor.getHeight();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(out);
        Matrix m = new Matrix();
        m.postScale(scale, scale);
        m.postTranslate(dx, dy);
        c.drawBitmap(photo, m, new Paint(Paint.FILTER_BITMAP_FLAG));
        try (FileOutputStream fo = new FileOutputStream(Background.file(this))) {
            out.compress(Bitmap.CompressFormat.JPEG, 92, fo);
        } catch (Exception e) { toast("couldn't save"); return; }
        Background.prefs(this).edit().putFloat("lum", Background.brightness(out)).remove("builtin").apply();
        Background.photoTheme(this, out);
        if (fromPhone) Background.keepMine(this);                // a new photo joins the list
        toast("background saved");
        finish();
    }

    private void remove() {
        Background.file(this).delete();
        Background.photoTheme(this, null);                       // back to the theme from before
        toast("background removed");
        finish();
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (menu) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP: menuSel = Math.max(0, menuSel - 1); editor.invalidate(); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: menuSel = Math.min(B + 2, menuSel + 1); editor.invalidate(); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: menuPick(menuSel); return true;
                case KeyEvent.KEYCODE_BACK: finish(); return true;
            }
            return super.onKeyDown(code, e);
        }
        if (photo == null) return super.onKeyDown(code, e);
        float step = editor.getWidth() / 10f;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: dx += step; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dx -= step; break;
            case KeyEvent.KEYCODE_DPAD_UP: dy += step; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dy -= step; break;
            case KeyEvent.KEYCODE_3: zoom(1.15f, editor.getWidth() / 2f, editor.getHeight() / 2f); return true;
            case KeyEvent.KEYCODE_1: zoom(1 / 1.15f, editor.getWidth() / 2f, editor.getHeight() / 2f); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: save(); return true;
            case KeyEvent.KEYCODE_BACK: finish(); return true;
            default: return super.onKeyDown(code, e);
        }
        clamp();
        editor.invalidate();
        return true;
    }

    /** Rows: 0 no background, 1..B the pictures, B+1 move / zoom the current one, B+2 a new one from the phone. */
    private void menuPick(int i) {
        if (i == 0) { remove(); return; }
        if (i <= B) {                                            // a picture from the list: use it and show the home screen
            int k = i - 1;
            boolean ok = k < Background.BUILT.length ? Background.useBuiltIn(this, Background.BUILT[k][0])
                    : Background.useMine(this, mine[k - Background.BUILT.length]);
            if (ok) goHome();
        } else if (i == B + 2) pick();
        else {                                                   // move / zoom: start from the whole picture when there is one
            Bitmap b = null;
            String cur = Background.prefs(this).getString("builtin", "");
            for (int k = 0; k < B; k++) if (key(k).equals(cur)) b = open(k);
            if (b == null) b = BitmapFactory.decodeFile(Background.file(this).getPath());
            if (b != null) { menu = false; setPhoto(b); }
        }
    }

    private void goHome() {
        startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }

    private int firstRow() { return Math.max(0, Math.min(menuSel - 4, B + 3 - 7)); }   // 7 rows fit; keep the pick in view

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private float dp(float v) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    /** Draws the photo where it will sit, with a faint sketch of the home screen and the key hints. */
    final class Editor extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final ScaleGestureDetector pinch;
        private float lx, ly;
        private boolean moved;

        Editor() {
            super(BackgroundActivity.this);
            pinch = new ScaleGestureDetector(BackgroundActivity.this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) {
                    if (photo != null) zoom(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                    return true;
                }
            });
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            if (menu) {
                if (ev.getAction() == MotionEvent.ACTION_UP) {
                    int row = firstRow() + (int) ((ev.getY() - dp(34)) / dp(36));
                    if (ev.getY() >= dp(34) && row >= 0 && row <= B + 2) menuPick(row);
                }
                return true;
            }
            if (photo == null) return true;
            pinch.onTouchEvent(ev);
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: lx = ev.getX(); ly = ev.getY(); moved = false; break;
                case MotionEvent.ACTION_MOVE:
                    if (ev.getPointerCount() == 1 && !pinch.isInProgress()) {
                        dx += ev.getX() - lx; dy += ev.getY() - ly;
                        if (Math.abs(ev.getX() - lx) + Math.abs(ev.getY() - ly) > 1) moved = true;
                        clamp();
                        invalidate();
                    }
                    lx = ev.getX(); ly = ev.getY();
                    break;
                case MotionEvent.ACTION_UP:            // a tap on the hint line saves
                    if (!moved && ev.getY() > getHeight() - dp(30)) save();
                    break;
            }
            return true;
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            c.drawColor(Theme.VOID);
            p.setTypeface(mono);
            if (menu) {
                p.setTypeface(bold);
                p.setTextSize(dp(13));
                p.setColor(Theme.AMBER);
                if (menuSel >= 1 && menuSel <= B && peekFor != menuSel) {   // preview the highlighted picture full screen
                    peek = open(menuSel - 1); peekFor = menuSel;
                }
                if (menuSel >= 1 && menuSel <= B && peek != null) {
                    float s = Math.max(w / peek.getWidth(), h / peek.getHeight());
                    Matrix pm = new Matrix();
                    pm.postScale(s, s);
                    pm.postTranslate((w - peek.getWidth() * s) / 2, (h - peek.getHeight() * s) / 2);
                    c.drawBitmap(peek, pm, p);
                    p.setColor((0x99 << 24) | (Theme.VOID & 0xFFFFFF));
                    c.drawRect(0, 0, w, h, p);
                }
                p.setColor(Theme.AMBER);
                c.drawText("background", dp(8), dp(24), p);
                String cur = Background.prefs(BackgroundActivity.this).getString("builtin", "");
                p.setTypeface(mono);
                p.setTextSize(dp(14));
                int first = firstRow();
                for (int i = first; i < Math.min(B + 3, first + 7); i++) {
                    String label = i == 0 ? "no background" : i <= B ? label(i - 1) + (key(i - 1).equals(cur) ? "  ✓" : "")
                            : i == B + 1 ? "move / zoom this one" : "+ new background";
                    float y = dp(34) + (i - first) * dp(36);
                    p.setColor(i == menuSel ? Theme.LIT : (0xB0 << 24) | (Theme.VOID & 0xFFFFFF));
                    c.drawRect(dp(4), y, w - dp(4), y + dp(32), p);
                    p.setColor(i == menuSel ? Theme.AMBER : i <= B || i == B + 2 ? Theme.GREEN : Theme.DIM);
                    c.drawText(label, dp(10), y + dp(21), p);
                }
                return;
            }
            if (photo == null) {
                p.setColor(Theme.DIM);
                p.setTextSize(dp(13));
                c.drawText("choose a photo…", dp(10), dp(30), p);
                return;
            }
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            m.postTranslate(dx, dy);
            c.drawBitmap(photo, m, p);
            // faint sketch of where the clock and tiles go
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(0x88FFFFFF);
            c.drawRect(dp(8), dp(22), w * 0.45f, dp(56), p);
            float top = h * 0.3f, gap = dp(4), tw = (w - dp(16) - 2 * gap) / 3;
            for (int r = 0; r < 3; r++)
                for (int k = 0; k < 3; k++)
                    c.drawRect(dp(8) + k * (tw + gap), top + r * (tw + gap), dp(8) + k * (tw + gap) + tw, top + r * (tw + gap) + tw, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xCC000000);
            c.drawRect(0, h - dp(24), w, h, p);
            p.setColor(0xFFFFFFFF);
            p.setTextSize(dp(10));
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText("drag / ←→↑↓ move · pinch / 1 3 zoom · OK save", w / 2, h - dp(9), p);
            p.setTextAlign(Paint.Align.LEFT);
        }
    }
}
