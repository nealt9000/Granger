import pytest

np = pytest.importorskip("numpy")

import replay
from rerank import doc_text, ranker
from test_replay import P


class FakeVectors:
    """Cosine is read from the passage's `cos` field."""
    def cos(self, question, p):
        return p.get("cos", 0.0)


def corpus(*ps, keywords=("steam", "engine", "boiler")):
    return {"keywords": list(keywords), "passages": list(ps)}


def book(source, text, cos):
    return dict(P(text, title="T " + source, source=source, location=source), cos=cos)


def test_all_lexical_at_the_app_threshold_is_the_app():
    c = corpus(book("A", "steam engine boiler", 0.1), book("B", "steam engine", 0.9), book("C", "steam", 0.8))
    cfg = replay.CONFIGS["baseline"]
    assert ranker(FakeVectors(), 12, 1.0, 0.6)("q", c, cfg) == replay.rank("q", c, cfg)


def test_all_cosine_orders_by_cosine_and_thresholds():
    a, b, c = book("A", "steam engine boiler", 0.1), book("B", "steam engine", 0.9), book("C", "steam", 0.5)
    cfg = replay.CONFIGS["baseline"]
    assert ranker(FakeVectors(), 12, 0.0, 0.4)("q", corpus(a, b, c), cfg) == [b, c]


def test_preselect_size_limits_what_can_be_reranked():
    strong = [book("S%d" % i, "steam engine boiler", 0.1) for i in range(12)]
    weak = book("W", "steam", 0.99)
    cfg = replay.CONFIGS["baseline"]
    assert weak not in ranker(FakeVectors(), 12, 0.0, -1.0)("q", corpus(*strong, weak), cfg)
    assert ranker(FakeVectors(), 24, 0.0, -1.0)("q", corpus(*strong, weak), cfg)[0] == weak


def test_doc_formats():
    p = P("body", title="Hamlet", heading="Act I")
    assert doc_text(p, "plain") == "title: none | text: body"
    assert doc_text(p, "titled") == "title: Hamlet — Act I | text: body"
    assert doc_text(P("body", title="Hamlet", heading="hamlet"), "titled") == "title: Hamlet | text: body"
    assert doc_text(P("body", title=""), "titled") == "title: none | text: body"


def test_fast_ranker_matches_ranker():
    from rerank import fast_ranker, prepare
    ps = [book("S%d" % i, "steam engine boiler"[: 5 + i * 3], 0.05 * i) for i in range(15)]
    c = dict(corpus(*ps), id="x")
    items = {"x": {"question": "q"}}
    cfg = replay.CONFIGS["baseline"]
    for n in (12, 24):
        prepared = prepare(items, {"x": c}, FakeVectors(), n, cfg)
        for alpha in (0.0, 0.5, 1.0):
            for t in (-1.0, 0.3, 0.6):
                assert fast_ranker(prepared, alpha, t)("q", c, cfg) == ranker(FakeVectors(), n, alpha, t)("q", c, cfg)
