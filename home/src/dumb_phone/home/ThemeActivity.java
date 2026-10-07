package dumb_phone.home;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

/**
 * "Themes": every colour theme in a list. Moving the highlight repaints this screen and a small
 * preview of the home screen in that theme; OK keeps it (every dumb_phone app follows), Back cancels.
 */
public class ThemeActivity extends Activity {
    private Typeface mono, bold, icons;
    private LinearLayout root;
    private TextView title, hint;
    private Preview preview;
    private ListView list;
    private String original;
    private String[] items;                              // the themes listed (plus "photo colours" with a photo)

    private String label(int i) { return items[i].startsWith(Theme.PHOTO) ? "photo colours" : items[i]; }
    private int originalIndex() {
        String base = swap && original.endsWith(Theme.SWAP) ? original.substring(0, original.length() - Theme.SWAP.length()) : original;
        for (int i = 0; i < items.length; i++) if (items[i].equals(base)) return i;
        return 0;
    }
    private int shown = -1;
    private boolean swap, onBg;
    private int origIdx;
    private TextView bgRow;

    private void paintBgRow() {
        bgRow.setText((onBg ? "› " : "  ") + (Background.on(this) ? "background photo  ✓  ›" : "background photo: off"));
        bgRow.setTextColor(onBg ? Theme.AMBER : Theme.GREEN);
        bgRow.setBackgroundColor(onBg ? Theme.LIT : 0);
    }                                // text and accent colours swapped (the * key)

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.load(this);
        original = Theme.name;
        swap = Theme.swapped(original);
        String base = swap ? original.substring(0, original.length() - Theme.SWAP.length()) : original;
        if (base.startsWith(Theme.PHOTO)) {                  // colours from the background photo: first in the list
            items = new String[Theme.NAMES.length + 1];
            items[0] = base;
            System.arraycopy(Theme.NAMES, 0, items, 1, Theme.NAMES.length);
        } else items = Theme.NAMES;
        mono = Typeface.createFromAsset(getAssets(), "JetBrainsMono-Regular.ttf");
        bold = Typeface.createFromAsset(getAssets(), "JetBrainsMono-Bold.ttf");
        icons = Typeface.createFromAsset(getAssets(), "phosphor-icons.ttf");

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(6), dp(8), dp(4));
        title = new TextView(this);
        title.setTypeface(bold);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setText("themes");
        root.addView(title);
        preview = new Preview(this);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(92));
        pp.setMargins(0, dp(4), 0, dp(6));
        root.addView(preview, pp);

        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        bgRow = new TextView(this);                      // first row: the home screen photo
        bgRow.setTypeface(mono);
        bgRow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        bgRow.setPadding(dp(6), dp(6), dp(6), dp(8));
        list.addHeaderView(bgRow, null, true);
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return items.length; }
            @Override public Object getItem(int i) { return items[i]; }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup parent) {
                TextView t = v != null ? (TextView) v : new TextView(ThemeActivity.this);
                t.setTypeface(mono);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                t.setPadding(dp(6), dp(6), dp(6), dp(6));
                boolean sel = i == shown;
                t.setText((sel ? "› " : "  ") + label(i) + (sel && swap ? " ⇄" : "") + (i == origIdx ? "  ✓" : ""));
                t.setTextColor(sel ? Theme.AMBER : Theme.GREEN);
                t.setBackgroundColor(sel ? Theme.LIT : 0);
                return t;
            }
        });
        list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                onBg = pos == 0;
                if (pos > 0) show(pos - 1); else paintBgRow();
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos == 0) {
                    if (Background.on(ThemeActivity.this)) {   // already on: new photo / move / remove
                        startActivity(new android.content.Intent(ThemeActivity.this, BackgroundActivity.class));
                    } else if (Background.useDefault(ThemeActivity.this)) {   // on: show it on the home screen right away
                        startActivity(new android.content.Intent(ThemeActivity.this, HomeActivity.class)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP));
                        finish();
                    }
                    return;
                }
                show(pos - 1); keep();
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        hint = new TextView(this);
        hint.setTypeface(mono);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setGravity(Gravity.CENTER);
        hint.setText("[OK] use  [*] swap colours  [back] cancel");
        hint.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { toggleSwap(); } });
        hint.setPadding(0, dp(4), 0, dp(2));
        root.addView(hint);
        setContentView(root);

        origIdx = originalIndex();
        final int start = origIdx;
        show(start);
        list.setFocusableInTouchMode(true);              // so the very first key press moves the highlight
        list.requestFocus();
        list.setSelection(start + 1);
        list.post(new Runnable() { @Override public void run() { list.requestFocus(); list.setSelection(start + 1); } });
    }

    private void toggleSwap() {
        swap = !swap;
        int i = shown;
        shown = -1;
        show(i);
    }

    /** Paint everything in theme number i (a preview until OK). */
    private void show(int i) {
        if (i < 0 || i >= items.length || i == shown) return;
        shown = i;
        Theme.apply(items[i] + (swap ? Theme.SWAP : ""));
        root.setBackgroundColor(Theme.VOID);
        getWindow().getDecorView().setBackgroundColor(Theme.VOID);
        title.setTextColor(Theme.AMBER);
        hint.setTextColor(Theme.DIM);
        preview.invalidate();
        paintBgRow();
        ((BaseAdapter) ((android.widget.HeaderViewListAdapter) list.getAdapter()).getWrappedAdapter()).notifyDataSetChanged();
    }

    private void keep() {
        getSharedPreferences("theme", MODE_PRIVATE).edit().putString("name", items[shown] + (swap ? Theme.SWAP : "")).commit();
        Background.tellFossify(this);
        finish();
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) { Theme.apply(original); finish(); return true; }
        if (code == KeyEvent.KEYCODE_STAR) { toggleSwap(); return true; }
        return super.onKeyDown(code, e);
    }

    @Override protected void onResume() {
        super.onResume();
        if (bgRow != null) paintBgRow();                 // back from Background: update its ✓
    }

    @Override protected void onPause() {
        super.onPause();
        if (isFinishing()) Theme.load(this);             // back to whatever is saved
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    /** A small home screen: status line, clock and date, three tiles. */
    final class Preview extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Preview(Context c) { super(c); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
            p.setStyle(Paint.Style.FILL);
            p.setColor(Theme.CELL);
            c.drawRect(0, 0, w, h, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1);
            p.setColor(Theme.RULE);
            c.drawRect(0, 0, w - 1, h - 1, p);
            p.setStyle(Paint.Style.FILL);
            float pad = 6 * d;
            p.setTypeface(mono);
            p.setTextSize(9 * d);
            p.setColor(Theme.DIM);
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText("carrier", pad, pad + 8 * d, p);
            p.setTextAlign(Paint.Align.RIGHT);
            c.drawText("86%", w - pad, pad + 8 * d, p);
            p.setTypeface(bold);
            p.setTextSize(20 * d);
            p.setColor(Theme.GREEN);
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText("12:30", pad, pad + 30 * d, p);
            p.setTypeface(mono);
            p.setTextSize(12 * d);
            p.setColor(Theme.AMBER);
            p.setTextAlign(Paint.Align.RIGHT);
            c.drawText("sat 03 oct", w - pad, pad + 30 * d, p);
            float top = pad + 38 * d, tw = (w - pad * 2 - 8 * d) / 3, th = h - top - pad;
            int[] glyph = {0xF05A3, 0xF06CB, 0xF0439};
            for (int k = 0; k < 3; k++) {
                float x = pad + k * (tw + 4 * d);
                p.setStyle(Paint.Style.FILL);
                p.setColor(k == 0 ? Theme.LIT : Theme.VOID);
                c.drawRect(x, top, x + tw, top + th, p);
                if (k == 0) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setColor(Theme.GREEN);
                    c.drawRect(x, top, x + tw, top + th, p);
                    p.setStyle(Paint.Style.FILL);
                }
                p.setTypeface(icons);
                p.setTextSize(16 * d);
                p.setTextAlign(Paint.Align.CENTER);
                p.setColor(k == 0 ? Theme.AMBER : Theme.GREEN);
                c.drawText(new String(Character.toChars(glyph[k])), x + tw / 2, top + th / 2 + 6 * d, p);
            }
        }
    }
}
