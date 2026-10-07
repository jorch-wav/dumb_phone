package dumb_phone.keypad;

import android.inputmethodservice.InputMethodService;
import android.os.SystemClock;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import java.util.ArrayList;
import java.util.List;

/**
 * The dumb_phone keyboard: normal multi-tap typing on the keypad (2 = a b c 2) with word suggestions
 * in a thin strip, no on-screen buttons and no internet.
 *
 *   2-9    letters (tap again within 0.9 s for the next one), hold = the digit
 *   0      space, hold = 0          1   . , ? ! ' " - (tap again for the next), hold = 1
 *   *      @ / : ; ( ) & + = * # $ % _, hold = 123 mode on/off
 *   #      abc -> Abc -> ABC, hold = pick another keyboard
 *   ← →    choose a suggestion while typing a word (else move the cursor), OK = use it (else new line)
 *   back   delete; hold = letters, faster, then whole words (after ~2 s); in an empty box it goes back
 */
public class KeypadService extends InputMethodService {
    static final long MULTI = 900, HOLD = 500;
    static final String[] SETS = {null, null, "abc2", "def3", "ghi4", "jkl5", "mno6", "pqrs7", "tuv8", "wxyz9"};
    static final String P1 = ".,?!'\"-", PSTAR = "@/:;()&+=*#$%_";
    static final int LOWER = 0, CAP = 1, UPPER = 2;

    Strip strip;
    Words words;
    final StringBuilder word = new StringBuilder();   // the word being typed (composing text)
    String punct;                                      // a punctuation mark being chosen (composing), or null
    String plist;                                      // the marks on that key (shown on the strip while choosing)
    List<String> sugg = new ArrayList<>();
    int sel = -1, mode = LOWER, lastKey = -1, idx, downKey = -1, ate;
    long lastTime;
    boolean active, digitsOnly, secret, numMode, longDone, autoSpace, delDown, letterUp;

    @Override public void onCreate() {
        super.onCreate();
        Theme.load(this);
        words = Words.get(this);
        registerReceiver(fromHome, new android.content.IntentFilter("dumb_phone.keypad.DELETE"), "dumb_phone.keypad.permission.KEYS", null);
    }

