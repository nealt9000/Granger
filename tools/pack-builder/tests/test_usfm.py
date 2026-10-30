import re
from collections import Counter

from bs4 import BeautifulSoup

from conftest import fixture
from usfm import book_pages, convert, inline, parse


def sample():
    return parse(fixture("psalms-sample.usfm").decode())


def html():
    return "".join(c[2] for c in sample().chapters)


def test_book_title_comes_from_h_and_chapters_from_c_with_the_cl_label():
    book = sample()
    assert book.code == "PSA" and book.title == "Psalms" and book.subtitle == "The Book of Psalms"
    assert [(number, heading) for number, heading, _ in book.chapters] == [("1", "Psalm 1"), ("2", "Psalm 2")]
    soup = BeautifulSoup(html(), "html.parser")
    assert [h.get_text() for h in soup.find_all("h3")] == ["BOOK 1", "Psalm 1", "Psalm 2"]
    assert soup.find("h3", id="c2").get_text() == "Psalm 2"


def test_without_a_label_a_chapter_is_chapter_n():
    book = parse("\\id GEN\n\\h Genesis\n\\c 1\n\\p\n\\v 1 In the beginning.\n\\c 2\n\\p\n\\v 1 Thus.\n")
    assert [heading for _, heading, _ in book.chapters] == ["Chapter 1", "Chapter 2"]
    assert '<h3 id="c1">Chapter 1</h3><p><sup class="v">1</sup>\u00a0In the beginning.</p>' == book.chapters[0][2]


def test_verse_numbers_are_small_superscripts():
    soup = BeautifulSoup(html(), "html.parser")
    assert [s.get_text() for s in soup.select("sup.v")] == ["1", "2", "1", "2", "3"]


def test_poetry_becomes_verse_lines_with_indents_and_stanza_gaps():
    soup = BeautifulSoup(sample().chapters[0][2], "html.parser")
    block = soup.select_one("div.gv")
    lines = block.find_all("span", class_="gv-l")
    assert [line.get_text() for line in lines] == [
        "1\u00a0Blessed is the man who doesn’t walk,", "nor stand on the path of sinners;",
        "nor sit in the seat of scoffers.", "2\u00a0His delight is in the Lord’s law. Selah."]
    assert [line.get("style") for line in lines] == [None, "margin-left: 1em", "margin-left: 2em", None]
    assert [c["class"] for c in block.children][3] == ["gv-gap"]


def test_section_headings_are_h4_and_parallel_references_are_dropped():
    soup = BeautifulSoup(html(), "html.parser")
    assert [h.get_text() for h in soup.find_all("h4")] == ["The Two Paths", "A smaller heading"]
    assert "Matthew 5" not in html()


def test_the_major_section_stays_above_its_chapter_heading():
    first = sample().chapters[0][2]
    assert first.index("BOOK 1") < first.index("Psalm 1") < first.index("The Two Paths")


def test_footnotes_cross_references_figures_and_word_data_are_dropped():
    text = html()
    assert "footnote" not in text and "Jer 17" not in text and "picture" not in text
    assert "strong" not in text and "H1870" not in text and "\\" not in text
    assert "Blessed is the man who" in text


def test_nd_is_small_caps_and_wj_add_and_w_keep_only_their_text():
    soup = BeautifulSoup(html(), "html.parser")
    assert soup.select_one("span.nd").get_text() == "the Lord"
    p = soup.select("p")
    said = next(x for x in p if "Jesus" in x.get_text())
    assert said.get_text().startswith("1\u00a0Jesus said, “Follow me.” He surely went on. 2\u00a0The prose")
    assert said.find("span") is None


def test_prose_paragraphs_descriptive_titles_and_escaping():
    soup = BeautifulSoup(sample().chapters[1][2], "html.parser")
    assert soup.select_one("p.d").get_text() == "A Psalm of David."
    assert soup.select_one("p.m").get_text() == "3\u00a0The last verse < the first."
    assert "on &amp; on" in sample().chapters[1][2]


def test_unbalanced_character_markers_are_closed():
    assert inline("\\nd Lord and \\bk Book", Counter()) == '<span class="nd">Lord and <i>Book</i></span>'
    unknown = Counter()
    assert inline("a \\zz odd\\zz* b", unknown) == "a odd b" and unknown == {"zz": 1}


def test_a_chapter_can_start_mid_paragraph():
    book = parse("\\id JHN\n\\h John\n\\c 1\n\\p\n\\v 1 One.\n\\c 2 \\v 1 Two.\n")
    assert book.chapters[1][2] == '<h3 id="c2">Chapter 2</h3><p><sup class="v">1</sup>\u00a0Two.</p>'


def test_a_one_chapter_book_has_no_chapter_heading():
    book = parse("\\id OBA\n\\h Obadiah\n\\c 1\n\\p\n\\v 1 The vision.\n")
    assert "<h3" not in book_pages(book, 150_000)[0].html
    book = parse("\\id PS2\n\\h Psalm 151\n\\c 1\n\\cp 151\n\\d A psalm.\n\\q1\n\\v 1 I was small.\n")
    assert book.chapters[0][:2] == ("151", "Chapter 151") and "<h3" not in book.chapters[0][2]


def test_one_page_per_book_and_front_matter_books_are_skipped():
    books = [("FRT", "\\id FRT\n\\h Preface\n\\ip A preface."), ("PSA", fixture("psalms-sample.usfm").decode()),
             ("OBA", "\\id OBA\n\\h Obadiah\n\\c 1\n\\p\n\\v 1 The vision.\n")]
    pages, skipped, unknown = convert(books, 150_000)
    assert [p.title for p in pages] == ["Psalms", "Obadiah"] and skipped == ["FRT"] and not unknown
    assert pages[0].html.startswith('<h2>Psalms</h2><p class="mt">The Book of Psalms</p>')


def test_a_big_book_splits_evenly_at_chapter_boundaries():
    usfm = "\\id PSA\n\\h Psalms\n" + "".join(f"\\c {n}\n\\q1\n\\v 1 {'word ' * 300}\n" for n in range(1, 151))
    pages = book_pages(parse(usfm), 100_000)
    assert [p.title for p in pages] == ["Psalms 1–50", "Psalms 51–100", "Psalms 101–150"]
    assert all(len(p.html.encode()) <= 100_000 for p in pages)
    assert [len(re.findall("<h3", p.html)) for p in pages] == [50, 50, 50]
    assert pages[0].html.startswith("<h2>Psalms 1–50</h2>") and pages[1].html.startswith("<h2>Psalms 51–100</h2>")


def test_a_verse_left_empty_once_its_footnote_is_gone_loses_its_number():
    book = parse("\\id SIR\n\\h Sirach\n\\c 1\n\\q1\n\\v 4 Wisdom. \\v 5 \\f + \\fr 1:5 \\ft Omitted.\\f*\n"
                 "\\q1\n\\v 6 To whom? \\v 7\n")
    assert [s.get_text() for s in BeautifulSoup(book.chapters[0][2], "html.parser").select("sup.v")] == ["4", "6"]
