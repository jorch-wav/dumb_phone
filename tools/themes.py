#!/usr/bin/env python3
"""Make the dumb_phone colour themes.

The hand-made themes are listed below. Every terminal theme in ../themes (ghostty format, from
github.com/mbadolato/iTerm2-Color-Schemes, MIT) is turned into the same 7 colours:
background, cell, highlight, text, dim, rule, accent. Then the list is written into each app's
Theme.java and into recolor-tt9.py.

  python3 tools/themes.py            write the files
  python3 tools/themes.py --preview  also draw screenshots/themes-all.png (needs Pillow)
"""
import colorsys, os, re, sys, unicodedata

TOP = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# name: (background, cell, highlight, text, dim, rule, accent)
HAND = [
    ("phosphor",    (0x040605, 0x0B1008, 0x16240E, 0x95EE62, 0x446B2D, 0x294A16, 0xFFB347)),
    ("amber",       (0x080502, 0x140E05, 0x2A1D0A, 0xFFB347, 0x7A5524, 0x4A3416, 0xFFE3A3)),
    ("nord",        (0x2E3440, 0x3B4252, 0x434C5E, 0x88C0D0, 0x616E88, 0x4C566A, 0xEBCB8B)),
    ("gruvbox",     (0x1D2021, 0x282828, 0x3C3836, 0xB8BB26, 0x7C6F64, 0x504945, 0xFE8019)),
    ("dracula",     (0x21222C, 0x282A36, 0x44475A, 0xBD93F9, 0x6272A4, 0x44475A, 0xFF79C6)),
    ("catppuccin",  (0x1E1E2E, 0x262637, 0x45475A, 0x89B4FA, 0x6C7086, 0x45475A, 0xF9E2AF)),
    ("solarized",   (0x002B36, 0x073642, 0x0E4B5A, 0x2AA198, 0x586E75, 0x0E4B5A, 0xB58900)),
    ("tokyo night", (0x1A1B26, 0x24283B, 0x2F3549, 0x7AA2F7, 0x565F89, 0x414868, 0xE0AF68)),
    ("mono",        (0x000000, 0x0D0D0D, 0x222222, 0xE6E6E6, 0x6E6E6E, 0x333333, 0xFFFFFF)),
    ("paper",       (0xE9E6DE, 0xDDD9CF, 0xC9C3B5, 0x1E1E1E, 0x6B675E, 0xB3AD9F, 0xA0521C)),
    ("everforest",  (0x2D353B, 0x343F44, 0x475258, 0xA7C080, 0x859289, 0x4F585E, 0xDBBC7F)),
    ("rose pine",   (0x191724, 0x1F1D2E, 0x26233A, 0xEBBCBA, 0x6E6A86, 0x403D52, 0xF6C177)),
    ("kanagawa",    (0x1F1F28, 0x2A2A37, 0x363646, 0xDCD7BA, 0x727169, 0x54546D, 0xFF9E3B)),
    ("monokai",     (0x272822, 0x2E2E2A, 0x3E3D32, 0xA6E22E, 0x75715E, 0x49483E, 0xF92672)),
    ("one dark",    (0x282C34, 0x2C313A, 0x3E4451, 0x61AFEF, 0x5C6370, 0x3E4451, 0xE5C07B)),
    ("ayu",         (0x0B0E14, 0x11151C, 0x1C212B, 0x39BAE6, 0x565B66, 0x242936, 0xFFB454)),
    ("crimson",     (0x070202, 0x140606, 0x2A0C0C, 0xFF5555, 0x7A2A2A, 0x4A1616, 0xFFB3B3)),
    ("latte",       (0xEFF1F5, 0xE6E9EF, 0xCCD0DA, 0x4C4F69, 0x8C8FA1, 0xBCC0CC, 0x1E66F5)),
    # from the picture of Jake with the walkman: the night sky in the window, Jake's yellow, his cream socks
    ("jake",        (0x030A75, 0x0B1280, 0x1A2290, 0xEDE8D0, 0x8E8DA8, 0x2A3196, 0xE2CF45)),
]
# terminal themes we already have by hand (or as near copies)
SKIP = {"phosphor", "amber", "nord", "dracula", "everforest", "tokyo night", "catppuccin mocha",
        "catppuccin latte", "gruvbox dark", "solarized dark", "rose pine", "kanagawa wave", "monokai pro"}


def rgb(h): return ((h >> 16) & 255, (h >> 8) & 255, h & 255)
def hexi(c): return (round(c[0]) << 16) | (round(c[1]) << 8) | round(c[2])
def mix(a, b, t): a, b = rgb(a), rgb(b); return hexi([a[i] + (b[i] - a[i]) * t for i in range(3)])


def lum(h):
    def ch(v):
        v /= 255
        return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4
    r, g, b = rgb(h)
    return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b)


def contrast(a, b):
    x, y = sorted((lum(a), lum(b)))
    return (y + 0.05) / (x + 0.05)


def hue_gap(a, b):
    ha, la, sa = colorsys.rgb_to_hls(*[v / 255 for v in rgb(a)])
    hb, lb, sb = colorsys.rgb_to_hls(*[v / 255 for v in rgb(b)])
    d = abs(ha - hb); d = min(d, 1 - d)
    return d if min(sa, sb) > 0.25 else abs(la - lb) / 3      # greys: only lightness counts


