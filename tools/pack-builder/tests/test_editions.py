import json
import zipfile

import pytest
from bs4 import BeautifulSoup
from lxml import etree

import editions
from conftest import fixture
from manifest import Rdf, load, validate

NAMES = {"Ulysses": "Odysseus", "Jove": "Zeus", "Minerva": "Athena", "Mars": "Ares", "Diomed": "Diomedes",
         "Saturn": "Cronus"}
BOOK = """<div><h1>The Odyssey</h1><h2>by Homer</h2><h5>rendered into English prose</h5><hr/><h3>Contents</h3>
<h2>Preface</h2><p>Ulysses is Butler's name for him; Jove too.</p>
<h2>BOOK I.</h2><h3>MINERVA’S VISIT TO ITHACA</h3>
<p>Then Minerva said, “Father, son of Saturn, it is for Ulysses’ sake and Ulysses's son; Jove’s will, Jove-begotten.”</p>
<p>Diomed met Diomede in the marsh; Mars mars nothing; Marsh and jovial stay.</p>
<h2>FOOTNOTES:</h2><p>[1] Ulysses here means the hero.</p></div>"""


def body():
    return BeautifulSoup(BOOK, "lxml").div


def text(tag) -> str:
    return " ".join(tag.get_text(" ").split())


def test_rename_replaces_whole_capitalised_words_between_the_headings():
    b = body()
    counts = editions.rename(b, NAMES, "Book I", "Footnotes")
    out = text(b)
    assert "ATHENA’S VISIT TO ITHACA" in out
    assert "Then Athena said, “Father, son of Cronus, it is for Odysseus’ sake and Odysseus's son; Zeus’s will, " \
           "Zeus-begotten.”" in out
    assert "Diomedes met Diomede in the marsh; Ares mars nothing; Marsh and jovial stay." in out
    # the preface and the notes keep the translator's names
    assert "Ulysses is Butler's name for him; Jove too." in out and "[1] Ulysses here means the hero." in out
    assert counts == {"Ulysses": 2, "Jove": 2, "Minerva": 1, "MINERVA": 1, "Saturn": 1, "Diomed": 1, "Mars": 1}
    assert editions.remaining(b, NAMES, "Book I", "Footnotes") == {}
    assert editions.remaining(b, NAMES) == {"Ulysses": 2, "Jove": 1}  # the preface's two and the note's one


def test_rename_without_headings_covers_the_whole_text():
    b = body()
    editions.rename(b, NAMES)
    assert editions.remaining(b, NAMES) == {} and "Ulysses" not in text(b)


def test_a_heading_named_but_missing_is_an_error():
    with pytest.raises(ValueError, match="Book One"):
        editions.rename(body(), NAMES, "Book One")
    with pytest.raises(ValueError, match="Notes"):
        editions.rename(body(), NAMES, "Book I", "Notes")


def test_the_homer_map_is_roman_to_greek_and_leaves_butlers_greek_spellings():
    names = editions.load_names("homer-greek")
    assert names["Ulysses"] == "Odysseus" and names["Jove"] == "Zeus" and names["Minerva"] == "Athena"
    assert names["Saturn"] == "Cronus" and names["Aurora"] == "Dawn" and names["Latona"] == "Leto"
    assert names["Alexandrus"] == "Alexandros" and names["Ilius"] == "Ilion"  # Neal, 2026-11-09
    for kept in ("Achilles", "Ajax", "Patroclus", "Hades", "Apollo"):
        assert kept not in names
    assert not set(names) & set(names.values())
    with pytest.raises(ValueError):
        editions.load_names("../manifest")


def test_front_note_sits_under_the_title_block():
    b = body()
    editions.front_note(b, "Names in their Greek forms.")
    note = b.find("div", class_="editor-note")
    assert text(note) == "Names in their Greek forms."
    assert note.find_previous_sibling().name == "h5" and note.find_next_sibling().name == "hr"


def test_front_note_without_a_title_goes_first():
    b = BeautifulSoup("<div><p>Text.</p></div>", "lxml").div
    editions.front_note(b, "Note.")
    assert b.contents[0]["class"] == ["editor-note"]


def manifest(tmp_path, **work):
    entry = {"gutenberg": 1727, "slug": "odyssey", "title": "The Odyssey", "author": "Homer",
             "translator": "Samuel Butler (Greek names)", "published": 1900, "section": "poetry"}
    entry.update(work)
    data = {"name": "granger_test", "title": "Granger Test", "language": "eng", "description": "Test pack",
            "sections": [{"id": "poetry", "title": "Epic & poetry"}], "works": [entry]}
    path = tmp_path / "manifest.json"
    path.write_text(json.dumps(data))
    return load(path)


def rdf(_):
    return Rdf("The Odyssey", ["en"], "Public domain in the USA.")


def test_manifest_reads_and_checks_rename_and_editor_note(tmp_path):
    good = manifest(tmp_path, rename={"names": "homer-greek", "from": "Book I", "to": "Footnotes"},
                    editorNote="Butler, with Greek names.")
    assert good.works[0].rename["names"] == "homer-greek" and good.works[0].editor_note == "Butler, with Greek names."
    assert validate(good, rdf) == []
    for bad, message in (({"names": "no-such-map"}, "no name map"), ({"names": "homer-greek", "into": "x"}, "rename must"),
                         ({"names": "homer-greek", "from": ""}, "heading's text"), ("homer-greek", "rename must")):
        errors = validate(manifest(tmp_path, rename=bad, editorNote="Note."), rdf)
        assert any(message in e for e in errors), (bad, errors)
    errors = validate(manifest(tmp_path, rename={"names": "homer-greek"}), rdf)
    assert any("needs an editorNote" in e for e in errors)


class RenamingSource:
    def html(self, book_id):
        return (fixture("pg-modern.html").replace(b"It was a dark night.", b"Ulysses prayed to Jove.")
                .replace(b"Johannes Gutenberg printed a Bible.", b"Jove printed a Bible."))


def test_build_epubs_renames_in_scope_and_says_so_front_and_back(tmp_path):
    from pack_builder import build_epubs
    data = {"name": "granger_test", "title": "Granger Classics", "language": "eng", "description": "Test",
            "sections": [{"id": "novels", "title": "Novels"}],
            "works": [{"gutenberg": 1342, "slug": "modern-book", "title": "The Modern Book", "author": "A. Writer",
                       "translator": "T. Ranslator (Greek names)", "published": 1900, "section": "novels",
                       "rename": {"names": "homer-greek", "from": "Chapter 1", "to": "Chapter 3"},
                       "editorNote": "The names in their Greek forms."}]}
    path = tmp_path / "manifest.json"
    path.write_text(json.dumps(data))
    out = tmp_path / "out"
    out.mkdir()
    [epub] = build_epubs(load(path), RenamingSource(), out, "2026-11-09")
    z = zipfile.ZipFile(epub)
    pages = " ".join(z.read(n).decode() for n in sorted(z.namelist()) if n.startswith("OEBPS/c0"))
    assert "Odysseus prayed to Zeus." in pages and "Jove printed a Bible." in pages  # chapter 3 is out of scope
    first = z.read("OEBPS/c001.xhtml").decode()
    assert '<div class="editor-note"><p>The names in their Greek forms.</p></div>' in first
    colophon = " ".join(etree.fromstring(z.read("OEBPS/colophon.xhtml")).itertext())
    assert "The names in their Greek forms." in colophon and "Translated by T. Ranslator (Greek names)" in colophon
    assert ".editor-note {" in z.read("OEBPS/book.css").decode()
