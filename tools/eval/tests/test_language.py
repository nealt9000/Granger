"""The Spanish layer's port (Plan L4): `language.of` is ResearchLanguage.of, the Spanish lists are the app's, and the
replay, kinds and rewrite mirrors give English questions exactly what they gave before Spanish."""
import json
import re
from pathlib import Path

import pytest

import kinds
import language
import replay
import rewrite

EVAL = Path(__file__).resolve().parents[1]


def questions(name):
    return [i["question"] for i in json.loads((EVAL / "dataset" / name).read_text(encoding="utf-8"))]


def labels(name):
    return [json.loads(l) for l in (EVAL / "sets" / name).read_text(encoding="utf-8").splitlines() if l.strip()]


def test_of_matches_research_language():
    assert language.of("How old was Mary Shelley?") == ("en",)
    assert language.of("¿cuántos años tenía Cervantes?") == ("es",)
    assert language.of("Los Angeles population") == ("en", "es")
    assert language.of("quien escribio rayuela") == ("es",)


def test_every_english_eval_question_is_english():
    for q in questions("tune.json") + questions("heldout.json") + questions("media.json"):
        assert "en" in language.of(q), q


def test_spanish_eval_questions_are_mostly_spanish():
    qs = questions("tune-es.json")
    assert sum(language.of(q)[0] != "es" for q in qs) <= len(qs) // 20


def test_the_spanish_lists_are_read_from_the_source():
    assert {"cuantos", "dime", "cuentame", "el"} <= language.STOPWORDS_ES
    assert {"son", "los", "con"} <= language.ENGLISH_TOO
    assert {"musica", "audiolibro"} <= language.CUES_ES and {"novela", "poesia"} <= language.LITERARY_ES
    assert not any(w.startswith("//") for w in language.STOPWORDS_ES)


def test_the_hand_copied_media_lists_match_the_source():
    src = (language.KT / "MediaCues.kt").read_text(encoding="utf-8")
    phrases = re.findall(r'listOf\(([^()]*)\)', language._block(src, "val PHRASES_ES = listOf"))
    assert [re.findall(r'"([^"]*)"', p) for p in phrases] == language.PHRASES_ES
    assert language.kotlin_set("MediaCues.kt", "val AFTER_TENGO = setOf") == language.AFTER_TENGO
    assert "ancho de banda" in src and "banda ancha" in src


def test_spanish_stopwords_in_keywords_dont_count():
    p = {"kind": "ARTICLE", "source": "w", "title": "Batalla de Boyacá", "location": "x", "heading": "Desarrollo",
         "text": "La batalla se libró junto al puente sobre el río Teatinos, cerca de Tunja, en Boyacá."}
    assert replay.scorer(["la batalla del puente de boyacá"], ("es",))(p) == pytest.approx(1.0)
    assert replay.scorer(["la batalla del puente de boyacá"])(p) < 1.0


def test_spanish_media_and_literary_cues():
    assert replay.media_asked("¿Tengo algo de Mercedes Sosa?") and replay.media_asked("¿Cuál es la banda sonora de Coco?")
    assert not replay.media_asked("¿qué es el ancho de banda?") and not replay.media_asked("tengo un lunar que me pica")
    assert kinds.literary("¿qué dice la novela sobre la luna?", [], [])
    assert kinds.literary("¿de qué trata la obra de teatro La vida es sueño?", [], [])
    assert not kinds.literary("¿qué tan lejos está la luna?", [], [])


def test_english_questions_are_unchanged():
    _, _, stop = rewrite.lexicon()
    p = {"kind": "BOOK", "source": "b", "title": "T", "location": "", "heading": "", "text": ""}
    for q in questions("tune.json") + questions("heldout.json") + questions("media.json"):
        en, of = ("en",), language.of(q)
        assert replay.media_asked(q, en) == replay.media_asked(q, of), q
        assert rewrite.extract(q, stop, languages=en) == rewrite.extract(q, stop), q
        kw = rewrite.extract(q, stop)
        p["text"] = q
        assert replay.scorer(kw + [q], en)(p) == replay.scorer(kw + [q], of)(p), q


def test_spanish_extract():
    _, _, stop = rewrite.lexicon()
    assert rewrite.extract("¿quién escribió La casa de los espíritus?", stop) == ["escribió", "casa", "espíritus"]
    assert rewrite.extract("¿Quién fue Bernardo O'Higgins?", stop) == ["bernardo", "o'higgins"]


def test_the_spanish_medical_lexicon_is_the_apps():
    # As MedicalEsTest: every Spanish medical label flagged, no ordinary question flagged.
    looks, strong, _ = rewrite.lexicon()
    rows = labels("medical-labels-es.jsonl") + labels("medical-heldout-es.jsonl")
    assert [r["q"] for r in rows if r["medical"] and not (looks(r["q"]) or strong(r["q"]))] == []
    assert [r["q"] for r in rows if r["kind"] == "other" and (looks(r["q"]) or strong(r["q"]))] == []
    # "mole" is a sauce in a Spanish question; the English lexicon is off.
    assert not looks("receta del mole poblano") and looks("How do I remove a mole?")
    assert looks("fiebre en bebes") and not looks("qué fue la fiebre del oro")
