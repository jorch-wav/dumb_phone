package dumb_phone.phone;

import static dumb_phone.phone.Theme.AMBER;
import static dumb_phone.phone.Theme.CELL;
import static dumb_phone.phone.Theme.DIM;
import static dumb_phone.phone.Theme.GREEN;
import static dumb_phone.phone.Theme.LIT;
import static dumb_phone.phone.Theme.RULE;
import static dumb_phone.phone.Theme.SEL;
import static dumb_phone.phone.Theme.VOID;

import android.Manifest;
import android.app.Activity;
import android.content.ContentProviderOperation;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Calls and contacts in the dumb_phone look: recents | contacts | favs | dial tabs (← →), type on the keypad
 * to search names (T9) and numbers, green key or OK to call, "+ new contact" to add one.
 */
public class PhoneActivity extends Activity {
    static final String[] PERMS = {Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE};
    static final String T9 = "22233344455566677778889999";   // a..z -> keypad digit

    static String g(int cp) { return new String(Character.toChars(cp)); }
    static final String IC_IN = g(0xF03F7), IC_OUT = g(0xF03FB), IC_MISSED = g(0xF03FA),
            IC_ADD = g(0xF0014), IC_CALL = g(0xF03F2), IC_SEARCH = g(0xF0349), IC_MSG = g(0xF0369), IC_STAR = g(0xF04CE), IC_WA = g(0xF05A3);

    // ---- data ----
    static final class Contact {
        long id; String name; boolean fav; final List<String[]> numbers = new ArrayList<>();   // {number, label}; fav = starred
        String t9;                                                                // T9 of each word, space separated
    }
    static final class Call {
        String number, name; int type, count = 1; long date;
    }
    /** A list row: icon, text, right-hand text, colour, what OK does. */
    static final class Row {
        String icon, text, right; int colour = GREEN, iconColour; Object item; Runnable action;   // iconColour 0 = same as text
        Row(String icon, String text, String right, Object item) { this.icon = icon; this.text = text; this.right = right; this.item = item; }
    }

