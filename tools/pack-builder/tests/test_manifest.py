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
