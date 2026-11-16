import io
import json
import zipfile

import pytest

import africanstorybook as asb

NODE = "8a061ce59f535fddb7e214ea4a53ab8d"

OPF = """<package unique-identifier="id_918" version="3.0" xmlns="http://www.idpf.org/2007/opf">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="id_918">https://www.africanstroybook.org/reader.php?id=918</dc:identifier>
    <dc:title>A very tall man</dc:title>
    <dc:publisher>African Stroybook Initiative</dc:publisher>
    <dc:date>2014-03-06T20:03:07Z</dc:date>
    <dc:creator id="author_0">Cornelius Wambi Gulere</dc:creator><meta refines="#author_0" property="role" scheme="marc:relators">aut</meta>
    <dc:creator id="author_1">Catherine Groenewald</dc:creator><meta refines="#author_1" property="role" scheme="marc:relators">ill</meta>
    <dc:language>en</dc:language>
  </metadata>
 <manifest>
   <item href="epub.css" id="default_css" media-type="text/css"/>
   <item href="images/cover.png" id="cover-image" media-type="image/png" properties="cover-image"/>
    <item href="0.xhtml" id="chapter_0" media-type="application/xhtml+xml"/>
    <item href="1.xhtml" id="chapter_1" media-type="application/xhtml+xml"/>
    <item href="2.xhtml" id="chapter_2" media-type="application/xhtml+xml"/>
 <item href="toc.ncx" id="ncx" media-type="application/x-dtbncx+xml"/>
 <item href="nav.xhtml" id="nav" media-type="application/xhtml+xml" properties="nav"/>
 </manifest>
 <spine toc="ncx"><itemref idref="chapter_0"/><itemref idref="chapter_1"/><itemref idref="chapter_2"/></spine>
</package>"""
NCX = ('<ncx><head><meta name="dtb:uid" content="https://www.africanstorybook.org/reader.php?id=918" /></head><navMap>'
       '<navPoint><content src="0.xhtml"/></navPoint><navPoint><content src="2.xhtml"/></navPoint></navMap></ncx>')
NAV = '<nav><ol><li><a href="0.xhtml">Cover</a></li><li><a href="1.xhtml">Page 1</a></li><li><a href="2.xhtml">Back cover</a></li></ol></nav>'
CSS = ".arabic {\n    font-family: x;\n}\n.alignRight {\n    direction: rtl;\n    unicode-bidi: bidi-override;\n}\nbody { color: black; }\n"
COVER = '<html><body><div id="centered"><img src="images/cover.png"/></div></body></html>'
PAGE = '<html><body><div class="books">His hoe was too short.</div></body></html>'
BACK = ('<html><body><div class="backcover_wrapper"><div class="backcover_copyright">© Text: Ugandan Community '
        'Libraries Association; Illustration: African Storybook Initiative 2014<br/>Creative Commons: Attribution 4.0'
        '<br/><span class="backcover_bold">Source </span>www.africanstorybook.org</div></div></body></html>')


