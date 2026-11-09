import json
import urllib.parse

import pytest

import wikisource
from sources import WIKISOURCE_AGENT, FetchError, WikisourceSource

HEADER = ('<div class="ws-header wst-header ws-noexport noprint"><a href="/wiki/Test_Work/Index">Index</a>'
          '<div id="ws-data">by A. Writer</div></div>')
PD_US = ('<div class="licenseContainer licenseBanner"><p>This work was published before January 1, 1931, and is in '
         'the <b>public domain</b> worldwide because the author died at least 100 years ago.</p></div>')
GOV = ('<div class="licenseContainer licenseBanner"><p>This work is in the <b>public domain</b> in the United States '
       'because it is a work of the United States federal government.</p></div>')


def page(title, body, templates=("Template:PD-old",), licence_box=PD_US):
    return {"asked": title, "title": title, "revid": 1, "templates": list(templates), "categories": [],
            "text": f'<div class="mw-parser-output">{HEADER}{body}{licence_box}</div>'}


PAGES = {
    "Test Work": page("Test Work", '<style>.mw-parser-output .wst-center{text-align:center}'
                                   '.mw-parser-output .wst-sister{border:1px solid}</style>'
                                   '<div class="wst-center"><p>THE TEST WORK</p></div>'
                                   '<table><tr><td><a href="/wiki/Test_Work/Book_1">BOOK I.</a></td></tr>'
                                   '<tr><td><a href="/wiki/Test_Work/Book_2">Book II: The Rest</a></td></tr>'
                                   '<tr><td><a href="/wiki/Test_Work/Index">Index</a></td></tr></table>'),
    "Test Work/Book 1": page("Test Work/Book 1",
                             '<span><span class="pagenum ws-pagenum" id="3" data-page-number="3">​</span></span>'
                             '<div class="wst-center"><p>CHAPTER I</p></div><div class="wst-center"><p>Book I</p></div>'
                             '<p><span class="smallcaps">The</span> start.<sup id="cite_ref-1" class="reference">'
                             '<a href="#cite_note-1"><span class="cite-bracket">[</span>1<span class="cite-bracket">]'
                             '</span></a></sup> See <a href="/wiki/Test_Work/Book_2#x">the rest</a> and '
                             '<a href="/wiki/Other_Work">another work</a>.</p>'
                             '<p><span class="mw-editsection">[edit]</span><br/></p>'
                             '<div class="mw-heading mw-heading2"><h2 id="Footnotes">Footnotes</h2></div>'
                             '<ol class="references"><li id="cite_note-1"><span class="mw-cite-backlink">'
                             '<a href="#cite_ref-1">↑</a></span> <span class="reference-text">A note.<br/>'
                             '<div class="wst-left-margin"><p>A quoted line.</p></div></span></li></ol>',
                             templates=()),
    "Test Work/Book 2": page("Test Work/Book 2",
                             '<div class="mw-heading mw-heading2"><h2 id="Part_1">Part 1</h2></div>'
                             '<p id="x">The rest.</p><div class="mw-collapsible mw-collapsed">'
                             '<div class="wst-collapsible-box-title"><p>[Advertisements]</p></div>'
                             '<p>Buy our books.</p></div><img src="//upload.example/x.png"/>'
                             '<div class="wst-center"><p>FINIS</p></div>'
                             '<div class="mw-heading mw-heading2"><h2 id="External_links">External links</h2></div>'
                             '<ul><li>A summary elsewhere</li></ul>', templates=()),
}


class FakeWikisource:
    def __init__(self, pages=PAGES):
        self.pages, self.asked = pages, []

    def parse(self, title):
        self.asked.append(title)
        return self.pages[title]


def test_licence_needs_a_us_template_and_a_box_that_says_so():
    found = wikisource.licence(PAGES["Test Work"])
    assert found.templates == ["PD-old"] and not found.government
    gov = wikisource.licence(page("Amendment", "<p>Text.</p>", ("Template:PD-USGov", "Template:License"), GOV))
    assert gov.government and gov.templates == ["PD-USGov"]
    assert wikisource.licence(page("X", "<p>x</p>", ("Template:PD-old",), licence_box="")) is None
    assert wikisource.licence(page("X", "<p>x</p>", ("Template:Copyrighted", "Template:PD-self"))) is None
    assert wikisource.licence(page("X", "<p>x</p>", ("Template:PD-US-no-notice",))).templates == ["PD-US-no-notice"]


