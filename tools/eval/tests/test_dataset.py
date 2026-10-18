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
