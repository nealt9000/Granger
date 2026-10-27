import rewrite


def test_the_lexicon_is_the_apps():
    looks, strong, stop = rewrite.lexicon()
    assert looks("warning signs of a stroke") and looks("my burns hurt") and not looks("who wrote moby dick")
    assert not looks("a scant reward")  # whole words only for scan
    assert strong("Can I double my sertraline dose?") and strong("My two year old has a fever, do I go to the ER?") is not None
    assert not strong("should I take the train")
    assert {"the", "what", "whats"} <= stop


def test_extract_matches_keywords_extract():
    _, _, stop = rewrite.lexicon()
    assert rewrite.extract("Who wrote Darwin’s The Yellow Wallpaper in 1892?", stop) == ["wrote", "darwin", "yellow", "wallpaper", "1892"]
    assert rewrite.extract("O'Brien and o'brien", stop) == ["o'brien"]


def test_model_flag_reads_the_rewrite_line():
    rec = {"prompts": [{"stage": "prior-rewrite", "output": "medical: yes"}, {"stage": "rewrite", "output": "keywords: a\nmedical: no"}]}
    assert rewrite.model_flag(rec) is False
    assert rewrite.model_flag({"prompts": [{"stage": "rewrite", "output": "keywords: a"}]}) is None


def test_centroid_scores():
    import numpy as np
    train = np.array([[1.0, 0.0], [0.9, 0.1], [0.0, 1.0], [0.1, 0.9]])
    s = rewrite.centroid_scores(train, [True, True, False, False], np.array([[1.0, 0.0], [0.0, 1.0]]))
    assert s[0] > 0 > s[1]
