import io
import json
from pathlib import Path

import pytest
from PIL import Image, ImageDraw

import covers

MANIFEST = json.loads((Path(__file__).parent.parent / "classics" / "manifest.json").read_text(encoding="utf-8"))
SECTIONS = {s["id"]: s["title"] for s in MANIFEST["sections"]}


def open_cover(data: bytes) -> Image.Image:
    return Image.open(io.BytesIO(data)).convert("RGB")


def cover(title="Pride and Prejudice", author="Jane Austen", translator=None, section="novels"):
    return covers.cover_jpeg(title, author, translator, section, SECTIONS[section])


def rgb(hex_colour: str) -> tuple[int, int, int]:
    return tuple(int(hex_colour[i:i + 2], 16) for i in (1, 3, 5))


def near(a, b, tolerance=6) -> bool:
    return all(abs(x - y) <= tolerance for x, y in zip(a, b))


def test_cover_is_a_600x900_jpeg():
    image = Image.open(io.BytesIO(cover()))
    assert image.format == "JPEG" and image.size == (600, 900)


def test_cover_is_deterministic():
    args = ("Meditations", "Marcus Aurelius", "George Long", "philosophy")
    assert cover(*args) == cover(*args)


@pytest.mark.parametrize("section", sorted(covers.GROUNDS))
def test_each_section_has_its_ground(section):
    image = open_cover(cover("Emma", "Jane Austen", None, section))
    for xy in ((10, 10), (590, 450), (10, 890)):
        assert near(image.getpixel(xy), rgb(covers.GROUNDS[section])), (section, xy)


def test_grounds_are_distinct_and_unknown_sections_fall_back():
    assert len(set(covers.GROUNDS.values())) == len(covers.GROUNDS) == len(SECTIONS)
    image = open_cover(covers.cover_jpeg("Emma", "Jane Austen", None, "other", "Other"))
    assert near(image.getpixel((10, 10)), rgb(covers.DEFAULT_GROUND))


def test_owl_and_wordmark_sit_at_the_foot():
    image = open_cover(cover())
    owl = image.crop((252, 690, 348, 786))
    assert any(near(p, rgb(covers.LANTERN), 40) for p in owl.get_flattened_data())  # the eyes
    assert any(near(p, rgb(covers.PAPER), 20) for p in owl.get_flattened_data())  # the mark


def _text_clear_of_margins(image: Image.Image, ground: str) -> None:
    """Nothing but ground in the side margins, or between the text block and the owl."""
    target = rgb(ground)
    for box in ((0, 0, 30, 900), (570, 0, 600, 900)):
        assert all(near(p, target, 12) for p in image.crop(box).get_flattened_data()), box


def _longest(field):
    return max(MANIFEST["works"], key=lambda w: len(w.get(field) or ""))


@pytest.mark.parametrize("field", ["title", "author", "translator"])
def test_longest_manifest_text_fits(field):
    w = _longest(field)
    data = covers.cover_jpeg(w["title"], w.get("author", ""), w.get("translator"), w["section"],
                             SECTIONS[w["section"]])
    _text_clear_of_margins(open_cover(data), covers.GROUNDS[w["section"]])


def test_longest_title_author_and_translator_together_fit_in_two_lines_each():
    title, author, translator = (_longest(f)[f] for f in ("title", "author", "translator"))
    draw = ImageDraw.Draw(Image.new("RGB", (600, 900)))
    blocks = covers._layout(draw, title, author, translator, 468, 528, 650)
    (_, title_lines, _, _), _, (_, author_lines, author_font, _), (_, credit_lines, credit_font, _) = blocks
    assert " ".join(title_lines) == title and len(title_lines) <= covers.MAX_TITLE_LINES
    assert " ".join(author_lines) == author and len(author_lines) <= 2
    assert " ".join(credit_lines) == f"translated by {translator}" and len(credit_lines) <= 2
    assert all(draw.textlength(line, font=author_font) <= 528 for line in author_lines)
    assert all(draw.textlength(line, font=credit_font) <= 528 for line in credit_lines)
    _text_clear_of_margins(open_cover(cover(title, author, translator, "libretti")), covers.GROUNDS["libretti"])


def test_no_ellipsis_and_no_lone_article():
    draw = ImageDraw.Draw(Image.new("RGB", (600, 900)))
    blocks = covers._layout(draw, "The Dhammapada", "", "F. Max Müller", 468, 528, 650)
    assert blocks[0][1] == ["The Dhammapada"]
    blocks = covers._layout(draw, "The Wealth of Nations", "Adam Smith", None, 468, 528, 650)
    assert blocks[0][1] == ["The Wealth", "of Nations"]


def test_anonymous_work_still_renders():
    assert open_cover(cover("Beowulf", "", "Francis Barton Gummere", "poetry")).size == (600, 900)


def test_owl_mask_is_even_odd():
    view, (eyes, pupils, mark) = covers.owl_paths()
    mask = covers._mask(mark, view, 96)
    assert mask.size == (96, 96) and 0 < sum(mask.get_flattened_data()) < 96 * 96 * 255
