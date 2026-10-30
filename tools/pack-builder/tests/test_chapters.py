import re

from bs4 import BeautifulSoup

from chapters import page_name, split


def body(html):
    return BeautifulSoup(f"<html><body>{html}</body></html>", "lxml").body


def test_splits_at_the_first_heading_level_used_three_times():
    chapters = split(body("<h1>Book</h1>" + "".join(f"<h2>Chapter {i}</h2><p>{'x ' * 50}</p>" for i in range(1, 6))))
    assert [c.title for c in chapters] == [f"Chapter {i}" for i in range(1, 6)]


def test_short_front_matter_merges_into_the_first_chapter():
    chapters = split(body("<p>By A. Author</p>" + "".join(f"<h2>C{i}</h2><p>t</p>" for i in range(3))))
    assert len(chapters) == 3 and "By A. Author" in chapters[0].html


def test_long_front_matter_becomes_its_own_page():
    chapters = split(body(f"<p>{'preface ' * 60}</p>" + "".join(f"<h2>C{i}</h2><p>t</p>" for i in range(3))))
    assert chapters[0].title == "Front matter" and len(chapters) == 4


def test_split_at_override_wins():
    html = "".join(f"<h2>Act {a}</h2>" + "".join(f"<h3>Scene {s}</h3><p>t</p>" for s in range(3)) for a in range(3))
    assert len(split(body(html), split_at="h3")) == 9


def test_oversized_part_is_split_at_the_next_level():
    big = "".join(f"<h3>Sub {i}</h3><p>{'w' * 60_000}</p>" for i in range(4))
    html = "<h2>Big</h2>" + big + "<h2>Small</h2><p>t</p><h2>Last</h2><p>t</p>"
    chapters = split(body(html), max_bytes=150_000)
    assert all(len(c.html.encode()) <= 150_000 for c in chapters)
    assert [c.title for c in chapters[:4]] == [f"Big — Sub {i}" for i in range(4)]
    assert [c.title for c in chapters[4:]] == ["Small", "Last"]


def test_book_without_headings_splits_by_size():
    chapters = split(body("".join(f"<p>{'z' * 1000}</p>" for i in range(400))), max_bytes=150_000)
    assert len(chapters) == 3 and chapters[0].title == "Part 1"
    assert all(len(c.html.encode()) <= 150_000 for c in chapters)


def test_headings_inside_wrappers_still_split():
    html = "".join(f'<div class="chapter"><h2>C{i}</h2><p>t{i}</p></div>' for i in range(3))
    chapters = split(body(html))
    assert [c.title for c in chapters] == ["C0", "C1", "C2"] and "t1" in chapters[1].html


def test_cross_chapter_anchor_is_rewritten():
    html = ('<h2>One</h2><p><a href="#n1">1</a> <a href="pg9-images.html#n1">again</a></p>'
            '<h2>Two</h2><p>t</p><h2>Notes</h2><p id="n1">Note one.</p>')
    chapters = split(body(html))
    assert 'href="003#n1"' in chapters[0].html and chapters[0].html.count("003#n1") == 2


def test_same_chapter_anchor_stays_local():
    chapters = split(body('<h2>A</h2><p><a href="#x">x</a></p><p id="x">t</p><h2>B</h2><h2>C</h2>'))
    assert 'href="#x"' in chapters[0].html


def test_unknown_anchor_is_unwrapped():
    chapters = split(body('<h2>A</h2><p><a href="#gone">kept text</a></p><h2>B</h2><h2>C</h2>'))
    assert "kept text" in chapters[0].html and "gone" not in chapters[0].html


def test_heading_titles_are_whitespace_collapsed_and_capped():
    chapters = split(body("<h2>  CHAPTER\n  I.<br/>Loomings  </h2><p>t</p>" + "<h2>B</h2><p>t</p><h2>" + "y" * 300 + "</h2><p>t</p>"))
    assert chapters[0].title == "CHAPTER I. Loomings"
    assert len(chapters[2].title) == 120


def test_page_names_are_three_digits():
    assert page_name(0) == "001" and page_name(41) == "042"


def test_page_numbers_are_left_out_of_titles():
    html = "".join(f'<h2><a id="c{i}"></a><span class="pagenum"><a id="p{i}">{{{i}}}</a></span>Chapter {i}</h2><p>t</p>'
                   for i in range(3))
    assert [c.title for c in split(body(html))] == ["Chapter 0", "Chapter 1", "Chapter 2"]


def test_footnote_markers_are_left_out_of_titles():
    html = "".join(f'<h2>SURA<a href="#fn{i}"><sup>1</sup></a> {i}</h2><p id="fn{i}">note</p>' for i in range(3))
    assert [c.title for c in split(body(html))] == ["SURA 0", "SURA 1", "SURA 2"]