    private Typeface mono, bold, icons;
    private final List<Contact> contacts = new ArrayList<>();
    private final Map<String, Contact> byNumber = new HashMap<>();
    private final List<Call> calls = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private int tab = 1;                   // 0 favs, 1 recents, 2 contacts, 3 dial
    static final int[] ORDER = {1, 2, 0, 3};   // left to right on screen: recents, contacts, favs, dial
    private String dial = "";              // the number being dialled
    /** A person's page: their numbers with call / message, and edit or save. */
    static final class Detail {
        String title; Contact contact; final List<String[]> numbers = new ArrayList<>();
    }
    private boolean loaded;                // contacts + calls read at least once
    private Detail picking;                // the open person page, or null
    private String query = "";
    private TextView tabFavs, tabRecents, tabContacts, tabDial, search, hint;
    private ListView list;
    private final Adapter adapter = new Adapter();
    private FrameLayout root;
    private View main, form;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.load(this);                               // colours of the chosen theme
        mono = Typeface.createFromAsset(getAssets(), "JetBrainsMono-Regular.ttf");
        bold = Typeface.createFromAsset(getAssets(), "JetBrainsMono-Bold.ttf");
        icons = Typeface.createFromAsset(getAssets(), "phosphor-icons.ttf");
        root = new FrameLayout(this);
        root.setBackgroundColor(VOID);
        main = buildMain();
        root.addView(main);
        setContentView(root);
        open(getIntent());
        if (missing()) requestPermissions(PERMS, 1);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        open(i);
        refresh(0);
    }

    /** extras: tab = favs | recents | contacts | dial, dial = digits already typed (from the home screen). */
    private void open(Intent i) {
        if (i == null) return;
        String t = i.getStringExtra("tab"), d = i.getStringExtra("dial");
        if (d != null) { tab = 3; dial = d; picking = null; }
        else if (t != null) { tab = "favs".equals(t) ? 0 : "contacts".equals(t) ? 2 : "dial".equals(t) ? 3 : 1; picking = null; if (tab != 2) query = ""; }
        setIntent(new Intent());           // don't apply it again on the next resume
    }

    @Override protected void onResume() {
        super.onResume();
        if (Theme.changed(this)) { recreate(); return; }   // theme picked in the home app
        if (form == null) hideKeyboard();
        refresh(0);
        loadInBackground();
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        loadInBackground();
    }

    private boolean missing() {
        for (String p : PERMS) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return true;
        return false;
    }

    // ---- layout ----

    private View buildMain() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(8), dp(6), dp(8), dp(4));

        LinearLayout tabs = new LinearLayout(this);
        tabFavs = text(bold, 14, GREEN);
        tabFavs.setText("favs");
        tabRecents = text(bold, 14, GREEN);
        tabRecents.setText("recents");
        tabContacts = text(bold, 14, GREEN);
        tabContacts.setText("contacts");
        tabDial = text(bold, 14, GREEN);
        tabDial.setText("dial");
        final TextView[] byTab = {tabFavs, tabRecents, tabContacts, tabDial};
        for (int k = 0; k < ORDER.length; k++) {
            final TextView t = byTab[ORDER[k]];
            final int index = ORDER[k];
            t.setGravity(Gravity.CENTER);
            t.setFocusable(false);                         // tabs change with ← → only
            t.setPadding(0, dp(2), 0, dp(5));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View x) { setTab(index); }
            });
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.addView(t, new LinearLayout.LayoutParams(-1, -2));
            View line = new View(this);                      // amber bar under the open tab
            t.setTag(line);
            col.addView(line, new LinearLayout.LayoutParams(dp(32), dp(2)));
            tabs.addView(col, new LinearLayout.LayoutParams(-2, -2, 1));   // sized by the word, spare room shared
        }
        v.addView(tabs);
        View rule = new View(this);
        rule.setBackgroundColor(RULE);
        v.addView(rule, new LinearLayout.LayoutParams(-1, 1));

        search = text(mono, 13, DIM);
        search.setSingleLine(true);
        search.setEllipsize(TextUtils.TruncateAt.START);
        search.setPadding(dp(2), dp(5), dp(2), dp(4));
        v.addView(search);

        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(LIT));
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View x, int pos, long id) { activate(rows.get(pos)); }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View x, int pos, long id) {
                Object it = rows.get(pos).item;
                if (it instanceof Contact || it instanceof Call) { openDetail(it); return true; }
                return false;
            }
        });
        v.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        hint = text(mono, 11, DIM);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(5), 0, dp(2));
        v.addView(hint);
        return v;
    }

    private void setTab(int t) {
        if (form != null) return;
        tab = t;
        picking = null;
        if (t != 2) query = "";
        refresh(0);
    }

    // ---- loading ----

    /** Synchronous load (used after saving a contact). */
    private void load() {
        List<Contact> c = new ArrayList<>(); Map<String, Contact> b = new HashMap<>(); List<Call> l = new ArrayList<>();
        loadInto(c, b, l);
        swap(c, b, l);
    }

    private void swap(List<Contact> c, Map<String, Contact> b, List<Call> l) {
        loaded = true;
        contacts.clear(); contacts.addAll(c);
        byNumber.clear(); byNumber.putAll(b);
        calls.clear(); calls.addAll(l);
    }

    /** Opening the app: show the screen at once, fill it in when the contacts and calls are read. */
    private void loadInBackground() {
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Contact> c = new ArrayList<>(); final Map<String, Contact> b = new HashMap<>(); final List<Call> l = new ArrayList<>();
                loadInto(c, b, l);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        int pos = list.getSelectedItemPosition();
                        swap(c, b, l);
                        if (form == null) refresh(Math.max(0, pos));
                    }
                });
            }
        }).start();
    }

    /** Reads contacts and the call log into the given lists (safe off the main thread). */
    private void loadInto(List<Contact> contacts, Map<String, Contact> byNumber, List<Call> calls) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            Map<Long, Contact> byId = new HashMap<>();
            try (Cursor c = getContentResolver().query(ContactsContract.Contacts.CONTENT_URI,
                    new String[]{ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.STARRED},
                    null, null, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " COLLATE NOCASE")) {
                while (c != null && c.moveToNext()) {
                    Contact k = new Contact();
                    k.id = c.getLong(0);
                    k.name = c.getString(1) == null ? "?" : c.getString(1);
                    k.t9 = t9(k.name);
                    k.fav = c.getInt(2) == 1;
                    contacts.add(k);
                    byId.put(k.id, k);
                }
            } catch (Exception x) { android.util.Log.w("dumb_phone-phone", "load", x); }
            try (Cursor c = getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.NUMBER,
                            ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.LABEL},
                    null, null, ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY + " DESC")) {
                java.util.Set<String> seen = new java.util.HashSet<>();   // "id/number": a blocklist contact can hold 800 numbers
                while (c != null && c.moveToNext()) {
                    Contact k = byId.get(c.getLong(0));
                    if (k == null || c.getString(1) == null) continue;
                    String num = c.getString(1), kn = key(num);
                    byNumber.put(kn, k);
                    if (!seen.add(k.id + "/" + kn)) continue;
                    String label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(getResources(), c.getInt(2), c.getString(3)).toString().toLowerCase();
                    k.numbers.add(new String[]{num, label});
                }
            } catch (Exception x) { android.util.Log.w("dumb_phone-phone", "load", x); }
        }
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            try (Cursor c = getContentResolver().query(CallLog.Calls.CONTENT_URI,
                    new String[]{CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE},
                    null, null, CallLog.Calls.DATE + " DESC")) {
                Call last = null;
                for (int n = 0; c != null && c.moveToNext() && n < 300; n++) {
                    Call k = new Call();
                    k.number = c.getString(0) == null ? "" : c.getString(0);
                    k.type = c.getInt(1);
                    k.date = c.getLong(2);
                    Contact who = byNumber.get(key(k.number));
                    k.name = who != null ? who.name : null;
                    // back-to-back calls with the same number and kind fold into one line, like the iPhone
                    if (last != null && key(last.number).equals(key(k.number)) && missed(last.type) == missed(k.type)) { last.count++; continue; }
                    calls.add(k);
                    last = k;
                }
            } catch (Exception x) { android.util.Log.w("dumb_phone-phone", "load", x); }
        }
    }

    static boolean missed(int type) { return type == CallLog.Calls.MISSED_TYPE || type == 5 || type == 6; }  // missed, rejected, blocked

    /** Last 9 digits: matches 0412 345 678 with +61 412 345 678. */
    static String key(String number) {
        String d = number.replaceAll("[^0-9]", "");
        return d.length() > 9 ? d.substring(d.length() - 9) : d;
    }

    static String t9(String name) {
        StringBuilder s = new StringBuilder();
        for (char ch : name.toLowerCase(Locale.ROOT).toCharArray()) {
            if (ch >= 'a' && ch <= 'z') s.append(T9.charAt(ch - 'a'));
            else if (ch >= '0' && ch <= '9') s.append(ch);
            else if (ch == ' ' || ch == '-' || ch == '.') s.append(' ');
        }
        return s.toString();
    }

    /** Normal (multi-tap) typing in the contacts search: each key's letters, then its digit. */
    static final String[] TAPS = {" 0", "1", "abc2", "def3", "ghi4", "jkl5", "mno6", "pqrs7", "tuv8", "wxyz9"};
    private int tapKey = -1, tapIdx;
    private long tapAt;

    /** Letters typed: a word of the name starts with them, or the name contains them. Digits only: T9 + number. */
    private boolean matches(Contact k, String q) {
        if (q.isEmpty()) return true;
        if (q.matches(".*[a-z].*")) {
            String name = k.name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "");
            for (String w : name.split(" +")) if (w.startsWith(q)) return true;
            return name.startsWith(q) || (q.length() >= 2 && name.contains(q));
        }
        String digits = q.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return false;
        for (String w : k.t9.split(" ")) if (w.startsWith(digits)) return true;
        if (k.t9.replace(" ", "").startsWith(digits)) return true;
        for (String[] n : k.numbers) if (n[0].replaceAll("[^0-9]", "").contains(digits)) return true;
        return false;
    }

    // ---- rows ----

    private void refresh(int select) {
        rows.clear();
        TextView[] tabViews = {tabFavs, tabRecents, tabContacts, tabDial};
        for (int k = 0; k < tabViews.length; k++) { tabViews[k].setTextColor(tab == k ? AMBER : DIM); underline(tabViews[k], tab == k); }
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, picking != null ? 18 : tab == 3 ? 22 : 13);
        search.setTypeface(picking != null ? bold : mono);
        if (missing()) {
            Row r = new Row(IC_SEARCH, "allow access to contacts and calls", "", null);
            r.colour = AMBER;
            r.action = new Runnable() { public void run() { requestPermissions(PERMS, 1); } };
            rows.add(r);
        } else if (picking != null) {
            search.setText(picking.title);
            search.setTextColor(AMBER);
            for (final String[] n : picking.numbers) {
                String what = n[1] == null || n[1].isEmpty() ? "" : " " + n[1];
                Row c = new Row(IC_CALL, "call" + what, n[0], null);
                c.colour = AMBER;
                c.action = new Runnable() { public void run() { call(n[0]); } };
                rows.add(c);
                Row m = new Row(IC_MSG, "message" + what, n[0], null);
                m.action = new Runnable() { public void run() { message(n[0]); } };
                rows.add(m);
                if (hasWhatsApp()) {
                    Row w = new Row(IC_WA, "whatsapp" + what, n[0], null);
                    w.action = new Runnable() { public void run() { whatsapp(n[0]); } };
                    rows.add(w);
                }
            }
            final Detail d = picking;
            if (d.contact != null) {
                Row star = new Row(IC_STAR, d.contact.fav ? "remove from favourites" : "add to favourites", "", null);
                star.action = new Runnable() { public void run() { toggleFav(d.contact); } };
                rows.add(star);
                Row e = new Row(IC_ADD, "edit contact", "", null);
                e.action = new Runnable() { public void run() { openInContactsApp(d.contact); } };
                rows.add(e);
            } else if (!d.numbers.isEmpty()) {
                final String num = d.numbers.get(0)[0];
                Row add = new Row(IC_ADD, "new contact", "", null);
                add.action = new Runnable() { public void run() { showForm("", num); } };
                rows.add(add);
                Row ex = new Row(IC_ADD, "add to a contact", "", null);
                ex.action = new Runnable() { public void run() { addToExisting(num); } };
                rows.add(ex);
            }
        } else if (tab == 0) {
            search.setText("favourites");
            search.setTextColor(DIM);
            for (Contact k : contacts) if (k.fav) {
                Row r = new Row(IC_STAR, k.name, k.numbers.size() == 1 ? "" : k.numbers.size() + " nos ", k);
                r.iconColour = AMBER;
                rows.add(r);
            }
            if (rows.isEmpty()) {
                rows.add(new Row("", loaded ? "no favourites yet" : "loading…", "", null));
                if (loaded) rows.add(new Row("", "in contacts: * on a name", "", null));
            }
        } else if (tab == 3) {
            search.setText(dial.isEmpty() ? "type a number" : dial);
            search.setTextColor(dial.isEmpty() ? DIM : AMBER);
            final String d = dial;
            if (!d.isEmpty()) {
                Row r = new Row(IC_CALL, "call", "", null);
                r.colour = AMBER;
                r.action = new Runnable() { public void run() { call(d); } };
                rows.add(r);
                Row msg = new Row(IC_MSG, "message", "", null);
                msg.action = new Runnable() { public void run() { message(d); } };
                rows.add(msg);
                Row add = new Row(IC_ADD, "new contact", "", null);
                add.action = new Runnable() { public void run() { showForm("", d); } };
                rows.add(add);
                Row existing = new Row(IC_ADD, "add to a contact", "", null);
                existing.action = new Runnable() { public void run() { addToExisting(d); } };
                rows.add(existing);
                int n = 0;
                for (Contact k : contacts) if (n < 6 && matches(k, d)) { rows.add(new Row("", k.name, "", k)); n++; }
            }
        } else if (tab == 1) {
            search.setText("type a number or name to search");
            search.setTextColor(DIM);
            for (Call k : calls) {
                boolean miss = missed(k.type);
                String icon = miss ? IC_MISSED : k.type == CallLog.Calls.OUTGOING_TYPE ? IC_OUT : IC_IN;
                String who = (k.name != null ? k.name : k.number.isEmpty() ? "unknown" : k.number) + (k.count > 1 ? " (" + k.count + ")" : "");
                Row r = new Row(icon, who, when(k.date), k);
                if (miss) r.colour = AMBER;
                rows.add(r);
            }
            if (calls.isEmpty()) rows.add(new Row("", loaded ? "no calls yet" : "loading…", "", null));
        } else {
            search.setText(query.isEmpty() ? "type a name (2 = a, 22 = b …)" : "search: " + query + "_");
            search.setTextColor(query.isEmpty() ? DIM : GREEN);
            final String q = query;
            if (q.replaceAll("[^0-9+*#]", "").length() >= 3) {
                Row r = new Row(IC_CALL, "call " + q, "", null);
                r.colour = AMBER;
                r.action = new Runnable() { public void run() { call(q); } };
                rows.add(r);
            }
            final boolean named = q.matches(".*[a-z].*");
            Row add = new Row(IC_ADD, q.isEmpty() ? "new contact" : "save " + q, "", null);
            add.action = new Runnable() { public void run() {
                if (named) showForm(q.substring(0, 1).toUpperCase(Locale.ROOT) + q.substring(1), "");
                else showForm("", q.replaceAll("[^0-9+*#]", ""));
            } };
            rows.add(add);
            List<Row> later = new ArrayList<>();          // word starts first, names that only contain it after
            for (Contact k : contacts) {
                if (!matches(k, q)) continue;
                Row r = new Row(k.fav ? IC_STAR : "", k.name, "", k);
                r.iconColour = AMBER;
                if (named && !(" " + k.name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "")).contains(" " + q)) later.add(r); else rows.add(r);
            }
            rows.addAll(later);
        }
        hint.setText(form != null ? "" : picking != null ? (picking.contact != null ? "[OK] choose  [*] star  [back] back" : "[OK] choose   [back] back")
                : tab == 0 ? "[OK] open  [call] call  [*] unstar"
                : tab == 1 ? "[OK] call back  [hold OK] more"
                : tab == 2 ? (query.isEmpty() ? "[OK] open  [call] call  [*] star" : "[OK] open  [*] star  [back] delete")
                : "[OK] pick  [back] delete  [hold 0] +");
        adapter.notifyDataSetChanged();
        if (!rows.isEmpty()) list.setSelection(Math.max(0, Math.min(select, rows.size() - 1)));
    }

    private void underline(TextView t, boolean on) {
        ((View) t.getTag()).setBackgroundColor(on ? AMBER : VOID);
    }

    private String when(long t) {
        Calendar now = Calendar.getInstance(), c = Calendar.getInstance();
        c.setTimeInMillis(t);
        int days = (now.get(Calendar.YEAR) - c.get(Calendar.YEAR)) * 365 + now.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR);
        String f = days == 0 ? "HH:mm" : days < 7 ? "EEE" : "d MMM";
        if (days == 1) return "yest";
        return new SimpleDateFormat(f, Locale.getDefault()).format(new Date(t)).toLowerCase();
    }

    /** OK: a recent call rings back; a favourite is called; a contact opens their page. */
    private void activate(Row r) {
        if (r.action != null) { r.action.run(); return; }
        if (r.item instanceof Call) call(((Call) r.item).number);
        else if (r.item instanceof Contact) openDetail(r.item);
    }

    /** The green key: call straight away (a contact with several numbers opens their page). */
    private void quickCall(Row r) {
        if (r.item instanceof Contact) {
            Contact k = (Contact) r.item;
            if (k.numbers.size() == 1) call(k.numbers.get(0)[0]);
            else openDetail(k);
        } else {
            activate(r);
        }
    }

    private void openDetail(Object item) {
        Detail d = new Detail();
        if (item instanceof Contact) {
            d.contact = (Contact) item;
            d.title = d.contact.name;
            d.numbers.addAll(d.contact.numbers);
            if (d.numbers.isEmpty()) { toast(d.title + " has no number"); }
        } else if (item instanceof Call) {
            Call k = (Call) item;
            d.contact = byNumber.get(key(k.number));
            d.title = d.contact != null ? d.contact.name : k.number;
            if (d.contact != null) d.numbers.addAll(d.contact.numbers);
            else d.numbers.add(new String[]{k.number, ""});
        } else return;
        picking = d;
        refresh(0);
    }

    /** Star or unstar a contact (Android's own "favourite", so other apps see it too). */
    private void toggleFav(Contact k) {
        android.content.ContentValues v = new android.content.ContentValues();
        v.put(ContactsContract.Contacts.STARRED, k.fav ? 0 : 1);
        try {
            getContentResolver().update(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, k.id), v, null, null);
            k.fav = !k.fav;
            toast(k.fav ? "★ " + k.name + " added to favourites" : k.name + " removed from favourites");
        } catch (Exception e) {
            toast("couldn't change favourites");
        }
        int pos = list.getSelectedItemPosition();
        refresh(Math.max(0, tab == 0 && picking == null ? Math.min(pos, rows.size() - 2) : pos));
    }

    private boolean hasWhatsApp() {
        try { getPackageManager().getPackageInfo("com.whatsapp", 0); return true; } catch (Exception e) { return false; }
    }

    /** Open a WhatsApp chat with this number (made international with the SIM's country, e.g. 0412… -> 61412…). */
    private void whatsapp(String number) {
        String iso = ((android.telephony.TelephonyManager) getSystemService(TELEPHONY_SERVICE)).getSimCountryIso();
        if (iso == null || iso.isEmpty()) iso = Locale.getDefault().getCountry();
        String e164 = android.telephony.PhoneNumberUtils.formatNumberToE164(number, iso.toUpperCase(Locale.ROOT));
        String digits = (e164 != null ? e164 : number).replaceAll("[^0-9]", "");
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=" + digits)).setPackage("com.whatsapp"));
        } catch (Exception e) {
            toast("couldn't open WhatsApp");
        }
    }

    private void message(String number) {
        try {
            startActivity(new Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)));
        } catch (Exception e) {
            toast("no messages app");
        }
    }

    private void call(String number) {
        if (number == null || number.isEmpty()) return;
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            startActivity(new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)));
            return;
        }
        if (android.telephony.PhoneNumberUtils.isEmergencyNumber(number)) {
            // apps may not call emergency numbers themselves: the phone's dial screen opens with it typed in
            startActivity(new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)));
            android.widget.Toast.makeText(this, number + ": press the green key to call", android.widget.Toast.LENGTH_LONG).show();
        } else startActivity(new Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)));
        picking = null;
        query = "";
        dial = "";
    }

    /** The system's "add this number to a contact" picker. */
    private void addToExisting(String number) {
        try {
            startActivity(new Intent(Intent.ACTION_INSERT_OR_EDIT).setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                    .putExtra(ContactsContract.Intents.Insert.PHONE, number));
        } catch (Exception e) {
            toast("no contacts app for that");
        }
    }

    private void openInContactsApp(Contact k) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, k.id)));
        } catch (Exception e) {
            toast("no contacts app to edit with");
        }
    }

    // ---- new contact ----

    private EditText nameField, numberField;

    private void showForm(String name, String number) {
        if (form != null) return;
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setBackgroundColor(VOID);
        f.setPadding(dp(10), dp(8), dp(10), dp(6));
        TextView title = text(bold, 15, AMBER);
        title.setText("new contact");
        title.setPadding(0, 0, 0, dp(8));
        f.addView(title);
        f.addView(label("name"));
        nameField = field(name, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        f.addView(nameField);
        f.addView(label("number"));
        numberField = field(number, InputType.TYPE_CLASS_PHONE);
        f.addView(numberField);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(0, dp(12), 0, 0);
        TextView save = button("save"), cancel = button("cancel");
        save.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View x) { saveContact(); } });
        cancel.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View x) { hideForm(); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMargins(0, 0, dp(6), 0);
        buttons.addView(save, lp);
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1));
        f.addView(buttons);
        form = f;
        root.addView(form);
        main.setVisibility(View.GONE);
        nameField.requestFocus();
    }

    private void hideForm() {
        if (form == null) return;
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(form.getWindowToken(), 0);
        root.removeView(form);
        form = null;
        main.setVisibility(View.VISIBLE);
        refresh(0);
    }

    private void saveContact() {
        String name = nameField.getText().toString().trim(), number = numberField.getText().toString().trim();
        if (name.isEmpty() || number.isEmpty()) { toast("needs a name and a number"); return; }
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        String[] acct = syncAccount();                 // a synced account (e.g. DAVx5 / Google) if there is one
        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, acct == null ? null : acct[0])
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, acct == null ? null : acct[1]).build());
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name).build());
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE).build());
        try {
            getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
        } catch (Exception e) {
            toast("couldn't save: " + e.getMessage());
            return;
        }
        toast(acct != null ? "saved " + name + " (synced)" : "saved " + name);
        query = "";
        tab = 2;
        hideForm();
        load();
        refresh(0);
        for (int i = 0; i < rows.size(); i++)
            if (rows.get(i).item instanceof Contact && ((Contact) rows.get(i).item).name.equals(name)) list.setSelection(i);
    }

    static final String DAVX5_BOOK = "at.bitfire.davdroid.address_book";

    /** {type, name} of a synced address book (e.g. DAVx5), or null to save on the phone only. */
    private String[] syncAccount() {
        try (Cursor c = getContentResolver().query(ContactsContract.RawContacts.CONTENT_URI,
                new String[]{ContactsContract.RawContacts.ACCOUNT_NAME},
                ContactsContract.RawContacts.ACCOUNT_TYPE + "=?", new String[]{DAVX5_BOOK}, null)) {
            if (c != null && c.moveToFirst()) return new String[]{DAVX5_BOOK, c.getString(0)};
        } catch (Exception ignored) { }
        return null;
    }

    private TextView label(String s) {
        TextView t = text(mono, 11, DIM);
        t.setText(s);
        t.setPadding(dp(2), dp(6), 0, dp(2));
        return t;
    }

    private EditText field(String value, int type) {
        EditText e = new EditText(this);
        e.setTypeface(mono);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setTextColor(GREEN);
        e.setHintTextColor(DIM);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setText(value);
        e.setPadding(dp(6), dp(6), dp(6), dp(6));
        final GradientDrawable d = new GradientDrawable();
        d.setColor(CELL);
        d.setStroke(dp(1), RULE);
        e.setBackground(d);
        e.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) { d.setStroke(dp(1), has ? GREEN : RULE); }
        });
        return e;
    }

    private TextView button(String s) {
        final TextView t = text(bold, 14, GREEN);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(8), 0, dp(8));
        t.setFocusable(true);
        t.setClickable(true);
        final GradientDrawable d = new GradientDrawable();
        d.setColor(CELL);
        d.setStroke(dp(1), CELL);
        t.setBackground(d);
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                d.setColor(has ? LIT : CELL);
                d.setStroke(dp(1), has ? GREEN : CELL);
                t.setTextColor(has ? AMBER : GREEN);
            }
        });
        return t;
    }

    // ---- keys ----

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (form != null) {
            if (code == KeyEvent.KEYCODE_BACK) { hideForm(); return true; }
            return super.onKeyDown(code, e);
        }
        char ch = 0;
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) ch = (char) ('0' + code - KeyEvent.KEYCODE_0);
        else if (code == KeyEvent.KEYCODE_POUND) ch = '#';
        else if (code == KeyEvent.KEYCODE_STAR) ch = '*';
        if (code == KeyEvent.KEYCODE_PLUS) ch = '+';
        if (code == KeyEvent.KEYCODE_STAR && picking != null && picking.contact != null) { toggleFav(picking.contact); return true; }
        if (code == KeyEvent.KEYCODE_STAR && picking == null && (tab == 0 || tab == 2)) {
            Object it = selected();                      // favs or contacts (searching or not): * stars / unstars
            if (it instanceof Contact) toggleFav((Contact) it);
            else if (tab == 2) toast("pick a name first, then *");
            return true;
        }
        if (ch != 0 && picking == null) {
            if (tab == 0) { tab = 2; query = ""; }       // favs: typing searches names on the contacts tab
            if (tab == 2) {
                if (ch < '0' || ch > '9') return true;
                int d = ch - '0';
                if (e.getRepeatCount() > 0) {                // hold a key = its digit
                    if (e.getRepeatCount() == 1 && !query.isEmpty()) { query = query.substring(0, query.length() - 1) + ch; tapKey = -1; refresh(0); }
                    return true;
                }
                long now = android.os.SystemClock.uptimeMillis();
                if (d == tapKey && now - tapAt < 900 && !query.isEmpty()) {   // same key again quickly: next letter
                    tapIdx = (tapIdx + 1) % TAPS[d].length();
                    query = query.substring(0, query.length() - 1) + TAPS[d].charAt(tapIdx);
                } else {
                    tapIdx = 0;
                    query += TAPS[d].charAt(0);
                }
                tapKey = d;
                tapAt = now;
            } else {                                     // recents or dial: type a number
                if (tab != 3) { tab = 3; dial = ""; }
                if (ch == '0' && e.getRepeatCount() > 0) {  // hold 0 = +
                    if (e.getRepeatCount() == 1 && dial.endsWith("0")) dial = dial.substring(0, dial.length() - 1) + "+";
                    return true;
                }
                dial += ch;
            }
            refresh(0);
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_STAR: {                 // recents: save the number
                Object it = selected();
                if (it instanceof Call && ((Call) it).name == null) showForm("", ((Call) it).number);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_LEFT: if (picking == null) setTab(ORDER[Math.max(0, place(tab) - 1)]); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (picking == null) setTab(ORDER[Math.min(ORDER.length - 1, place(tab) + 1)]); return true;
            case KeyEvent.KEYCODE_CALL: {
                int pos = list.getSelectedItemPosition();
                if (pos >= 0 && pos < rows.size()) quickCall(rows.get(pos));
                else if (tab == 3 && !dial.isEmpty()) call(dial);
                else if (!query.isEmpty()) call(query);
                else if (!rows.isEmpty()) activate(rows.get(0));
                return true;
            }
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_BACK:
                if (picking != null) { picking = null; refresh(0); return true; }
                if (tab == 3 && !dial.isEmpty()) { dial = dial.substring(0, dial.length() - 1); refresh(0); return true; }
                if (tab == 2 && !query.isEmpty()) { query = query.substring(0, query.length() - 1); tapKey = -1; refresh(0); return true; }
                break;
        }
        return super.onKeyDown(code, e);
    }

    /** Where tab t sits on screen (0 = leftmost). */
    private static int place(int t) {
        for (int i = 0; i < ORDER.length; i++) if (ORDER[i] == t) return i;
        return 0;
    }

    private Object selected() {
        int pos = list.getSelectedItemPosition();
        return pos >= 0 && pos < rows.size() ? rows.get(pos).item : null;
    }

    // ---- helpers ----

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private TextView text(Typeface tf, int size, int color) {
        TextView t = new TextView(this);
        t.setTypeface(tf);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(color);
        return t;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View v, ViewGroup parent) {
            LinearLayout row = (LinearLayout) v;
            if (row == null) {
                row = new LinearLayout(PhoneActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(4), dp(7), dp(4), dp(7));
                TextView icon = text(icons, 15, GREEN);
                icon.setMinWidth(dp(22));
                row.addView(icon);
                TextView name = text(mono, 15, GREEN);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                row.addView(text(mono, 12, DIM));
            }
            Row r = rows.get(i);
            TextView icon = (TextView) row.getChildAt(0), name = (TextView) row.getChildAt(1), right = (TextView) row.getChildAt(2);
            icon.setText(r.icon);
            icon.setTextColor(r.iconColour != 0 ? r.iconColour : r.colour);
            icon.setVisibility(r.icon.isEmpty() && tab <= 1 ? View.GONE : View.VISIBLE);
            name.setText(r.text);
            name.setTextColor(r.colour);
            right.setText(r.right);
            return row;
        }
    }

    /** Close the keyboard if another app left it open (it would sit over this screen). */
    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
    }

}
