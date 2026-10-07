package dumb_phone.podcasts;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Podcast cover art: downloaded once, shrunk to a small square and kept in the cache folder, so a
 * cover costs a few KB of memory and no data after the first time.
 */
final class Art {
    private static final int SIZE = 112;                     // px; enough for the biggest cover we draw
    private static final Map<String, Bitmap> mem = new HashMap<>();
    private static final Set<String> busy = new HashSet<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** Show the cover for url in v (blank until it is ready). */
    static void into(final Context c, final String url, final ImageView v) {
        v.setTag(url);
        if (url == null) { v.setImageBitmap(null); return; }
        Bitmap b = mem.get(url);
        if (b != null) { v.setImageBitmap(b); return; }
        v.setImageBitmap(null);
        final File f = new File(c.getCacheDir(), "art-" + Integer.toHexString(url.hashCode()) + ".png");
        if (!busy.add(url)) { retryLater(c, url, v); return; }
        new Thread(new Runnable() {
            @Override public void run() {
                Bitmap got = null;
                try {
                    if (f.isFile()) got = BitmapFactory.decodeFile(f.getPath());
                    if (got == null) got = download(url, f);
                } catch (Throwable ignored) { }
                final Bitmap fin = got;
                main.post(new Runnable() {
                    @Override public void run() {
                        busy.remove(url);
                        if (fin == null) return;
                        mem.put(url, fin);
                        if (url.equals(v.getTag())) v.setImageBitmap(fin);
                    }
                });
            }
        }).start();
    }

    private static void retryLater(final Context c, final String url, final ImageView v) {
        main.postDelayed(new Runnable() {
            @Override public void run() { if (url.equals(v.getTag()) && mem.containsKey(url)) v.setImageBitmap(mem.get(url)); }
        }, 1500);
    }

    private static Bitmap download(String url, File out) throws Exception {
        String u = url;
        HttpURLConnection c = null;
        for (int hop = 0; hop < 5; hop++) {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "dumb_phone-podcasts");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                u = new URL(new URL(u), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            break;
        }
        byte[] data;
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) { bo.write(buf, 0, n); if (bo.size() > 8_000_000) break; }
            data = bo.toByteArray();
        } finally {
            c.disconnect();
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int sample = 1;
        while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= SIZE) sample *= 2;   // decode small: covers are often 3000 px
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap big = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (big == null) return null;
        int side = Math.min(big.getWidth(), big.getHeight());
        Bitmap sq = Bitmap.createBitmap(big, (big.getWidth() - side) / 2, (big.getHeight() - side) / 2, side, side);
        Bitmap small = Bitmap.createScaledBitmap(sq, SIZE, SIZE, true);
        try (FileOutputStream fo = new FileOutputStream(out)) { small.compress(Bitmap.CompressFormat.PNG, 100, fo); }
        return small;
    }
}
