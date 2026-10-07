package dumb_phone.home;

import android.app.Notification;
import android.app.PendingIntent;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;

/**
 * The phone's new notifications, for the home screen's count and the panel (the phone has no status
 * bar to pull down). Two ways in:
 * - as a notification listener (normal Android; setup.sh switches it on), which also knows when one goes away;
 * - from the key service's accessibility events (Android Go blocks listeners), where an app's
 *   notifications are cleared once you open that app.
 * The accessibility list is saved (files/notifications.json, private to the app) so it survives the app
 * being restarted or updated; tap targets (PendingIntents) can't be saved, so restored ones open the app.
 */
public class Notifications extends NotificationListenerService {
    static final class Item {
        final String pkg, key, title, text;
        final long time;
        final PendingIntent open;
        final boolean autoCancel;
        Item(String pkg, String key, String title, String text, long time, PendingIntent open, boolean autoCancel) {
            this.pkg = pkg; this.key = key; this.title = title; this.text = text; this.time = time; this.open = open; this.autoCancel = autoCancel;
        }
    }

    static Notifications instance;
    private static final List<Item> current = new ArrayList<>();
    static Runnable onChange;                                    // the home screen redraws its count / list

    // ---- listener (normal Android) ----

    @Override public void onListenerConnected() { instance = this; refresh(); }
    @Override public void onListenerDisconnected() { if (instance == this) instance = null; }
    @Override public void onNotificationPosted(StatusBarNotification sbn) { refresh(); }
    @Override public void onNotificationRemoved(StatusBarNotification sbn) { refresh(); }

