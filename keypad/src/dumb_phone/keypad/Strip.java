package dumb_phone.keypad;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/** The thin line above the keypad: the typing mode (abc / Abc / ABC / 123) and the word suggestions.
 *  The chosen suggestion is lit; tapping one uses it. */
final class Strip extends View {
    private final KeypadService k;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint();
    private final float dp;
    private float[] left = new float[0], right = new float[0];

    Strip(KeypadService k) {
        super(k);
        this.k = k;
        dp = getResources().getDisplayMetrics().density;
        Typeface f;
        try { f = Typeface.createFromAsset(k.getAssets(), "JetBrainsMono-Regular.ttf"); } catch (Exception e) { f = Typeface.MONOSPACE; }
        text.setTypeface(f);
        text.setTextSize(15 * getResources().getDisplayMetrics().scaledDensity);
    }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Math.round(26 * dp));
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawColor(Theme.VOID);
        fill.setColor(Theme.RULE);
        c.drawRect(0, 0, w, Math.max(1, dp), fill);
        float base = h / 2f - (text.descent() + text.ascent()) / 2f, pad = 6 * dp, x = pad;
        text.setColor(Theme.DIM);
        String tag = k.modeTag();
        c.drawText(tag, x, base, text);
        x += text.measureText(tag) + 10 * dp;
        if (k.punct != null && k.plist != null) {               // choosing a mark with 1 or *: show them all
            float cw = text.measureText("M"), gap = Math.min(8 * dp, Math.max(2 * dp, (w - x - pad - cw * k.plist.length()) / Math.max(1, k.plist.length() - 1)));
            left = new float[0]; right = new float[0];
            for (int i = 0; i < k.plist.length(); i++) {
                if (k.plist.charAt(i) == k.punct.charAt(0)) {
                    fill.setColor(colour(0));
                    c.drawRect(x - 2 * dp, 3 * dp, x + cw + 2 * dp, h - 2 * dp, fill);
                    text.setColor(Theme.VOID);
                } else text.setColor(Theme.GREEN);
                c.drawText(k.plist.substring(i, i + 1), x, base, text);
                x += cw + gap;
            }
            return;
        }
        int n = k.sugg.size();
        left = new float[n]; right = new float[n];
        for (int i = 0; i < n; i++) {
            String s = k.sugg.get(i);
            float tw = text.measureText(s);
            if (x + tw > w - pad / 2) { left = java.util.Arrays.copyOf(left, i); right = java.util.Arrays.copyOf(right, i); break; }
            left[i] = x - 4 * dp; right[i] = x + tw + 4 * dp;
            if (i == k.sel) {                                  // the chosen one: a solid block
                fill.setColor(colour(0));
                c.drawRect(left[i], 3 * dp, right[i], h - 2 * dp, fill);
            }
            text.setColor(i == k.sel ? Theme.VOID : colour(i));
            c.drawText(s, x, base, text);
            x += tw + 12 * dp;
        }
    }

    /** Suggestion colours take turns (accent, text, …) so the words stand apart; any colour that isn't
     *  readable on the background (contrast under 4.5) falls back to the text colour. */
    private static int colour(int i) {
        int c = i % 2 == 0 ? Theme.AMBER : Theme.GREEN;
        return contrast(c, Theme.VOID) >= 4.5 ? c : Theme.GREEN;
    }

    private static double lum(int c) {
        double[] v = {((c >> 16) & 255) / 255.0, ((c >> 8) & 255) / 255.0, (c & 255) / 255.0};
        for (int i = 0; i < 3; i++) v[i] = v[i] <= 0.03928 ? v[i] / 12.92 : Math.pow((v[i] + 0.055) / 1.055, 2.4);
        return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
    }

    private static double contrast(int a, int b) {
        double x = lum(a), y = lum(b);
        return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP)
            for (int i = 0; i < left.length; i++) if (e.getX() >= left[i] && e.getX() <= right[i]) { k.pick(i); break; }
        return true;
    }
}