def test_subpages_are_followed_in_link_order_under_headings_from_the_links():
    source = FakeWikisource()
    book = wikisource.read(source, "Test Work", ["Index"])
    assert source.asked == ["Test Work", "Test Work/Book 1", "Test Work/Book 2"]
    assert book.pages == ["Test Work", "Test Work/Book 1", "Test Work/Book 2"]
    body = book.cleaned.body
    assert [(h.name, h.get_text()) for h in body.find_all(["h2", "h3", "h4"])] == [
        ("h2", "Chapter I: Book I"), ("h2", "Book II: The Rest"), ("h3", "Part 1")]
    text = " ".join(body.get_text(" ").split())
    assert "THE TEST WORK" not in text  # the contents page's own text goes
    assert text.startswith("Chapter I: Book I The start. 1 See the rest and another work . Footnotes A note.")


def test_wikisource_noise_goes_and_notes_stay_with_page_ids():
    body = wikisource.read(FakeWikisource(), "Test Work", ["Index"]).cleaned.body
    html = str(body)
    for gone in ("ws-header", "pagenum", "[edit]", "cite-bracket", "[1]", "↑", "licenseContainer",
                 "Advertisements", "Buy our books", "<img", "data-page", "Other_Work", "public domain",
                 "External links", "A summary elsewhere"):
        assert gone not in html, gone
    note = body.find(id="w2-cite_note-1")
    assert note is not None and "A note." in note.get_text()
    assert body.select_one("sup a")["href"] == "#w2-cite_note-1"
    assert body.find("a", string="the rest")["href"] == "#w3-x" and body.find(id="w3-x") is not None
    assert body.find("p", class_="ws-notes").get_text() == "Footnotes"  # a notes heading isn't a section


def test_only_the_styles_the_text_uses_are_kept():
    css = wikisource.read(FakeWikisource(), "Test Work", ["Index"]).cleaned.styles
    assert ".wst-center { text-align:center }" in css and "wst-sister" not in css and "mw-parser-output" not in css


def test_a_red_link_or_a_missing_licence_fails():
    pages = dict(PAGES)
    pages["Test Work"] = page("Test Work", '<a href="/w/index.php?title=Test_Work/Book_3&amp;action=edit&amp;'
                                           'redlink=1">Book III</a><a href="/wiki/Test_Work/Book_1">Book I</a>')
    with pytest.raises(wikisource.Incomplete, match="Book 3"):
        wikisource.read(FakeWikisource(pages), "Test Work")
    pages["Test Work"] = page("Test Work", "<p>x</p>", templates=("Template:Copyrighted",))
    with pytest.raises(wikisource.NotPublicDomain):
        wikisource.read(FakeWikisource(pages), "Test Work")


def test_source_asks_the_api_politely_and_caches(tmp_path):
    calls = []

    def fetch(url):
        calls.append(url)
        return json.dumps({"parse": {"title": "Test Work", "revid": 7, "text": "<p>x</p>",
                                     "templates": [{"title": "Template:PD-old"}],
                                     "categories": [{"category": "1908_works"}]}}).encode()

    s = WikisourceSource(tmp_path, fetch=fetch, delay_s=0)
    assert s.parse("Test_Work")["templates"] == ["Template:PD-old"]
    assert s.parse("Test Work")["revid"] == 7
    assert len(calls) == 1
    query = urllib.parse.parse_qs(urllib.parse.urlsplit(calls[0]).query)
    assert calls[0].startswith("https://en.wikisource.org/w/api.php?")
    assert query["action"] == ["parse"] and query["page"] == ["Test Work"] and query["redirects"] == ["1"]
    assert "@" not in WIKISOURCE_AGENT and "opengranger.org" in WIKISOURCE_AGENT


def test_source_errors_name_the_page_and_leave_no_cache(tmp_path):
    s = WikisourceSource(tmp_path, fetch=lambda url: b'{"error": {"info": "The page you specified doesn\'t exist."}}',
                         delay_s=0)
    with pytest.raises(FetchError, match="Missing Work"):
        s.parse("Missing Work")
    with pytest.raises(FetchError, match="title"):
        s.parse("Bad|Title")
    assert not any(tmp_path.iterdir())


def test_blocks_inside_phrasing_become_valid_nesting():
    body = wikisource.read(FakeWikisource(), "Test Work", ["Index"]).cleaned.body
    note = body.find(id="w2-cite_note-1")
    holder = note.find(string="A quoted line.").find_parent("div", class_="wst-left-margin").parent
    assert holder.name == "div" and "reference-text" in holder["class"]
    for tag in body.find_all(["span", "i", "b", "p"]):
        assert tag.find(["div", "p"]) is None, tag
