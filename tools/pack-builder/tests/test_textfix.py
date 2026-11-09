"""Text-file structure put back: the Analects' and the Tao Te Ching's real source, scripture refs, note marks."""
from bs4 import BeautifulSoup

import textfix
from conftest import FIXTURES


def body_of(html):
    return BeautifulSoup(f"<div id='b'>{html}</div>", "lxml").find(id="b")


def fixture(name):
    return body_of((FIXTURES / "textfix" / name).read_text())


def flat(tag):
    return " ".join(tag.get_text().split())


def test_analects_hidden_starts_split_page_breaks_join_and_labels_promote():
    body = fixture("analects-book1.html")
    counts = textfix.restore(body, min_hidden=5)
    assert counts["split"] >= 20 and counts["joined"] >= 1
    textfix.promote(body)
    assert body.find("h2").get_text() == "Book I. Hsio R." or flat(body.find("h2")).startswith("CONFUCIAN")
    chapters = [flat(h) for h in body.select("h3.gx-chap")]
    assert chapters[:4] == ["Chapter 1", "Chapter 2", "Chapter 3", "Chapter 4"]
    # Chapter II verse 2 was split by a page break: one paragraph again, with its number set apart
    verse = next(p for p in body.find_all("p") if "superior man bends" in p.get_text())
    assert verse.select_one(".gx-n").get_text() == "2" and "That being established" in verse.get_text()
    assert "CHAP." not in body.get_text()
    third = body.select("h3.gx-chap")[2].find_next("p")
    assert flat(third).startswith("The Master said, 'Fine words")  # no verse number: a one-verse chapter


def test_a_book_with_too_few_hidden_starts_or_pretty_printed_html_is_left_alone():
    body = fixture("tao-1-4.html")  # every line indented: pretty-printing, not hidden starts
    assert textfix.restore(body) == {"hidden": 0, "split": 0, "joined": 0}


def test_tao_chapters_from_its_numbers():
    body = fixture("tao-1-4.html")
    textfix.promote(body)
    assert [flat(h) for h in body.select(".gx-chap")] == ["Chapter 1", "Chapter 2", "Chapter 3", "Chapter 4"]
    first = body.select_one(".gx-chap").find_next("p")
    assert first.select_one(".gx-n").get_text() == "1" and "gx-v" in first["class"]
    assert flat(first).startswith("1 The Tao that can be trodden")


def test_verse_refs_start_their_own_paragraphs():
    body = body_of("<p>36:15 These were dukes of the sons of Esau: duke Kenaz, 36:16 Duke Korah, duke Gatam.</p>"
                   "<p>1:1. In the beginning God created heaven, and earth.</p>")
    assert textfix.verse_refs(body) == 3
    paras = body.find_all("p")
    assert [p.select_one(".gx-n").get_text() for p in paras] == ["36:15", "36:16", "1:1"]
    assert flat(paras[1]) == "36:16 Duke Korah, duke Gatam."


def test_glued_note_marks():
    body = body_of("<p>the prophets before thee,g and have firm assurance of the life to come:h these</p>")
    assert textfix.note_markers(body, "letters") == 2
    assert [s.get_text() for s in body.select("sup.gx-ref")] == ["g", "h"]
    body = body_of("<p>Did he not find thee an orphan2 and gave thee a home? In 1910 the</p>")
    assert textfix.note_markers(body, "digits") == 1 and body.sup.get_text() == "2"
