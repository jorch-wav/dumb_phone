package dumb_phone.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * A thin outline in the theme colour around the item the keys have selected, for apps that don't show
 * one themselves (Auxio's rows look the same selected or not). Event-driven only: it moves when the
 * focus moves, and costs nothing otherwise.
 */
final class FocusRing {
    static final java.util.Set<String> APPS = new java.util.HashSet<>(java.util.Arrays.asList("org.oxycblt.auxio"));
    /** The ring in the app's own accent where it can't follow our themes (Auxio is set to phosphor green). */
    private static final java.util.Map<String, Integer> COLOUR = new java.util.HashMap<>();
    static { COLOUR.put("org.oxycblt.auxio", 0xFF95EE62); }
    private static int colour = 0;

    private static View ring;
    private static AccessibilityNodeInfo node;
    private static final Handler h = new Handler();

    /** A view got the focus (TYPE_VIEW_FOCUSED). */
    static void focused(Context c, AccessibilityEvent e) {
        if (e.getPackageName() == null || !APPS.contains(e.getPackageName().toString())) { hide(c); return; }
        node = e.getSource();
        if (node == null) return;
        place(c);
        h.removeCallbacksAndMessages(null);
        h.postDelayed(new Runnable() { public void run() { place(c); } }, 150);   // again once a list has scrolled to it
    }

    private static void place(Context c) {
        if (node == null) return;
        try { node.refresh(); } catch (Exception ignored) { }
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.width() <= 0 || r.height() <= 0) { hide(c); return; }
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(r.width(), r.height(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = r.left;
        lp.y = r.top;
        try {
            if (ring == null) { ring = new Outline(c); wm.addView(ring, lp); }
            else { wm.updateViewLayout(ring, lp); ring.invalidate(); }
        } catch (Exception e) { ring = null; }
    }

    /** After an arrow key in one of APPS: ring whatever has the input focus now (list rows don't announce it). */
    /** Auxio's keys land on each row's small ⋯ button: the ring goes round the whole row, and OK opens the row
     *  (hold OK = the ⋯ menu, see VolumeService.rowKey). */
    static void afterKey(final android.accessibilityservice.AccessibilityService s) {
        h.removeCallbacksAndMessages(null);
        h.postDelayed(new Runnable() { public void run() {
            try {
                AccessibilityNodeInfo root = s.getRootInActiveWindow();
                if (root == null || root.getPackageName() == null || !APPS.contains(root.getPackageName().toString())) { hide(s); return; }
                AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (f == null) { hide(s); return; }
                String id = f.getViewIdResourceName();
                if (id != null && id.endsWith("_menu") && f.getParent() != null) f = f.getParent();   // ring the whole row
                Integer col = COLOUR.get(root.getPackageName().toString());
                colour = col == null ? 0 : col;
                node = f;
                place(s);
            } catch (Exception ignored) { }
        } }, 120);                                            // after the app has moved the focus (and scrolled)
    }

    /** A window changed: drop the ring unless one of APPS is still in front. */
    static void windowChanged(android.accessibilityservice.AccessibilityService s) {
        if (ring == null) return;
        try {
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            if (root != null && root.getPackageName() != null && APPS.contains(root.getPackageName().toString())) return;
        } catch (Exception ignored) { }
        hide(s);
    }

    static void hide(Context c) {
        h.removeCallbacksAndMessages(null);
        node = null;
        if (ring == null) return;
        try { ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).removeView(ring); } catch (Exception ignored) { }
        ring = null;
    }

    private static final class Outline extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Outline(Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
        }
        @Override protected void onDraw(Canvas c) {
            float w = 2 * getResources().getDisplayMetrics().density;
            p.setStrokeWidth(w);
            p.setColor(colour != 0 ? colour : Theme.AMBER);
            c.drawRoundRect(w / 2, w / 2, getWidth() - w / 2, getHeight() - w / 2, 3 * w, 3 * w, p);
        }
    }
}
