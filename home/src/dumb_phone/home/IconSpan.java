package dumb_phone.home;

import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.style.MetricAffectingSpan;

/** Draws part of a text in the Nerd Font, at its own size (the tile glyphs). */
final class IconSpan extends MetricAffectingSpan {
    private final Typeface face;
    private final int px;

    IconSpan(Typeface face, int px) { this.face = face; this.px = px; }

    @Override public void updateDrawState(TextPaint p) { apply(p); }
    @Override public void updateMeasureState(TextPaint p) { apply(p); }

    private void apply(Paint p) {
        p.setTypeface(face);
        p.setTextSize(px);
    }
}
