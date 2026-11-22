"""Renders the iPhone icon's dark and tinted variants for review (Plan I7 call 7: traced, not drawn).

    ~/.venvs/granger-vectors/bin/python -I tools/ios/make_appicon_variants.py OUT_DIR

Every variant is the launcher mark's own layers from tools/brand/granger-mark.svg (the eyes, the pupils, the owl in the
G), placed as make_appicon.py places them (the rim circle at 58/72 of the icon), in colours Android already uses:

- light: the current icon (make_appicon.py): paper owl, lantern eyes, night pupils on enamel;
- dark: the same three colours on a transparent background, so iOS draws its own dark backdrop behind them;
- tinted-a: Android's themed-icon layer (ic_launcher_monochrome: the owl and the pupils in one colour, the eyes cut
  out), white on black, for iOS to tint;
- tinted-b: the light icon's layers in grey by their lightness (owl white, eyes light grey, pupils black) on black.

Writes 1024 px PNGs of each, and mocks of each on a light and a dark home screen (the dark and tinted ones with the
backdrop and tint iOS gives them, approximated). Nothing goes into the asset catalog: Neal picks first. Needs fontTools
and Pillow (the granger-vectors venv).
"""
import re
import sys
from pathlib import Path

from fontTools.pens.basePen import BasePen
from fontTools.svgLib.path import parse_path
from PIL import Image, ImageChops, ImageDraw, ImageFont, ImageOps

ROOT = Path(__file__).resolve().parents[2]
ENAMEL, PAPER, LANTERN, NIGHT = "#2E4A45", "#EFE4D0", "#F0A35E", "#181C1D"
SIDE = 1024
SHARE = 58 / 72  # make_appicon.py: the rim circle's diameter over the launcher's visible area
SS = 4  # supersampling


class Flatten(BasePen):
    """Collects each subpath as a polygon, curves cut into short lines."""

    def __init__(self, scale, offset, steps=24):
        super().__init__(None)
        self.scale, self.offset, self.steps = scale, offset, steps
        self.polys, self.cur = [], []

    def _pt(self, p):
        return (p[0] * self.scale + self.offset, p[1] * self.scale + self.offset)

    def _moveTo(self, p):
        self.cur = [self._pt(p)]

    def _lineTo(self, p):
        self.cur.append(self._pt(p))

    def _curveToOne(self, p1, p2, p3):
        p0 = self._getCurrentPoint()
        for i in range(1, self.steps + 1):
            t = i / self.steps
            mt = 1 - t
            x = mt**3 * p0[0] + 3 * mt * mt * t * p1[0] + 3 * mt * t * t * p2[0] + t**3 * p3[0]
            y = mt**3 * p0[1] + 3 * mt * mt * t * p1[1] + 3 * mt * t * t * p2[1] + t**3 * p3[1]
            self.cur.append(self._pt((x, y)))

    def _qCurveToOne(self, p1, p2):
        p0 = self._getCurrentPoint()
        for i in range(1, self.steps + 1):
            t = i / self.steps
            mt = 1 - t
            self.cur.append(self._pt((mt * mt * p0[0] + 2 * mt * t * p1[0] + t * t * p2[0], mt * mt * p0[1] + 2 * mt * t * p1[1] + t * t * p2[1])))

    def _closePath(self):
        if len(self.cur) > 2:
            self.polys.append(self.cur)
        self.cur = []

    _endPath = _closePath


def layers():
    svg = (ROOT / "tools/brand/granger-mark.svg").read_text()
    view = float(re.search(r'viewBox="0 0 ([\d.]+) ', svg).group(1))
    out = {"mark": re.findall(r'<path fill-rule="evenodd" d="([^"]+)"/>', svg)[0]}
    for name in ("eyes", "pupils"):
        out[name] = re.search(rf'<path class="{name}" fill-rule="evenodd" d="([^"]+)"/>', svg).group(1)
    return out, view


def mask(d, view, side):
    """An even-odd filled mask of path [d] at [side] px, the mark placed as make_appicon.py places it."""
    big = side * SS
    scale = big * SHARE / view
    pen = Flatten(scale, (big - view * scale) / 2)
    parse_path(d, pen)
    m = Image.new("1", (big, big), 0)
    for poly in pen.polys:
        one = Image.new("1", (big, big), 0)
        ImageDraw.Draw(one).polygon(poly, fill=1)
        m = ImageChops.logical_xor(m, one)
    return m.convert("L").resize((side, side), Image.LANCZOS)


def paint(base, m, colour):
    """[colour] through mask [m] over [base], composited straight (no dark fringe on a transparent base)."""
    layer = Image.new("RGBA", base.size, colour)
    layer.putalpha(ImageChops.multiply(m, layer.getchannel("A")))
    base.alpha_composite(layer)


