package dumb_phone.home;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.util.Log;

import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

/**
 * Offline speech to text (Vosk) kept inside the dumb_phone service: the model is loaded once and stays in
 * memory, so listening starts at once. The mic opens on start(); audio that arrives while the model is
 * still loading is kept and fed in afterwards, so nothing said early is lost.
 */
final class Voice {
    interface Listener {
        void partial(String text);      // live words so far
        void done(String text);         // final text ("" = nothing heard)
        void failed(String why);
    }

    private static final int RATE = 16000;
    private static final long MAX_MS = 60000;   // a forgotten tap stops by itself
    private final Handler main;
    private final String path;
    private volatile Model model;
    private volatile boolean loading, stop;
    private Thread worker;

    Voice(Handler main, String modelPath) { this.main = main; this.path = modelPath; }

    boolean busy() { return worker != null && worker.isAlive(); }

    /** Load the model in the background (idempotent). */
    void load() {
        if (model != null || loading) return;
        loading = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    LibVosk.setLogLevel(LogLevel.WARNINGS);
                    long t = System.currentTimeMillis();
                    model = new Model(path);
                    Log.w("dumb_phone-voice", "model loaded in " + (System.currentTimeMillis() - t) + " ms");
                } catch (Throwable e) {
                    Log.w("dumb_phone-voice", "model load failed", e);
                } finally {
                    loading = false;
                }
            }
        }, "voice-load").start();
    }

    boolean loaded() { return model != null; }

    /** Let the model go to free ~100 MB for other apps (not while listening); the next use reloads it. */
    void free() {
        if (busy() || loading || model == null) return;
        Model m = model;
        model = null;
        try { m.close(); } catch (Throwable ignored) { }
        Log.w("dumb_phone-voice", "model freed for other apps");
    }

    void start(final Listener l) {
        if (busy()) return;
        stop = false;
        load();
        worker = new Thread(new Runnable() {
            @Override public void run() { listen(l); }
        }, "voice");
        worker.start();
    }

    /** Stop listening; the final text arrives through done(). */
    void stop() { stop = true; }

    private static final byte[] END = new byte[0];

    /** This thread only records (it must never fall behind, or the mic drops sound); a second one recognises. */
    private void listen(final Listener l) {
        long t0 = System.currentTimeMillis();
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        final java.util.concurrent.LinkedBlockingQueue<byte[]> q = new java.util.concurrent.LinkedBlockingQueue<>();
        AudioRecord rec = null;
        Thread rt = null;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE * 2 * 2));      // 2 s of slack
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) { post(l, null, "mic busy"); return; }
            rec.startRecording();
            rt = new Thread(new Runnable() { @Override public void run() { recognise(q, l); } }, "voice-asr");
            rt.start();
            long began = System.currentTimeMillis(), first = 0, bytes = 0;
            int peak = 0;
            byte[] buf = new byte[3200];                     // 0.1 s
            while (!stop && System.currentTimeMillis() - began < MAX_MS) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) continue;
                if (first == 0) first = System.currentTimeMillis();
                bytes += n;
                for (int k = 0; k + 1 < n; k += 2) { int v = Math.abs((short) ((buf[k] & 0xff) | (buf[k + 1] << 8))); if (v > peak) peak = v; }
                q.add(java.util.Arrays.copyOf(buf, n));
            }
            long held = System.currentTimeMillis() - began;
            rec.stop();
            Log.w("dumb_phone-voice", "mic open after " + (began - t0) + " ms, first sound +" + (first - began) + " ms; recorded "
                    + bytes / 32 + " of " + held + " ms, peak " + peak + "/32767");
        } catch (Throwable e) {
            Log.w("dumb_phone-voice", "record failed", e);
            if (rt == null) post(l, null, "voice error");
        } finally {
            q.add(END);
            if (rec != null) rec.release();
        }
        if (rt != null) try { rt.join(); } catch (InterruptedException ignored) { }
    }

    private void recognise(java.util.concurrent.LinkedBlockingQueue<byte[]> q, Listener l) {
        Recognizer r = null;
        StringBuilder text = new StringBuilder();
        try {
            while (model == null && loading) Thread.sleep(50);
            if (model == null) { post(l, null, "voice model missing"); return; }
            r = new Recognizer(model, RATE);
            long lastShown = 0;
            String lastPartial = "";
            while (true) {
                byte[] b = q.take();
                if (b == END) break;
                feed(r, b, b.length, text);
                long now = System.currentTimeMillis();
                if (now - lastShown > 400 && q.isEmpty()) {     // live words only when caught up (they cost time)
                    lastShown = now;
                    String p = join(text, field(r.getPartialResult(), "partial"));
                    if (!p.equals(lastPartial)) { lastPartial = p; post(l, p, null, false); }
                }
            }
            String fin = join(text, field(r.getFinalResult(), "text"));
            Log.w("dumb_phone-voice", "heard: '" + fin + "'");
            post(l, fin, null, true);
        } catch (Throwable e) {
            Log.w("dumb_phone-voice", "recognise failed", e);
            post(l, null, "voice error");
        } finally {
            if (r != null) r.close();
        }
    }

    private static void feed(Recognizer r, byte[] b, int n, StringBuilder text) {
        if (r.acceptWaveForm(b, n)) {                        // a pause ended a phrase: keep it, carry on
            String t = field(r.getResult(), "text");
            if (!t.isEmpty()) { if (text.length() > 0) text.append(' '); text.append(t); }
        }
    }

    private static String join(StringBuilder done, String more) {
        if (more.isEmpty()) return done.toString();
        return done.length() == 0 ? more : done + " " + more;
    }

    private static String field(String json, String key) {
        try { return new JSONObject(json).optString(key, "").trim(); } catch (Exception e) { return ""; }
    }

    private void post(Listener l, String text, String error) { post(l, text, error, true); }

    private void post(final Listener l, final String text, final String error, final boolean last) {
        main.post(new Runnable() {
            @Override public void run() {
                if (error != null) l.failed(error);
                else if (last) l.done(text);
                else l.partial(text);
            }
        });
    }
}