    private void refresh() {
        List<Item> keep = new ArrayList<>();
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all != null) for (StatusBarNotification s : all) {
                if (s.isOngoing() || summaryWithChildren(s, all)) continue;
                Item it = item(s.getPackageName(), s.getKey(), s.getNotification(), s.getPostTime());
                if (it != null) keep.add(it);
            }
        } catch (Exception ignored) { }
        synchronized (current) { current.clear(); current.addAll(keep); sort(); }
        changed();
    }

    private static boolean summaryWithChildren(StatusBarNotification s, StatusBarNotification[] all) {
        if ((s.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) == 0) return false;
        for (StatusBarNotification o : all)
            if (o != s && o.getGroupKey() != null && o.getGroupKey().equals(s.getGroupKey())) return true;
        return false;
    }

    // ---- accessibility events (Android Go) ----

    /** A notification was posted (seen by the key service). */
    /** A notification arrived (accessibility path). True = it's new news (worth a sound). */
    static boolean posted(String pkg, Notification n) {
        if (n == null || pkg == null) return false;
        if (pkg.equals("android") || pkg.equals("com.android.systemui")) return false;
        if ((n.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE | Notification.FLAG_GROUP_SUMMARY)) != 0) return false;
        String key = pkg.equals(TELECOM) ? pkg : pkg + "|" + text(n, Notification.EXTRA_TITLE);   // missed calls: one line that updates
        if (pkg.equals(TELECOM) && n.deleteIntent != null) missedClear = n.deleteIntent;   // Android's own "clear missed calls"
        Item it = item(pkg, key, n, System.currentTimeMillis());
        if (it == null) return false;
        boolean fresh = true;
        synchronized (current) {
            for (int i = current.size() - 1; i >= 0; i--) if (current.get(i).key.equals(it.key)) {
                Item old = current.get(i);
                if ((n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0 || String.valueOf(old.text).equals(String.valueOf(it.text))) fresh = false;
                if (instance == null) current.remove(i);            // newer replaces
            }
            if (instance != null) return fresh;                     // the listener keeps the list itself
            current.add(it);
            sort();
            while (current.size() > 30) current.remove(current.size() - 1);
        }
        save();
        changed();
        return fresh;
    }

    /** You opened this app: its notifications count as seen. */
    static void opened(String pkg) {
        if (instance != null || pkg == null) return;
        boolean any = false;
        // our Contacts app = missed calls seen (not the stock dialer: it is also the call screen during every call)
        boolean calls = pkg.equals("dumb_phone.phone");
        synchronized (current) {
            for (int i = current.size() - 1; i >= 0; i--)
                if (current.get(i).pkg.equals(pkg) || (calls && current.get(i).pkg.equals(TELECOM))) { current.remove(i); any = true; }
        }
        if (calls) clearMissed();
        if (any) { save(); changed(); }
    }

    // ---- shared ----

    private static Item item(String pkg, String key, Notification n, long time) {
        if (n == null || n.extras == null) return null;
        String title = text(n, Notification.EXTRA_TITLE);
        if (title.isEmpty()) return null;
        String body = text(n, Notification.EXTRA_TEXT);
        return new Item(pkg, key, title, body, time, n.contentIntent, (n.flags & Notification.FLAG_AUTO_CANCEL) != 0);
    }

    private static String text(Notification n, String k) {
        CharSequence c = n.extras == null ? null : n.extras.getCharSequence(k);
        return c == null ? "" : c.toString();
    }

    private static void sort() {
        java.util.Collections.sort(current, new java.util.Comparator<Item>() {
            @Override public int compare(Item a, Item b) { return Long.compare(b.time, a.time); }
        });
    }

    private static void changed() { if (onChange != null) onChange.run(); }

    static boolean available() { return true; }

    static int count() { synchronized (current) { return current.size(); } }

    static List<Item> list() { synchronized (current) { return new ArrayList<>(current); } }

    static void dismiss(Item it) {
        if (instance != null) { try { instance.cancelNotification(it.key); } catch (Exception ignored) { } return; }
        synchronized (current) { current.remove(it); }
        if (TELECOM.equals(it.pkg)) clearMissed();
        save();
        changed();
    }

    static void dismissAll() {
        if (instance != null) { try { instance.cancelAllNotifications(); } catch (Exception ignored) { } return; }
        synchronized (current) { current.clear(); }
        clearMissed();
        save();
        changed();
    }

    static final String TELECOM = "com.android.server.telecom";   // posts the "missed call" notification
    private static PendingIntent missedClear;

    /** Seen the missed calls: have Android clear its own notice too (and the LED), with the notification's
     *  own delete action (only the default dialer may clear it directly; ours isn't one). */
    static void clearMissed() {
        if (missedClear == null) return;
        try { missedClear.send(); android.util.Log.i("dumb_phone-home", "missed calls cleared"); } catch (Exception ignored) { }
        missedClear = null;
    }
    private static java.io.File store;

    /** From the key service at start: where to keep the list, and what was there before a restart. */
    static void init(android.content.Context c) {
        store = new java.io.File(c.getFilesDir(), "notifications.json");
        if (instance != null || !store.isFile()) return;
        try {
            byte[] b = new byte[(int) store.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(store)) { int o = 0, n; while (o < b.length && (n = in.read(b, o, b.length - o)) > 0) o += n; }
            org.json.JSONArray a = new org.json.JSONArray(new String(b, "UTF-8"));
            List<Item> got = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                got.add(new Item(o.getString("p"), o.getString("k"), o.getString("t"), o.optString("x"), o.getLong("w"), null, true));
            }
            synchronized (current) {
                for (Item it : got) { boolean dup = false; for (Item x : current) if (x.key.equals(it.key)) dup = true; if (!dup) current.add(it); }
                sort();
            }
            changed();
        } catch (Exception ignored) { }
    }

    private static void save() {
        if (store == null || instance != null) return;
        try {
            org.json.JSONArray a = new org.json.JSONArray();
            synchronized (current) {
                for (Item it : current) a.put(new org.json.JSONObject().put("p", it.pkg).put("k", it.key).put("t", it.title).put("x", it.text).put("w", it.time));
            }
            java.io.File tmp = new java.io.File(store.getPath() + ".tmp");
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(tmp)) { o.write(a.toString().getBytes("UTF-8")); }
            tmp.renameTo(store);
        } catch (Exception ignored) { }
    }
}
