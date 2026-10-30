import json

from conftest import fixture
from manifest import PD_DEATH_LINE, PD_LINE, Rdf, load, parse_rdf, validate

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


# --- Clásicos Granger (Plan L3) ---

GALDOS = {"name": "Benito Pérez Galdós", "role": "author", "died": 1920,
          "diedSource": "https://www.wikidata.org/wiki/Q207946"}


def manifest_es(tmp_path, **work):
    entry = {"gutenberg": 17340, "slug": "marianela", "title": "Marianela", "author": "Benito Pérez Galdós",
             "translator": None, "published": 1878, "section": "novelas", "learner": True, "people": [GALDOS]}
    entry.update(work)
    data = {"name": "clasicos_test", "title": "Clásicos Test", "language": "spa", "description": "Prueba",
            "collection": "clasicos-granger", "bookIdPrefix": "gc-es-", "checkDeaths": True,
            "sections": [{"id": "novelas", "title": "Novelas", "domain": "literature"}], "works": [entry]}
    path = tmp_path / "manifest_es.json"
    path.write_text(json.dumps(data))
    return load(path)


def rdf_es(_):
    return Rdf("Marianela", ["es"], PD)


def test_load_reads_the_spanish_fields(tmp_path):
    m = manifest_es(tmp_path)
    assert (m.code, m.collection, m.book_id_prefix, m.domains, m.check_deaths) == \
        ("es", "clasicos-granger", "gc-es-", {"novelas": "literature"}, True)
    w = m.works[0]
    assert w.learner and w.people[0].died == 1920 and w.people[0].died_source.endswith("Q207946")


def test_english_manifest_defaults_stay_as_they_were(tmp_path):
    m = manifest(tmp_path)
    assert (m.code, m.collection, m.book_id_prefix, m.domains, m.check_deaths) == \
        ("en", "granger-classics", "gc-", None, False)
    assert m.works[0].learner is False and m.works[0].people is None


def test_language_check_reads_the_manifest_language(tmp_path):
    m = manifest_es(tmp_path)
    assert validate(m, rdf_es) == []
    assert validate(m, lambda _: Rdf("Marianela", ["en", "es"], PD)) == []  # a bilingual school edition
    assert any("language" in e for e in validate(m, lambda _: Rdf("Marianela", ["en"], PD)))


def test_spanish_stop_words_are_ignored_in_the_title_check(tmp_path):
    m = manifest_es(tmp_path, title="La Regenta")
    assert validate(m, lambda _: Rdf("Regenta", ["es"], PD)) == []


def test_death_after_1945_is_an_error(tmp_path):
    assert PD_DEATH_LINE == 1945
    baroja = {"name": "Pío Baroja", "role": "author", "died": 1956,
              "diedSource": "https://www.wikidata.org/wiki/Q312907"}
    errors = validate(manifest_es(tmp_path, people=[baroja]), rdf_es)
    assert len(errors) == 1 and "1956" in errors[0]
    assert validate(manifest_es(tmp_path, people=[{**baroja, "died": 1945}]), rdf_es) == []


def test_translator_and_editor_count_too(tmp_path):
    peres = {"name": "Ramón D. Perés", "role": "translator", "died": 1956,
             "diedSource": "https://www.wikidata.org/wiki/Q1"}
    assert any("Perés" in e for e in validate(manifest_es(tmp_path, people=[GALDOS, peres]), rdf_es))


def test_unknown_death_year_is_an_error_unless_pre_1800_with_a_note(tmp_path):
    unknown = {"name": "J. A. R.", "role": "translator", "died": None, "diedSource": ""}
    assert any("unknown" in e for e in validate(manifest_es(tmp_path, people=[unknown]), rdf_es))
    early = {"name": "Luis Gómez de Tapia", "role": "translator", "died": None, "diedSource": ""}
    assert validate(manifest_es(tmp_path, published=1580, people=[early], note="16th-century translator"),
                    rdf_es) == []
    assert validate(manifest_es(tmp_path, published=1580, people=[early]), rdf_es)  # no note


def test_anonymous_work_needs_a_note_and_people_must_be_listed(tmp_path):
    assert validate(manifest_es(tmp_path, people=[], note="Anónimo"), rdf_es) == []
    assert any("note" in e for e in validate(manifest_es(tmp_path, people=[]), rdf_es))
    assert any("no people" in e for e in validate(manifest_es(tmp_path, people=None), rdf_es))


def test_a_death_year_needs_a_source(tmp_path):
    errors = validate(manifest_es(tmp_path, people=[{**GALDOS, "diedSource": ""}]), rdf_es)
    assert any("diedSource" in e for e in errors)


def test_death_check_is_off_for_the_english_manifest(tmp_path):
    assert validate(manifest(tmp_path), rdf_ok) == []