def ascii_name(n):
    return unicodedata.normalize("NFKD", n).encode("ascii", "ignore").decode().lower()


def convert(path):
    pal, bg, fg = {}, None, None
    for line in open(path, encoding="utf-8"):
        m = re.match(r"\s*palette\s*=\s*(\d+)=#([0-9a-fA-F]{6})", line)
        if m: pal[int(m.group(1))] = int(m.group(2), 16)
        m = re.match(r"\s*(background|foreground)\s*=\s*#([0-9a-fA-F]{6})", line)
        if m:
            if m.group(1) == "background": bg = int(m.group(2), 16)
            else: fg = int(m.group(2), 16)
    if bg is None or fg is None: return None
    # accent: a colour that stands out from both the background and the text (yellow first, like amber)
    acc = None
    for i in (11, 3, 13, 9, 14, 12, 10, 5, 1, 6, 4, 2):
        c = pal.get(i)
        if c is not None and contrast(c, bg) >= 3 and hue_gap(c, fg) > 0.08:
            acc = c; break
    if acc is None: acc = max(pal.values(), key=lambda c: contrast(c, bg))
    return (bg, mix(bg, fg, 0.06), mix(bg, fg, 0.14), fg, mix(bg, fg, 0.5), mix(bg, fg, 0.24), acc)


def themes():
    out = list(HAND)
    have = {n for n, _ in HAND}
    extra = []
    for f in sorted(os.listdir(os.path.join(TOP, "themes"))):
        n = ascii_name(f)
        if n in SKIP or n in have: continue
        t = convert(os.path.join(TOP, "themes", f))
        if t and contrast(t[0], t[3]) >= 4: extra.append((n, t))
    return out + extra


def java_block(ts):
    names = ", ".join('"%s"' % n for n, _ in ts)
    rows = "\n".join("            {%s}," % ", ".join("0xFF%06X" % c for c in t) for _, t in ts)
    return names, rows


def write_java(ts):
    names, rows = java_block(ts)
    for app in ("home", "phone", "radio", "podcasts", "snake", "trains", "keypad"):
        p = os.path.join(TOP, app, "src", "dumb_phone", app, "Theme.java")
        s = open(p).read()
        s = re.sub(r"static final String\[\] NAMES = \{.*?\};", "static final String[] NAMES = {" + names + "};", s, flags=re.S)
        s = re.sub(r"static final int\[\]\[\] P = \{.*?\n    \};", "static final int[][] P = {\n" + rows + "\n    };", s, flags=re.S)
        s = re.sub(r"static boolean light\(\) \{.*?\}", "static boolean light() { return (((VOID >> 16) & 255) * 299 + ((VOID >> 8) & 255) * 587 + (VOID & 255) * 114) / 1000 > 128; }", s, flags=re.S)
        open(p, "w").write(s)


def write_tt9(ts):
    p = os.path.join(TOP, "recolor-tt9.py")
    s = open(p).read()
    rows = "\n".join('    "%s": (%s),' % (n.replace(" ", "-"), ", ".join("0xFF%06X" % c for c in t)) for n, t in ts)
    s = re.sub(r"THEMES = \{.*?\n\}", "THEMES = {\n" + rows + "\n}", s, flags=re.S)
    open(p, "w").write(s)


def preview(ts):
    from PIL import Image, ImageDraw, ImageFont
    font = ImageFont.truetype(os.path.join(TOP, "fonts", "JetBrainsMono-Bold.ttf"), 13)
    small = ImageFont.truetype(os.path.join(TOP, "fonts", "JetBrainsMono-Regular.ttf"), 10)
    W, H, cols = 150, 92, 6
    img = Image.new("RGB", (W * cols, H * ((len(ts) + cols - 1) // cols)), (0, 0, 0))
    d = ImageDraw.Draw(img)
    for i, (n, t) in enumerate(ts):
        x, y = (i % cols) * W, (i // cols) * H
        bg, cell, lit, fg, dim, rule, acc = [rgb(c) for c in t]
        d.rectangle([x, y, x + W - 2, y + H - 2], fill=bg)
        d.text((x + 6, y + 4), "16:48", font=font, fill=fg)
        d.text((x + 62, y + 6), "sat 03", font=small, fill=acc)
        d.line([x + 6, y + 22, x + W - 8, y + 22], fill=rule)
        d.rectangle([x + 6, y + 27, x + 70, y + 55], fill=lit, outline=fg)
        d.text((x + 10, y + 34), "radio", font=small, fill=acc)
        d.rectangle([x + 74, y + 27, x + W - 8, y + 55], fill=cell)
        d.text((x + 78, y + 34), "notes", font=small, fill=fg)
        d.text((x + 6, y + 62), n[:20], font=small, fill=fg)
        d.text((x + 6, y + 75), "dim text", font=small, fill=dim)
    out = os.path.join(TOP, "screenshots", "themes-all.png")
    img.save(out)
    print("preview:", out)


if __name__ == "__main__":
    ts = themes()
    write_java(ts)
    write_tt9(ts)
    print(len(ts), "themes:", ", ".join(n for n, _ in ts))
    if "--preview" in sys.argv: preview(ts)
