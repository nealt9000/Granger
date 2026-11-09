"""Draws a Granger Classics cover (brand direction D): the section in tracked capitals, the title, a lantern rule,
the author and translator, and the owl over "GRANGER CLASSICS" (or "CLÁSICOS GRANGER"), on one ground colour per
section.

The owl is rasterised here from `assets/granger-mark-owl.svg`, so the builder needs nothing outside its own tree.
"""
import io
import re
from functools import cache
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFont

ASSETS = Path(__file__).parent / "assets"
OWL_SVG = ASSETS / "granger-mark-owl.svg"
GROUNDS = {"scripture": "#5B3322", "philosophy": "#354059", "poetry": "#2E4A45", "drama": "#6B2A2A",
           "libretti": "#4A3A5A", "novels": "#24292A"}
DEFAULT_GROUND = GROUNDS["novels"]
# Grounds offered for Clásicos Granger's learner sections (graded readers, fables and children's books), each as dark
# as the six above (paper at least 7.6:1, phosphor at least 5:1). Neal picks one from the GC2 board
# (docs/superpowers/spikes/2026-11-09-classics-round-2/covers/); until then LEARNER_GROUND is a placeholder.
LEARNER_CANDIDATES = {"brass": "#53401B", "moss": "#3B4725", "pine": "#1E3D4A"}
LEARNER_GROUND = LEARNER_CANDIDATES["brass"]  # PLACEHOLDER: not yet picked by Neal
# Clásicos Granger's sections, each on the nearest English ground; the learner sections on their own.
SPANISH_GROUNDS = {"escritura": GROUNDS["scripture"], "ensayo": GROUNDS["philosophy"],
                   "historia": GROUNDS["philosophy"], "poesia": GROUNDS["poetry"], "teatro": GROUNDS["drama"],
                   "novelas": GROUNDS["novels"], "cuentos": GROUNDS["novels"], "universales": GROUNDS["novels"],
                   "lecturas": LEARNER_GROUND, "infantil": LEARNER_GROUND}
PAPER, SUB, LANTERN, PHOSPHOR = "#EFE4D0", "#CFC4B0", "#F0A35E", "#91C4BA"
WORDMARK = "GRANGER CLASSICS"
TRACK = 0.14
MAX_TITLE_LINES, MAX_NAME_LINES = 4, 2
TITLE_TOP, TITLE_TOP_MIN = 250, 170
OWL_SIZE = 96
# Short words that mustn't stand alone on a line, in English and in Spanish (Clásicos Granger).
LONE_WORDS = {"the", "a", "an", "of", "and", "or", "on", "in", "to", "at", "by", "for", "with",
              "el", "la", "los", "las", "lo", "un", "una", "de", "del", "al", "en", "con", "por", "para",
              "y", "o", "e", "u"}
# A line may end "Pride and", "Crimen y" or "Libro de", not "The Wealth of" or "Don Álvaro o la". (Spanish
# prepositions may end a line: "de" is too common to keep off a line's end without worse breaks.)
DANGLING_WORDS = LONE_WORDS - {"and", "or", "y", "o", "e", "u", "de", "a", "en", "con", "por", "para"}


