import shutil

import pytest

import dataset
from conftest import TEST_ZIM


def item(**kw):
    base = {"id": "a-1", "group": "g", "split": "tune", "answerable": True, "medical": False,
            "multiStep": False, "question": "q?", "priorQuestion": None,
            "answers": [{"source": "granger-test", "location": "Photosynthesis", "quote": "green plants use sunlight"}],
            "reference": "r", "goodGuess": None, "notes": ""}
    base.update(kw)
    return base


def test_rejects_duplicate_ids():
    with pytest.raises(ValueError, match="a-1"):
        dataset.check([item(), item()])


def test_answerable_needs_an_answer_location():
    with pytest.raises(ValueError, match="a-1"):
        dataset.check([item(answers=[])])
    with pytest.raises(ValueError, match="a-1"):
        dataset.check([item(answers=[{"source": "s", "location": "", "quote": "x"}])])


def test_unknown_split_is_rejected():
    with pytest.raises(ValueError, match="split"):
        dataset.check([item(split="dev")])


def test_validate_finds_location_and_quote_in_zim(tmp_path):
    shutil.copy(TEST_ZIM, tmp_path / "granger-test.zim")
    assert dataset.validate_answers([item()], packs=tmp_path) == []
    bad_path = item(id="b-2", answers=[{"source": "granger-test", "location": "Photosyntesis", "quote": "green plants"}])
    bad_quote = item(id="c-3", answers=[{"source": "granger-test", "location": "Photosynthesis", "quote": "purple cows"}])
    problems = dataset.validate_answers([item(), bad_path, bad_quote], packs=tmp_path)
    assert len(problems) == 2
    assert "b-2" in problems[0] and "c-3" in problems[1]


def test_location_matches_entry_path_not_title(tmp_path):
    shutil.copy(TEST_ZIM, tmp_path / "granger-test.zim")
    # "Café au lait" is a title; its path is Café_au_lait, so a spaced location does not match at runtime and must not validate
    spaced = item(id="t-1", answers=[{"source": "granger-test", "location": "Café au lait", "quote": "x"}])
    assert "t-1" in dataset.validate_answers([spaced], packs=tmp_path)[0]


def test_validate_books(tmp_path):
    import zipfile
    with zipfile.ZipFile(tmp_path / "pg1.epub", "w") as z:
        z.writestr("META-INF/container.xml", '<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>')
        z.writestr("OEBPS/content.opf", '<package xmlns="http://www.idpf.org/2007/opf"><manifest>'
                   '<item id="c1" href="chap1.xhtml" media-type="application/xhtml+xml"/></manifest>'
                   '<spine><itemref idref="c1"/></spine></package>')
        z.writestr("OEBPS/chap1.xhtml", "<html><body><p>It was the best of times.</p></body></html>")
    ok = item(answers=[{"source": "book:granger:pg1", "location": "chap1", "quote": "best of  times"}])
    bad = item(id="b-2", answers=[{"source": "book:granger:pg1", "location": "chap9", "quote": "best of times"}])
    problems = dataset.validate_answers([ok, bad], books=tmp_path)
    assert len(problems) == 1 and "b-2" in problems[0]


def test_article_location_must_be_the_exact_entry_path(tmp_path):
    shutil.copy(TEST_ZIM, tmp_path / "granger-test.zim")
    # "Photosynth" is a substring of the entry path Photosynthesis; an article answer must name the whole path
    short = item(id="s-1", answers=[{"source": "granger-test", "location": "Photosynth", "quote": "green plants"}])
    assert "s-1" in dataset.validate_answers([short], packs=tmp_path)[0]
    # a fragment on either side is ignored, and the match is case-insensitive
    frag = item(id="s-2", answers=[{"source": "granger-test", "location": "photosynthesis#Light", "quote": "green plants"}])
    assert dataset.validate_answers([frag], packs=tmp_path) == []


def media_item(**kw):
    base = item(id="media-001", group="media", split="media", answers=[{"source": "album:pink floyd|the wall", "location": "", "quote": ""}])
    base.update(kw)
    return base


def test_media_split_loads_and_media_sources_match_by_source_only(tmp_path):
    control = media_item(id="media-control-001", group="media-control", answers=[], notes="must not cite media")
    book = media_item(id="media-002", answers=[{"source": "audiobook:PHONE:jungle", "location": "", "quote": ""}])
    f = tmp_path / "media.json"
    f.write_text(__import__("json").dumps([media_item(), book, control]))
    assert [i["id"] for i in dataset.load(f)] == ["media-001", "media-002", "media-control-001"]
    # an empty location is fine for a media source, but not for an article or a book
    with pytest.raises(ValueError, match="a-1"):
        dataset.check([item(answers=[{"source": "book:granger:x", "location": "", "quote": ""}])])
    # only a media control may be answerable with no answers
    with pytest.raises(ValueError, match="media-003"):
        dataset.check([media_item(id="media-003", answers=[])])
    assert dataset.is_media("album:x") and dataset.is_media("audiobook:PHONE:x") and not dataset.is_media("book:granger:x")
    assert dataset.location_matches("album:x", "", "") and dataset.location_matches("album:x", "", "anything")
    assert not dataset.location_matches("granger-test", "", "Photosynthesis")


def test_validate_checks_media_sources_against_the_library_export(tmp_path):
    export = {"albums": {"pink floyd|the wall": "The Wall"}, "audiobooks": {"audiobook:PHONE:jungle": "The Jungle Book"}}
    ok = [media_item(), media_item(id="media-002", answers=[{"source": "audiobook:PHONE:jungle", "location": "", "quote": ""}])]
    assert dataset.validate_answers(ok, media=export) == []
    gone_album = media_item(id="media-003", answers=[{"source": "album:pink floyd|animals", "location": "", "quote": ""}])
    gone_book = media_item(id="media-004", answers=[{"source": "audiobook:PHONE:wicked", "location": "", "quote": ""}])
    with_location = media_item(id="media-005", answers=[{"source": "album:pink floyd|the wall", "location": "x", "quote": ""}])
    problems = dataset.validate_answers(ok + [gone_album, gone_book, with_location], media=export)
    assert len(problems) == 3 and "media-003" in problems[0] and "media-004" in problems[1] and "media-005" in problems[2]
    # without an export, media sources are skipped like books without a books folder
    assert dataset.validate_answers([gone_album]) == []
