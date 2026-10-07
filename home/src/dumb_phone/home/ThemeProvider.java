package dumb_phone.home;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/** Tells the other dumb_phone apps which colour theme is chosen (one row: name + background, text, accent). */
public class ThemeProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Cursor query(Uri uri, String[] projection, String sel, String[] args, String sort) {
        String n = Theme.chosen(getContext());
        int[] col = Theme.colours(n);                    // also the colours, for the Fossify theme bridge
        MatrixCursor c = new MatrixCursor(new String[]{"name", "background", "text", "accent"});
        c.addRow(new Object[]{n, col[0], col[1], col[2]});
        return c;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