    /** Back in WhatsApp: the app takes the key before any keyboard, so the home app passes it on. */
    private final android.content.BroadcastReceiver fromHome = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, android.content.Intent i) {
            String hold = i.getStringExtra("hold");
            if ("up".equals(hold)) stopHold();
            else if (active) { if ("down".equals(hold)) startHold(); else delete(); }
        }
    };

    // Holding delete, like the iPhone: one letter, then letters (faster and faster), then whole words.
    private final android.os.Handler h = new android.os.Handler();
    private long holdStart;
    private final Runnable holdTick = new Runnable() { @Override public void run() {
        long t = SystemClock.uptimeMillis() - holdStart;
        boolean more = t < 2200 ? delete() : deleteWord();
        if (more) h.postDelayed(this, t < 1200 ? 120 : t < 2200 ? 70 : 230);
        else delDown = false;
    } };

    private boolean startHold() {
        h.removeCallbacks(holdTick);
        delDown = delete();
        if (delDown) { holdStart = SystemClock.uptimeMillis(); h.postDelayed(holdTick, 500); }
        return delDown;
    }

    private void stopHold() { h.removeCallbacks(holdTick); }

    @Override public View onCreateInputView() {
        strip = new Strip(this);
        return strip;
    }

    @Override public boolean onEvaluateFullscreenMode() { return false; }
    @Override public boolean onEvaluateInputViewShown() { return active && !digitsOnly; }   // shown with the keypad too

    @Override public void onStartInput(EditorInfo attr, boolean restarting) {
        super.onStartInput(attr, restarting);
        reset();
        int t = attr.inputType, cls = t & InputType.TYPE_MASK_CLASS, v = t & InputType.TYPE_MASK_VARIATION;
        active = t != InputType.TYPE_NULL;
        digitsOnly = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME;
        secret = cls == InputType.TYPE_CLASS_TEXT && (v == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD);
        if (!restarting) { mode = LOWER; numMode = false; }
    }

    @Override public void onStartInputView(EditorInfo attr, boolean restarting) {
        super.onStartInputView(attr, restarting);
        if (Theme.changed(this)) Theme.load(this);
        redraw();
    }

    @Override public void onFinishInput() {
        super.onFinishInput();
        stopHold();
        reset();
        words.save();
    }

    @Override public void onDestroy() { stopHold(); words.save(); unregisterReceiver(fromHome); super.onDestroy(); }

    private void reset() {
        word.setLength(0); punct = null; sugg.clear(); sel = -1; lastKey = -1; downKey = -1; autoSpace = false;
    }

    /** The cursor moved. If it's the user (a tap elsewhere, a selection) the word being typed is left as
     *  it is. These messages can arrive late, after our own next edit, so a message without a composing
     *  word is checked against the text itself before anything is dropped. */
    @Override public void onUpdateSelection(int os, int oe, int ns, int ne, int cs, int ce) {
        super.onUpdateSelection(os, oe, ns, ne, cs, ce);
        String cur = punct != null ? punct : word.toString();
        if (cur.isEmpty()) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (ns == ne && cs >= 0 && ns == ce) return;                       // all as expected
        if (ns == ne && cs < 0) {
            CharSequence b = ic.getTextBeforeCursor(cur.length(), 0);
            if (b != null && b.toString().equals(cur)) return;            // a late message: the text is still ours
        }
        ic.finishComposingText();
        reset();
        redraw();
    }

    // ---- keys ----

    private static int digit(int code) {
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) return code - KeyEvent.KEYCODE_0;
        if (code == KeyEvent.KEYCODE_STAR) return 10;
        if (code == KeyEvent.KEYCODE_POUND) return 11;
        return -1;
    }



    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (!active || digitsOnly || getCurrentInputConnection() == null) return false;
        boolean eat = down(code, e);
        if (eat && e.getRepeatCount() == 0) ate = code;
        return eat;
    }

    @Override public boolean onKeyUp(int code, KeyEvent e) {
        if (ate != code) return false;
        ate = 0;
        if (digit(code) >= 0 && downKey == code) {
            downKey = -1;
            if (!longDone) tap(code);
        }
        if (code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_BACK) { stopHold(); delDown = false; }
        return true;
    }

    private boolean down(int code, KeyEvent e) {
        int d = digit(code);
        if (d >= 0) {
            if (!isInputViewShown()) showSelf();
            if (e.getRepeatCount() == 0) { downKey = code; longDone = false; }
            else if (!longDone && (e.isLongPress() || e.getEventTime() - e.getDownTime() >= HOLD)) { longDone = true; hold(code); }
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DEL: case KeyEvent.KEYCODE_BACK:
                if (e.getRepeatCount() == 0) return startHold();
                return delDown;                                    // repeats: holdTick does the deleting
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: {
                int dir = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
                if (word.length() > 0 && !sugg.isEmpty()) {
                    sel = Math.max(-1, Math.min(sugg.size() - 1, sel + dir));
                    redraw();
                    return true;
                }
                if (e.getRepeatCount() == 0) finish();
                return move(dir);
            }
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                if (e.getRepeatCount() > 0) return true;
                if (sel >= 0 && sel < sugg.size()) { pick(sel); return true; }
                finish();
                sendKeyChar('\n');                                 // new line, or the box's own action (search, next…)
                return true;
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
                finish();
                return false;
        }
        return false;
    }

    /** Shows the strip (Android doesn't open a keyboard by itself when the phone has keys). */
    private void showSelf() {
        try {
            android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            im.showSoftInputFromInputMethod(getWindow().getWindow().getAttributes().token, 0);
        } catch (Exception ignored) { }
    }

    private void tap(int code) {
        int d = digit(code);
        long now = SystemClock.uptimeMillis();
        boolean again = code == lastKey && now - lastTime < MULTI;
        lastKey = code; lastTime = now;
        if (d == 0) { finish(); commit(" "); return; }
        if (d == 1 || d == 10) { punct(d == 1 ? P1 : PSTAR, again); return; }
        if (d == 11) {                                          // #: case, or leave 123 mode
            if (numMode) numMode = false; else mode = (mode + 1) % 3;
            lastKey = -1;
            redraw();
            return;
        }
        if (numMode) { finish(); commit(String.valueOf(d)); return; }
        String set = SETS[d];
        InputConnection ic = getCurrentInputConnection();
        if (again && punct == null && word.length() > 0) {      // next letter on the same key
            idx = (idx + 1) % set.length();
            word.setCharAt(word.length() - 1, letterUp ? Character.toUpperCase(set.charAt(idx)) : set.charAt(idx));
        } else {
            if (punct != null) { ic.finishComposingText(); punct = null; }
            idx = 0;
            letterUp = mode != LOWER;
            if (mode == CAP) mode = LOWER;                      // Abc = one capital
            word.append(letterUp ? Character.toUpperCase(set.charAt(0)) : set.charAt(0));
        }
        autoSpace = false;
        ic.setComposingText(word, 1);
        suggest();
    }

    private void hold(int code) {
        int d = digit(code);
        if (d == 11) {                                          // hold #: the keyboard list
            android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (im != null) im.showInputMethodPicker();
            return;
        }
        if (d == 10) { numMode = !numMode; lastKey = -1; redraw(); return; }
        finish();
        commit(String.valueOf(d));
        lastKey = -1;
    }

    private void punct(String list, boolean again) {
        InputConnection ic = getCurrentInputConnection();
        if (again && punct != null && list.indexOf(punct.charAt(0)) >= 0) {
            idx = (idx + 1) % list.length();
        } else {
            boolean wasAuto = autoSpace;
            finish();
            if (wasAuto && list == P1) {                        // "word ." -> "word."
                CharSequence b = ic.getTextBeforeCursor(1, 0);
                if (b != null && b.length() == 1 && b.charAt(0) == ' ') ic.deleteSurroundingText(1, 0);
            }
            idx = 0;
        }
        plist = list;
        punct = String.valueOf(list.charAt(idx));
        ic.setComposingText(punct, 1);
        redraw();
    }

    /** Ends the word or mark being typed: it stays as it is. */
    private void finish() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null && (word.length() > 0 || punct != null)) ic.finishComposingText();
        if (word.length() > 0 && !secret) words.learn(word.toString());
        word.setLength(0); punct = null; sugg.clear(); sel = -1; autoSpace = false;
        redraw();
    }

    private void commit(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(s, 1);
    }

    void pick(int i) {
        if (i < 0 || i >= sugg.size()) return;
        String w = sugg.get(i);
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.commitText(w + " ", 1);
        if (!secret) words.learn(w);
        word.setLength(0); sugg.clear(); sel = -1; lastKey = -1;
        autoSpace = true;
        redraw();
    }

    /** Back / delete: the last letter typed, else the character before the cursor. False = the box is
     *  empty, so back does its usual job. */
    private boolean delete() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return false;
        lastKey = -1; autoSpace = false;
        if (punct != null) { ic.setComposingText("", 1); ic.finishComposingText(); punct = null; redraw(); return true; }
        if (word.length() > 0) {
            word.setLength(word.length() - 1);
            if (word.length() == 0) { ic.commitText("", 1); sugg.clear(); sel = -1; redraw(); }
            else { ic.setComposingText(word, 1); suggest(); }
            return true;
        }
        CharSequence s = ic.getSelectedText(0);
        if (s != null && s.length() > 0) { ic.commitText("", 1); return true; }
        CharSequence b = ic.getTextBeforeCursor(2, 0);
        if (b == null || b.length() == 0) return false;
        int n = b.length() == 2 && Character.isSurrogatePair(b.charAt(0), b.charAt(1)) ? 2 : 1;
        ic.deleteSurroundingText(n, 0);
        return true;
    }

    /** The word before the cursor (and the spaces after it); false when there's nothing left. */
    private boolean deleteWord() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return false;
        lastKey = -1; autoSpace = false;
        if (punct != null || word.length() > 0) {
            ic.setComposingText("", 1); ic.finishComposingText();
            punct = null; word.setLength(0); sugg.clear(); sel = -1; redraw();
            return true;
        }
        CharSequence s = ic.getSelectedText(0);
        if (s != null && s.length() > 0) { ic.commitText("", 1); return true; }
        CharSequence b = ic.getTextBeforeCursor(64, 0);
        if (b == null || b.length() == 0) return false;
        int i = b.length();
        while (i > 0 && Character.isWhitespace(b.charAt(i - 1))) i--;
        while (i > 0 && !Character.isWhitespace(b.charAt(i - 1))) i--;
        ic.deleteSurroundingText(b.length() - i, 0);
        return true;
    }

    /** Moves the cursor one character; false at either end of the text (so the key can leave the box). */
    private boolean move(int dir) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return false;
        ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
        if (et == null || et.text == null) return false;
        int a = et.startOffset + Math.min(et.selectionStart, et.selectionEnd), b = et.startOffset + Math.max(et.selectionStart, et.selectionEnd);
        int to;
        if (a != b) to = dir < 0 ? a : b;                      // a selection: go to its edge
        else {
            to = a + dir;
            int end = et.startOffset + et.text.length();
            if (to < 0 || to > end) return false;
            if (to > 0 && to < end && dir < 0 && Character.isLowSurrogate(et.text.charAt(to - et.startOffset))) to--;
            if (to > 0 && to < end && dir > 0 && Character.isLowSurrogate(et.text.charAt(to - et.startOffset))) to++;
        }
        ic.setSelection(to, to);
        return true;
    }

    private void suggest() {
        sugg = secret || word.length() == 0 ? new ArrayList<String>() : words.suggest(word.toString(), 4);
        sel = -1;
        redraw();
    }

    String modeTag() {
        if (numMode) return "123";
        return mode == UPPER ? "ABC" : mode == CAP ? "Abc" : "abc";
    }

    private void redraw() { if (strip != null) strip.invalidate(); }
}
