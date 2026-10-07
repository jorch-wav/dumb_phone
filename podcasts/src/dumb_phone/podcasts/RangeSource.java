package dumb_phone.podcasts;

import android.media.MediaDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * An episode file fed to MediaPlayer by us (the phone's own MediaTek HTTP streaming is unreliable).
 * Keeps one connection open and reads ahead; a jump outside the buffered window reconnects with
 * an HTTP Range request, so seeking and resuming work.
 */
final class RangeSource extends MediaDataSource {
    private static final int WINDOW = 1 << 20;      // keep the last 1 MB in memory
    private static final int AHEAD = 512 << 10;     // read forward instead of reconnecting for jumps under 512 KB

    private final String url;                        // final address after redirects
    private final long size;                         // -1 if unknown
    private final boolean ranges;
    private final byte[] buf = new byte[WINDOW];
    private long bufStart, bufEnd;                   // buf holds [bufStart, bufEnd)
    private HttpURLConnection conn;
    private InputStream in;
    private long connPos;                            // next byte the open connection will give
    private volatile boolean closed;

    /** Resolves redirects and finds the size. Blocks: call off the main thread. */
    RangeSource(String start) throws IOException {
        String u = start;
        HttpURLConnection c = null;
        int code = 0;
        for (int hop = 0; hop < 8; hop++) {
            c = open(u, 0);
            code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                u = new URL(new URL(u), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            break;
        }
        if (code >= 400) { c.disconnect(); throw new IOException("server said " + code); }
        url = u;
        ranges = code == 206;
        long total = -1;
        String cr = c.getHeaderField("Content-Range");                 // "bytes 0-.../TOTAL"
        if (cr != null && cr.contains("/")) {
            try { total = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim()); } catch (Exception ignored) { }
        }
        if (total < 0 && code == 200) total = c.getContentLength() > 0 ? c.getContentLengthLong() : -1;
        size = total;
        conn = c;
        in = c.getInputStream();
        connPos = 0;
    }

    private static HttpURLConnection open(String u, long from) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "dumb_phone-podcasts");
        c.setRequestProperty("Range", "bytes=" + from + "-");
        return c;
    }

    private void reconnect(long pos) throws IOException {
        if (conn != null) conn.disconnect();
        if (!ranges) throw new IOException("server can't jump");
        conn = open(url, pos);
        if (conn.getResponseCode() != 206) throw new IOException("server ignored the range");
        in = conn.getInputStream();
        connPos = pos;
        bufStart = bufEnd = pos;
    }

    /** Read from the connection, appending to the window, until it holds pos (or the file ends).
     *  A connection that drops before the end (Wi-Fi blip, server closing it) is reopened at the same
     *  byte, a few times with a short wait, instead of looking like the end of the episode. */
    private boolean fill(long pos) throws IOException {
        byte[] tmp = new byte[16384];
        int tries = 0;
        while (bufEnd <= pos && !closed) {
            int n;
            try { n = in.read(tmp); } catch (IOException e) { n = -2; }
            if (n < 0) {
                boolean early = n == -2 || (size >= 0 && bufEnd < size);
                if (!early || !ranges || closed || ++tries > 6) { if (n == -2) throw new IOException("connection lost"); return false; }
                try { Thread.sleep(1000L * tries); } catch (InterruptedException ie) { return false; }
                long keepStart = bufStart, keepEnd = bufEnd;
                try {
                    if (conn != null) conn.disconnect();
                    conn = open(url, keepEnd);
                    if (conn.getResponseCode() != 206) continue;
                    in = conn.getInputStream();
                    connPos = keepEnd;
                } catch (IOException e) { continue; }
                bufStart = keepStart; bufEnd = keepEnd;     // the buffered window is still good
                continue;
            }
            tries = 0;
            for (int k = 0; k < n; k++) buf[(int) ((bufEnd + k) % WINDOW)] = tmp[k];
            bufEnd += n;
            connPos += n;
            if (bufEnd - bufStart > WINDOW) bufStart = bufEnd - WINDOW;
        }
        return bufEnd > pos;
    }

    @Override public synchronized int readAt(long pos, byte[] b, int off, int len) throws IOException {
        if (closed || len <= 0) return -1;
        if (size >= 0 && pos >= size) return -1;
        if (pos < bufStart || pos > bufEnd + AHEAD) reconnect(pos);
        if (bufEnd <= pos && !fill(pos)) return -1;
        int n = (int) Math.min(len, bufEnd - pos);
        for (int k = 0; k < n; k++) b[off + k] = buf[(int) ((pos + k) % WINDOW)];
        return n;
    }

    @Override public long getSize() { return size; }

    @Override public void close() {
        closed = true;
        try { if (conn != null) conn.disconnect(); } catch (Exception ignored) { }
    }
}
