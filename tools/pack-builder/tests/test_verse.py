from bs4 import BeautifulSoup

import verse


def marked(html):
    soup = BeautifulSoup(f"<div id='page'>{html}</div>", "lxml")
    verse.mark(soup)
    return soup.find(id="page")


def lines(block):
    return [(" ".join(s.get_text().split()), s.get("style")) for s in block.find_all("span", class_="gv-l")]


def test_br_lines_become_hanging_lines_and_a_blank_line_a_stanza_gap():
    page = marked("<p class='poem'>\nOf Mans First Disobedience, and the Fruit<br/>\nOf that Forbidden Tree<br/>\n<br/>\n"
                  "Brought Death into the World<br/>\n</p>")
    p = page.p
    assert p["class"] == ["poem", "gv"] and p.find("br") is None
    assert [c["class"] for c in p.children] == [["gv-l"], ["gv-l"], ["gv-gap"], ["gv-l"]]
    assert lines(p)[0] == ("Of Mans First Disobedience, and the Fruit", None)


def test_nbsp_and_spaces_beyond_the_source_indent_become_margins():
    page = marked("<p>\n      Midway upon the journey of our life<br/>\n      \xa0\xa0I found myself<br/>\n"
                  "        For the straightforward pathway<br/>\n      had been lost.</p>")
    assert lines(page.p) == [("Midway upon the journey of our life", None),
                             ("I found myself", "margin-left: min(0.60em, 35%)"),
                             ("For the straightforward pathway", "margin-left: min(0.60em, 35%)"),
                             ("had been lost.", None)]


def test_a_line_with_no_newline_before_it_is_not_outdented_against_the_source_indent():
    page = marked("<p><span>G</span>lory\n              and loveliness have passed away;<br/>\n"
                  "                For if we wander out in early morn,<br/>\n              Into the east</p>")
    assert [style for _, style in lines(page.p)] == [None, "margin-left: min(0.60em, 35%)", None]


def test_trailing_line_numbers_and_glosses_go_to_the_margin():
    page = marked("<p>Whan that Aprille with his shoures soote*,           *sweet<br/>\n"
                  "The droghte of March hath perced to the roote<br/>\n"
                  "Bathed every veyne in swich licour\xa0\xa0\xa0\xa0\xa020<br/>\n\xa0\xa0\xa0\xa0\xa0\xa01</p>")
    spans = page.p.find_all("span", class_="gv-l")
    assert [n.get_text() for n in page.find_all("span", class_="gv-n")] == ["*sweet", "20"]
    assert spans[0].contents[0]["class"] == ["gv-n"]  # first, so it floats beside its own line
    assert spans[0].get_text() == "*sweetWhan that Aprille with his shoures soote*,"
    assert spans[3].get_text() == "1"  # a line that is only a number stays a line


def test_a_pre_becomes_a_verse_div_keeping_its_indents_and_inline_tags():
    page = marked('<pre class="verse" id="p1">\n   I celebrate myself,\n         and sing <i>myself</i>,\n\n\n'
                  '   And what I assume\n</pre>')
    div = page.find("div", class_="gv-pre")
    assert div["class"] == ["verse", "gv", "gv-pre"] and div["id"] == "p1" and page.find("pre") is None
    assert [c["class"] for c in div.children] == [["gv-l"], ["gv-l"], ["gv-gap"], ["gv-l"]]
    assert lines(div)[1] == ("and sing myself,", "margin-left: min(3.00em, 35%)")
    assert div.find("i").get_text() == "myself"


def test_a_drop_cap_inside_verse_is_unwrapped():
    page = marked('<p class="pfirst">\n<span class="dropcap" style="font-size: 4.00em">O</span>f Mans First '
                  "Disobedience<br/>\nOf that Forbidden Tree<br/>\n</p><p><span class='dropcap'>I</span>t was prose.</p>")
    assert page.p.find(class_="dropcap") is None and lines(page.p)[0][0] == "Of Mans First Disobedience"
    assert page.find_all("p")[1].find(class_="dropcap") is not None


def test_a_speakers_name_in_capitals_is_set_in_small_capitals():
    page = marked('<p class="drama">\nPOLONIUS.<br/>\nGive him this money and these notes, Reynaldo.<br/>\n</p>'
                  "<p>\nBOOK I.<br/>\n</p><p>\nI.<br/>\nNot a NAME but a line.<br/>\n</p>")
    first = page.p.find("span", class_="gv-l")
    assert first["class"] == ["gv-l", "gv-sp"] and first.get_text() == "POLONIUS."
    assert page.find_all("p")[1].find(class_="gv-sp") is None  # one line: a heading, not a speech


def test_prose_with_a_few_breaks_and_long_lines_is_left_alone():
    prose = ("It is a truth universally acknowledged, that a single man in possession of a good fortune, "
             "must be in want of a wife.")
    html = f"<p>{prose}<br/>{prose}<br/>{prose}</p><div>a<br/><p>b</p><br/>c</div><p>one<br/>two</p>"
    page = marked(html)
    assert page.find(class_="gv") is None and len(page.find_all("br")) == 5


def test_blocks_already_marked_as_verse_are_left_as_they_are():
    html = '<div class="gv"><span class="gv-l">The LORD is my shepherd;<br/>I shall lack nothing.<br/>x</span></div>'
    page = marked(html)
    assert str(page.div) == html


def test_an_anchor_on_an_empty_line_moves_onto_the_next_line():
    page = marked('<p>\nfirst line<br/>\n<a id="l2"></a><br/>\nsecond line<br/>\n</p>')
    spans = page.p.find_all("span", recursive=False)
    assert [s["class"] for s in spans] == [["gv-l"], ["gv-gap"], ["gv-l"]]
    assert spans[2].find("a")["id"] == "l2"