def cover_jpeg(title: str, author: str, translator: str | None, section_id: str, section_title: str,
               size: tuple[int, int] = (600, 900), wordmark: str = WORDMARK,
               translated_by: str = "translated by", ground: str | None = None, language: str = "en") -> bytes:
    width, height = size
    ground = ground or ground_for(section_id)
    image = Image.new("RGB", size, ground)
    draw = ImageDraw.Draw(image)

    _tracked(draw, width / 2, 96, section_title.upper(), _font("semibold", 20), PHOSPHOR)
    owl_top = height - 210
    _paste_owl(image, OWL_SIZE, ((width - OWL_SIZE) // 2, owl_top), ground)
    _tracked(draw, width / 2, height - 70, wordmark, _font("semibold", 20), PHOSPHOR)

    blocks = _layout(draw, title, author, translator, int(width * 0.78), int(width * 0.88), owl_top - 40,
                     translated_by, language)
    for i, (y, lines, font, step) in enumerate(blocks):
        for line in lines:
            if line is None:  # the rule under the title
                draw.rectangle((width / 2 - 44, y, width / 2 + 44, y + 3), fill=LANTERN)
            else:
                draw.text((width / 2, y), line, font=font, fill=PAPER if i == 0 else SUB, anchor="mt")
            y += step

    out = io.BytesIO()
    image.save(out, "JPEG", quality=85)
    return out.getvalue()


def ground_for(section_id: str) -> str:
    """The section's ground colour: an English or a Spanish section id, else the novels' ground."""
    return GROUNDS.get(section_id) or SPANISH_GROUNDS.get(section_id) or DEFAULT_GROUND


def _layout(draw, title: str, author: str, translator: str | None, box: int, name_box: int, bottom: int,
            translated_by: str = "translated by", language: str = "en"):
    """The title, rule and names as (top, lines, font, line step) blocks that end above `bottom`.

    The names keep their size where they can. A long block first rises (up to TITLE_TOP_MIN), then the title
    shrinks, then the names shrink. Every text wraps at word boundaries and nothing is cut.
    """
    author = author.strip()
    credit = f"{translated_by} {translator.strip()}" if translator and translator.strip() else ""
    names = [(text, style, start, floor, spacing) for text, style, start, floor, spacing in
             ((author, "italic", 32, 24, 46 / 32), (credit, "regular", 22, 18, 30 / 22)) if text]

    for shrink in range(0, 16, 2):
        named = [_fit(draw, text, style, name_box, MAX_NAME_LINES, max(start - shrink, floor), spacing)
                 for text, style, start, floor, spacing in names]
        if not all(named):
            continue
        for px in range(64, 29, -2):
            fitted = _fit(draw, title, "regular", box, MAX_TITLE_LINES, px, 1.22, search=language == "es")
            if not fitted:
                continue
            for top in range(TITLE_TOP, TITLE_TOP_MIN - 1, -10):
                blocks = [(top, *fitted)]
                y = top + fitted[2] * len(fitted[0]) + 30
                blocks.append((y, [None], None, 0))
                y += 34
                for lines, font, step in named:
                    blocks.append((y, lines, font, step))
                    y += step * len(lines)
                if y <= bottom:
                    return blocks
    raise ValueError(f"cover text does not fit: {title!r} / {author!r} / {translator!r}")


@cache
def _font(style: str, px: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(ASSETS / "fonts" / f"spectral_{style}.ttf"), px)


def _fit(draw, text: str, style: str, box: int, max_lines: int, px: int, spacing: float, search: bool = False):
    """(lines, font, line step) for the text at `px`, or None if it needs more than max_lines lines, a word is wider
    than the box, or a short word like "The" would sit alone on a line.

    Of the wraps with the fewest lines, the one that doesn't end a line on an article or preposition ("The Wealth /
    of Nations", not "The Wealth of / Nations") is chosen; then the most even, so the last line isn't left with
    one word when it needn't be. With `search` (Spanish titles, where "de la" and "del" are everywhere), when every
    such wrap still ends a line on an article, every break with as many lines is tried ("Historia / de la guerra /
    del Peloponeso"). English covers keep the narrower wraps only, as Neal approved them.
    """
    font = _font(style, px)
    lines = _wrap(draw, text, font, box)
    if len(lines) > max_lines or any(draw.textlength(line, font=font) > box for line in lines):
        return None
    candidates = [lines]
    for narrower in range(box - 4, 0, -4):
        trial = _wrap(draw, text, font, narrower)
        if len(trial) > len(lines) or any(draw.textlength(line, font=font) > narrower for line in trial):
            break
        if trial != candidates[-1]:
            candidates.append(trial)
    candidates = [c for c in candidates if len(c) == 1 or not any(line.lower() in LONE_WORDS for line in c)]
    if not candidates:
        return None
    dangling = lambda c: sum(line.split()[-1].lower() in DANGLING_WORDS for line in c[:-1])
    least = min(map(dangling, candidates))
    if least and search:  # every narrower wrap still ends a line on "la" or "del": try every break, as many lines
        others = [c for c in _breaks(draw, text.split(), font, box, len(lines))
                  if not any(line.lower() in LONE_WORDS for line in c) and dangling(c) < least]
        if others:
            fewest = min(map(dangling, others))
            # the most even of those: the narrowest widest line
            return (min((c for c in others if dangling(c) == fewest),
                        key=lambda c: max(draw.textlength(line, font=font) for line in c)), font, int(px * spacing))
    candidates = [c for c in candidates if dangling(c) == least]
    lines = candidates[-1]
    return lines, font, int(px * spacing)


def _breaks(draw, words: list[str], font, box: int, count: int) -> list[list[str]]:
    """Every way to set the words on `count` lines that each fit the box."""
    if count == 1:
        line = " ".join(words)
        return [[line]] if words and draw.textlength(line, font=font) <= box else []
    out = []
    for i in range(1, len(words) - count + 2):
        first = " ".join(words[:i])
        if draw.textlength(first, font=font) > box:
            break
        out += [[first, *rest] for rest in _breaks(draw, words[i:], font, box, count - 1)]
    return out


def _wrap(draw, text: str, font, box: int) -> list[str]:
    """Greedy word wrap. A word wider than the box stays whole on its own line."""
    lines: list[str] = []
    line = ""
    for word in text.split():
        trial = f"{line} {word}".strip()
        if not line or draw.textlength(trial, font=font) <= box:
            line = trial
        else:
            lines.append(line)
            line = word
    return lines + [line] if line else lines


def _tracked(draw, cx: float, y: float, text: str, font, fill) -> None:
    """Letter-spaced text centred on cx, on the baseline y."""
    width = sum(font.getlength(c) for c in text) + TRACK * font.size * (len(text) - 1)
    x = cx - width / 2
    for c in text:
        draw.text((x, y), c, font=font, fill=fill, anchor="ls")
        x += font.getlength(c) + TRACK * font.size


def _paste_owl(image: Image.Image, size: int, xy: tuple[int, int], ground: str) -> None:
    """The owl: lantern eyes, pupils in the ground colour, the mark in paper."""
    view, (eyes, pupils, mark) = owl_paths()
    for d, colour in ((eyes, LANTERN), (pupils, ground), (mark, PAPER)):
        image.paste(Image.new("RGB", (size, size), colour), xy, _mask(d, view, size))


@cache
def owl_paths() -> tuple[float, tuple[str, str, str]]:
    """The owl's viewBox size and its three path strings: eyes, pupils, mark."""
    svg = OWL_SVG.read_text(encoding="utf-8")
    view = float(re.search(r'viewBox="0 0 ([\d.]+)', svg).group(1))
    eyes, pupils, mark = re.findall(r' d="([^"]+)"', svg)
    return view, (eyes, pupils, mark)


_TOKEN = re.compile(r"[MCLHVZ]|-?\d*\.?\d+(?:e-?\d+)?", re.I)


def _subpaths(d: str) -> list[list[tuple[float, float]]]:
    """The path's subpaths as polygons, Béziers flattened to 12 segments. Absolute M, L, H, V, C and Z only."""
    tokens = _TOKEN.findall(d)
    subs: list[list[tuple[float, float]]] = []
    i, command = 0, None
    x = y = 0.0

    def number():
        nonlocal i
        i += 1
        return float(tokens[i - 1])

    while i < len(tokens):
        if tokens[i].isalpha():
            command = tokens[i]
            i += 1
            if command in "Zz":
                continue
        if command == "M":
            x, y = number(), number()
            subs.append([(x, y)])
            command = "L"  # further pairs after M are lines
        elif command == "L":
            x, y = number(), number()
            subs[-1].append((x, y))
        elif command == "H":
            x = number()
            subs[-1].append((x, y))
        elif command == "V":
            y = number()
            subs[-1].append((x, y))
        elif command == "C":
            a, b, c = (number(), number()), (number(), number()), (number(), number())
            x0, y0 = x, y
            for step in range(1, 13):
                t = step / 12
                m = 1 - t
                subs[-1].append((m ** 3 * x0 + 3 * m * m * t * a[0] + 3 * m * t * t * b[0] + t ** 3 * c[0],
                                 m ** 3 * y0 + 3 * m * m * t * a[1] + 3 * m * t * t * b[1] + t ** 3 * c[1]))
            x, y = c
        else:
            raise ValueError(f"unsupported path command {command!r}")
    return subs


@cache
def _mask(d: str, view: float, size: int, supersample: int = 4) -> Image.Image:
    """An even-odd coverage mask of the path at size × size, drawn large and scaled down."""
    big = size * supersample
    scale = big / view
    acc = Image.new("1", (big, big), 0)
    for sub in _subpaths(d):
        layer = Image.new("1", acc.size, 0)
        ImageDraw.Draw(layer).polygon([(px * scale, py * scale) for px, py in sub], fill=1)
        acc = ImageChops.logical_xor(acc, layer)
    return acc.convert("L").resize((size, size), Image.LANCZOS)
