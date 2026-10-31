"""The question's languages and the Spanish word lists, as core/research has them (languages spec §6.2, Plan L4).

`of` is `ResearchLanguage.of`: the languages a question may be in, main one first, from function words only one language
uses. The Spanish lists are read from the Kotlin source, so a replay of an `-es` dataset uses the app's own words; an
English question gets exactly what it got before Spanish.
"""
import re
import unicodedata
from pathlib import Path

KT = Path(__file__).resolve().parents[2] / "core/research/src/main/kotlin/app/granger/core/research"
EN, ES = "en", "es"
ENGLISH_ONLY = (EN,)


def _block(src, name):
    """The body of `name = setOf(...)` (or `listOf`), up to its closing bracket; brackets inside strings don't count."""
    at = src.index("(", src.index(name)) + 1
    depth, i, quoted = 1, at, False
    while depth:
        ch = src[i]
        if quoted:
            if ch == "\\":
                i += 1
            elif ch == '"':
                quoted = False
        elif ch == '"':
            quoted = True
        else:
            depth += {"(": 1, ")": -1}.get(ch, 0)
        i += 1
    return src[at:i - 1]


def kotlin_set(file, name):
    """The strings of `name = setOf(...)` in core/research's [file], comments left out."""
    body = _block((KT / file).read_text(encoding="utf-8"), name)
    body = "\n".join(l for l in body.splitlines() if not l.strip().startswith("//"))
    return set(re.findall(r'"([^"\\]*)"', body))


ENGLISH_SIGNS = kotlin_set("ResearchLanguage.kt", "val ENGLISH = setOf")
SPANISH_SIGNS = kotlin_set("ResearchLanguage.kt", "val SPANISH = setOf")
STOPWORDS_ES = kotlin_set("Keywords.kt", "val STOPWORDS_ES = setOf")
ENGLISH_TOO = kotlin_set("SpanishWords.kt", "val ENGLISH_TOO = setOf")
CUES_ES = kotlin_set("MediaCues.kt", "val CUES_ES = setOf")
LITERARY_ES = kotlin_set("FictionWeight.kt", "val LITERARY_ES = setOf")
# MediaCues.kt's PHRASES_ES, AFTER_TENGO and BANDWIDTH_ES (tests/test_language.py checks them against the source).
PHRASES_ES = [["leido", "por"], ["leida", "por"], ["tengo", "algo", "de"], ["hay", "algo", "de"]]
AFTER_TENGO = {"algo", "algun", "alguna", "algunos", "algunas", "a", "de"}
BANDWIDTH_ES = re.compile(r"\bancho de banda\b|\bbanda ancha\b")

WORD = re.compile(r"[^\W_]+")  # Kotlin's [\p{L}\p{N}]+
SPANISH_MARKS = re.compile(r"[¿¡]")


def fold(text):
    """ResearchLanguage.fold: accents off, lower case, ’ as '."""
    nfd = unicodedata.normalize("NFD", text)
    return "".join(c for c in nfd if not unicodedata.category(c).startswith("M")).lower().replace("’", "'")


def of(question):
    """ResearchLanguage.of: (EN,), (ES,), or both with the main one first."""
    tokens = WORD.findall(question)
    # A capitalised word before another is part of a name ("Los Angeles"), not Spanish.
    words = [fold(t) for i, t in enumerate(tokens)
             if not (t[0].isupper() and i + 1 < len(tokens) and tokens[i + 1][0].isupper())]
    en = sum(1 for w in words if w in ENGLISH_SIGNS)
    marks = 2 if SPANISH_MARKS.search(question) else 0
    es = sum(1 for w in words if w in SPANISH_SIGNS) + marks
    # Spanish words that are English too ("hay fever") don't make a question Spanish alone.
    plainly = sum(1 for w in words if w in SPANISH_SIGNS and w not in ENGLISH_TOO) + marks
    if plainly >= 1 and en == 0:
        return (ES,)
    if es >= 1 and en == 0:
        return (EN, ES)
    if es >= 2 and es > en:
        return (ES, EN)
    if es >= 2:
        return (EN, ES)
    if en >= 1:
        return (EN,)
    return (EN, ES)


def leads(languages):
    return bool(languages) and languages[0] == ES


def counts(word, languages):
    """SpanishWords.counts: a Spanish entry that's English too ("son", "Los") counts only when Spanish leads."""
    return ES in languages and (leads(languages) or word not in ENGLISH_TOO)


def phrase_counts(phrase, languages):
    return ES in languages and (leads(languages) or any(w not in ENGLISH_TOO for w in phrase))


def spanish_stopword(word, languages):
    """The Spanish half of Keywords.isStopword."""
    return ES in languages and fold(word) in STOPWORDS_ES and counts(word.lower(), languages)


def spanish_media(question, languages):
    """The Spanish half of MediaCues.asked: a cue, a phrase, or "tengo" first then a word of AFTER_TENGO or a name."""
    if ES not in languages:
        return False
    folded = "".join(c for c in unicodedata.normalize("NFKD", question) if not unicodedata.category(c).startswith("M")).lower()
    ws = WORD.findall(folded)
    spanish = WORD.findall(BANDWIDTH_ES.sub(" ", folded))
    if any(w in CUES_ES and counts(w, languages) for w in spanish):
        return True
    if any(ws[i:i + len(ph)] == ph and phrase_counts(ph, languages) for ph in PHRASES_ES for i in range(len(ws))):
        return True
    if ws[:1] == ["tengo"] and len(ws) >= 2:
        raw = WORD.findall(question)
        return ws[1] in AFTER_TENGO or (len(raw) > 1 and raw[1][0].isupper())
    return False


def spanish_literary(question, languages):
    """The Spanish half of FictionWeight.literary: a word of LITERARY_ES, or "obra de teatro"."""
    if ES not in languages:
        return False
    folded = [fold(w) for w in WORD.findall(question)]
    return any(w in LITERARY_ES and counts(w, languages) for w in folded) or any(
        folded[i:i + 3] == ["obra", "de", "teatro"] for i in range(len(folded)))
