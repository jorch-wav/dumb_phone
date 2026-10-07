package dumb_phone.dumbtrains;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Trains anywhere there's open timetable data, with no API key. Uses Transitous (api.transitous.org),
 * a free, community-run routing service built on MOTIS that aggregates open GTFS feeds worldwide
 * (including most of Australia). It asks that apps using it stay open-source and non-commercial and
 * keep traffic light, so this only calls it when you open the app or refresh, and sends a real
 * User-Agent. Nothing here needs a key or a server of our own.
 */
final class Transit {
    private static final String BASE = "https://api.transitous.org/api/v1";
    private static final String UA = "dumb_phone-dumb-trains (open-source dumbphone app)";
    static String state = "";   // "" = anywhere, else "vic"/"nsw"/"qld"/"wa"/"sa"/"tas"/"act"/"nt": those stops show first

    static final class Stop { String id, name, area; }
    static final class Trip { long leave, arrive; String line, platform; boolean live; int changes; }

    static String get(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        c.setRequestProperty("User-Agent", UA);
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    private static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); }

    /** Transit stops whose name matches (MOTIS geocode). Drops plain map places, keeps real stops. */
    static List<Stop> search(String q) throws Exception {
        JSONArray a = new JSONArray(get("/geocode?text=" + enc(q)));
        List<Stop> out = new ArrayList<>();
        for (int i = 0; i < a.length() && out.size() < 12; i++) {
            JSONObject o = a.getJSONObject(i);
            String id = o.optString("id", "");
            if (id.startsWith("node/") || id.startsWith("way/") || id.startsWith("relation/")) continue;  // an OSM place, not a stop
            String type = o.optString("type", "");
            if (!type.isEmpty() && !"STOP".equalsIgnoreCase(type)) continue;
            Stop s = new Stop();
            s.id = id;
            s.name = o.optString("name");
            JSONArray areas = o.optJSONArray("areas");
            s.area = areas != null && areas.length() > 0 ? areas.getJSONObject(0).optString("name", "") : "";
            if (!s.id.isEmpty() && !s.name.isEmpty()) out.add(s);
        }
        final String pre = "au-" + state + "-";
        java.util.Collections.sort(out, new java.util.Comparator<Stop>() {   // your state first, then train stations
            @Override public int compare(Stop x, Stop y) {
                if (!state.isEmpty()) {          // a Perth user's WA stops beat a same-named station interstate / overseas
                    boolean sx = x.id.startsWith(pre), sy = y.id.startsWith(pre);
                    if (sx != sy) return sx ? -1 : 1;
                }
                boolean rx = rail(x), ry = rail(y);
                if (rx != ry) return rx ? -1 : 1;
                return 0;
            }
        });
        return out;
    }

    /** A train station, not a bus/tram/ferry stop (ids like "...sydney-trains..." / "...:rail:...", or names ending "Stn"/"Station"). */
    private static boolean rail(Stop s) {
        String id = s.id.toLowerCase(Locale.ROOT), nm = s.name.toLowerCase(Locale.ROOT);
        if (id.contains("bus") || id.contains("tram") || id.contains("ferry") || id.contains("coach")) return false;
        return id.contains("train") || id.contains(":rail:") || id.contains("-rail")
                || nm.contains("railway station") || nm.contains("train station")
                || nm.endsWith(" stn") || nm.endsWith(" station");   // Transperth etc. abbreviate to "Perth Stn"
    }

    /** Train journeys a -> b, at most `want`: leaving at/after `when` (ms; 0 = now), or arriving by `when`. */
    static List<Trip> trips(Stop a, Stop b, long when, int want, boolean arriveBy) throws Exception {
        String t = when > 0 ? "&time=" + iso(when) : "";
        JSONObject d = new JSONObject(get("/plan?fromPlace=" + enc(a.id) + "&toPlace=" + enc(b.id)
                + "&transitModes=RAIL,SUBWAY,METRO&arriveBy=" + arriveBy + t));
        JSONArray its = d.optJSONArray("itineraries");
        List<Trip> out = new ArrayList<>();
        if (its == null) return out;
        for (int i = 0; i < its.length() && out.size() < want; i++) {
            JSONObject it = its.getJSONObject(i);
            JSONArray legs = it.getJSONArray("legs");
            JSONObject first = null;
            for (int j = 0; j < legs.length(); j++) {
                JSONObject l = legs.getJSONObject(j);
                if (isTrain(l.optString("mode"))) { first = l; break; }   // the first train leg: when/where you board
            }
            if (first == null) continue;
            Trip tr = new Trip();
            tr.leave = ms(first.optString("startTime"));
            tr.arrive = ms(it.optString("endTime"));
            tr.line = first.optString("routeShortName", "");
            if (tr.line.isEmpty()) tr.line = first.optString("headsign", "");
            if (tr.line.isEmpty()) tr.line = first.optString("routeLongName", "");
            tr.line = tr.line.toLowerCase(Locale.ROOT);
            tr.platform = first.getJSONObject("from").optString("track", "");
            tr.live = first.optBoolean("realTime", false);
            tr.changes = it.optInt("transfers", 0);
            out.add(tr);
        }
        return out;
    }

    /** MOTIS calls heavy rail RAIL, but city metro lines come back as SUBWAY/METRO (Melbourne, Sydney). All are "trains" here. */
    private static boolean isTrain(String mode) {
        return "RAIL".equals(mode) || "SUBWAY".equals(mode) || "METRO".equals(mode)
                || "HIGHSPEED_RAIL".equals(mode) || "LONG_DISTANCE".equals(mode) || "NIGHT_RAIL".equals(mode)
                || "REGIONAL_RAIL".equals(mode) || "REGIONAL_FAST_RAIL".equals(mode);
    }

    private static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    private static long ms(String iso) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'X'", Locale.ROOT);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(iso).getTime();
        } catch (Exception e) {
            try {
                SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                return f.parse(iso).getTime();
            } catch (Exception e2) { return 0; }
        }
    }
}
