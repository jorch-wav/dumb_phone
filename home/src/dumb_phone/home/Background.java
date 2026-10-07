package dumb_phone.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;

import java.io.File;

/**
 * The home screen's background photo (optional). The photo is saved already cut to the screen
 * (files/background.jpg). To keep text readable on any photo, like the iPhone does, it is shaded
 * with the theme's background colour: more for photos that fight the theme (bright photo on a
 * dark theme, dark photo on a light one), and the tiles become see-through panels.
 */
final class Background {
    static File file(Context c) { return new File(c.getFilesDir(), "background.jpg"); }
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("background", Context.MODE_PRIVATE); }
    static boolean on(Context c) { return file(c).isFile(); }
    /** Changes whenever a new photo is saved or removed (home redraws then). */
    static long stamp(Context c) { return on(c) ? file(c).lastModified() : 0; }

    /** The photo with its shade, or null for the plain theme colour. */
    static Drawable drawable(Context c) {
        if (!on(c)) return null;
        Bitmap b = BitmapFactory.decodeFile(file(c).getPath());
        if (b == null) return null;
        float lum = prefs(c).getFloat("lum", 0.5f);              // 0 = black photo, 1 = white photo
        float fight = Theme.light() ? 1 - lum : lum;               // how much the photo works against the theme
        int alpha = Math.round(255 * Math.min(0.6f, 0.2f + 0.4f * fight));
        BitmapDrawable photo = new BitmapDrawable(c.getResources(), b);
        int v = Theme.VOID & 0xFFFFFF;                             // plus a fade behind the clock and status line
        android.graphics.drawable.GradientDrawable top = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xD8000000 | v, 0xC8000000 | v, 0x70000000 | v, v});
        LayerDrawable all = new LayerDrawable(new Drawable[]{photo, new ColorDrawable((alpha << 24) | v), top});
        all.setLayerInset(2, 0, 0, 0, Math.round(c.getResources().getDisplayMetrics().heightPixels * 0.6f));
        return all;
    }

    /** A theme colour made see-through for the tiles when there is a photo. */
    static int panel(Context c, int colour, boolean selected) {
        if (!on(c)) return colour;
        return ((selected ? 0xE0 : 0xA8) << 24) | (colour & 0xFFFFFF);
    }

    /** Over a photo: a soft shadow under every text so it reads on any part of the picture. */
    static void shadows(Context c, android.view.View v) {
        if (!on(c)) return;
        if (v instanceof android.widget.TextView)
            ((android.widget.TextView) v).setShadowLayer(4, 0, 1, Theme.VOID);
        if (v instanceof android.view.ViewGroup)
            for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) shadows(c, ((android.view.ViewGroup) v).getChildAt(i));
    }

    /** The built-in pictures (assets/backgrounds/<file>.jpg): file name and the name shown.
     *  Empty in the public build (the originals were copyrighted). Drop your own royalty-free
     *  480x640 JPEGs in home/assets/backgrounds/ and list them here as {"file", "shown name"}. */
    static final String[][] BUILT = {
        {"whale", "The Whale"},          // a ship moored to a sea-monster (British Library Harley 3244, public domain)
        {"crocodile", "The Crocodile"},  // a confused crocodile (British Library Royal 12 F xiii, public domain)
    };

    /** Turn the background on with a random built-in picture (none in the public build). */
    static boolean useDefault(Context c) {
        if (BUILT.length == 0) return false;
        return useBuiltIn(c, BUILT[new java.util.Random().nextInt(BUILT.length)][0]);
    }

    /** Use built-in picture `name`, cut to fit the screen. */
    static boolean useBuiltIn(Context c, String name) {
        try (java.io.InputStream in = c.getAssets().open("backgrounds/" + name + ".jpg")) {
            Bitmap src = BitmapFactory.decodeStream(in);
            android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
            int w = dm.widthPixels, h = dm.heightPixels;
            float scale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
            android.graphics.Canvas cv = new android.graphics.Canvas(out);
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postScale(scale, scale);
            float fx = name.equals("krumm") ? 0.6f : 0.5f;          // where the subject is, across the picture
            float left = Math.max(0, Math.min(src.getWidth() * scale - w, src.getWidth() * scale * fx - w / 2f));
            m.postTranslate(-left, (h - src.getHeight() * scale) / 2f);
            cv.drawBitmap(src, m, new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(file(c))) { out.compress(Bitmap.CompressFormat.JPEG, 92, fo); }
            prefs(c).edit().putFloat("lum", brightness(out)).putString("builtin", name).apply();
            if (name.equals("burns")) fixedTheme(c, "dracula");    // Mr Burns keeps the colours he was made with
            else if (name.equals("jake")) fixedTheme(c, "jake");   // Jake has his own theme (sky blue + Jake yellow)
            else photoTheme(c, out);
            return true;
        } catch (Exception e) { return false; }
    }

    /** Photos the user added (kept already cut to the screen), oldest first. */
    static File[] mine(Context c) {
        File d = new File(c.getFilesDir(), "my-backgrounds");
        File[] fs = d.listFiles();
        if (fs == null) return new File[0];
        java.util.Arrays.sort(fs);
        return fs;
    }

    /** Keep the background just saved from the user's own photo in the list (at most 12, oldest go). */
    static void keepMine(Context c) {
        File d = new File(c.getFilesDir(), "my-backgrounds");
        d.mkdirs();
        File to = new File(d, System.currentTimeMillis() + ".jpg");
        try (java.io.InputStream in = new java.io.FileInputStream(file(c)); java.io.OutputStream o = new java.io.FileOutputStream(to)) {
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        } catch (Exception e) { return; }
        prefs(c).edit().putString("builtin", "my:" + to.getName()).apply();
        File[] all = mine(c);
        for (int i = 0; i < all.length - 12; i++) all[i].delete();
    }

    /** Use one of the user's kept photos again. */
    static boolean useMine(Context c, File f) {
        Bitmap b = BitmapFactory.decodeFile(f.getPath());
        if (b == null) return false;
        try (java.io.FileOutputStream fo = new java.io.FileOutputStream(file(c))) { b.compress(Bitmap.CompressFormat.JPEG, 92, fo); }
        catch (Exception e) { return false; }
        prefs(c).edit().putFloat("lum", brightness(b)).putString("builtin", "my:" + f.getName()).apply();
        photoTheme(c, b);
        return true;
    }

    /** Colours from a picture, like the iPhone: a dark background tinted with its main colour, near-white
     *  text with a hint of it, and a vivid accent (a second colour of the picture if it has one). */
    static int[] palette(Bitmap b) {
        float[] weight = new float[36];                     // 10° hue buckets, weighted by how colourful a pixel is
        float[] hsv = new float[3];
        int stepX = Math.max(1, b.getWidth() / 60), stepY = Math.max(1, b.getHeight() / 60);
        for (int y = 0; y < b.getHeight(); y += stepY)
            for (int x = 0; x < b.getWidth(); x += stepX) {
                android.graphics.Color.colorToHSV(b.getPixel(x, y), hsv);
                weight[((int) hsv[0] / 10) % 36] += hsv[1] * hsv[2];
            }
        int h1 = 0;
        for (int i = 1; i < 36; i++) if (weight[i] > weight[h1]) h1 = i;
        int h2 = -1;                                         // the strongest colour at least 50° away from the main one
        for (int i = 0; i < 36; i++) {
            int d = Math.min(Math.abs(i - h1), 36 - Math.abs(i - h1));
            if (d >= 5 && (h2 < 0 || weight[i] > weight[h2])) h2 = i;
        }
        float total = 0;
        for (float w : weight) total += w;
        float hue = h1 * 10 + 5;
        boolean grey = total < 0.08f * (b.getWidth() / stepX) * (b.getHeight() / stepY);
        float sat = grey ? 0 : 1;
        int bg = android.graphics.Color.HSVToColor(new float[]{hue, 0.45f * sat, 0.11f});
        int text = android.graphics.Color.HSVToColor(new float[]{hue, 0.16f * sat, 0.97f});
        float accHue = h2 >= 0 && weight[h2] > 0.25f * weight[h1] ? h2 * 10 + 5 : hue;
        int acc = android.graphics.Color.HSVToColor(new float[]{accHue, grey ? 0 : 0.6f, 1f});
        return new int[]{bg, Theme.mix(bg, text, 0.06f), Theme.mix(bg, text, 0.14f), text, Theme.mix(bg, text, 0.5f), Theme.mix(bg, text, 0.24f), acc};
    }

    /** Switch every app to the photo's colours (remembering the theme from before); null = back to it. */
    static void photoTheme(Context c, Bitmap b) {
        SharedPreferences t = c.getSharedPreferences("theme", Context.MODE_PRIVATE);
        String now = t.getString("name", "phosphor");
        if (b == null) {
            if (t.contains("beforePhoto")) t.edit().putString("name", t.getString("beforePhoto", "phosphor")).remove("beforePhoto").commit();
            tellFossify(c);
            return;
        }
        StringBuilder n = new StringBuilder(Theme.PHOTO);
        for (int col : palette(b)) n.append(String.format("%06X,", col & 0xFFFFFF));
        SharedPreferences.Editor e = t.edit().putString("name", n.substring(0, n.length() - 1));
        if (!t.contains("beforePhoto")) e.putString("beforePhoto", now);
        e.commit();
        tellFossify(c);
    }

    /** Use a named theme with a picture (remembering the theme from before, like photoTheme). */
    static void fixedTheme(Context c, String theme) {
        SharedPreferences t = c.getSharedPreferences("theme", Context.MODE_PRIVATE);
        String now = t.getString("name", "phosphor");
        SharedPreferences.Editor e = t.edit().putString("name", theme);
        if (!t.contains("beforePhoto")) e.putString("beforePhoto", now);
        e.commit();
        tellFossify(c);
    }

    /** Fossify apps (Messages, Phone…) re-read the shared theme (our theme bridge app) when told to. */
    static void tellFossify(Context c) {
        for (android.content.pm.ApplicationInfo ai : c.getPackageManager().getInstalledApplications(0))
            if (ai.packageName.startsWith("org.fossify.") && !ai.packageName.equals("org.fossify.thankyou"))
                c.sendBroadcast(new android.content.Intent("org.fossify.android.GLOBAL_CONFIG_UPDATED")
                        .setPackage(ai.packageName).addFlags(0x20 /* include stopped apps */));
    }

    /** Average brightness 0..1 of a picture (sampled). */
    static float brightness(Bitmap b) {
        double sum = 0;
        int n = 0, stepX = Math.max(1, b.getWidth() / 40), stepY = Math.max(1, b.getHeight() / 40);
        for (int y = 0; y < b.getHeight(); y += stepY)
            for (int x = 0; x < b.getWidth(); x += stepX) {
                int p = b.getPixel(x, y);
                sum += (0.299 * ((p >> 16) & 255) + 0.587 * ((p >> 8) & 255) + 0.114 * (p & 255)) / 255;
                n++;
            }
        return n == 0 ? 0.5f : (float) (sum / n);
    }
}
