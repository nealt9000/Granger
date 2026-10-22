import kinds
import replay
from test_replay import P


def B(slug, text="steam engine boiler", title=None):
    return P(text, title=title or slug, source="book:granger:gc-" + slug, location=slug)


def test_kind_from_the_classics_sections():
    assert kinds.kind(B("moby-dick")) == "fiction"
    assert kinds.kind(B("king-james-bible")) == "scripture"
    assert kinds.kind(B("leviathan")) == "philosophy"
    assert kinds.kind(P("x", kind="ARTICLE")) == "reference"
    assert kinds.kind(P("x", kind="MEDIA")) == "media"
    assert kinds.kind(P("x", source="book:archive-org:steamengineprinc00crofrich")) == "other"


def test_literary_by_cue_word_or_a_named_fiction_title():
    moby = B("moby-dick", title="Moby Dick")
    assert kinds.literary("What does Ishmael say first?", [], [])
    assert kinds.literary("what happens to the pequod", ["moby dick", "pequod"], [moby])
    assert not kinds.literary("how far away is the moon", ["moon", "distance"], [moby])


def test_weight_one_is_the_app():
    c = {"keywords": ["steam", "engine", "boiler"],
         "passages": [B("dracula", "steam engine boiler"), P("steam engine", kind="ARTICLE", source="wiki", location="Steam")]}
    cfg = replay.CONFIGS["baseline"]
    assert kinds.lexical_ranker(1.0, "filter")("how does a boiler work", c, cfg) == replay.rank("how does a boiler work", c, cfg)


def test_fiction_drops_on_factual_questions_only():
    dracula = B("dracula", "steam engine boiler", title="Dracula")
    wiki = P("steam engine", kind="ARTICLE", source="wiki", location="Steam")
    c = {"keywords": ["steam", "engine", "boiler"], "passages": [dracula, wiki]}
    cfg = replay.CONFIGS["baseline"]
    assert kinds.lexical_ranker(0.5, "order")("how does a boiler work", c, cfg) == [wiki, dracula]
    assert kinds.lexical_ranker(0.5, "filter")("how does a boiler work", c, cfg) == [wiki]
    assert kinds.lexical_ranker(0.5, "filter")("which novel has a boiler", c, cfg) == [dracula, wiki]
