import importlib.util
from pathlib import Path

# Loaded by path: tools/qidindex and tools/subjects both have build.py and check.py, so neither goes on sys.path here.
_spec = importlib.util.spec_from_file_location("subjects_assign", Path(__file__).resolve().parents[1] / "assign.py")
assign = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(assign)

# A tiny class graph: dish -> food (root), assassination -> murder -> homicide (History root) -> killing,
# murder -> occurrence (event class); economist -> social scientist (stop) -> scientist (Science occupation root).
PARENTS = {"Q_dish": ["Q2095"], "Q_assassination": ["Q_murder"], "Q_murder": ["Q149086", "Q1190554"],
           "Q188094": ["Q15319501"], "Q15319501": ["Q901"], "Q_lake_type": ["Q23397"]}


def rules():
    spec, _ = assign.load_spec()
    return assign.Rules(spec, PARENTS, {}, this_year=2026)


def test_class_rules_and_the_root_itself():
    r = rules()
    assert r.rules_only({"q": "Q_pizza", "P31": ["Q_dish"]})[:2] == ("food", ["food"])
    assert r.rules_only({"q": "Q2095"})[:2] == ("food", ["food"])          # the article about the root class itself
    assert r.rules_only({"q": "Q_x", "P31": ["Q13406463"]})[2] == "dropped"  # a list page
    assert r.rules_only({"q": "Q_y", "P31": ["Q_unknown"]})[:2] == (None, [])


def test_stop_classes_keep_economists_out_of_science():
    r = rules()
    assert r.candidates({"q": "Q_p", "P31": ["Q5"], "P106": ["Q188094"]}) == {"business": 0}


def test_history_for_past_events_and_dead_public_figures_only():
    r = rules()
    event = {"q": "Q_e", "P31": ["Q_assassination"], "P585": [["+1914-06-28T00:00:00Z", 11]]}
    assert r.rules_only(event)[1] == ["history"]
    assert r.past(event, "society") and not r.past(dict(event, P585=[["+2025-01-01T00:00:00Z", 11]]), "society")
    assert not r.past(dict(event, P571=[["+1914-01-01T00:00:00Z", 9]]), "society")  # a founded thing isn't an event
    politician = {"q": "Q_r", "P31": ["Q5"], "P106": ["Q82955"]}
    assert r.rules_only(dict(politician, P570=[["+1794-07-28T00:00:00Z", 11]]))[1] == ["society", "history"]
    assert r.rules_only(politician)[1] == ["society"]


def test_places_as_a_second_subject_needs_coordinates():
    r = rules()
    item = {"q": "Q_o", "P31": ["Q_dish"]}
    _, subs, _, _ = r.decide(item, {"food": 0}, {"food": 1.0, "places": 0.8}, {})
    assert subs == ["food"]
    _, subs, _, _ = r.decide(dict(item, P625=[1.0, 2.0]), {"food": 0}, {"food": 1.0, "places": 0.8}, {})
    assert subs == ["food", "places"]


def test_lead_roles_in_both_languages():
    import re
    role_of = {"politician": "society", "actor": "film", "actriz": "film", "política": "society"}
    rx = lambda lang: re.compile(r"\b(" + "|".join(sorted(role_of, key=len, reverse=True)) + r")" + assign.PLURAL[lang] + r"\b")
    en = assign.lead_roles("Ronald Reagan was an American politician and actor who served as president.", role_of, rx("en"))
    assert en == {"society": 1.0, "film": 0.8}
    es = assign.lead_roles("Eva Perón fue una actriz y política argentina.", role_of, rx("es"), "es")
    assert es == {"film": 1.0, "society": 0.8}


def test_year_reads_wikidata_times():
    assert assign.year([["+1830-10-04T00:00:00Z", 11]]) == 1830
    assert assign.year([["-0044-03-15T00:00:00Z", 11]]) == -44
    assert assign.year(None) is None


def test_a_subjects_own_article_belongs_to_it_alone():
    r = rules()
    # Wikidata calls Art an Olympic sport (the old art competitions); it's Art & architecture's own article.
    art = {"q": "Q735", "P31": ["Q31629"]}
    assert r.rules_only(art)[:2] == ("art", ["art"])
    assert r.decide({"q": "Q12147"}, {"food": 1}, {"food": 0.9}, {})[:2] == ("health", ["health"])
    assert r.rules_only({"q": "Q8434"})[:2] == ("society", ["society"])   # Education: Society & politics


def test_history_starts_with_recorded_history():
    r = rules()
    deep = {"q": "Q_c", "P31": ["Q_assassination"], "P580": [["-538800000-01-01T00:00:00Z", 3]]}
    assert not r.past(deep, "science")
    assert r.past(dict(deep, P580=[["-2560-01-01T00:00:00Z", 9]]), "science")   # the Great Pyramid's age is History


def test_nothing_before_recorded_history_is_history():
    # The Hadean reaches History through "historical period"; dated 4.6 billion years ago, it never takes it.
    r = rules()
    hadean = {"q": "Q_h", "P31": ["Q11514315"], "P580": [["-4600000000-01-01T00:00:00Z", 3]]}
    assert "history" not in r.rules_only(hadean)[1]
    assert r.rules_only(dict(hadean, P580=[["-500-01-01T00:00:00Z", 9]]))[0] == "history"


def test_wikimedia_internal_items_are_dropped():
    assert rules().rules_only({"q": "Q5296", "P31": ["Q17442446", "Q35127"]})[2] == "dropped"
