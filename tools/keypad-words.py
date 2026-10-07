#!/usr/bin/env python3
"""Make keypad/assets/words.txt: the suggestion word list for the dumb_phone keyboard.

Source: the English (UK) word list of Android's own keyboard (AOSP LatinIME, Apache 2.0), with its
word frequencies. Keeps the most common words (letters and apostrophes only), drops ones flagged
offensive or not-a-word. One line per word, "word<TAB>frequency", sorted by the word in lower case
with apostrophes removed (that's what the keyboard searches by: typing "dont" finds "don't").
"""
import base64, gzip, os, re, sys, urllib.request

N = int(sys.argv[1]) if len(sys.argv) > 1 else 40000
URL = ("https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/"
       "dictionaries/en_GB_wordlist.combined.gz?format=TEXT")
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "keypad", "assets", "words.txt")

text = gzip.decompress(base64.b64decode(urllib.request.urlopen(URL, timeout=60).read())).decode("utf-8")
best = {}
for line in text.splitlines():
    m = re.match(r"^ word=([^,]+),f=(\d+),flags=([a-z_]*)", line)
    if not m:
        continue
    w, f, flags = m.group(1), int(m.group(2)), m.group(3)
    if f == 0 or flags in ("offensive", "nonword") or not re.fullmatch(r"[A-Za-z][A-Za-z']*", w):
        continue
    if len(w) == 1 and w not in ("a", "I"):
        continue
    key = w.lower().replace("'", "")
    if w not in best or best[w] < f:
        best[w] = f
words = sorted(best.items(), key=lambda x: -x[1])[:N]
words.sort(key=lambda x: (x[0].lower().replace("'", ""), -x[1]))
with open(out, "w") as o:
    for w, f in words:
        o.write(f"{w}\t{f}\n")
print(f"{len(words)} words -> {os.path.normpath(out)} ({os.path.getsize(out) // 1024} KB)")
