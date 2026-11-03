import zipfile

import cards

CARD = {
    "slug": "pride-and-prejudice", "title": "Pride and Prejudice", "author": "Jane Austen", "published": 1813,
    "kind": "novel", "firstLine": "It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife.",
    "begins": "Mrs. Bennet tells her husband that Netherfield Park is let at last.",
    "happens": "Elizabeth Bennet meets Mr. Darcy.",
    "characters": [{"name": "Elizabeth Bennet", "who": "the heroine"}, {"name": "the narrator", "who": "unnamed"}],
}
TEXT = "Chapter I\n\nIT is a truth universally acknowledged, that a single man in possession\nof a good fortune, must be in want of a wife. Elizabeth Bennet ..."


def test_a_card_true_to_its_text_has_no_problems():
    # Case, line breaks and punctuation don't matter to the first line; an unnamed character isn't searched for.
    assert cards.problems(CARD, TEXT) == []


def test_a_first_line_or_name_not_in_the_text_and_a_long_card_are_problems():
    bad = dict(CARD, firstLine="It was a dark and stormy night.", characters=[{"name": "Mr. Darcy", "who": "x" * 1600}])
    ps = cards.problems(bad, TEXT)
    assert any(p.startswith("first line not in the text") for p in ps)
    assert "name not in the text: Mr. Darcy" in ps
    assert any("over 1600" in p for p in ps)
    assert cards.problems(dict(CARD, begins=""), TEXT) == ["missing begins"]


def test_each_section_names_its_book():
    secs = cards.sections(CARD)
    assert [h for h, _ in secs] == ["Pride and Prejudice: how it begins", "Pride and Prejudice: what happens",
                                    "Pride and Prejudice: who's who"]
    assert all("Pride and Prejudice" in p for _, p in secs)
    assert "(a novel by Jane Austen, 1813)" in secs[0][1]
    assert cards.sections(dict(CARD, kind="epic poem", author=""))[0][1].startswith("How Pride and Prejudice (an epic poem, 1813)")


def test_build_writes_an_epub_with_three_files_a_card(tmp_path):
    out = tmp_path / "notes.epub"
    cards.build([CARD, dict(CARD, slug="emma", title="Emma")], out, "2026-11-02")
    with zipfile.ZipFile(out) as z:
        assert z.namelist()[0] == "mimetype" and z.getinfo("mimetype").compress_type == zipfile.ZIP_STORED
        assert sum(n.endswith(".xhtml") and n.startswith("OEBPS/c") for n in z.namelist()) == 6
        assert 'href="c003.xhtml">Emma<' in z.read("OEBPS/nav.xhtml").decode()


def test_a_translation_gives_both_dates():
    c = dict(CARD, slug="war-and-peace", title="War and Peace", author="Leo Tolstoy", published=1923,
             translator="Aylmer Maude and Louise Maude")
    assert "(a novel by Leo Tolstoy, 1865–1869; tr. Aylmer Maude and Louise Maude, 1923)" in cards.sections(c)[0][1]
    assert cards.year(dict(c, slug="other")) == "tr. Aylmer Maude and Louise Maude, 1923"


def test_a_first_line_that_is_speech_keeps_its_own_quotation_marks():
    assert cards.quoted("“TOM!”") == "“TOM!”"
    assert cards.quoted("It is a truth.") == "“It is a truth.”"
