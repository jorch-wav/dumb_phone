package dumb_phone.home;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * A soft, bell-like chime synthesised on the fly (no sound file): overlapping notes, each a sine with
 * a few gentle overtones (marimba-ish) and a slow fade. Played on the notification volume.
 */
final class Chime {
    /** Plugged in: three rising notes (G3 C4 E4). Low battery: two falling notes (E4 A3). Both swell louder. */
    static void plugged() { play(new double[]{196.00, 261.63, 329.63}, new double[]{0.5, 0.75, 1.0}, 0.38, 6500); }
    static void low() { play(new double[]{329.63, 220.00}, new double[]{0.65, 1.0}, 0.55, 6500); }
    /** Unplugged: "goodbye", the same notes falling (E4 C4 G3), getting softer, quieter overall. */
    static void unplugged() { play(new double[]{329.63, 261.63, 196.00}, new double[]{1.0, 0.7, 0.45}, 0.38, 4200); }

    /**
     * Ambient, Eno-ish: each note is a small pad (main tone, a detuned twin, a soft voice an octave
     * below, a faint glassy fifth above) that swells in slowly; the notes get louder; a long hall
     * reverb holds it all together.
     */
    private static void play(final double[] notes, final double[] amp, final double gap, final int loud) {
        new Thread(new Runnable() {
            @Override public void run() {
                int rate = 22050;
                double ring = 2.2;                                        // each note sustains this long
                int dry = (int) (rate * (gap * (notes.length - 1) + ring));
                int len = dry + (int) (rate * 4.0);                       // + the echo and reverb tail
                float[] mix = new float[len];
                for (int n = 0; n < notes.length; n++) {
                    int start = (int) (rate * gap * n);
                    double f = notes[n];
                    for (int i = 0; start + i < dry && i < rate * ring; i++) {
                        double t = (double) i / rate;
                        double swell = Math.min(1, t / 0.14);
                        swell = swell * swell * (3 - 2 * swell);          // smooth fade-in
                        double env = swell * Math.exp(-t * 1.5);
                        double glass = Math.min(1, t / 0.5) * Math.exp(-t * 1.2);   // fades in later, lingers
                        double v = Math.sin(2 * Math.PI * f * t)
                                + 0.6 * Math.sin(2 * Math.PI * f * 1.005 * t + 1.3)            // detuned twin: slow beating
                                + 0.35 * Math.sin(2 * Math.PI * f * 0.5 * t + 0.7)             // soft voice an octave below
                                + 0.40 * Math.sin(2 * Math.PI * f * 2.0 * t) * Math.exp(-t * 3); // octave, carries on tiny speakers
                        double g = 0.18 * glass * Math.sin(2 * Math.PI * f * 3.0 * t + 2.1);    // glassy fifth above
                        mix[start + i] += (float) (amp[n] * (env * v + g));
                    }
                }
                // echo: soft repeats every 330 ms, each a little quieter and darker
                {
                    int d = rate * 330 / 1000;
                    float[] echo = new float[len];
                    float lp = 0;
                    for (int i = 0; i < len; i++) {
                        float back = i >= d ? echo[i - d] : 0;
                        lp = back * 0.55f + lp * 0.45f;
                        echo[i] = mix[i] + lp * 0.58f;
                    }
                    for (int i = 0; i < len; i++) mix[i] = mix[i] + (echo[i] - mix[i]) * 0.8f;
                }
                // long hall reverb: a short pre-delay, four damped feedback combs, two all-passes
                int pre = rate * 30 / 1000;
                float[] in = new float[len];
                for (int i = pre; i < len; i++) in[i] = mix[i - pre];
                int[] combs = {743, 809, 877, 941};
                int[] alls = {131, 331};
                float[] wet = new float[len];
                for (int d : combs) {
                    float[] buf = new float[d];
                    float lp = 0;
                    int k = 0;
                    for (int i = 0; i < len; i++) {
                        float out = buf[k];
                        lp = out * 0.5f + lp * 0.5f;                      // damping: dark, soft tail
                        buf[k] = in[i] + lp * 0.87f;
                        wet[i] += out * 0.25f;
                        k = (k + 1) % d;
                    }
                }
                for (int d : alls) {
                    float[] buf = new float[d];
                    int k = 0;
                    for (int i = 0; i < len; i++) {
                        float b = buf[k];
                        float out = -wet[i] + b;
                        buf[k] = wet[i] + b * 0.5f;
                        wet[i] = out;
                        k = (k + 1) % d;
                    }
                }
                for (int i = 0; i < len; i++) mix[i] = mix[i] * 0.6f + wet[i] * 0.8f;
                float peak = 0;
                for (float x : mix) peak = Math.max(peak, Math.abs(x));
                short[] pcm = new short[len];
                for (int i = 0; i < len; i++) {
                    double fadeOut = Math.min(1, (len - i) / (rate * 0.8));       // let the tail die away
                    pcm[i] = (short) (mix[i] / peak * loud * fadeOut);
                }
                try {
                    AudioTrack tr = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                            .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                            .setBufferSizeInBytes(pcm.length * 2)
                            .setTransferMode(AudioTrack.MODE_STATIC).build();
                    tr.write(pcm, 0, pcm.length);
                    tr.play();
                    Thread.sleep(len * 1000L / rate + 200);
                    tr.release();
                } catch (Exception ignored) { }
            }
        }).start();
    }
}
