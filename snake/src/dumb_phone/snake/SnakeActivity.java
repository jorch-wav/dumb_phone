package dumb_phone.snake;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayDeque;
import java.util.Random;

/**
 * Classic Snake, Nokia style, in the chosen theme.
 * Keys: D-pad or 2/4/6/8 steer, OK/5 pause, OK starts again after a crash, Back leaves (the game pauses).
 * Touch: tap the left/right/top/bottom of the field to turn. Hitting a wall or yourself ends the game.
 */
public class SnakeActivity extends Activity {
    private Board board;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.load(this);
        board = new Board(this);
        setContentView(board);
    }

    @Override protected void onResume() {
        super.onResume();
        if (Theme.changed(this)) { Theme.load(this); board.invalidate(); }
    }

    @Override protected void onPause() {
        super.onPause();
        board.pause(true);
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (board.key(code)) return true;
        return super.onKeyDown(code, e);
    }

    static final class Board extends View {
        static final int COLS = 20;
        private int rows, cell, top, left;
        private final ArrayDeque<int[]> snake = new ArrayDeque<>();
        private int dx = 1, dy = 0, ndx = 1, ndy = 0, fx, fy, score, best;
        private boolean paused = true, over, started;
        private final Random rnd = new Random();
        private final Handler h = new Handler();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Typeface mono, bold;
        private final SharedPreferences prefs;
        private final Runnable tick = new Runnable() {
            @Override public void run() { step(); if (!paused && !over) h.postDelayed(this, delay()); }
        };

        Board(Context c) {
            super(c);
            setFocusable(true);
            if (android.os.Build.VERSION.SDK_INT >= 26) setDefaultFocusHighlightEnabled(false);
            prefs = c.getSharedPreferences("snake", Context.MODE_PRIVATE);
            best = prefs.getInt("best", 0);
            mono = Typeface.createFromAsset(c.getAssets(), "JetBrainsMono-Regular.ttf");
            bold = Typeface.createFromAsset(c.getAssets(), "JetBrainsMono-Bold.ttf");
        }

        /** Faster as the snake grows: 220 ms per step down to 75 ms. */
        private long delay() { return Math.max(75, 220 - score * 6); }

        @Override protected void onSizeChanged(int w, int hgt, int ow, int oh) {
            int margin = Math.round(8 * getResources().getDisplayMetrics().density);
            cell = (w - 2 * margin) / COLS;
            left = (w - cell * COLS) / 2;
            top = (int) (cell * 2.4f);                     // room for the score line
            rows = (hgt - top - margin) / cell;
            reset();
        }

        private void reset() {
            snake.clear();
            int y = rows / 2;
            for (int x = 6; x >= 3; x--) snake.addLast(new int[]{x, y});
            dx = ndx = 1; dy = ndy = 0;
            score = 0;
            over = false;
            paused = true;
            started = false;
            food();
            invalidate();
        }

        private void food() {
            while (true) {
                int x = rnd.nextInt(COLS), y = rnd.nextInt(rows);
                boolean hit = false;
                for (int[] s : snake) if (s[0] == x && s[1] == y) { hit = true; break; }
                if (!hit) { fx = x; fy = y; return; }
            }
        }

        private void step() {
            if (paused || over) return;
            if (ndx != -dx || ndy != -dy) { dx = ndx; dy = ndy; }   // no turning straight back
            int[] head = snake.peekFirst();
            int nx = head[0] + dx, ny = head[1] + dy;
            boolean eat = nx == fx && ny == fy;
            boolean dead = nx < 0 || ny < 0 || nx >= COLS || ny >= rows;
            if (!dead) {
                int n = 0, len = snake.size();
                for (int[] s : snake) {                    // the tail moves away this step unless we eat
                    if (++n == len && !eat) break;
                    if (s[0] == nx && s[1] == ny) { dead = true; break; }
                }
            }
            if (dead) {
                over = true;
                if (score > best) { best = score; prefs.edit().putInt("best", best).apply(); }
                performHapticFeedback(0);
            } else {
                snake.addFirst(new int[]{nx, ny});
                if (eat) { score++; food(); } else snake.removeLast();
            }
            invalidate();
        }

        void pause(boolean p) {
            if (over) return;
            paused = p;
            h.removeCallbacks(tick);
            if (!paused) h.postDelayed(tick, delay());
            invalidate();
        }

        private void turn(int x, int y) {
            if (over) return;
            ndx = x; ndy = y;
            if (paused) { started = true; pause(false); }
        }

        boolean key(int code) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_2: turn(0, -1); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_8: turn(0, 1); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_4: turn(-1, 0); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_6: turn(1, 0); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_5:
                    if (over) reset();
                    else { started = true; pause(!paused); }
                    return true;
            }
            return false;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() != MotionEvent.ACTION_DOWN) return true;
            if (over) { reset(); return true; }
            float x = e.getX() / getWidth() - 0.5f, y = e.getY() / getHeight() - 0.5f;
            if (Math.abs(x) > Math.abs(y)) turn(x > 0 ? 1 : -1, 0); else turn(0, y > 0 ? 1 : -1);
            return true;
        }

        @Override protected void onDraw(Canvas c) {
            c.drawColor(Theme.VOID);
            // score line
            p.setTypeface(bold);
            p.setTextSize(cell * 1.3f);
            p.setColor(Theme.GREEN);
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText(String.format("%04d", score), left, cell * 1.5f, p);
            p.setTypeface(mono);
            p.setColor(Theme.DIM);
            p.setTextAlign(Paint.Align.RIGHT);
            c.drawText("best " + String.format("%04d", best), left + cell * COLS, cell * 1.5f, p);
            // field: border + faint cells
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1);
            p.setColor(Theme.RULE);
            c.drawRect(left - 1, top - 1, left + cell * COLS, top + cell * rows, p);
            p.setStyle(Paint.Style.FILL);
            int gap = Math.max(1, cell / 8);
            // food
            p.setColor(Theme.AMBER);
            c.drawRect(left + fx * cell + gap * 2, top + fy * cell + gap * 2, left + (fx + 1) * cell - gap * 2, top + (fy + 1) * cell - gap * 2, p);
            // snake: head in the accent, body in the text colour
            boolean first = true;
            for (int[] s : snake) {
                p.setColor(first ? (over ? Theme.DIM : Theme.AMBER) : (over ? Theme.DIM : Theme.GREEN));
                c.drawRect(left + s[0] * cell + gap, top + s[1] * cell + gap, left + (s[0] + 1) * cell - gap, top + (s[1] + 1) * cell - gap, p);
                first = false;
            }
            // messages
            String big = null, small = null;
            if (over) { big = "game over"; small = score >= best && score > 0 ? "new best! · OK to play again" : "OK to play again"; }
            else if (!started) { big = "snake"; small = "arrows or 2 4 6 8 · OK to start"; }
            else if (paused) { big = "paused"; small = "OK to go on"; }
            if (big != null) {
                float cy = top + cell * rows / 2f;
                p.setColor(Theme.CELL);
                c.drawRect(left, cy - cell * 2.2f, left + cell * COLS, cy + cell * 1.6f, p);
                p.setTextAlign(Paint.Align.CENTER);
                p.setTypeface(bold);
                p.setTextSize(cell * 1.6f);
                p.setColor(Theme.AMBER);
                c.drawText(big, left + cell * COLS / 2f, cy - cell * 0.3f, p);
                p.setTypeface(mono);
                p.setTextSize(cell * 0.85f);
                p.setColor(Theme.GREEN);
                c.drawText(small, left + cell * COLS / 2f, cy + cell * 1.0f, p);
            }
        }
    }
}
