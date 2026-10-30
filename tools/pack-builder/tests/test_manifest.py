import json

from conftest import fixture
from manifest import PD_LINE, Rdf, load, parse_rdf, validate

PD = "Public domain in the USA."


def rdf_ok(_):
    return Rdf("The Koran (Al-Qur'an)", ["en"], PD)


def manifest(tmp_path, **work):
    entry = {"gutenberg": 2800, "slug": "koran-rodwell", "title": "The Koran", "author": "",
             "translator": "J. M. Rodwell", "published": 1861, "section": "scripture", "splitAt": "h4",
             "note": "Rodwell 1861"}
    entry.update(work)
    data = {"name": "granger_test", "title": "Granger Test", "language": "eng", "description": "Test pack",
            "sections": [{"id": "scripture", "title": "Scripture & religion"}], "works": [entry]}
    path = tmp_path / "manifest.json"
    path.write_text(json.dumps(data))
    return load(path)


def test_load_reads_the_manifest(tmp_path):
    m = manifest(tmp_path)
    assert m.name == "granger_test" and m.sections == [("scripture", "Scripture & religion")]
    w = m.works[0]
    assert (w.gutenberg, w.slug, w.translator, w.published, w.split_at, w.title_check) == \
        (2800, "koran-rodwell", "J. M. Rodwell", 1861, "h4", True)


def test_valid_manifest_has_no_errors(tmp_path):
    assert validate(manifest(tmp_path), rdf_ok) == []


def test_published_after_1930_is_an_error(tmp_path):
    assert PD_LINE == 1930
    assert any("1931" in e for e in validate(manifest(tmp_path, published=1931), rdf_ok))


def test_copyrighted_rights_is_an_error(tmp_path):
    errors = validate(manifest(tmp_path), lambda _: parse_rdf(fixture("pg-copyrighted.rdf")))
    assert any("rights" in e for e in errors)


def test_non_english_is_an_error(tmp_path):
    assert any("language" in e for e in validate(manifest(tmp_path), lambda _: Rdf("The Koran", ["fr"], PD)))


def test_title_mismatch_is_an_error_unless_disabled(tmp_path):
    def rdf(_):
        return Rdf("Moby Dick; Or, The Whale", ["en"], PD)
    assert validate(manifest(tmp_path), rdf)
    assert validate(manifest(tmp_path, titleCheck=False), rdf) == []


def test_duplicate_slug_or_id_unsafe_slug_and_unknown_section_are_errors(tmp_path):
    m = manifest(tmp_path)
    base = m.works[0]
    m.works += [
        type(base)(**{**vars(base), "gutenberg": 1}),                    # duplicate slug
        type(base)(**{**vars(base), "slug": "other"}),                   # duplicate Gutenberg id
        type(base)(**{**vars(base), "gutenberg": 2, "slug": "Bad Slug"}),
        type(base)(**{**vars(base), "gutenberg": 3, "slug": "x", "section": "nowhere"}),
    ]
    assert len(validate(m, rdf_ok)) == 4


def test_bad_split_at_is_an_error(tmp_path):
    assert any("splitAt" in e for e in validate(manifest(tmp_path, splitAt="h7"), rdf_ok))


def test_parse_rdf_reads_title_language_rights():
    assert parse_rdf(fixture("pg2800.rdf")) == Rdf("The Koran (Al-Qur'an)", ["en"], PD)


def test_rights_must_be_exactly_public_domain_in_the_usa(tmp_path):
    def rdf(_):
        return Rdf("The Koran", ["en"], "Public domain in the USA, but see the notes.")
    assert any("rights" in e for e in validate(manifest(tmp_path), rdf))


def test_reading_settings_default_from_the_section(tmp_path):
    w = manifest(tmp_path).works[0]
    assert (w.verse, w.line_numbers, w.title_overrides, w.source, w.licence) == (None, "margin", {}, "gutenberg", None)
    assert w.reads_as_verse                                  # scripture is a verse section
    assert not manifest(tmp_path, verse=False).works[0].reads_as_verse
    novel = manifest(tmp_path, section="novels").works[0]
    assert not novel.reads_as_verse and manifest(tmp_path, section="novels", verse=True).works[0].reads_as_verse


