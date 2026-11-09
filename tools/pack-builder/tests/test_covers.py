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


def test_a_clasicos_cover_carries_its_own_wordmark_and_credit(monkeypatch):
    drawn = []
    real = covers._tracked
    monkeypatch.setattr(covers, "_tracked",
                        lambda draw, cx, y, text, *a: drawn.append(text) or real(draw, cx, y, text, *a))
    credits = []
    real_fit = covers._fit
    monkeypatch.setattr(covers, "_fit",
                        lambda draw, text, *a, **k: credits.append(text) or real_fit(draw, text, *a, **k))
    covers.cover_jpeg("Nuevo Testamento", "", "Casiodoro de Reina", "escritura", "Escritura",
                      wordmark="CLÁSICOS GRANGER", translated_by="traducción de")
    assert "CLÁSICOS GRANGER" in drawn and covers.WORDMARK not in drawn
    assert "traducción de Casiodoro de Reina" in credits


MANIFEST_ES = json.loads((Path(__file__).parent.parent / "classics" / "manifest_es.json").read_text(encoding="utf-8"))


@pytest.mark.parametrize("section, english", [("escritura", "scripture"), ("ensayo", "philosophy"),
                                              ("historia", "philosophy"), ("poesia", "poetry"), ("teatro", "drama"),
                                              ("novelas", "novels"), ("cuentos", "novels"), ("universales", "novels")])
def test_clasicos_sections_wear_the_nearest_english_ground(section, english):
    assert covers.ground_for(section) == covers.GROUNDS[english]
    image = open_cover(covers.cover_jpeg("Fábulas", "Tomás de Iriarte", None, section, "Sección"))
    assert near(image.getpixel((10, 10)), rgb(covers.GROUNDS[english]))


def test_every_clasicos_section_has_a_ground_and_the_learner_ones_share_a_new_one():
    assert {s["id"] for s in MANIFEST_ES["sections"]} == set(covers.SPANISH_GROUNDS)
    learner = {covers.ground_for("lecturas"), covers.ground_for("infantil")}
    assert learner == {covers.LEARNER_GROUND} and covers.LEARNER_GROUND in covers.LEARNER_CANDIDATES.values()
    assert not set(covers.LEARNER_CANDIDATES.values()) & set(covers.GROUNDS.values())


def _contrast(a: str, b: str) -> float:
    def lum(h):
        return sum(w * (c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4)
                   for w, c in zip((0.2126, 0.7152, 0.0722), (v / 255 for v in rgb(h))))
    hi, lo = sorted((lum(a), lum(b)), reverse=True)
    return (hi + 0.05) / (lo + 0.05)


@pytest.mark.parametrize("ground", sorted(covers.LEARNER_CANDIDATES.values()))
def test_learner_candidates_are_as_dark_as_the_other_grounds(ground):
    assert _contrast(covers.PAPER, ground) >= 7.6 and _contrast(covers.PHOSPHOR, ground) >= 5.0


def test_a_ground_can_be_given_for_a_board():
    image = open_cover(covers.cover_jpeg("Fábulas", "Tomás de Iriarte", None, "infantil", "Infantil", ground="#1E3D4A"))
    assert near(image.getpixel((10, 10)), rgb("#1E3D4A"))


def test_spanish_titles_do_not_end_a_line_on_an_article():
    draw = ImageDraw.Draw(Image.new("RGB", (600, 900)))
    def title(text, language="es"):
        return covers._layout(draw, text, "Tucídides", None, 468, 528, 650, "traducción de", language)[0][1]
    assert title("Historia de la guerra del Peloponeso (tomo 1 de 2)") == [
        "Historia", "de la guerra", "del Peloponeso", "(tomo 1 de 2)"]
    assert title("El filibusterismo") == ["El filibusterismo"]  # "El" never stands alone
    assert title("Libro de buen amor") == ["Libro de", "buen amor"]  # a line may end on "de"
    # English covers keep their approved wraps
    assert title("Heart of Darkness", "en") == ["Heart of", "Darkness"]


def test_the_longest_spanish_covers_fit():  # all 185 take half a minute; the dozen longest stand in
    sections = {s["id"]: s["title"] for s in MANIFEST_ES["sections"]}
    longest = sorted(MANIFEST_ES["works"], key=lambda w: -len(w["title"] + w.get("author", "") +
                                                             (w.get("translator") or "")))[:12]
    for w in longest:
        covers.cover_jpeg(w["title"], w.get("author", ""), w.get("translator"), w["section"], sections[w["section"]],
                          wordmark="CLÁSICOS GRANGER", translated_by="traducción de", language="es")
