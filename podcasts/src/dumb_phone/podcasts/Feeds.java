package dumb_phone.podcasts;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The shows (from an OPML file), their newest episodes (fetched on demand, cached as small JSON files),
 * and what has been played. Feeds are read with a streaming parser that stops after MAX items, so a
 * show with a thousand episodes costs no more than one with ten.
 */
final class Feeds {
    static final int MAX = 10;                   // newest episodes fetched per show at first ("load more" adds 10)
    static final long FRESH = 6 * 3600 * 1000L;  // re-fetch a show when its cache is older than this

    static final class Show {
        final String title, url;
        final List<Episode> episodes = new ArrayList<>();
        long fetched;                            // 0 = never
        String image;                            // cover art URL from the feed
        int limit = MAX;                         // how many episodes to fetch
        boolean more;                            // the feed has more than we fetched
        boolean loading;
        int attempt;                             // bumps per fetch, so a late answer is ignored
        String error;
        Show(String title, String url) { this.title = title; this.url = url; }
    }

    static final class Episode {
        String title, url, show, image;
        long date, duration;                     // ms
    }

    final List<Show> shows = new ArrayList<>();
    private final Context ctx;
    private final SharedPreferences prefs;

    Feeds(Context c) {
        ctx = c;
        prefs = c.getSharedPreferences("pods", Context.MODE_PRIVATE);
        File pushed = new File(c.getExternalFilesDir(null), "feeds.opml");
        File subs = subsFile();
        try (InputStream in = subs.isFile() ? new FileInputStream(subs) : pushed.isFile() ? new FileInputStream(pushed) : c.getAssets().open("feeds.opml")) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, null);
            for (int t = p.getEventType(); t != XmlPullParser.END_DOCUMENT; t = p.next()) {
                if (t == XmlPullParser.START_TAG && "outline".equals(p.getName())) {
                    String url = p.getAttributeValue(null, "xmlUrl");
                    String title = p.getAttributeValue(null, "text");
                    if (title == null) title = p.getAttributeValue(null, "title");
                    if (url != null) shows.add(new Show(title != null ? title : url, url));
                }
            }
        } catch (Exception ignored) {
        }
        for (Show s : shows) readCache(s);
    }

    // ---- subscribing ----

    private File subsFile() { return new File(ctx.getFilesDir(), "subs.opml"); }

    boolean has(String url) {
        for (Show s : shows) if (s.url.equals(url)) return true;
        return false;
    }

    /** Subscribe; the new show goes first so it is easy to find. */
    Show add(String title, String url) {
        for (Show s : shows) if (s.url.equals(url)) return s;
        Show s = new Show(title, url);
        shows.add(0, s);
        saveSubs();
        return s;
    }

    void remove(Show s) {
        shows.remove(s);
        cacheFile(s).delete();
        saveSubs();
    }

    private void saveSubs() {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<opml version=\"2.0\"><head><title>dumb_phone podcasts</title></head><body>\n");
        for (Show s : shows)
            b.append("<outline type=\"rss\" text=\"").append(esc(s.title)).append("\" xmlUrl=\"").append(esc(s.url)).append("\"/>\n");
        b.append("</body></opml>\n");
        try (FileOutputStream out = new FileOutputStream(subsFile())) { out.write(b.toString().getBytes("UTF-8")); } catch (Exception ignored) { }
    }

    private static String esc(String v) {
        return v.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Apple's podcast directory: {title, author, feed url} for a search (call off the main thread). */
    static List<String[]> search(String term) throws Exception {
        String q = java.net.URLEncoder.encode(term, "UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL("https://itunes.apple.com/search?media=podcast&limit=15&term=" + q).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "dumb_phone-podcasts");
        StringBuilder body = new StringBuilder();
        try (InputStream in = c.getInputStream()) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) body.append(new String(b, 0, n, "UTF-8"));
        } finally {
            c.disconnect();
        }
        JSONArray r = new JSONObject(body.toString()).getJSONArray("results");
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < r.length(); i++) {
            JSONObject o = r.getJSONObject(i);
            String feed = o.optString("feedUrl", "");
            if (feed.isEmpty()) continue;
            out.add(new String[]{o.optString("collectionName", feed), o.optString("artistName", ""), feed});
        }
        return out;
    }

    // ---- played / position ----

    long position(String url) { return prefs.getLong("pos:" + url, 0); }

    boolean played(String url) { return prefs.getBoolean("done:" + url, false); }

    void savePosition(String url, long ms, long duration) {
        SharedPreferences.Editor e = prefs.edit().putLong("pos:" + url, ms);
        if (duration > 0 && ms > duration - 60000) e.putBoolean("done:" + url, true).putLong("pos:" + url, 0);
        e.apply();
    }

    void setPlayed(String url, boolean done) {
        prefs.edit().putBoolean("done:" + url, done).putLong("pos:" + url, 0).apply();
    }

    // ---- cache ----

    private File cacheFile(Show s) {
        return new File(ctx.getCacheDir(), "feed-" + Integer.toHexString(s.url.hashCode()) + ".json");
    }

    private void readCache(Show s) {
        File f = cacheFile(s);
        if (!f.isFile()) return;
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; }
            JSONObject o = new JSONObject(new String(b, 0, n, "UTF-8"));
            s.fetched = o.getLong("fetched");
            s.image = o.optString("img", null);
            s.more = o.optBoolean("more", false);
            s.limit = Math.max(MAX, o.optInt("limit", MAX));
            JSONArray a = o.getJSONArray("episodes");
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.getJSONObject(i);
                Episode ep = new Episode();
                ep.title = e.getString("t"); ep.url = e.getString("u"); ep.date = e.optLong("d"); ep.duration = e.optLong("l");
                ep.image = e.optString("i", null);
                ep.show = s.title;
                s.episodes.add(ep);
            }
        } catch (Exception ignored) {
        }
    }

    private void writeCache(Show s, List<Episode> eps) {
        try {
            JSONArray a = new JSONArray();
            for (Episode e : eps) a.put(new JSONObject().put("t", e.title).put("u", e.url).put("d", e.date).put("l", e.duration).put("i", e.image));
            JSONObject o = new JSONObject().put("fetched", System.currentTimeMillis()).put("episodes", a)
                    .put("img", s.image).put("more", s.more).put("limit", s.limit);
            try (FileOutputStream out = new FileOutputStream(cacheFile(s))) { out.write(o.toString().getBytes("UTF-8")); }
        } catch (Exception ignored) {
        }
    }

    boolean stale(Show s) { return System.currentTimeMillis() - s.fetched > FRESH; }

    /** Download and parse the newest episodes (call off the main thread). Returns the new list or throws. */
    List<Episode> fetch(Show s) throws Exception {
        String u = s.url;
        HttpURLConnection c = null;
        for (int hop = 0; hop < 6; hop++) {                 // follow redirects, also http <-> https
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
            if (code >= 400) throw new Exception("server said " + code);
            break;
        }
        List<Episode> out = new ArrayList<>();
        boolean more = false;
        try (InputStream in = new BufferedInputStream(c.getInputStream())) {
            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            p.setInput(in, null);
            Episode cur = null;
            String tag = null;
            boolean channelImage = false;
            for (int t = p.getEventType(); t != XmlPullParser.END_DOCUMENT; t = p.next()) {
                if (t == XmlPullParser.START_TAG) {
                    String n = p.getName();
                    if (("item".equals(n) || "entry".equals(n)) && out.size() >= s.limit) { more = true; break; }   // enough: stop reading
                    if ("itunes:image".equals(n)) {
                        String href = p.getAttributeValue(null, "href");
                        if (href != null) { if (cur != null) cur.image = href; else if (s.image == null || !channelImage) { s.image = href; channelImage = true; } }
                    } else if (cur == null && "url".equals(n) && "image".equals(tag) && !channelImage) {
                        String iu = p.nextText().trim();
                        if (!iu.isEmpty()) s.image = iu;
                        tag = null;
                        continue;
                    }
                    if ("item".equals(n) || "entry".equals(n)) { cur = new Episode(); cur.show = s.title; }
                    else if (cur != null && "enclosure".equals(n)) cur.url = p.getAttributeValue(null, "url");
                    else if (cur != null && "link".equals(n) && "enclosure".equals(p.getAttributeValue(null, "rel")))
                        cur.url = p.getAttributeValue(null, "href");
                    tag = n;
                } else if (t == XmlPullParser.TEXT && cur != null && tag != null) {
                    String v = p.getText().trim();
                    if (v.isEmpty()) continue;
                    if ("title".equals(tag) && cur.title == null) cur.title = v;
                    else if ("pubDate".equals(tag) || "published".equals(tag)) cur.date = parseDate(v);
                    else if ("itunes:duration".equals(tag)) cur.duration = parseDuration(v);
                } else if (t == XmlPullParser.END_TAG) {
                    String n = p.getName();
                    if (("item".equals(n) || "entry".equals(n)) && cur != null) {
                        if (cur.url != null) { if (cur.title == null) cur.title = "episode"; out.add(cur); }
                        cur = null;
                    }
                    tag = null;
                }
            }
        } finally {
            c.disconnect();                                  // stops the download once we have enough
        }
        if (out.isEmpty()) throw new Exception("no episodes found");
        s.more = more;
        writeCache(s, out);
        return out;
    }

    static long parseDate(String v) {
        String[] fmts = {"EEE, dd MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz",
                "dd MMM yyyy HH:mm:ss Z", "yyyy-MM-dd'T'HH:mm:ssX"};
        for (String f : fmts) {
            try {
                Date d = new SimpleDateFormat(f, Locale.US).parse(v);
                if (d != null) return d.getTime();
            } catch (Exception ignored) { }
        }
        return 0;
    }

    static long parseDuration(String v) {
        try {
            String[] parts = v.split(":");
            long s = 0;
            for (String part : parts) s = s * 60 + Long.parseLong(part.trim().split("\\.")[0]);
            return s * 1000;
        } catch (Exception e) {
            return 0;
        }
    }
}