def variants(side=SIDE):
    ls, view = layers()
    eyes, pupils, mark = (mask(ls[k], view, side) for k in ("eyes", "pupils", "mark"))
    out = {}
    for name, bg in (("light", ENAMEL), ("dark", (0, 0, 0, 0))):
        im = Image.new("RGBA", (side, side), bg)
        paint(im, eyes, LANTERN)
        paint(im, pupils, NIGHT)
        paint(im, mark, PAPER)
        out[name] = im
    # Android's themed icon: the pupils and the owl in one colour; the eyes are holes (the background shows).
    a = Image.new("RGBA", (side, side), "#000000")
    paint(a, pupils, "#FFFFFF")
    paint(a, mark, "#FFFFFF")
    out["tinted-a"] = a
    # The colour icon's layers by lightness: paper white, lantern a light grey, night black.
    b = Image.new("RGBA", (side, side), "#000000")
    lantern_grey = round(sum(w * int(LANTERN[i:i + 2], 16) for w, i in ((0.299, 1), (0.587, 3), (0.114, 5))))
    paint(b, eyes, (lantern_grey,) * 3 + (255,))
    paint(b, pupils, "#000000")
    paint(b, mark, "#FFFFFF")
    out["tinted-b"] = b
    return out


def squircle(side):
    """iOS's icon shape, approximated by a rounded square with corners 22.37% of the side, as an anti-aliased mask."""
    big = side * SS
    m = Image.new("L", (big, big), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, big - 1, big - 1], radius=round(big * 0.2237), fill=255)
    return m.resize((side, side), Image.LANCZOS)


def backdrop_dark(side):
    """iOS's dark backdrop behind a transparent dark icon: a near-black vertical gradient."""
    top, bottom = (58, 58, 60), (18, 18, 20)
    g = Image.new("RGBA", (side, side))
    d = ImageDraw.Draw(g)
    for y in range(side):
        t = y / (side - 1)
        d.line([(0, y), (side, y)], fill=tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3)) + (255,))
    return g


def tint(icon, colour):
    """iOS's tinted look, approximated: the icon's lightness becomes the tint's strength over a dark backdrop."""
    grey = ImageOps.grayscale(icon.convert("RGB"))
    base = backdrop_dark(icon.size[0])
    fg = Image.new("RGBA", icon.size, colour)
    return Image.composite(fg, base, grey)


def wallpaper(w, h, dark):
    stops = [(20, 32, 38), (44, 30, 52)] if dark else [(214, 228, 240), (246, 226, 214)]
    g = Image.new("RGBA", (w, h))
    d = ImageDraw.Draw(g)
    for y in range(h):
        t = y / (h - 1)
        d.line([(0, y), (w, y)], fill=tuple(round(stops[0][i] + (stops[1][i] - stops[0][i]) * t) for i in range(3)) + (255,))
    return g


def font(size):
    for f in ("/System/Library/Fonts/SFNS.ttf", "/System/Library/Fonts/HelveticaNeue.ttc", "/System/Library/Fonts/Helvetica.ttc"):
        try:
            return ImageFont.truetype(f, size)
        except OSError:
            continue
    return ImageFont.load_default()


def mock(shown, dark, neighbours):
    """A strip of a home screen: four icons in a row with labels, Granger's second."""
    icon, gap, pad = 240, 84, 72
    w = pad * 2 + icon * 4 + gap * 3
    h = pad * 2 + icon + 70
    im = wallpaper(w, h, dark)
    shape = squircle(icon)
    label = font(34)
    names = ["Notes", "Granger", "Maps", "Books"]
    for i in range(4):
        x = pad + i * (icon + gap)
        art = shown.resize((icon, icon), Image.LANCZOS) if i == 1 else neighbours[i].resize((icon, icon), Image.LANCZOS)
        im.paste(art, (x, pad), shape)
        d = ImageDraw.Draw(im)
        tw = d.textlength(names[i], font=label)
        d.text((x + (icon - tw) / 2, pad + icon + 16), names[i], font=label, fill=(255, 255, 255) if dark else (20, 20, 20))
    return im


def neighbour(side, colour, dark_mode, tinted):
    """A plain stand-in app icon: a disc on a colour, shown as iOS would in the same mode."""
    im = Image.new("RGBA", (side, side), colour if not (dark_mode or tinted) else (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    disc = (255, 255, 255) if not (dark_mode or tinted) else colour
    d.ellipse([side * 0.3, side * 0.3, side * 0.7, side * 0.7], fill=disc)
    if tinted:
        return tint(Image.alpha_composite(Image.new("RGBA", im.size, (0, 0, 0, 255)), im), TINT)
    if dark_mode:
        return Image.alpha_composite(backdrop_dark(side), im)
    return im


TINT = (232, 176, 96, 255)  # an amber tint, one of iOS's choices


def main(out: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    v = variants()
    for name, im in v.items():
        (im if name == "dark" else im.convert("RGB")).save(out / f"AppIcon-{name}-1024.png")
    # How each looks on a home screen. Light mode: the light icon. Dark mode: the dark icon over iOS's backdrop.
    # Tinted mode: the tinted icon through an amber tint.
    colours = [(250, 204, 21), None, (52, 199, 89), (255, 149, 0)]
    shown = {
        "light": v["light"],
        "dark": Image.alpha_composite(backdrop_dark(SIDE), v["dark"]),
        "tinted-a": tint(v["tinted-a"], TINT),
        "tinted-b": tint(v["tinted-b"], TINT),
    }
    for name, art in shown.items():
        for wall in ("light", "dark"):
            dark_mode = name == "dark"
            tinted = name.startswith("tinted")
            n = [neighbour(256, c, dark_mode, tinted) if c else None for c in colours]
            mock(art, wall == "dark", n).convert("RGB").save(out / f"mock-{name}-on-{wall}.png")
    print(out)


if __name__ == "__main__":
    main(Path(sys.argv[1]))
