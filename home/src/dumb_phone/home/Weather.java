package dumb_phone.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

/**
 * Weather for the panel: now + 3 days from open-meteo.com (free, no account), at the phone's rough
 * location (network, not GPS). Fetched at most once an hour and kept, so opening the panel costs nothing.
 */
final class Weather {
    private static final long FRESH = 3_600_000L;
    private static boolean busy;

    private static Panel.Strip strip;                    // the panel's hour strip (keeps its scroll position while open)

    static void addTo(final HomeActivity h, final Panel p) {
        if (strip != null) strip.offset = 0;
        p.sources.add(new Panel.RowSource() {
            @Override public void addRows(List<Panel.Row> out) {
                SharedPreferences pr = h.getSharedPreferences("weather", Context.MODE_PRIVATE);
                final String json = pr.getString("json", null);
                long at = pr.getLong("at", 0);
                out.add(new Panel.Header("weather"));
                if (json == null) out.add(new Panel.Info(busy ? "loading…" : "no forecast yet (needs internet)"));
                else panelRows(h, json, out);
                if (System.currentTimeMillis() - at > FRESH || (json != null && !json.contains("\"hourly\""))) fetch(h, p);
            }
        });
    }

    private static void panelRows(final HomeActivity h, String json, List<Panel.Row> out) {
        try {
            final JSONObject o = uptoNow(new JSONObject(json));
            JSONObject cur = o.getJSONObject("current");
            JSONObject d = o.getJSONObject("daily");
            int code = cur.getInt("weather_code");
            String today = Math.round(d.getJSONArray("temperature_2m_max").getDouble(0)) + "° / "
                    + Math.round(d.getJSONArray("temperature_2m_min").getDouble(0)) + "°";
            out.add(new Panel.Line(glyph(code, cur.optInt("is_day", 1) == 1),
                    Math.round(cur.getDouble("temperature_2m")) + "°  " + words(code), today, true));
            if (o.has("hourly")) {
                if (strip == null) strip = new Panel.Strip();
                strip.cols.clear();
                int start = nowIndex(o);
                JSONObject hr = o.getJSONObject("hourly");
                JSONArray t = hr.getJSONArray("time");
                for (int i = start; i < Math.min(t.length(), start + 12); i++)
                    strip.cols.add(new String[]{i == start ? "now" : t.getString(i).substring(11, 13),
                            String.valueOf(hourGlyph(o, i)), Math.round(hr.getJSONArray("temperature_2m").getDouble(i)) + "°"});
                strip.open = new Runnable() { public void run() { h.startActivity(new android.content.Intent(h, WeatherActivity.class)); } };
                out.add(strip);
            }
        } catch (Exception e) {
            out.add(new Panel.Info("couldn't read the forecast"));
        }
    }

    /** Index in the hourly arrays of the current hour (or the first hour after now). */
    static int nowIndex(JSONObject o) {
        try {
            JSONArray t = o.getJSONObject("hourly").getJSONArray("time");
            String now = new SimpleDateFormat("yyyy-MM-dd'T'HH", Locale.US).format(new java.util.Date());
            for (int i = 0; i < t.length(); i++) if (t.getString(i).compareTo(now) >= 0 || t.getString(i).startsWith(now)) return i;
            return Math.max(0, t.length() - 1);
        } catch (Exception ignored) { }
        return 0;
    }

