package dumb_phone.radio;

import android.media.MediaDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Feeds a live Icecast/Shoutcast stream to MediaPlayer ourselves. The phone's own (MediaTek)
 * HTTP streaming keeps failing on live radio. Also strips the ICY metadata and reports the song.
 */
final class StreamSource extends MediaDataSource {
    interface TitleListener { void onTitle(String title); }

    private static final int CAP = 2 << 20;            // keep the last 2 MB
    private final byte[] ring = new byte[CAP];
    private long written;                              // bytes received so far (stream position)
    private volatile boolean closed, ended;
    private HttpURLConnection conn;

    StreamSource(final String url, final TitleListener titles) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() { pump(url, titles); }
        }, "radio-stream");
        t.setDaemon(true);
        t.start();
    }

    private void pump(String url, TitleListener titles) {
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Icy-MetaData", "1");
            conn.setRequestProperty("User-Agent", "dumb_phone-radio");
            int metaint = 0;
            try { metaint = Integer.parseInt(conn.getHeaderField("icy-metaint").trim()); } catch (Exception ignored) { }
            String type = String.valueOf(conn.getContentType()).toLowerCase();
            aligning = type.contains("aac") || url.toLowerCase().contains(".aac");
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[16384];
            int untilMeta = metaint;
            while (!closed) {
                int want = metaint > 0 ? Math.min(buf.length, untilMeta) : buf.length;
                int n = in.read(buf, 0, want);
                if (n < 0) break;
                audio(buf, n);
                if (metaint > 0 && (untilMeta -= n) == 0) {
                    untilMeta = metaint;
                    int len = in.read() * 16;
                    if (len < 0) break;
                    if (len > 0) {
                        byte[] meta = new byte[len];
                        int got = 0;
                        while (got < len) {
                            int r = in.read(meta, got, len - got);
                            if (r < 0) break;
                            got += r;
                        }
                        String title = streamTitle(new String(meta, 0, got, "UTF-8"));
                        if (title != null && titles != null) titles.onTitle(title);
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            ended = true;
            synchronized (this) { notifyAll(); }
        }
    }

    static String streamTitle(String meta) {
        int i = meta.indexOf("StreamTitle='");
        if (i < 0) return null;
        int j = meta.indexOf("';", i + 13);
        if (j < 0) j = meta.lastIndexOf('\'');
        return j > i + 13 ? meta.substring(i + 13, j).trim() : "";
    }

    // The phone only recognises AAC (ADTS) when the data starts exactly on a frame, so drop the
    // partial frame a live stream usually starts with.
    private boolean aligning;
    private byte[] pending = new byte[0];

    private void audio(byte[] b, int n) {
        if (!aligning) { put(b, n); return; }
        byte[] all = new byte[pending.length + n];
        System.arraycopy(pending, 0, all, 0, pending.length);
        System.arraycopy(b, 0, all, pending.length, n);
        for (int i = 0; i + 7 <= all.length; i++) {
            int len = adtsLength(all, i);
            if (len <= 0) continue;
            if (i + len + 2 > all.length) break;                  // need the next header to be sure
            if (adtsLength(all, i + len) > 0) {
                aligning = false;
                pending = null;
                put(all, i, all.length - i);
                return;
            }
        }
        pending = all.length > 65536 ? new byte[0] : all;        // no frame found in 64 KB: start over
        if (all.length > 65536) { aligning = false; put(all, 0, all.length); }
    }

    private static int adtsLength(byte[] b, int i) {
        if (i + 6 >= b.length) return (i + 1 < b.length && (b[i] & 0xFF) == 0xFF && (b[i + 1] & 0xF6) == 0xF0) ? 1 : 0;
        if ((b[i] & 0xFF) != 0xFF || (b[i + 1] & 0xF6) != 0xF0) return 0;
        int len = ((b[i + 3] & 0x03) << 11) | ((b[i + 4] & 0xFF) << 3) | ((b[i + 5] & 0xE0) >> 5);
        return len >= 7 ? len : 0;
    }

    private void put(byte[] b, int n) { put(b, 0, n); }

    private synchronized void put(byte[] b, int from, int n) {
        for (int k = 0; k < n; ) {
            int at = (int) (written % CAP);
            int c = Math.min(n - k, CAP - at);
            System.arraycopy(b, from + k, ring, at, c);
            k += c;
            written += c;
        }
        notifyAll();
    }

    @Override public synchronized int readAt(long pos, byte[] b, int off, int size) throws IOException {
        long deadline = System.currentTimeMillis() + 20000;
        while (written <= pos && !ended && !closed) {
            long wait = deadline - System.currentTimeMillis();
            if (wait <= 0) return -1;
            try { wait(wait); } catch (InterruptedException e) { return -1; }
        }
        if (written <= pos || pos < written - CAP) return -1;   // stream ended, or that part is long gone
        int n = (int) Math.min(size, written - pos);
        for (int k = 0; k < n; ) {
            int at = (int) ((pos + k) % CAP);
            int c = Math.min(n - k, CAP - at);
            System.arraycopy(ring, at, b, off + k, c);
            k += c;
        }
        return n;
    }

    @Override public long getSize() { return -1; }

    @Override public void close() {
        closed = true;
        synchronized (this) { notifyAll(); }
        try { if (conn != null) conn.disconnect(); } catch (Exception ignored) { }
    }
}