def test_a_heading_only_part_merges_into_the_next():
    html = ("<h2>PART I</h2>" + "".join(f"<h2>Chapter {i}</h2><p>t{i}</p>" for i in range(1, 3))
            + "<h2>PART II</h2><h2>Chapter 3</h2><p>t3</p>")
    chapters = split(body(html))
    assert [c.title for c in chapters] == ["PART I — Chapter 1", "Chapter 2", "PART II — Chapter 3"]
    assert "PART I" in chapters[0].html and "t1" in chapters[0].html


def test_clean_title_drops_byline_and_contents():
    from chapters import clean_title
    assert clean_title("by William Shakespeare — Contents", "Hamlet", "William Shakespeare", 0) == "Part 1"
    assert clean_title("ACT I — Scene II", "Hamlet", "William Shakespeare", 3) == "Act I — Scene II"
    assert clean_title("Hamlet — Table of Contents", "Hamlet", "", 4) == "Part 5"
    assert clean_title("Hamlet — ACT I", "Hamlet", "", 0) == "Act I"


def test_clean_title_strips_numeric_markers():
    from chapters import clean_title
    assert clean_title("The Knight's Tale <1>", "Tales", "Chaucer", 2) == "The Knight's Tale"


def test_unwrapped_link_keeps_its_id():
    chapters = split(body('<h2>A</h2><p><a name="top" href="#nowhere">text</a></p><h2>B</h2><p>x</p><h2>C</h2>'))
    assert '<span id="top">text</span>' in chapters[0].html
    assert "<a" not in chapters[0].html


def test_page_href_names_cross_page_targets():
    html = '<h2>A</h2><p><a href="#z">to Z</a></p><h2>B</h2><p>b</p><h2 id="z">C</h2><p>x</p>'
    chapters = split(body(html), page_href=lambda i: f"c{i + 1:03d}.xhtml")
    assert 'href="c003.xhtml#z"' in chapters[0].html


# The Faerie Queene's indent rules, which match only while each line sits in its stanza and poem.
POEM_RULES = [".poem p", ".poem p.i2", ".poem p.i20", ".poem .stanza", "div.poem > div.stanza > p.i2"]


def dead_rules(html, rules=POEM_RULES):
    """Like the audit's deadrules.py: a rule is dead on a page when its last part matches more than the whole."""
    page = BeautifulSoup(html, "lxml")
    return [r for r in rules if len(page.select(r)) < len(page.select(r.replace(">", " ").split()[-1]))]


def stanza(n):
    lines = "".join(f'<p class="i2">Line {n}.{i}, {"faire and gentle " * 4}</p>' for i in range(8))
    return f'<div class="stanza"><p class="i20">{n}</p><p>First {n}</p>{lines}</div>'


def test_a_poem_split_by_size_keeps_its_wrapper_on_every_page():
    html = '<h2>Canto I</h2><div class="poem" id="canto-1">' + "".join(stanza(n) for n in range(60)) + "</div>"
    chapters = split(body(html), max_bytes=20_000)
    assert len(chapters) >= 3
    for c in chapters:
        assert dead_rules(c.html) == [], c.title
        assert len(c.html.encode()) <= 20_000
    assert "".join(c.html for c in chapters).count('id="canto-1"') == 1
    assert 'id="canto-1"' in chapters[0].html
    assert all(BeautifulSoup(c.html, "lxml").select("div.stanza") for c in chapters)


def test_a_stanza_too_big_for_a_page_keeps_both_wrappers():
    html = '<h2>Canto I</h2><div class="poem"><div class="stanza">' + "".join(
        f'<p class="i2">Line {i} {"word " * 40}</p>' for i in range(200)) + "</div></div>"
    chapters = split(body(html), max_bytes=10_000)
    assert len(chapters) >= 3
    for c in chapters:
        assert dead_rules(c.html) == [] and len(c.html.encode()) <= 10_000


def test_a_wrapper_opened_at_headings_keeps_its_rules():
    html = ('<div class="book" id="b" lang="la">' + "".join(
        f'<h2>Canto {i}</h2><div class="poem"><div class="stanza"><p class="i2">l{i}</p></div></div><p>prose {i}</p>'
        for i in range(3)) + "</div>")
    chapters = split(body(html))
    assert [c.title for c in chapters] == ["Canto 0", "Canto 1", "Canto 2"]
    for c in chapters:
        page = BeautifulSoup(c.html, "lxml")
        assert dead_rules(c.html, [".book h2", ".book p", ".book .poem p.i2"]) == []
        assert page.select_one("div.book")["lang"] == "la"
    assert 'id="b"' in chapters[0].html and 'id="b"' not in chapters[1].html + chapters[2].html


def test_nested_wrappers_opened_at_headings_are_each_kept():
    html = ('<div class="volume">' + "".join(
        f'<div class="part">' + "".join(f"<h2>C{p}{c}</h2><p>t</p>" for c in range(2)) + "</div>" for p in range(2))
        + "</div>")
    chapters = split(body(html))
    assert len(chapters) == 4
    for c in chapters:
        assert dead_rules(c.html, [".volume .part h2", ".volume .part p"]) == []