    /** A saved forecast as it stands now: days before today are dropped (so "today" is today, not the day
     *  it was fetched), and when the saved "current" reading is from an earlier hour, "now" is taken from
     *  this hour's forecast. Saves a fetch: nothing new is downloaded. */
    static JSONObject uptoNow(JSONObject o) {
        try {
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
            String hour = new SimpleDateFormat("yyyy-MM-dd'T'HH", Locale.US).format(new java.util.Date());
            JSONObject d = o.optJSONObject("daily");
            if (d != null && d.has("time")) {
                JSONArray t = d.getJSONArray("time");
                int skip = 0;
                while (skip < t.length() - 1 && t.getString(skip).compareTo(today) < 0) skip++;
                if (skip > 0) {
                    java.util.Iterator<String> keys = d.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        JSONArray a = d.optJSONArray(k), b = new JSONArray();
                        if (a == null) continue;
                        for (int i = skip; i < a.length(); i++) b.put(a.get(i));
                        d.put(k, b);
                    }
                }
            }
            JSONObject cur = o.optJSONObject("current"), hr = o.optJSONObject("hourly");
            if (cur != null && hr != null && !cur.optString("time").startsWith(hour)) {
                int i = nowIndex(o);
                cur.put("temperature_2m", hr.getJSONArray("temperature_2m").getDouble(i));
                cur.put("weather_code", hr.getJSONArray("weather_code").getInt(i));
                cur.put("is_day", hr.getJSONArray("is_day").optInt(i, 1));
                cur.put("time", hr.getJSONArray("time").getString(i));
            }
        } catch (Exception ignored) { }
        return o;
    }

    /** "15:00  14°  40%" for hourly index i. */
    static String hourText(JSONObject o, int i) {
        try {
            JSONObject hr = o.getJSONObject("hourly");
            String t = hr.getJSONArray("time").getString(i).substring(11, 16);
            int rain = hr.getJSONArray("precipitation_probability").optInt(i, 0);
            return t + "  " + Math.round(hr.getJSONArray("temperature_2m").getDouble(i)) + "°" + (rain >= 20 ? "  " + rain + "%" : "");
        } catch (Exception e) { return "?"; }
    }

    static int hourGlyph(JSONObject o, int i) {
        try {
            JSONObject hr = o.getJSONObject("hourly");
            return glyph(hr.getJSONArray("weather_code").getInt(i), hr.getJSONArray("is_day").optInt(i, 1) == 1);
        } catch (Exception e) { return 0xF0590; }
    }

    /** The place name shown in the Weather app ("" if unknown). */
    static String place(Context c) {
        SharedPreferences pr = c.getSharedPreferences("weather", Context.MODE_PRIVATE);
        String n = pr.getString("place", "");
        return (n.isEmpty() ? "your area" : n) + (pr.getBoolean("manual", false) ? "" : " (automatic)");
    }

    /** The place as a short tag for the home screen: "Melbourne" -> MEL, "New York" -> NY ("" if unknown). */
    static String shortPlace(Context c) {
        String n = c.getSharedPreferences("weather", Context.MODE_PRIVATE).getString("place", "").split(",")[0].trim();
        if (n.isEmpty()) return "";
        String[] w = n.split("[\\s-]+");
        StringBuilder b = new StringBuilder();
        if (w.length > 1) for (String x : w) { if (!x.isEmpty() && b.length() < 3) b.append(x.charAt(0)); }
        else b.append(n, 0, Math.min(3, n.length()));
        return b.toString().toUpperCase(java.util.Locale.getDefault());
    }

    /** Use this place from now on (null = automatic again); the next look fetches its forecast. */
    static void setPlace(Context c, String name, double lat, double lon) {
        SharedPreferences.Editor e = c.getSharedPreferences("weather", Context.MODE_PRIVATE).edit();
        if (name == null) e.putBoolean("manual", false).remove("ipAt").remove("place");
        else e.putBoolean("manual", true).putString("place", name).putFloat("lat", (float) lat).putFloat("lon", (float) lon);
        e.putLong("at", 0).apply();                           // stale: refetch
    }

    /** Places matching a name, from Open-Meteo's free geocoding: {label, lat, lon} (call off the main thread). */
    static java.util.List<String[]> search(String name) throws Exception {
        URL u = new URL("https://geocoding-api.open-meteo.com/v1/search?count=8&language=en&name=" + java.net.URLEncoder.encode(name, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        java.util.List<String[]> out = new java.util.ArrayList<>();
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            JSONArray r = new JSONObject(bo.toString("UTF-8")).optJSONArray("results");
            if (r != null) for (int i = 0; i < r.length(); i++) {
                JSONObject o = r.getJSONObject(i);
                String label = o.optString("name") + (o.optString("admin1").isEmpty() ? "" : ", " + o.optString("admin1"))
                        + (o.optString("country_code").isEmpty() ? "" : ", " + o.optString("country_code"));
                out.add(new String[]{label, String.valueOf(o.getDouble("latitude")), String.valueOf(o.getDouble("longitude"))});
            }
        } finally { c.disconnect(); }
        return out;
    }

    /** The saved forecast, or null. */
    static JSONObject saved(Context c) {
        try { String j = c.getSharedPreferences("weather", Context.MODE_PRIVATE).getString("json", null); return j == null ? null : uptoNow(new JSONObject(j)); }
        catch (Exception e) { return null; }
    }

    static long savedAt(Context c) { return c.getSharedPreferences("weather", Context.MODE_PRIVATE).getLong("at", 0); }

    /** Refresh the forecast if it is older than an hour; done runs on the main thread afterwards. */
    static void refresh(final android.app.Activity a, final Runnable done) {
        if (System.currentTimeMillis() - savedAt(a) < FRESH && saved(a) != null && saved(a).has("hourly")
                && !a.getSharedPreferences("weather", Context.MODE_PRIVATE).getString("place", "").isEmpty()) return;
        fetchThen(a, done);
    }

    /** No location service on the phone (e.g. no Google): a rough city-level position from the internet address. */
    private static Location byIp() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://ipwho.is/").openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "dumb_phone");
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                JSONObject o = new JSONObject(bo.toString("UTF-8"));
                if (!o.optBoolean("success", false)) return null;
                Location l = new Location("ip");
                l.setLatitude(o.getDouble("latitude"));
                l.setLongitude(o.getDouble("longitude"));
                android.os.Bundle ex = new android.os.Bundle();
                ex.putString("city", o.optString("city", ""));
                l.setExtras(ex);
                return l;
            } finally { c.disconnect(); }
        } catch (Throwable e) { return null; }
    }

    private static void fetch(final HomeActivity h, final Panel p) {
        fetchThen(h, new Runnable() { public void run() { p.refresh(); } });
    }

    private static void fetchThen(final android.app.Activity h, final Runnable done) {
        if (busy) return;
        final Location known = where(h);
        busy = true;
        new Thread(new Runnable() {
            @Override public void run() {
                String json = null;
                Location loc = known;
                if (loc == null) {
                    loc = byIp();
                    if (loc != null) h.getSharedPreferences("weather", Context.MODE_PRIVATE).edit()
                            .putFloat("lat", (float) loc.getLatitude()).putFloat("lon", (float) loc.getLongitude())
                            .putString("place", loc.getExtras() == null ? "" : loc.getExtras().getString("city", ""))
                            .putLong("ipAt", System.currentTimeMillis()).apply();
                } else if (h.getSharedPreferences("weather", Context.MODE_PRIVATE).getString("place", "").isEmpty()) {
                    Location named = byIp();                      // located without a name: get the city for the label once
                    if (named != null && named.getExtras() != null)
                        h.getSharedPreferences("weather", Context.MODE_PRIVATE).edit()
                                .putString("place", named.getExtras().getString("city", "")).apply();
                }
                try {
                    if (loc == null) throw new Exception("no location");
                    URL u = new URL(String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                            + "&current=temperature_2m,weather_code,is_day&hourly=temperature_2m,weather_code,precipitation_probability,is_day"
                            + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset"
                            + "&timezone=auto&forecast_days=7", loc.getLatitude(), loc.getLongitude()));
                    HttpURLConnection c = (HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(15000);
                    c.setRequestProperty("User-Agent", "dumb_phone");
                    try (InputStream in = c.getInputStream()) {
                        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                        byte[] b = new byte[4096];
                        int n;
                        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                        json = bo.toString("UTF-8");
                    } finally { c.disconnect(); }
                    new JSONObject(json).getJSONObject("current");        // sanity check
                } catch (Throwable e) { json = null; }
                final String fin = json;
                h.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        busy = false;
                        if (fin != null) h.getSharedPreferences("weather", Context.MODE_PRIVATE).edit()
                                .putString("json", fin).putLong("at", System.currentTimeMillis()).apply();
                        if (done != null) done.run();
                    }
                });
            }
        }).start();
    }

    /** Last known rough position (network first), remembered so a missing fix doesn't lose the forecast. */
    private static Location where(Context c) {
        SharedPreferences pr = c.getSharedPreferences("weather", Context.MODE_PRIVATE);
        if (pr.getBoolean("manual", false)) {                  // a place picked in the Weather app
            Location l = new Location("manual");
            l.setLatitude(pr.getFloat("lat", 0));
            l.setLongitude(pr.getFloat("lon", 0));
            return l;
        }
        Location best = null;
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            for (String prov : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER}) {
                Location l = lm.getLastKnownLocation(prov);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best == null) lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, new android.location.LocationListener() {
                public void onLocationChanged(Location l) {
                    c.getSharedPreferences("weather", Context.MODE_PRIVATE).edit()
                            .putFloat("lat", (float) l.getLatitude()).putFloat("lon", (float) l.getLongitude()).apply();
                }
                public void onStatusChanged(String s, int i, android.os.Bundle b) { }
                public void onProviderEnabled(String s) { }
                public void onProviderDisabled(String s) { }
            }, android.os.Looper.getMainLooper());
        } catch (SecurityException ignored) { }
        if (best != null) {
            pr.edit().putFloat("lat", (float) best.getLatitude()).putFloat("lon", (float) best.getLongitude()).apply();
            return best;
        }
        if (pr.contains("lat") && System.currentTimeMillis() - pr.getLong("ipAt", 0) < 86_400_000L || pr.contains("lat") && !pr.contains("ipAt")) {
            Location l = new Location("saved");
            l.setLatitude(pr.getFloat("lat", 0));
            l.setLongitude(pr.getFloat("lon", 0));
            return l;
        }
        return null;
    }

    static int glyph(int code, boolean day) {
        if (code == 0) return day ? 0xF0599 : 0xF0594;              // sunny / clear night
        if (code <= 2) return 0xF0595;                               // partly cloudy
        if (code == 3) return 0xF0590;                               // cloudy
        if (code == 45 || code == 48) return 0xF0591;                // fog
        if (code >= 95) return 0xF0593;                              // thunder
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return 0xF0598;   // snow
        if (code >= 80) return 0xF0596;                              // showers
        return 0xF0597;                                              // rain / drizzle
    }

    static String words(int code) {
        if (code == 0) return "clear";
        if (code <= 2) return "partly cloudy";
        if (code == 3) return "cloudy";
        if (code == 45 || code == 48) return "fog";
        if (code >= 95) return "storms";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "snow";
        if (code >= 80) return "showers";
        if (code >= 61) return "rain";
        return "drizzle";
    }
}
