package dumb_phone.dumbtrains;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import static dumb_phone.dumbtrains.Theme.DIM;
import static dumb_phone.dumbtrains.Theme.GREEN;
import static dumb_phone.dumbtrains.Theme.SEL;
import static dumb_phone.dumbtrains.Theme.VOID;

/** Type part of a station's name (the keypad, T9), ↓ to the list, OK picks it. */
public class PickActivity extends Activity {
    private final List<Transit.Stop> found = new ArrayList<>();
    private ArrayAdapter<Transit.Stop> adapter;
    private TextView hint;
    private final Handler main = new Handler();
    private int gen;
    private String query = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.load(this);
        final Typeface mono = Typeface.createFromAsset(getAssets(), "JetBrainsMono-Regular.ttf");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(VOID);
        root.setPadding(px(6), px(6), px(6), px(4));
        TextView title = new TextView(this);
        title.setText(getIntent().getStringExtra("title") + ": type the station name");
        title.setTypeface(mono);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        title.setTextColor(DIM);
        root.addView(title);
        final EditText box = new EditText(this);
        box.setSingleLine(true);
        box.setTypeface(mono);
        box.setTextColor(GREEN);
        box.setHintTextColor(DIM);
        box.setHint("e.g. flinders");
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        box.setBackgroundColor(Theme.CELL);
        box.setPadding(px(6), px(4), px(6), px(4));
        root.addView(box);
        hint = new TextView(this);
        hint.setTypeface(mono);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        hint.setTextColor(DIM);
        hint.setPadding(0, px(3), 0, px(3));
        hint.setText("↓ then OK to pick");
        root.addView(hint);
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(SEL));
        adapter = new ArrayAdapter<Transit.Stop>(this, 0, found) {
            @Override public View getView(int i, View v, ViewGroup p) {
                TextView t = v instanceof TextView ? (TextView) v : new TextView(PickActivity.this);
                Transit.Stop st = found.get(i); t.setText(st.name + (st.area == null || st.area.isEmpty() ? "" : "   " + st.area));
                t.setTypeface(mono);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                t.setTextColor(GREEN);
                t.setPadding(px(4), px(7), px(4), px(7));
                return t;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> a, View v, int pos, long id) {
                Transit.Stop s = found.get(pos);
                setResult(RESULT_OK, new Intent().putExtra("id", s.id).putExtra("name", s.name).putExtra("area", s.area));
                finish();
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        box.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                main.removeCallbacks(search);
                if (query.length() >= 2) main.postDelayed(search, 500);   // search once you pause typing
            }
        });
        box.requestFocus();
    }

    private final Runnable search = new Runnable() { public void run() {
        final String q = query;
        final int g = ++gen;
        hint.setText("searching…");
        new Thread(new Runnable() { public void run() {
            List<Transit.Stop> got = null;
            try { got = Transit.search(q); } catch (Exception ignored) { }
            final List<Transit.Stop> fg = got;
            main.post(new Runnable() { public void run() {
                if (g != gen) return;
                found.clear();
                if (fg != null) found.addAll(fg);
                adapter.notifyDataSetChanged();
                hint.setText(fg == null ? "couldn't reach the trains service" : found.isEmpty() ? "no station called that" : "↓ then OK to pick");
            } });
        } }).start();
    } };

    private int px(int dp) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics())); }
}