def test_clean_title_quietens_titles_in_capitals():
    from chapters import clean_title
    def t(title):
        return clean_title(title, "Paradise Lost", "John Milton", 0)
    assert t("BOOK I.") == "Book I"
    assert t("CANTO THE SECOND.") == "Canto the Second"
    assert t("THE ARGUMENT.") == "The Argument"
    assert t("BOOK IV. THE ARGUMENT.") == "Book IV. The Argument"
    assert t("LOVE’S LABOUR’S LOST — ’TIS PITY") == "Love’s Labour’s Lost — ’Tis Pity"
    assert t("THE TROJAN-WOMEN") == "The Trojan-Women"
    assert t("NOTES ON THE U.S.A.") == "Notes on the U.S.A."
    assert t("QUESTIONS AND TOPICS (part 2)") == "Questions and Topics (part 2)"
    assert t("SCENE I. A room in the castle.") == "SCENE I. A room in the castle."  # not all capitals
    assert t("THE END...") == "The End..."


def test_a_definition_list_split_by_size_keeps_each_term_with_its_definition():
    notes = "".join(f'<dt>{i}.</dt><dd>{"note text " * 30}</dd>' for i in range(80))
    chapters = split(body(f'<h2>Footnotes</h2><dl class="notes">{notes}</dl>'), max_bytes=6_000)
    assert len(chapters) >= 3
    for c in chapters:
        for dl in BeautifulSoup(c.html, "lxml").find_all("dl"):
            items = dl.find_all(["dt", "dd"], recursive=False)
            assert items[0].name == "dt" and items[-1].name == "dd", c.title


def bible(books):
    """Douay-shaped: each book an h3, each chapter an h4 '<Name> Chapter N' in a wrapper div."""
    html = '<div class="chapter"><h2>THE OLD TESTAMENT</h2></div>'
    for title, name, count, words in books:
        html += f'<div class="chapter"><h3>{title}</h3></div><p>About {name}.</p>'
        html += "".join(f'<div class="section"><h4>{name} Chapter {n}</h4></div><p>{n}:1. {"verse " * words}</p>'
                        for n in range(1, count + 1))
    return body(html)


def test_packed_sections_make_one_page_per_book_named_for_its_chapters():
    chapters = split(bible([("THE BOOK OF RUTH", "Ruth", 4, 50), ("THE BOOK OF JOB", "Job", 42, 50),
                            ("THE EPISTLE OF ST. PAUL TO PHILEMON", "Philemon", 1, 50)]),
                     split_at="h3", max_bytes=150_000, pack=True)
    assert [c.title for c in chapters] == ["Ruth", "Job", "Philemon"]
    assert "About Ruth." in chapters[0].html and "Ruth Chapter 4" in chapters[0].html


def test_packed_sections_split_a_big_book_evenly_at_chapter_boundaries():
    chapters = split(bible([("THE BOOK OF PSALMS", "Psalms", 150, 200), ("THE BOOK OF RUTH", "Ruth", 4, 50)]),
                     split_at="h3", max_bytes=60_000, pack=True)
    titles = [c.title for c in chapters]
    assert titles[-1] == "Ruth" and titles[0].startswith("Psalms 1–")
    assert all(re.fullmatch(r"Psalms \d+–\d+", t) for t in titles[:-1]), titles
    sizes = [len(c.html.encode()) for c in chapters[:-1]]
    assert max(sizes) <= 60_000 and min(sizes) > 0.7 * max(sizes)
    numbers = [int(n) for t in titles[:-1] for n in t.split()[1].split("–")]
    assert numbers[0] == 1 and numbers[-1] == 150
    assert all(b == a + 1 for a, b in zip(numbers[1::2], numbers[2::2]))  # no chapter lost or repeated
    assert "About Psalms." in chapters[0].html


def test_without_pack_a_big_book_still_splits_one_page_per_chapter():
    chapters = split(bible([("THE BOOK OF PSALMS", "Psalms", 20, 600)]), split_at="h3", max_bytes=60_000)
    assert len(chapters) == 20


def test_packed_sections_name_a_book_by_its_chapters_past_a_preface():
    html = ('<h3>THE LAMENTATIONS OF JEREMIAS</h3><h4>PREFACE</h4><p>pre</p>'
            + "".join(f"<h4>Lamentations Chapter {n}</h4><p>v</p>" for n in range(1, 6))
            + '<h3>THE BOOK OF RUTH</h3><h4>Ruth Chapter 1</h4><p>v</p>'
            + '<h3>THE GOSPEL OF ST. MARK</h3><h4>Mark Chapter 1</h4><p>v</p>')
    chapters = split(body(html), split_at="h3", pack=True)
    assert [c.title for c in chapters] == ["Lamentations", "Ruth", "Mark"]
