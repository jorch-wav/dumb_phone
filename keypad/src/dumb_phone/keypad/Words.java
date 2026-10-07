package dumb_phone.keypad;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The suggestion words: assets/words.txt (40 000 common words with how common they are, sorted by
 * search key = lower case without apostrophes) kept as one block of text plus offsets, so it costs
 * about 1 MB. Words you finish typing are remembered in files/learned.txt and rank higher next time;
 * words that aren't in the list are learned too.
 */
final class Words {
    private static Words sOne;
    private final StringBuilder blob = new StringBuilder(400_000);
    private int[] start = new int[40_000];
    private byte[] freq = new byte[40_000];
    private int n;
    private final Map<String, Integer> learned = new HashMap<>();   // word as typed -> times used
    private final File file;
    private boolean dirty;

    static synchronized Words get(Context c) {
        if (sOne == null) sOne = new Words(c.getApplicationContext());
        return sOne;
    }

    private Words(Context c) {
        file = new File(c.getFilesDir(), "learned.txt");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open("words.txt"), "UTF-8"), 32768)) {
            for (String l; (l = r.readLine()) != null; ) {
                int t = l.indexOf('\t');
                if (t <= 0) continue;
                if (n == start.length) { start = java.util.Arrays.copyOf(start, n * 2); freq = java.util.Arrays.copyOf(freq, n * 2); }
                start[n] = blob.length();
                freq[n] = (byte) Integer.parseInt(l.substring(t + 1));
                blob.append(l, 0, t);
                n++;
            }
        } catch (Exception ignored) { }
        start = java.util.Arrays.copyOf(start, n + 1);
        start[n] = blob.length();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            for (String l; (l = r.readLine()) != null; ) {
                int t = l.indexOf('\t');
                if (t > 0) learned.put(l.substring(0, t), Integer.parseInt(l.substring(t + 1)));
            }
        } catch (Exception ignored) { }
    }

    private String word(int i) { return blob.substring(start[i], start[i + 1]); }

    /** Search key: lower case, no apostrophes. */
    static String key(CharSequence w) {
        StringBuilder b = new StringBuilder(w.length());
        for (int i = 0; i < w.length(); i++) {
            char ch = w.charAt(i);
            if (ch != '\'') b.append(Character.toLowerCase(ch));
        }
        return b.toString();
    }

    /** Compares word i's key with the first prefix.length() chars of prefix: <0, 0 (starts with it), >0. */
    private int cmp(int i, String prefix) {
        int p = 0;
        for (int j = start[i]; j < start[i + 1] && p < prefix.length(); j++) {
            char ch = blob.charAt(j);
            if (ch == '\'') continue;
            int d = Character.toLowerCase(ch) - prefix.charAt(p++);
            if (d != 0) return d;
        }
        return p == prefix.length() ? 0 : -1;
    }

    /** Up to max words that start with what was typed, most likely first; never the typed word itself. */
    List<String> suggest(String typed, int max) {
        List<String> out = new ArrayList<>();
        String k = key(typed);
        if (k.isEmpty()) return out;
        int lo = 0, hi = n;                                  // first word whose key >= k
        while (lo < hi) { int m = (lo + hi) >>> 1; if (cmp(m, k) < 0) lo = m + 1; else hi = m; }
        final List<String> cand = new ArrayList<>();
        final List<Integer> score = new ArrayList<>();
        for (int i = lo; i < n && cmp(i, k) == 0; i++) {
            String w = word(i);
            Integer u = learned.get(w);
            add(cand, score, w, (freq[i] & 255) + (u == null ? 0 : Math.min(u, 10) * 25));
        }
        for (Map.Entry<String, Integer> e : learned.entrySet())   // learned words that aren't in the list
            if (key(e.getKey()).startsWith(k) && !cand.contains(e.getKey())) add(cand, score, e.getKey(), 120 + Math.min(e.getValue(), 10) * 25);
        boolean upper = typed.length() > 1 && typed.equals(typed.toUpperCase()) && !typed.equals(typed.toLowerCase());
        boolean cap = Character.isUpperCase(typed.charAt(0));
        for (int i = 0; i < cand.size() && out.size() < max; i++) {
            String w = cand.get(i);
            if (upper) w = w.toUpperCase();
            else if (cap) w = Character.toUpperCase(w.charAt(0)) + w.substring(1);
            if (!w.equals(typed) && !out.contains(w)) out.add(w);
        }
        return out;
    }

    /** Keeps cand sorted by score, highest first; only the best few are kept. */
    private static void add(List<String> cand, List<Integer> score, String w, int s) {
        int i = score.size();
        while (i > 0 && score.get(i - 1) < s) i--;
        if (i >= 8) return;
        cand.add(i, w); score.add(i, s);
        if (cand.size() > 8) { cand.remove(8); score.remove(8); }
    }

    /** A word was finished: count it. A list word is counted as the list spells it ("dont" -> "don't");
     *  a new word in lower case unless it has capitals inside ("iPhone"). */
    void learn(String w) {
        if (w.length() < 2 || w.length() > 30) return;
        String k = key(w), stored = null;
        int lo = 0, hi = n;
        while (lo < hi) { int m = (lo + hi) >>> 1; if (cmp(m, k) < 0) lo = m + 1; else hi = m; }
        for (int i = lo; i < n && cmp(i, k) == 0 && stored == null; i++) if (key(word(i)).equals(k)) stored = word(i);
        if (stored == null) stored = w.substring(1).equals(w.substring(1).toLowerCase()) ? w.toLowerCase() : w;
        Integer u = learned.get(stored);
        learned.put(stored, u == null ? 1 : u + 1);
        dirty = true;
    }

    void save() {
        if (!dirty) return;
        dirty = false;
        try (FileOutputStream o = new FileOutputStream(file)) {
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, Integer> e : learned.entrySet()) b.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
            o.write(b.toString().getBytes("UTF-8"));
        } catch (Exception ignored) { }
    }
}
