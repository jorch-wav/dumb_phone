package dumb_phone.radio;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The station list, plus favourites. Stored as CSV in the app's files ("Genre,-" header lines, then
 * "Name,url" lines; the PyRadio format). The first run starts from the sample in assets/stations.csv.
 * To load your own list: adb push my.csv /sdcard/Android/data/dumb_phone.radio/files/stations.csv
 * (it replaces the list the next time the app opens, then is renamed stations.csv.imported).
 */
final class Stations {
    static final class Station {
        final String name, url, genre;
        Station(String name, String url, String genre) { this.name = name; this.url = url; this.genre = genre; }
    }

    static final class Group {
        final String name;
        final List<Station> stations = new ArrayList<>();
        Group(String name) { this.name = name; }
    }

    final List<Group> groups = new ArrayList<>();
    final List<Station> all = new ArrayList<>();
    private final SharedPreferences prefs;
    private final Set<String> favs;
    private final File mine, pushed;

    Stations(Context c) {
        prefs = c.getSharedPreferences("radio", Context.MODE_PRIVATE);
        mine = new File(c.getFilesDir(), "stations.csv");
        pushed = new File(c.getExternalFilesDir(null), "stations.csv");
        if (pushed.isFile()) {                                   // a list sent over adb replaces ours once
            parse(open(c, pushed, null), groups, all);
            if (!all.isEmpty()) { save(); pushed.renameTo(new File(pushed.getPath() + ".imported")); }
            else { groups.clear(); }
        }
        if (all.isEmpty()) parse(open(c, mine, "stations.csv"), groups, all);
        if (!prefs.contains("favs")) {
            List<Station> seed = new ArrayList<>();
            parse(open(c, new File(c.getExternalFilesDir(null), "favorites.csv"), "favorites.csv"), new ArrayList<Group>(), seed);
            Set<String> s = new HashSet<>();
            for (Station st : seed) s.add(st.url);
            prefs.edit().putStringSet("favs", s).apply();
        }
        favs = new HashSet<>(prefs.getStringSet("favs", new HashSet<String>()));
    }

    private static InputStream open(Context c, File f, String asset) {
        try {
            if (f.isFile()) return new FileInputStream(f);
            return asset == null ? null : c.getAssets().open(asset);
        } catch (Exception e) {
            return null;
        }
    }

