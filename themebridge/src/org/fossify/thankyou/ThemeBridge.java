package org.fossify.thankyou;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * Answers Fossify apps' "shared theme" question with the dumb_phone theme (read from the home app),
 * so Fossify Messages / Phone recolour themselves whenever a theme or background is picked.
 */
public class ThemeBridge extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Cursor query(Uri uri, String[] projection, String sel, String[] args, String sort) {
        MatrixCursor out = new MatrixCursor(new String[]{"_id", "theme_type", "text_color", "background_color", "primary_color",
                "accent_color", "app_icon_color", "show_checkmarks_on_switches", "last_updated_ts", "font_type", "font_name"});
        if (uri.getPath() != null && uri.getPath().contains("fonts")) return out;
        int bg = 0xFF040605, text = 0xFF95EE62, accent = 0xFFFFB347;
        String name = "phosphor";
        try (Cursor c = getContext().getContentResolver().query(Uri.parse("content://dumb_phone.theme/current"), null, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getColumnCount() >= 4) {
                name = c.getString(0); bg = c.getInt(1); text = c.getInt(2); accent = c.getInt(3);
            }
        } catch (Exception ignored) { }
        int ts = (int) (Math.abs((long) name.hashCode()) % 1_000_000_000L);   // changes when the theme does
        out.addRow(new Object[]{1, 2 /* custom */, text, bg, accent, accent, iconColour(accent), 0, ts, -1, ""});
        return out;
    }

    /** Fossify apps switch their launcher entry to the one matching the icon colour; a colour that isn't one
     *  of their 19 leaves NO entry enabled ("Launcher activity not found", the app can't open). So send
     *  the nearest of their own icon colours (md_app_icon_colors, read from Fossify Messages 1.9.1). */
    private static final int[] ICONS = {0xFFD32F2F, 0xFFC2185B, 0xFF7B1FA2, 0xFF512DA8, 0xFF303F9F, 0xFF1976D2, 0xFF0288D1,
            0xFF0097A7, 0xFF00796B, 0xFF106D1F, 0xFF689F38, 0xFFA4B42B, 0xFFFBC02D, 0xFFFFA000, 0xFFF57C00, 0xFFE64A19,
            0xFF5D4037, 0xFF455A64, 0xFF000000};

    static int iconColour(int c) {
        int best = ICONS[9]; long bestD = Long.MAX_VALUE;
        for (int k : ICONS) {
            long dr = ((c >> 16) & 255) - ((k >> 16) & 255), dg = ((c >> 8) & 255) - ((k >> 8) & 255), db = (c & 255) - (k & 255);
            long d = dr * dr * 2 + dg * dg * 4 + db * db * 3;
            if (d < bestD) { bestD = d; best = k; }
        }
        return best;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
