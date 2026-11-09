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
    assert any("source must be" in e for e in validate(manifest(tmp_path, source="fadedpage"), rdf_ok))


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
    assert any("source must be" in e for e in validate(manifest(tmp_path, source="fadedpage"), rdf_ok))


def test_cut_after_is_read_and_must_be_text(tmp_path):
    assert manifest(tmp_path).works[0].cut_after is None
    assert manifest(tmp_path, cutAfter="bury us all!”").works[0].cut_after == "bury us all!”"
    assert validate(manifest(tmp_path, cutAfter="bury us all!”"), rdf_ok) == []
    assert any("cutAfter" in e for e in validate(manifest(tmp_path, cutAfter=""), rdf_ok))
    assert any("cutAfter" in e for e in validate(manifest(tmp_path, cutAfter=3), rdf_ok))


def test_pack_sections_is_read_and_must_be_true_or_false(tmp_path):
    assert manifest(tmp_path).works[0].pack_sections is False
    assert manifest(tmp_path, packSections=True).works[0].pack_sections is True
    assert any("packSections" in e for e in validate(manifest(tmp_path, packSections="yes"), rdf_ok))


def test_title_check_treats_dashes_as_word_breaks(tmp_path):
    def rdf(_):
        return Rdf("The Metamorphoses of Ovid, Books VIII-XV", ["en"], PD)
    assert validate(manifest(tmp_path, title="Metamorphoses, Books VIII–XV"), rdf) == []
    assert validate(manifest(tmp_path, title="Metamorphoses, Books VIII—XV"), rdf) == []
    assert validate(manifest(tmp_path, title="Metamorphoses, Books VIII–XVI"), rdf)


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


def no_rdf(_):
    raise AssertionError("only Gutenberg works have an RDF record")


SE_RIGHTS = ("The source text and artwork in this ebook are believed to be in the United States public domain [...] "
             "dedicate their contributions to the worldwide public domain via the terms in the CC0 1.0 Universal "
             "Public Domain Dedication")


def se_work(tmp_path, **work):
    row = dict(source="standardebooks", gutenberg=None, se="franz-kafka/the-castle/willa-muir_edwin-muir",
               slug="castle", title="The Castle", author="Franz Kafka", translator="Willa and Edwin Muir",
               published=1930)
    row.update(work)
    return manifest(tmp_path, **row)


def test_standard_ebooks_rows_load_and_pass_with_cc0_rights_and_old_dates(tmp_path):
    from manifest import SeRecord
    m = se_work(tmp_path)
    assert (m.works[0].source, m.works[0].se, m.works[0].where) == (
        "standardebooks", "franz-kafka/the-castle/willa-muir_edwin-muir",
        "castle (se franz-kafka/the-castle/willa-muir_edwin-muir)")
    asked = []
    record = SeRecord(placeholder=False, title="The Castle", rights=SE_RIGHTS, years=[1926, 1930])
    assert validate(m, no_rdf, se_for=lambda i: asked.append(i) or record) == []
    assert asked == ["franz-kafka/the-castle/willa-muir_edwin-muir"]


def test_standard_ebooks_rows_refuse_placeholders_late_dates_bad_rights_titles_and_ids(tmp_path):
    from manifest import SeRecord
    good = SeRecord(placeholder=False, title="The Castle", rights=SE_RIGHTS, years=[1930])

    def errors(record=good, **work):
        return validate(se_work(tmp_path, **work), no_rdf, se_for=lambda i: record)

    assert any("placeholder" in e for e in errors(SeRecord(placeholder=True)))
    assert any("1937" in e for e in errors(SeRecord(False, "The Castle", SE_RIGHTS, [1926, 1937])))
    assert any("after the 1930" in e for e in errors(published=1931))
    assert any("CC0" in e for e in errors(SeRecord(False, "The Castle", "All rights reserved", [1930])))
    assert any("doesn't match" in e for e in errors(SeRecord(False, "The Trial", SE_RIGHTS, [1930])))
    assert any("se must be" in e for e in errors(se="../etc/passwd"))
    assert any("se must be" in e for e in errors(se="kafka"))


class Licence:
    def __init__(self, government=False):
        self.government = government


def test_wikisource_rows_need_a_us_licence_and_the_year_line_unless_government(tmp_path):
    def ws(**work):
        row = dict(source="wikisource", gutenberg=None, wikisource="A Room of One's Own (Hogarth 1929)",
                   slug="room-of-one-s-own", title="A Room of One's Own", author="Virginia Woolf", published=1929)
        row.update(work)
        return manifest(tmp_path, **row)

    m = ws(wsSkip=["Index"])
    assert m.works[0].ws_skip == ["Index"] and m.works[0].where.startswith("room-of-one-s-own (wikisource")
    assert validate(m, no_rdf, ws_licence_for=lambda t: Licence()) == []
    assert any("no US public-domain licence" in e for e in validate(m, no_rdf, ws_licence_for=lambda t: None))
    late = ws(published=1971)
    assert any("after the 1930" in e for e in validate(late, no_rdf, ws_licence_for=lambda t: Licence()))
    assert validate(late, no_rdf, ws_licence_for=lambda t: Licence(government=True)) == []
    assert any("page title" in e for e in validate(ws(wikisource=""), no_rdf))


def compiled_work(tmp_path, parts):
    return manifest(tmp_path, source="compiled", gutenberg=None, slug="founding-documents",
                    title="Founding Documents", author="", translator=None, published=1787, splitAt="h2", parts=parts)


def test_compiled_rows_check_each_part_by_its_source(tmp_path):
    parts = [{"title": "The Declaration", "gutenberg": 1, "select": "div.etext"},
             {"title": "The Articles", "wikisource": "Articles of Confederation and Perpetual Union"},
             {"title": "Amendments", "pages": [
                 {"title": "Amendment XI", "wikisource": "11th Amendment to the United States Constitution"},
                 {"title": "Amendment XXVII", "html": "<p>No law...</p>",
                  "licence": "a US government work: https://www.archives.gov/founding-docs/amendments-11-27"}]}]
    m = compiled_work(tmp_path, parts)
    w = m.works[0]
    assert [p.source for p in w.parts] == ["gutenberg", "wikisource", "pages"]
    assert w.parts[0].select == "div.etext" and [p.source for p in w.parts[2].pages] == ["wikisource", "text"]
    looked = []
    assert validate(m, lambda i: looked.append(i) or rdf_ok(i), ws_licence_for=lambda t: looked.append(t) or Licence()) == []
    assert looked == [1, "Articles of Confederation and Perpetual Union",
                      "11th Amendment to the United States Constitution"]


def test_compiled_rows_refuse_a_copyrighted_part_an_unlicensed_text_and_no_parts(tmp_path):
    def errors(parts, rights=PD):
        return validate(compiled_work(tmp_path, parts), lambda i: Rdf("x", ["en"], rights),
                        ws_licence_for=lambda t: None)

    assert any("rights are" in e and "part 1" in e for e in errors([{"title": "A", "gutenberg": 7}], "Copyrighted."))
    assert any("licence" in e for e in errors([{"title": "A", "wikisource": "Some Page"}]))
    assert any("US government" in e for e in errors([{"title": "A", "html": "<p>x</p>", "licence": "mine"}]))
    assert any("needs parts" in e for e in errors([]))
    assert any("needs a title" in e for e in errors([{"gutenberg": 7}]))