def test_reading_settings_are_read(tmp_path):
    w = manifest(tmp_path, verse=True, lineNumbers="drop", titleOverrides={"3": "Book I"}).works[0]
    assert (w.verse, w.line_numbers, w.title_overrides) == (True, "drop", {"3": "Book I"})
    assert validate(manifest(tmp_path, lineNumbers="drop", titleOverrides={"3": "Book I"}), rdf_ok) == []


def test_bad_reading_settings_are_errors(tmp_path):
    assert any("verse" in e for e in validate(manifest(tmp_path, verse="yes"), rdf_ok))
    assert any("lineNumbers" in e for e in validate(manifest(tmp_path, lineNumbers="left"), rdf_ok))
    assert any("titleOverrides" in e for e in validate(manifest(tmp_path, titleOverrides={"x": "Book I"}), rdf_ok))
    assert any("titleOverrides" in e for e in validate(manifest(tmp_path, titleOverrides=["Book I"]), rdf_ok))


def test_unknown_source_is_an_error(tmp_path):
    assert any("source" in e for e in validate(manifest(tmp_path, source="wikisource"), rdf_ok))


def test_non_gutenberg_work_needs_a_public_domain_licence_with_its_url(tmp_path):
    def no_rdf(_):
        raise AssertionError("only Gutenberg works have an RDF record")
    ebible = dict(source="ebible", gutenberg=None, ebible="engwebu")
    assert any("licence" in e for e in validate(manifest(tmp_path, **ebible), no_rdf))
    assert any("licence" in e for e in validate(manifest(tmp_path, **ebible, licence="Public domain"), no_rdf))
    assert any("licence" in e for e in validate(
        manifest(tmp_path, **ebible, licence="CC BY-SA 4.0 https://ebible.org/engwebu/copyright.htm"), no_rdf))
    good = "Public domain (no copyright) https://ebible.org/engwebu/copyright.htm"
    m = manifest(tmp_path, **ebible, licence=good)
    assert m.works[0].gutenberg is None and m.works[0].licence == good
    assert validate(m, no_rdf) == []


def ebible_row(**work):
    row = {"source": "ebible", "ebible": "engbsb", "slug": "berean-standard-bible", "title": "Berean Standard Bible",
           "author": "", "translator": None, "published": 2016, "section": "scripture", "splitAt": None,
           "licence": 'https://berean.bible/terms.htm: "officially dedicated to the public domain"'}
    row.update(work)
    return row


def ebible_manifest(tmp_path, **work):
    m = manifest(tmp_path)
    data = json.loads((tmp_path / "manifest.json").read_text())
    data["works"].append(ebible_row(**work))
    (tmp_path / "manifest.json").write_text(json.dumps(data))
    return load(tmp_path / "manifest.json")


def test_ebible_rows_load_and_skip_the_rdf_and_year_checks(tmp_path):
    m = ebible_manifest(tmp_path)
    w = m.works[1]
    assert (w.source, w.ebible, w.gutenberg, w.published) == ("ebible", "engbsb", None, 2016)
    assert m.works[0].source == "gutenberg" and m.works[0].licence is None
    looked_up = []
    assert validate(m, lambda i: looked_up.append(i) or rdf_ok(i), lambda i: b"<b>Public Domain</b>") == []
    assert looked_up == [2800]


def test_ebible_rows_need_a_quoted_licence_with_its_url_and_a_public_domain_page(tmp_path):
    errors = validate(ebible_manifest(tmp_path, licence="free"), rdf_ok, lambda i: b"Public Domain")
    assert any("licence" in e and "berean-standard-bible (ebible engbsb)" in e for e in errors)
    errors = validate(ebible_manifest(tmp_path), rdf_ok, lambda i: b"All rights reserved")
    assert any("Public Domain" in e for e in errors)
    assert any("ebible id" in e for e in validate(ebible_manifest(tmp_path, ebible="../x"), rdf_ok))
    assert any("source" in e for e in validate(manifest(tmp_path, source="wikisource"), rdf_ok))


def test_cut_after_is_read_and_must_be_text(tmp_path):
    assert manifest(tmp_path).works[0].cut_after is None
    assert manifest(tmp_path, cutAfter="bury us all!”").works[0].cut_after == "bury us all!”"
    assert validate(manifest(tmp_path, cutAfter="bury us all!”"), rdf_ok) == []
    assert any("cutAfter" in e for e in validate(manifest(tmp_path, cutAfter=""), rdf_ok))
    assert any("cutAfter" in e for e in validate(manifest(tmp_path, cutAfter=3), rdf_ok))