    private static void parse(InputStream in, List<Group> groups, List<Station> all) {
        if (in == null) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            Group g = null;
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] f = line.split(",");
                if (f.length < 2) continue;
                String name = f[0].trim(), url = f[1].trim();
                if (url.equals("-")) {
                    g = new Group(name);
                    groups.add(g);
                    continue;
                }
                if (g == null) { g = new Group("Stations"); groups.add(g); }
                Station s = new Station(name, url, g.name);
                g.stations.add(s);
                all.add(s);
            }
        } catch (Exception ignored) {
        }
    }

    /** True when a new list was pushed over adb since this one was read. */
    boolean stale() { return pushed.isFile(); }

    boolean has(String url) { return byUrl(url) != null; }

    /** Add a station under the genre given (a new genre goes to the end). */
    Station add(String name, String url, String genre) {
        Station have = byUrl(url);
        if (have != null) return have;
        Group g = null;
        for (Group x : groups) if (x.name.equalsIgnoreCase(genre)) g = x;
        if (g == null) { g = new Group(genre); groups.add(g); }
        Station s = new Station(name, url, g.name);
        g.stations.add(s);
        all.add(s);
        save();
        return s;
    }

    void remove(Station s) {
        all.remove(s);
        for (java.util.Iterator<Group> it = groups.iterator(); it.hasNext(); ) {
            Group g = it.next();
            g.stations.remove(s);
            if (g.stations.isEmpty()) it.remove();
        }
        if (favs != null && favs.remove(s.url)) prefs.edit().putStringSet("favs", new HashSet<>(favs)).apply();
        save();
    }

    /** Commas would break the CSV, so they become " ·". */
    private static String clean(String v) { return v.replace(",", " ·").replace("\n", " ").trim(); }

    private void save() {
        StringBuilder b = new StringBuilder();
        for (Group g : groups) {
            b.append(clean(g.name)).append(",-\n");
            for (Station s : g.stations) b.append(clean(s.name)).append(',').append(s.url).append('\n');
        }
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(mine)) { out.write(b.toString().getBytes("UTF-8")); }
        catch (Exception ignored) { }
    }

    /** Search the free community directory radio-browser.info: {name, url, genre, details} (call off the main thread). */
    static List<String[]> search(String term) throws Exception {
        String q = java.net.URLEncoder.encode(term, "UTF-8");
        Exception last = null;
        for (String host : new String[]{"de1", "nl1", "at1", "de2"}) {
            java.net.HttpURLConnection c = null;
            try {
                c = (java.net.HttpURLConnection) new java.net.URL("https://" + host + ".api.radio-browser.info/json/stations/search?name=" + q
                        + "&limit=25&hidebroken=true&order=clickcount&reverse=true").openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                c.setRequestProperty("User-Agent", "dumb_phone/1.0");
                StringBuilder body = new StringBuilder();
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    body.append(bo.toString("UTF-8"));
                }
                org.json.JSONArray a = new org.json.JSONArray(body.toString());
                List<String[]> out = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) {
                    org.json.JSONObject o = a.getJSONObject(i);
                    String url = o.optString("url_resolved", "");
                    if (url.isEmpty()) url = o.optString("url", "");
                    String codec = o.optString("codec", "").toUpperCase(java.util.Locale.US);
                    if (url.isEmpty() || codec.contains("FLAC") || codec.contains("OGG")) continue;   // this player can't do those
                    String name = o.optString("name", url).trim();
                    String place = o.optString("state", "").trim();
                    if (place.isEmpty()) place = o.optString("countrycode", "").trim();
                    if (!place.isEmpty() && !name.toLowerCase(java.util.Locale.US).contains(place.toLowerCase(java.util.Locale.US)))
                        name = name + " · " + place;
                    int kbps = o.optInt("bitrate", 0);
                    String details = (codec.isEmpty() ? "" : codec.toLowerCase(java.util.Locale.US)) + (kbps > 0 ? " " + kbps + "k" : "");
                    String tags = o.optString("tags", "");
                    if (!tags.isEmpty()) details += "  ·  " + tags.replace(",", ", ");
                    out.add(new String[]{name, url, tags, details.trim()});
                }
                return out;
            } catch (Exception e) {
                last = e;
            } finally {
                if (c != null) c.disconnect();
            }
        }
        throw last;
    }

    /** Pick the genre for a found station: an existing genre named in its tags, else its first tag. */
    String genreFor(String tags) {
        String[] t = tags.toLowerCase(java.util.Locale.US).split(",");
        for (String tag : t) {
            tag = tag.trim();
            if (tag.isEmpty()) continue;
            for (Group g : groups) if (g.name.toLowerCase(java.util.Locale.US).equals(tag)) return g.name;
        }
        for (String tag : t) {
            tag = tag.trim();
            if (tag.isEmpty()) continue;
            for (Group g : groups) {
                String n = g.name.toLowerCase(java.util.Locale.US);
                if (n.contains(tag) || tag.contains(n)) return g.name;
            }
        }
        String first = t.length > 0 ? t[0].trim() : "";
        if (first.isEmpty()) return "Added";
        return Character.toUpperCase(first.charAt(0)) + first.substring(1);
    }

    boolean isFav(Station s) { return favs.contains(s.url); }

    boolean toggleFav(Station s) {
        if (!favs.remove(s.url)) favs.add(s.url);
        prefs.edit().putStringSet("favs", new HashSet<>(favs)).apply();
        return isFav(s);
    }

    /** The groups to show: everything, or only favourites (still under their genre headers). */
    List<Group> view(boolean favsOnly) {
        if (!favsOnly) return groups;
        List<Group> out = new ArrayList<>();
        for (Group g : groups) {
            Group f = new Group(g.name);
            for (Station s : g.stations) if (isFav(s)) f.stations.add(s);
            if (!f.stations.isEmpty()) out.add(f);
        }
        return out;
    }

    Station byUrl(String url) {
        if (url == null) return null;
        for (Station s : all) if (s.url.equals(url)) return s;
        return null;
    }
}
