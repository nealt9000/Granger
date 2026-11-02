"""Draft rebranded Classics covers, three directions, on four sample books. Usage: draft.py REPO out.png"""
import re, sys
from PIL import Image, ImageDraw, ImageFont

REPO, OUT = sys.argv[1], sys.argv[2]
sys.path.insert(0, REPO + "/docs/superpowers/brand/2026-10-27-owl-mark/redraw")
from render_svg import mask

FONTS = REPO + "/app/src/main/res/font/"
svg = open(REPO + "/docs/superpowers/brand/2026-10-27-owl-mark/granger-mark-owl.svg").read()
D = float(re.search(r'viewBox="0 0 ([\d.]+)', svg).group(1))
PATHS = re.findall(r' d="([^"]+)"', svg)
hx = lambda c: tuple(int(c[i:i + 2], 16) for i in (0, 2, 4))
f = lambda name, px: ImageFont.truetype(FONTS + f"spectral_{name}.ttf", px)

ENAMEL, GUNMETAL, NIGHT, PAPER, INK, LANTERN, PHOSPHOR, BRASS = (
    "2E4A45", "24292A", "181C1D", "EFE4D0", "1F1813", "F0A35E", "91C4BA", "A87B52")
STYLES = {
    "A  Gunmetal (the app's own cover tile)": dict(ground=GUNMETAL, title=PAPER, sub="CFC4B0", rule=LANTERN,
        eyes=LANTERN, pupils=GUNMETAL, mark=PAPER, word=PHOSPHOR, owl="foot"),
    "B  Enamel (the launcher)": dict(ground=ENAMEL, title=PAPER, sub="D6E0DA", rule=LANTERN,
        eyes=LANTERN, pupils=ENAMEL, mark=PAPER, word=PAPER, owl="head"),
    "C  Field paper": dict(ground=PAPER, title=INK, sub="4A453F", rule=BRASS,
        eyes=LANTERN, pupils=ENAMEL, mark=ENAMEL, word=ENAMEL, owl="foot"),
}
BOOKS = [("Paradise Lost", "John Milton", None, "Epic & poetry"),
         ("Meditations", "Marcus Aurelius", "George Long", "Philosophy"),
         ("The King James Bible", "", None, "Scripture & religion"),
         ("The Rime of the Ancient Mariner", "Samuel Taylor Coleridge", None, "Epic & poetry")]
W, H = 600, 900


def owl(img, size, xy, s):
    for d, k in zip(PATHS, ("eyes", "pupils", "mark")):
        img.paste(Image.new("RGB", (size, size), hx(s[k])), xy, mask(d, D, size))


def tracked(d, cx, y, text, font, track, fill):
    width = sum(font.getlength(c) for c in text) + track * font.size * (len(text) - 1)
    x = cx - width / 2
    for c in text:
        d.text((x, y), c, font=font, fill=fill, anchor="ls")
        x += font.getlength(c) + track * font.size


def wrap(d, text, font, box):
    lines, line = [], ""
    for w in text.split():
        t = f"{line} {w}".strip()
        if d.textlength(t, font=font) <= box: line = t
        else: lines.append(line); line = w
    return lines + [line]


def cover(book, s):
    title, author, translator, section = book
    img = Image.new("RGB", (W, H), hx(s["ground"]))
    d = ImageDraw.Draw(img)
    if s["owl"] == "head":
        owl(img, 150, ((W - 150) // 2, 70), s)
        top = 300
    else:
        tracked(d, W / 2, 96, section.upper(), f("semibold", 20), 0.14, hx(s["word"]))
        top = 250
    for px in range(64, 30, -2):
        tf = f("regular", px)
        lines = wrap(d, title, tf, int(W * 0.78))
        if len(lines) <= 4: break
    y = top
    for line in lines:
        d.text((W / 2, y), line, font=tf, fill=hx(s["title"]), anchor="mt")
        y += int(px * 1.22)
    y += 30
    d.rectangle((W / 2 - 44, y, W / 2 + 44, y + 3), fill=hx(s["rule"]))
    y += 34
    if author:
        d.text((W / 2, y), author, font=f("italic", 32), fill=hx(s["sub"]), anchor="mt")
        y += 46
    if translator:
        d.text((W / 2, y), f"translated by {translator}", font=f("regular", 22), fill=hx(s["sub"]), anchor="mt")
    if s["owl"] == "foot":
        owl(img, 96, ((W - 96) // 2, H - 210), s)
        tracked(d, W / 2, H - 70, "GRANGER CLASSICS", f("semibold", 20), 0.14, hx(s["word"]))
    else:
        tracked(d, W / 2, H - 70, "GRANGER CLASSICS", f("semibold", 20), 0.14, hx(s["word"]))
    return img


SCALE, PAD, LAB = 0.5, 24, 44
cw, ch = int(W * SCALE), int(H * SCALE)
board = Image.new("RGB", (PAD + len(BOOKS) * (cw + PAD), PAD + len(STYLES) * (ch + LAB + PAD)), hx("3A3F40"))
bd = ImageDraw.Draw(board)
lab = ImageFont.truetype(REPO + "/app/src/main/res/font/courier_prime_regular.ttf", 22)
for r, (name, s) in enumerate(STYLES.items()):
    y0 = PAD + r * (ch + LAB + PAD)
    bd.text((PAD, y0 + 26), name, font=lab, fill=hx(PAPER), anchor="ls")
    for c, book in enumerate(BOOKS):
        board.paste(cover(book, s).resize((cw, ch), Image.LANCZOS), (PAD + c * (cw + PAD), y0 + LAB))
board.save(OUT)