def epub(**override):
    files = {"META-INF/container.xml": '<container><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>',
             "content.opf": OPF, "toc.ncx": NCX, "nav.xhtml": NAV, "epub.css": CSS, "images/cover.png": b"PNG",
             "0.xhtml": COVER, "1.xhtml": PAGE, "2.xhtml": BACK}
    files.update(override)
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as z:
        z.writestr(zipfile.ZipInfo("mimetype", (2022, 5, 26, 22, 56, 0)), "application/epub+zip")
        for name, body in files.items():
            info = zipfile.ZipInfo(name, (2022, 5, 26, 22, 56, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, body)
    return out.getvalue()


def read(data):
    z = zipfile.ZipFile(io.BytesIO(data))
    return z, {n: z.read(n).decode("utf-8", "replace") for n in z.namelist()}


# ---------- ids and levels ----------

def test_a_book_id_is_the_language_and_the_whole_node_id():
    assert asb.book_id("en", NODE) == f"asb-en-{NODE}"
    assert len(asb.book_id("sw", NODE)) == 39


@pytest.mark.parametrize("lang, node", [("en", "8a061ce5"), ("en", NODE.upper()), ("en", "../" + NODE[3:]),
                                        ("English", NODE), ("", NODE)])
def test_a_book_id_refuses_what_isnt_a_language_and_a_node(lang, node):
    with pytest.raises(ValueError):
        asb.book_id(lang, node)


@pytest.mark.parametrize("group, level", [("Level 1", 1), ("Level 5", 5), (" Level 3 ", 3), ("Level 6", None),
                                          ("", None), ("First words", None), (None, None)])
def test_levels_come_from_the_breadcrumb(group, level):
    assert asb.level_of(group) == level


def test_release_names_are_one_release_per_language():
    assert asb.release_tag("en", "2026-11-16") == "asb-en-2026-11-16"
    assert asb.release_url("sw", "2026-11-16", "x.epub") == \
        "https://github.com/nealt9000/Granger/releases/download/asb-sw-2026-11-16/x.epub"
    assert asb.collection_of("en") == "african-storybook-en"


def item(key, lang="en", group="Level 1", title="T", fmt="epub"):
    return {"key": key, "path": key[:8] + ".epub", "format": fmt, "title": title, "language": lang, "group": group,
            "author": "A", "description": "D", "bytes": 10}


def test_chosen_takes_one_language_in_manifest_order_with_ids_and_levels():
    other = "1" * 32
    items = [item(NODE, group="Level 2"), item(other, lang="sw"), item("2" * 32, group="Level 4"),
             item("3" * 32, fmt="pdf")]
    got = asb.chosen(items, "en")
    assert [(b["id"], b["level"]) for b in got] == [(f"asb-en-{NODE}", 2), ("asb-en-" + "2" * 32, 4)]
    assert [b["id"] for b in asb.chosen(items, "sw")] == ["asb-sw-" + other]


def test_chosen_refuses_a_book_without_a_level():
    with pytest.raises(ValueError, match="no level"):
        asb.chosen([item(NODE, group="")], "en")


# ---------- the EPUB fix ----------

def test_fix_corrects_the_site_name_so_the_ncx_matches_the_opf():
    fixed, changes = asb.fix_epub(epub())
    _, files = read(fixed)
    assert "stroybook" not in files["content.opf"].lower()
    assert "https://www.africanstorybook.org/reader.php?id=918</dc:identifier>" in files["content.opf"]
    assert "<dc:publisher>African Storybook Initiative</dc:publisher>" in files["content.opf"]
    assert "typo:content.opf" in changes


def test_fix_drops_only_the_css_properties_epub_bans():
    fixed, changes = asb.fix_epub(epub())
    css = read(fixed)[1]["epub.css"]
    assert "direction" not in css and "unicode-bidi" not in css
    assert ".alignRight {" in css and "font-family: x;" in css and "body { color: black; }" in css
    assert "css:epub.css" in changes


def test_fix_drops_the_fonts_their_rules_and_their_manifest_items():
    css = ("@font-face {\n    font-family: 'NotoSans-Regular';\n    src: url(fonts/NotoSans.otf)  format('opentype');\n}\n"
           "@font-face {\n  font-family: 'NotoKufiArabic';\n  src: url('fonts/NotoKufiArabic.otf') format('opentype');\n}\n"
           "p { font-family: 'NotoSans-Regular', sans-serif; }\n")
    opf = OPF.replace('<item href="epub.css"', '<item href="fonts/NotoSans.otf" id="NotoSans-Regular" '
                      'media-type="application/vnd.ms-opentype"/>\n   <item href="fonts/NotoKufiArabic.otf" '
                      'id="NotoKufiArabic" media-type="application/vnd.ms-opentype"/>\n   <item href="epub.css"')
    fixed, changes = asb.fix_epub(epub(**{"content.opf": opf, "epub.css": css, "fonts/": b"",
                                          "fonts/NotoSans.otf": b"OTTO" * 100, "fonts/NotoKufiArabic.otf": b"OTTO"}))
    z, files = read(fixed)
    assert not [n for n in z.namelist() if n.startswith("fonts")]
    assert ".otf" not in files["content.opf"] and 'href="epub.css"' in files["content.opf"]
    assert "@font-face" not in files["epub.css"] and "p { font-family: 'NotoSans-Regular', sans-serif; }" in files["epub.css"]
    assert "fonts" in changes
    assert "fonts" not in asb.fix_epub(fixed)[1]


def test_fix_names_the_cover_and_back_cover_as_related_skips_them():
    fixed, _ = asb.fix_epub(epub())
    z, files = read(fixed)
    names = z.namelist()
    assert "cover.xhtml" in names and "colophon.xhtml" in names and "1.xhtml" in names
    assert "0.xhtml" not in names and "2.xhtml" not in names
    assert 'href="cover.xhtml" id="chapter_0"' in files["content.opf"]
    assert 'href="colophon.xhtml" id="chapter_2"' in files["content.opf"]
    assert 'href="cover.xhtml">Cover' in files["nav.xhtml"] and 'href="1.xhtml">Page 1' in files["nav.xhtml"]
    assert 'src="colophon.xhtml"' in files["toc.ncx"]
    assert "backcover_copyright" in files["colophon.xhtml"]


def test_fix_carries_the_back_covers_attribution_into_the_metadata():
    fixed, _ = asb.fix_epub(epub())
    opf = read(fixed)[1]["content.opf"]
    assert ("<dc:rights>© Text: Ugandan Community Libraries Association; Illustration: African Storybook Initiative "
            "2014 · Creative Commons: Attribution 4.0 · Source: www.africanstorybook.org</dc:rights></metadata>") in opf


def test_fix_falls_back_to_the_licence_when_the_back_cover_has_no_credit():
    fixed, _ = asb.fix_epub(epub(**{"2.xhtml": "<html><body>backcover</body></html>"}))
    assert f"<dc:rights>{asb.LICENSE}</dc:rights>" in read(fixed)[1]["content.opf"]


def test_fix_is_deterministic_keeps_mimetype_first_and_stored_and_is_idempotent():
    once, _ = asb.fix_epub(epub())
    assert asb.fix_epub(epub())[0] == once
    z = zipfile.ZipFile(io.BytesIO(once))
    first = z.infolist()[0]
    assert first.filename == "mimetype" and first.compress_type == zipfile.ZIP_STORED
    assert all(i.date_time == (2022, 5, 26, 22, 56, 0) for i in z.infolist())
    again, changes = asb.fix_epub(once)
    assert changes == [] and read(again)[1] == read(once)[1]


def test_fix_mends_xml_that_doesnt_parse():
    page = '<html><body><div class="books">Romeo & Basetsana\x0b<br>Next &amp; &#233; &nbsp;</div></body></html>'
    fixed, changes = asb.fix_epub(epub(**{"1.xhtml": page}))
    body = read(fixed)[1]["1.xhtml"]
    assert body == '<html><body><div class="books">Romeo &amp; Basetsana<br/>Next &amp; &#233; &nbsp;</div></body></html>'
    assert {"xmlchar:1.xhtml", "amp:1.xhtml", "br:1.xhtml"} <= set(changes)


def test_fix_drops_an_empty_creator_and_its_role():
    opf = OPF.replace("Catherine Groenewald", "").replace("<dc:creator id=\"author_0\">Cornelius Wambi Gulere",
                                                          "<dc:creator id=\"author_0\">Angie & Upesh")
    fixed, changes = asb.fix_epub(epub(**{"content.opf": opf}))
    out = read(fixed)[1]["content.opf"]
    assert 'id="author_1"' not in out and 'refines="#author_1"' not in out
    assert "Angie &amp; Upesh" in out and 'refines="#author_0"' in out
    assert "creator:content.opf" in changes


def test_fix_gives_an_empty_cover_the_first_pages_picture():
    page = '<html><body><div id="container"><img src="images/309.png"/></div></body></html>'
    fixed, changes = asb.fix_epub(epub(**{"images/cover.png": b"", "images/309.png": b"PICTURE", "1.xhtml": page}))
    assert zipfile.ZipFile(io.BytesIO(fixed)).read("images/cover.png") == b"PICTURE"
    assert "cover:images/cover.png" in changes
    assert asb.fix_epub(epub())[0] == asb.fix_epub(epub())[0]  # a real cover is left alone
    assert zipfile.ZipFile(io.BytesIO(asb.fix_epub(epub())[0])).read("images/cover.png") == b"PNG"


def test_epub_facts_reads_title_language_roles_date_and_cover():
    facts = asb.epub_facts(epub())
    assert facts == {"title": "A very tall man", "language": "en", "date": "2014-03-06T20:03:07Z",
                     "authors": ["Cornelius Wambi Gulere"], "illustrators": ["Catherine Groenewald"],
                     "cover": "images/cover.png"}


# ---------- the release descriptor ----------

def test_describe_writes_the_classics_shape_with_levels_and_sources(tmp_path, monkeypatch):
    fixed, _ = asb.fix_epub(epub())
    books = asb.chosen([item(NODE, group="Level 2", title="A very tall man")], "en")
    books[0]["sourceSha256"] = "ab" * 32
    (tmp_path / f"{books[0]['id']}.epub").write_bytes(fixed)
    d = asb.describe("en", books, tmp_path, "2026-11-16")
    assert (d["releaseDate"], d["collection"], d["title"], d["levels"]) == \
        ("2026-11-16", "african-storybook-en", "African Storybook · English", {2: 1})
    b = d["books"][0]
    # catalog_gen.book_entries needs these.
    for key in ("id", "title", "author", "language", "sizeBytes", "sha256", "urls", "published", "section"):
        assert key in b
    assert b["id"] == f"asb-en-{NODE}" and b["section"] == "Level 2" and b["level"] == 2
    assert b["published"] == 2014 and b["author"] == "A" and b["illustrator"] == "Catherine Groenewald"
    assert b["description"] == "D" and b["domain"] == "literature" and b["cover"] == "images/cover.png"
    assert b["urls"] == [f"https://github.com/nealt9000/Granger/releases/download/asb-en-2026-11-16/asb-en-{NODE}.epub"]
    assert b["sizeBytes"] == len(fixed) and b["node"] == NODE and b["sourceSha256"] == "ab" * 32
    path = tmp_path / "out.json"
    asb.write_descriptor(d, path)
    assert json.loads(path.read_text(encoding="utf-8"))["books"][0]["title"] == "A very tall man"
