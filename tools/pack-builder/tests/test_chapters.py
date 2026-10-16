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
    chapters = split(body("<h2>  CHAPTER\n  I.<br/>Loomings  </h2><p>t</p>" + "<h2>B</h2><h2>" + "y" * 300 + "</h2>"))
    assert chapters[0].title == "CHAPTER I. Loomings"
    assert len(chapters[2].title) == 120


def test_page_names_are_three_digits():
    assert page_name(0) == "001" and page_name(41) == "042"


def test_page_numbers_are_left_out_of_titles():
    html = "".join(f'<h2><a id="c{i}"></a><span class="pagenum"><a id="p{i}">{{{i}}}</a></span>Chapter {i}</h2>'
                   for i in range(3))
    assert [c.title for c in split(body(html))] == ["Chapter 0", "Chapter 1", "Chapter 2"]
