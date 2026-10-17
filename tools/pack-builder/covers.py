"""Draws a book cover: the flame mark, the title and the author on a near-black ground."""
import io
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ASSETS = Path(__file__).parent / "assets"
GROUND, INK, FLAME = "#0E0E0E", "#ECE6DA", "#E8601C"
MAX_TITLE_LINES = 5
MARK_SIZE = 190


def cover_jpeg(title: str, author: str, size: tuple[int, int] = (600, 900)) -> bytes:
    width, height = size
    image = Image.new("RGB", size, GROUND)
    draw = ImageDraw.Draw(image)

    mark = Image.open(ASSETS / "flame-mark.png").convert("RGBA").resize((MARK_SIZE, MARK_SIZE), Image.LANCZOS)
    image.paste(Image.new("RGB", mark.size, FLAME), ((width - MARK_SIZE) // 2, height // 3 // 2 - MARK_SIZE // 2),
                mark.getchannel("A"))

    box = int(width * 0.8)
    title_font, title_lines = _fit(draw, title, box, MAX_TITLE_LINES, 64, 22)
    y = height * 5 // 12
    y = _lines(draw, title_lines, title_font, width, y, 1.2)
    y += 36
    draw.rectangle((width // 2 - 60, y, width // 2 + 60, y + 3), fill=FLAME)
    if author.strip():
        author_font, author_lines = _fit(draw, author, box, 2, 34, 18)
        _lines(draw, author_lines, author_font, width, y + 36, 1.3)

    out = io.BytesIO()
    image.save(out, "JPEG", quality=85)
    return out.getvalue()


def _font(px: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(ASSETS / "fonts" / "EBGaramond.ttf"), px)


def _fit(draw, text: str, box: int, max_lines: int, start: int, floor: int):
    """The largest font size at which the text wraps into at most max_lines lines no wider than box.

    At the floor size an overlong text is cut with an ellipsis rather than overflowing.
    """
    for px in range(start, floor - 1, -2):
        font = _font(px)
        lines = _wrap(draw, text, font, box)
        if len(lines) <= max_lines:
            return font, lines
    font = _font(floor)
    lines = _wrap(draw, text, font, box)[:max_lines]
    last = lines[-1]
    while last and draw.textlength(last + "…", font=font) > box:
        last = last[:-1]
    lines[-1] = last.rstrip() + "…"
    return font, lines


def _wrap(draw, text: str, font, box: int) -> list[str]:
    lines: list[str] = []
    line = ""
    for word in text.split():
        while draw.textlength(word, font=font) > box:  # a single word wider than the box is broken up
            cut = len(word)
            while cut > 1 and draw.textlength(word[:cut], font=font) > box:
                cut -= 1
            if line:
                lines.append(line)
                line = ""
            lines.append(word[:cut])
            word = word[cut:]
        trial = f"{line} {word}".strip()
        if draw.textlength(trial, font=font) <= box:
            line = trial
        else:
            lines.append(line)
            line = word
    if line:
        lines.append(line)
    return lines


def _lines(draw, lines: list[str], font, width: int, y: int, spacing: float) -> int:
    step = int(font.size * spacing)
    for line in lines:
        draw.text((width // 2, y), line, font=font, fill=INK, anchor="mt")
        y += step
    return y
