#!/usr/bin/env python3
"""Plan R1 follow-up: answer a multi-step question as simple ones (Neal, 2026-10-23).

The model splits the question into simple questions; each is searched by meaning (on the Mac, as the phone would) and
answered with the app's own answer prompt; a later one may use an earlier one's answer (#1); a last answer prompt
combines them. The model steps replay on the phone through `answerers.py`'s replay (EvalActivity --es replay), so
each round is: write prompts here, replay them there, read the replies back.

  multistep.py split    BASE NEW                # round 1: NEW/split-prompts.jsonl, one per question of BASE
  multistep.py hop      BASE NEW --round N      # rounds 2, 3: the sub-questions whose #refs are answered
  multistep.py combine  BASE NEW [--strict]     # round 4: the combined answer prompts (--strict: gate + arithmetic)
  multistep.py replay   NEW STAGE --model FILE  # pushes NEW/STAGE-prompts.jsonl, replays it, pulls NEW/STAGE-replies.jsonl
  multistep.py build    BASE NEW [--strict]     # NEW/results.jsonl: BASE's records, multi-step ones answered this way

A question the model doesn't split keeps BASE's record, so NEW measures the change as the app would see it.

The item's question languages (`language.of`, the app's ResearchLanguage.of) pick how the parts' answers are read for
--strict's arithmetic: a question that may be Spanish also reads YearFacts' Spanish dates, eras, ranges and event words
(Plan L4). The worked facts stay English prompt text, as YearFacts' block does. An English question reads as before.
"""
import argparse
import datetime
import json
import re
import shutil
import sys
from pathlib import Path

import answerers
import language
import merge
from language import EN, ES, ENGLISH_ONLY

HERE = Path(__file__).resolve().parent
RUNS = HERE / "runs"
VECTORS = HERE.parent / "vectors"
RELEASES = VECTORS / "releases"
WORK = Path.home() / "Developer/Granger/vectors-work/build"

DECLINE = "NOT IN LIBRARY"
MAX_PARTS = 3
SUB_PASSAGES = 4
COMBINED_PASSAGES = 8
GATE = 0.78  # Ranking's denseGate: a sub-question with nothing this close is not in the library
TEXT_CHARS, MAX_HEADER = 1200, 240

SPLIT = """Break the question into simple questions that can each be answered from one encyclopedia article.
Write one per line, numbered. A later question may use #1 or #2 for the answer to an earlier one.
If the question is already simple, write it alone as 1.
Example:
Question: Who was older, Mozart or Haydn?
1. When was Mozart born?
2. When was Haydn born?
Example:
Question: What river runs through the capital of France?
1. What is the capital of France?
2. What river runs through #1?
Example:
Question: What is a bowline knot?
1. What is a bowline knot?
"""

# v2: most questions are simple; a split is asked for only when the answer needs facts from separate articles.
SPLIT_V2 = """Does answering the question need two or more separate facts that would be in different encyclopedia articles?
If not, reply exactly: SIMPLE
If so, write the simple questions, numbered, one fact each. When a question needs an earlier answer, write #1 or #2 for it.
Don't write a question that repeats the whole question or combines the others.
Example:
Question: Who was older, Mozart or Haydn?
1. When was Mozart born?
2. When was Haydn born?
Example:
Question: What language is spoken in the country where the Taj Mahal is?
1. In which country is the Taj Mahal?
2. What language is spoken in #1?
Example:
Question: Was Napoleon alive when the Eiffel Tower was built?
1. When did Napoleon die?
2. When was the Eiffel Tower built?
Example:
Question: How did Julius Caesar die?
SIMPLE
Example:
Question: What does the Rosetta Stone say?
SIMPLE
"""

SPLITS = {"v1": SPLIT, "v2": SPLIT_V2}

RULES = """You are Granger's research librarian. Answer the question using only the numbered passages from the user's library below.
Rules:
- Answer the question directly in your first sentence, then give the supporting details.
- Put the passage number in square brackets after every claim it supports, like [2]. Cite only the numbers listed.
- You may combine facts from different passages and work out simple things from them (dates, ages, which came first, how long between), citing every passage you used.
- Use the source details (title, author, date, collection) when they matter to the question.
- When passages from different works bear on each other, say how, citing each.
- Don't add facts that aren't in the passages.
- Passages about a different person, year, place or event than the question asks about don't answer it.
- If the passages don't answer the question, reply exactly: NOT IN LIBRARY
"""


# ---- prompts ----

def split_prompt(question, prior=None, version="v1"):
    head = SPLITS[version] + ("Previous question: %s\n" % prior if prior else "")
    return head + "Question: %s" % question


NUMBERED = re.compile(r"^\s*(\d+)[.)]\s*(.+?)\s*$")


def parse_split(reply):
    """The numbered questions of a split reply, at most MAX_PARTS; [] when it isn't numbered."""
    out = []
    for line in (reply or "").splitlines():
        m = NUMBERED.match(line)
        if m and int(m.group(1)) == len(out) + 1:
            out.append(m.group(2))
    return out[:MAX_PARTS]


REF = re.compile(r"#(\d+)")
CITE = re.compile(r"\s*\[\d+(?:\s*,\s*\d+)*\]")


def refs(question):
    return {int(n) for n in REF.findall(question)}


def first_sentence(answer, languages=ENGLISH_ONLY):
    """[answer]'s first sentence without citations. Spanish first ([languages]) breaks as YearFacts' SENTENCE_ES does."""
    text = CITE.sub("", answer or "").strip()
    if language.leads(languages) or EN not in languages:
        return _first_sentence_es(text)
    m = re.search(r"(.+?[.!?])(\s|$)", text)
    return (m.group(1) if m else text).strip()


def fill(question, answers, languages=ENGLISH_ONLY):
    """[question] with each #n replaced by answer n's first sentence; None while one is missing or declined."""
    need = refs(question)
    if any(n not in answers or answers[n] is None for n in need):
        return None
    return REF.sub(lambda m: "(%s)" % first_sentence(answers[int(m.group(1))], languages).rstrip("."), question)


def _cut(text, limit):
    if len(text) <= limit:
        return text
    head = text[:limit]
    space = max(head.rfind(" "), head.rfind("\n"))
    return (head[:space] if space > 0 else head).rstrip() + "…"


# ---- strict: the split gate and the arithmetic, in code (Neal, 2026-10-23) ----

MONTHS = {m: n for n, m in enumerate(("january february march april may june july august september october november "
                                       "december").split(), 1)}
_M = "(%s)" % "|".join(m.capitalize() for m in MONTHS)
DATE_MDY = re.compile(_M + r" (\d{1,2}),? (\d{3,4})")
DATE_DMY = re.compile(r"(\d{1,2}) " + _M + r",? (\d{3,4})")
YEAR = re.compile(r"\b(\d{1,4})\s*(BC|BCE)\b|\b(1\d{3}|20\d{2})\b")
AMOUNT = re.compile(r"\b(\d[\d,]*(?:\.\d+)?)\s+([A-Za-z][A-Za-z-]*)")
# Spanish-led items: the number may use "." for thousands and "," for decimals, and the unit may be accented ("años").
AMOUNT_ES = re.compile(r"\b(\d(?:[\d.,]*\d)?)\s+([^\W\d_](?:[^\W\d_]|-)*)")
_THOUSANDS_DOT = re.compile(r"\d{1,3}(?:\.\d{3})+(?:,\d+)?")
_THOUSANDS_COMMA = re.compile(r"\d{1,3}(?:,\d{3})+(?:\.\d+)?")
_DECIMAL_COMMA = re.compile(r"\d+,\d+")
_DECIMAL_DOT = re.compile(r"\d+(?:\.\d+)?")


def when(text, languages=ENGLISH_ONLY, question=None):
    """The first date in [text]: (year, month, day), with month and day 0 for a bare year; BC years are negative.
    [languages] are the item question's; Spanish among them reads Spanish dates too, and the part's [question] picks
    the event's year as YearFacts does (`_when_es`). English alone ignores [question]."""
    if ES in languages:
        return _when_es(text, languages, question)
    found = []
    for m in DATE_MDY.finditer(text):
        found.append((m.start(), (int(m.group(3)), MONTHS[m.group(1).lower()], int(m.group(2)))))
    for m in DATE_DMY.finditer(text):
        found.append((m.start(), (int(m.group(3)), MONTHS[m.group(2).lower()], int(m.group(1)))))
    for m in YEAR.finditer(text):
        found.append((m.start(), (-int(m.group(1)), 0, 0) if m.group(1) else (int(m.group(3)), 0, 0)))
    return min(found)[1] if found else None


# ---- Spanish dates (Plan L4): YearFacts' Spanish forms, ported so the eval and the app read a date alike ----

def _kotlin_groups(name):
    """The word sets of YearFacts.kt's `name = listOf(setOf(...), ...)`."""
    body = language._block((language.KT / "YearFacts.kt").read_text(encoding="utf-8"), name)
    return [set(re.findall(r'"([^"\\]*)"', g)) for g in re.findall(r"setOf\(([^)]*)\)", body)]


MESES = [m for m in re.findall(r'"([^"]*)"', language._block((language.KT / "YearFacts.kt").read_text(encoding="utf-8"),
                                                               "private val MESES = listOf"))] + ["setiembre"]
MONTHS_ES = dict({m: n for n, m in enumerate(MESES[:12], 1)}, setiembre=9)
_MES = "(%s)" % "|".join("[%s%s]%s" % (m[0].upper(), m[0], m[1:]) for m in MESES)
# "18 de junio de 1815", "18 junio 1815", "3 de enero del 2000".
DATE_DMY_ES = re.compile(r"(\d{1,2}) (?:de )?" + _MES + r" (?:de |del )?(\d{3,4})")
# "a. C.", "a.C.", "a. de C.", "antes de Cristo", "a. e. c.", "AEC", "a. n. e." (YearFacts' BC_ES); and their AD forms.
BC_ES = r"\s*(?:a\.\s?C\.|a\.\s?de\s?C\.|antes de Cristo|a\.\s?e\.\s?c\.|AEC\b|a\.\s?n\.\s?e\.)"
AD_ES = r"\s*(?:d\.\s?C\.|d\.\s?de\s?C\.|despu[eé]s de Cristo|d\.\s?n\.\s?e\.|e\.\s?c\.|EC\b)"
YEAR_ES = re.compile(r"\b(\d{1,4})" + BC_ES + r"|\b(\d{1,4})" + AD_ES + r"|\b(1\d{3}|20\d{2})\b")
_Y = r"(1\d{3}|20\d{2})"
# YearFacts' RANGE_ES: "de 1810 a 1825", "desde 1810 hasta 1825", "entre 1810 y 1825", "1810-1825"; the last group is the end.
RANGE_ES = re.compile(r"\b(?:(?:de|desde) %s (?:a|hasta) %s|entre %s y %s|%s\s*[–—-]\s*%s)\b" % ((_Y,) * 6))
GROUPS_ES = _kotlin_groups("private val GROUPS_ES = listOf")
ENDS_ES = language.kotlin_set("YearFacts.kt", "private val ENDS_ES = setOf")
_LETTERS = re.compile(r"[^\W\d_]+")
# YearFacts' SENTENCE_ES: "a. C.", "Sr." and the like don't end a sentence; one may open with ¿ ¡ « or a capital.
_NOT_AN_END = {"a", "d", "e", "c", "Sr", "Sra", "Dr", "Dra", "Sto", "Sta", "Ud", "Uds"}
_END_ES = re.compile(r"[.!?]\s+(?=[¿¡«\"'(\[]?([^\W_]))")


def _first_sentence_es(text):
    for m in _END_ES.finditer(text):
        nxt = m.group(1)
        if not (nxt.isupper() or nxt.isdigit()):
            continue
        before = re.search(r"(\w+)\.$", text[:m.start() + 1]) if text[m.start()] == "." else None
        if before and before.group(1) in _NOT_AN_END and (m.start() - len(before.group(1)) == 0 or
                                                           not text[m.start() - len(before.group(1)) - 1].isalnum()):
            continue
        return text[:m.start() + 1].strip()
    return text.strip()


def _event_words(languages):
    """YearFacts' Spanish event words for [languages]: all of them when Spanish leads, else those that aren't English
    too (English multistep had no event words, so an English-led question gains only plainly Spanish ones)."""
    keep = (lambda w: True) if language.leads(languages) else (lambda w: w not in language.ENGLISH_TOO)
    return [{w for w in g if keep(w)} for g in GROUPS_ES], {w for w in ENDS_ES if keep(w)}


def _dates(text, languages):
    """Every date in [text] as (start, end, (year, month, day)), in order: English forms for English, YearFacts' Spanish
    ones for Spanish. A year with an era is that era's, not a bare year at the same place ("1000 a. C.")."""
    found, eras = [], set()
    if ES in languages:
        for m in DATE_DMY_ES.finditer(text):
            found.append((m.start(), m.end(), (int(m.group(3)), MONTHS_ES[m.group(2).lower()], int(m.group(1)))))
        for m in YEAR_ES.finditer(text):
            if m.group(1) or m.group(2):
                eras.add(m.start())
                found.append((m.start(), m.end(), (-int(m.group(1)), 0, 0) if m.group(1) else (int(m.group(2)), 0, 0)))
            else:
                found.append((m.start(), m.end(), (int(m.group(3)), 0, 0)))
    if EN in languages:
        for m in DATE_MDY.finditer(text):
            found.append((m.start(), m.end(), (int(m.group(3)), MONTHS[m.group(1).lower()], int(m.group(2)))))
        for m in DATE_DMY.finditer(text):
            found.append((m.start(), m.end(), (int(m.group(3)), MONTHS[m.group(2).lower()], int(m.group(1)))))
        for m in YEAR.finditer(text):
            if m.group(1):
                eras.add(m.start())
            found.append((m.start(), m.end(), (-int(m.group(1)), 0, 0) if m.group(1) else (int(m.group(3)), 0, 0)))
    bare = lambda f: not f[2][1] and f[2][0] > 0 and f[1] - f[0] == 4
    return sorted({f for f in found if not (f[0] in eras and bare(f))}, key=lambda f: (f[0], f[2]))


def _date_at(dates, at):
    """The full date whose match covers the year at [at] (YearFacts' dateIn), else the year there alone."""
    here = [d for start, _, d in dates if start == at]
    if not here:
        return None
    full = [d for start, end, d in dates if start <= at < end and d[1] and d[0] == here[0][0]]
    return full[0] if full else here[0]


def _when_es(text, languages, question):
    """when() for a question that may be Spanish. With the part question's event words in [text], the year after the
    first of them (a range's end for an end word: "cayó ... de 1961 a 1989"), as YearFacts.eventFor; else the first date."""
    dates = _dates(text, languages)
    if not dates:
        return None
    if question:
        groups, ends = _event_words(languages)
        asked = set(_LETTERS.findall(language.fold(question)))
        wanted = set().union(*(g for g in groups if g & asked))
        words = [(m.start(), m.group()) for m in _LETTERS.finditer(language.fold(text)) if m.group() in wanted]
        if words:
            if any(w in ends for _, w in words):
                r = RANGE_ES.search(text)
                if r:
                    end = [i for i in range(1, r.re.groups + 1) if r.group(i)][-1]
                    d = _date_at(dates, r.start(end))
                    if d:
                        return d
            after = [f for f in dates if f[0] > words[0][0]]
            if after:
                return _date_at(dates, after[0][0]) or after[0][2]
    return dates[0][2]


def _number_es(s):
    """A Spanish-written number: "1.500" and "1.500,5" with dot thousands, "2,5" a decimal comma. "1,500" stays a
    thousand-five-hundred (as in Mexico, and as an English-trained model may write it), and "1.5" a decimal."""
    if _THOUSANDS_DOT.fullmatch(s):
        return float(s.replace(".", "").replace(",", "."))
    if _THOUSANDS_COMMA.fullmatch(s):
        return float(s.replace(",", ""))
    if _DECIMAL_COMMA.fullmatch(s):
        return float(s.replace(",", "."))
    if _DECIMAL_DOT.fullmatch(s):
        return float(s)
    return None


def amount(text, languages=ENGLISH_ONLY):
    """The first number with a unit in [text]: (value, unit, word), the unit singular and lowercase for comparing.
    An item whose question leads with Spanish ([languages], `language.of`) reads Spanish numbers ([_number_es]);
    any other reads English ones only, as before."""
    if language.leads(languages):
        for m in AMOUNT_ES.finditer(text):
            value = _number_es(m.group(1))
            if value is not None:
                return value, m.group(2).lower().rstrip("s"), m.group(2)
        return None
    for m in AMOUNT.finditer(text):
        try:
            value = float(m.group(1).replace(",", ""))
        except ValueError:
            continue
        return value, m.group(2).lower().rstrip("s"), m.group(2)
    return None


def _show(d):
    y, mo, dd = d
    year = "%d BC" % -y if y < 0 else str(y)
    return "%s %d, %s" % (list(MONTHS)[mo - 1].capitalize(), dd, year) if mo else year


def _num(v):
    return ("%d" % v) if v == int(v) else ("%g" % v)


def worked(notes, languages=ENGLISH_ONLY):
    """Facts worked out exactly from the parts' answers: the time between dates, which came first, the difference
    between amounts with the same unit. [notes] are (question, answer) pairs; [] when nothing can be worked out.
    [languages] are the item question's (`language.of`)."""
    out = []
    parts = [(q, first_sentence(a, languages)) for q, a in notes]
    for i in range(len(parts)):
        for j in range(i + 1, len(parts)):
            (qa, a), (qb, b) = parts[i], parts[j]
            da, db = when(a, languages, qa), when(b, languages, qb)
            if da and db and (da[0] != db[0] or (da[1] and db[1])):
                if da == db:
                    out.append('"%s" and "%s" give the same date, %s.' % (qa, qb, _show(da)))
                    continue
                first, last, fq, lq = (da, db, qa, qb) if da < db else (db, da, qb, qa)
                if first[1] and last[1]:
                    d1 = datetime.date(max(first[0], 1), first[1], first[2]) if first[0] > 0 else None
                    d2 = datetime.date(max(last[0], 1), last[1], last[2]) if last[0] > 0 else None
                    if d1 and d2:
                        months = (d2.year - d1.year) * 12 + d2.month - d1.month - (d2.day < d1.day)
                        span = "%d years and %d months" % divmod(months, 12) if months % 12 else "%d years" % (months // 12)
                    else:
                        span = "%d years" % (last[0] - first[0] - (first[0] < 0 < last[0]))
                else:
                    span = "%d years" % (last[0] - first[0] - (first[0] < 0 < last[0]))
                out.append('%s ("%s") is earlier than %s ("%s"), by %s.' % (_show(first), fq, _show(last), lq, span))
                continue
            na, nb = amount(a, languages), amount(b, languages)
            if na and nb and na[1] == nb[1] and na[0] != nb[0]:
                big, small, bq, sq = (na, nb, qa, qb) if na[0] > nb[0] else (nb, na, qb, qa)
                out.append('%s %s ("%s") is more than %s %s ("%s"), by %s %s.' % (
                    _num(big[0]), big[2], bq, _num(small[0]), small[2], sq, _num(round(big[0] - small[0], 6)), big[2]))
    return out


def strict_ok(rec, qs, state):
    """The split gate: every part found passages, the parts' best passages come from two or more articles, and the
    whole question's own passages didn't already reach all of those articles."""
    tops = []
    for n in range(1, len(qs) + 1):
        s = state.get("%s#%d" % (rec["id"], n)) or {}
        if not s.get("passages") or not s.get("answer"):
            return False
        tops.append(s["passages"][0]["title"])
    if len(set(tops)) < 2:
        return False
    had = {p.get("title") for p in rec.get("chosen") or []}
    return not set(tops) <= had


def answer_prompt(question, passages, notes=None, facts=None):
    """AnswerPrompt.build without medical, prior or quote; [notes] are the parts' answers, for the combined prompt."""
    lines = [RULES, "Passages:"]
    for i, p in enumerate(passages):
        if i:
            lines.append("")
        head = "[%d] %s" % (i + 1, p.get("title", ""))
        if p.get("heading"):
            head += " · " + p["heading"]
        lines.append(_cut(head, MAX_HEADER))
        lines.append(_cut(p.get("text", ""), TEXT_CHARS))
    lines.append("")
    if notes:
        lines.append("Answers to the parts of the question, from the same passages:")
        lines += ["- %s %s" % (q, a) for q, a in notes]
        lines.append("")
    if facts:
        lines.append("Worked out exactly from those answers (use these figures as given, citing the passages they come from):")
        lines += ["- " + f for f in facts]
        lines.append("")
    lines.append("Question: %s" % question)
    return "\n".join(lines) + "\nAnswer:"


def renumber(answer, mapping):
    """[answer]'s citations [n] as [mapping[n]], for a part's answer moved into the combined prompt."""
    def one(m):
        ns = [int(x) for x in re.findall(r"\d+", m.group(2))]
        kept = [str(mapping[n]) for n in ns if n in mapping]
        return m.group(1) + "[" + ", ".join(kept) + "]" if kept else ""
    return re.sub(r"(\s*)(\[\d+(?:\s*,\s*\d+)*\])", one, answer)


# ---- state ----

def read(path):
    return answerers.read_jsonl(path) if Path(path).exists() else []


def state_path(new):
    return RUNS / new / "multistep.json"


def load_state(new):
    p = state_path(new)
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else {}


def save_state(new, state):
    state_path(new).write_text(json.dumps(state, indent=1, ensure_ascii=False), encoding="utf-8")


def replies(new, stage):
    return {r["id"]: r for r in read(RUNS / new / ("%s-replies.jsonl" % stage))}


# ---- search, on the Mac as the phone would ----

def dense_search(texts, k=24):
    """Top [k] dense hits per text over the released vector files, scanned as hits.py scans the phone's."""
    sys.path.insert(0, str(VECTORS))
    import gvec
    import hits
    import numpy as np
    from sentence_transformers import SentenceTransformer
    released = {json.loads(p.read_text())["fileName"] for p in RELEASES.glob("*.json")}
    srcs = [s for s in hits.sources(WORK) if s[0].name in released]
    model = SentenceTransformer("google/embeddinggemma-2", device="cpu")
    vecs = np.array(model.encode_query(list(texts), batch_size=32), np.float32)
    files = []
    for f, _, _, _ in srcs:
        _, q, scales, _, _ = gvec.read(f)
        files.append((q, scales))
    best = hits.scan(gvec.truncate(vecs, 256), files, k)
    wanted = {}
    for b in best:
        for _, fi, row in b:
            wanted.setdefault(fi, set()).add(row)
    chunks = {}
    for fi, rows in wanted.items():
        with srcs[fi][1].open(encoding="utf-8") as f:
            for n, line in enumerate(f):
                if n in rows:
                    chunks[(fi, n)] = json.loads(line)
    out = []
    for b in best:
        ps = []
        for score, fi, row in b:
            c, (_, _, kind, label) = chunks[(fi, row)], srcs[fi]
            ps.append({"kind": kind, "source": label, "title": c["title"], "location": c["key"],
                       "heading": c.get("heading") or "", "text": c["text"], "cosine": round(score, 4)})
        out.append(ps)
    return out


def pick(hits):
    """The passages for one part: dense hits from the floor up, unique by text, at most 2 per document; [] under the gate."""
    if not hits or max(p["cosine"] for p in hits) < GATE:
        return []
    seen, per_doc, out = set(), {}, []
    for p in sorted(hits, key=lambda p: -p["cosine"]):
        k, d = merge.text_key(p), (p["source"], p["location"])
        if p["cosine"] < 0.74 or k in seen or per_doc.get(d, 0) >= 2:
            continue
        seen.add(k)
        per_doc[d] = per_doc.get(d, 0) + 1
        out.append(p)
        if len(out) == SUB_PASSAGES:
            break
    return out


# ---- commands ----

def base_items(base):
    ds = json.loads((RUNS / base / "dataset.json").read_text(encoding="utf-8"))
    items = ds["items"] if isinstance(ds, dict) else ds
    return {i["id"]: i for i in items}


def cmd_split(a):
    (RUNS / a.new).mkdir(parents=True, exist_ok=True)
    rows = [{"id": id, "prompt": split_prompt(it["question"], it.get("priorQuestion"), a.prompt), "passages": 0}
            for id, it in base_items(a.base).items()]
    answerers.write_jsonl(RUNS / a.new / "split-prompts.jsonl", rows)
    print("%d split prompts -> runs/%s/split-prompts.jsonl" % (len(rows), a.new))
    return 0


def parts_of(new):
    """{id: [sub-questions]} for every question the model split into two or more."""
    out = {}
    for id, r in replies(new, "split").items():
        qs = parse_split(r.get("reply"))
        # A list of statements, or a part twice, is not a split.
        if len(qs) >= 2 and len(set(qs)) == len(qs) and all(q.endswith("?") for q in qs):
            out[id] = qs
    return out


def cmd_hop(a):
    state = load_state(a.new)
    parts = parts_of(a.new)
    # Read back the previous hop's replies.
    for stage in ("hop%d" % n for n in range(2, a.round)):
        for key, r in replies(a.new, stage).items():
            s = state[key]
            reply = r.get("reply") or ""
            s["answer"] = None if r.get("outcome") == "declined" or DECLINE in reply else reply
    todo, items = [], base_items(a.base)
    for id, qs in parts.items():
        languages = language.of(items[id]["question"])
        answers = {}
        for n, q in enumerate(qs, 1):
            s = state.get("%s#%d" % (id, n))
            if s is not None and "answer" in s:
                answers[n] = s["answer"]
        for n, q in enumerate(qs, 1):
            key = "%s#%d" % (id, n)
            if key in state:
                continue
            filled = fill(q, answers, languages)
            if filled is None and refs(q) and all(m in answers for m in refs(q)):
                state[key] = {"question": q, "filled": None, "passages": [], "answer": None}  # an earlier part was declined
            elif filled is not None:
                todo.append((key, filled))
    hits = dense_search([q for _, q in todo]) if todo else []
    rows = []
    for (key, q), h in zip(todo, hits):
        ps = pick(h)
        state[key] = {"question": q, "filled": q, "passages": ps}
        if ps:
            rows.append({"id": key, "prompt": answer_prompt(q, ps), "passages": len(ps)})
        else:
            state[key]["answer"] = None
    save_state(a.new, state)
    answerers.write_jsonl(RUNS / a.new / ("hop%d-prompts.jsonl" % a.round), rows)
    print("%d parts searched, %d to answer -> runs/%s/hop%d-prompts.jsonl" % (len(todo), len(rows), a.new, a.round))
    return 0


def combined(item, qs, state):
    """The combined prompt's passages and notes: every answered part's passages (renumbered) and its answer."""
    passages, notes, index = [], [], {}
    for n in range(1, len(qs) + 1):
        s = state.get("%s#%d" % (item["id"], n)) or {}
        if not s.get("answer"):
            continue
        mapping = {}
        for i, p in enumerate(s["passages"], 1):
            k = merge.text_key(p)
            if k not in index and len(passages) < COMBINED_PASSAGES:
                passages.append(p)
                index[k] = len(passages)
            if k in index:
                mapping[i] = index[k]
        notes.append((s["filled"], renumber(s["answer"], mapping)))
    return passages, notes


def cmd_combine(a):
    state = load_state(a.new)
    for stage in ("hop2", "hop3", "hop4"):
        for key, r in replies(a.new, stage).items():
            reply = r.get("reply") or ""
            state[key]["answer"] = None if r.get("outcome") == "declined" or DECLINE in reply else reply
    save_state(a.new, state)
    items, rows = base_items(a.base), []
    for id, qs in used_parts(a).items():
        passages, notes = combined(items[id], qs, state)
        if passages:
            facts = worked(notes, language.of(items[id]["question"])) if a.strict else None
            rows.append({"id": id, "prompt": answer_prompt(items[id]["question"], passages, notes, facts),
                         "passages": len(passages)})
    answerers.write_jsonl(RUNS / a.new / "combine-prompts.jsonl", rows)
    print("%d combined prompts -> runs/%s/combine-prompts.jsonl" % (len(rows), a.new))
    return 0


def used_parts(a):
    """parts_of(NEW), and with --strict only the splits the gate lets through (the rest keep BASE's record)."""
    parts = parts_of(a.new)
    if not getattr(a, "strict", False):
        return parts
    state = load_state(a.new)
    base = {r["id"]: r for r in answerers.read_jsonl(RUNS / a.base / "results.jsonl")}
    return {id: qs for id, qs in parts.items() if strict_ok(base[id], qs, state)}


def cmd_build(a):
    state, items = load_state(a.new), base_items(a.base)
    parts, final = used_parts(a), replies(a.new, "combine")
    out = []
    for rec in answerers.read_jsonl(RUNS / a.base / "results.jsonl"):
        rec = {k: v for k, v in rec.items() if k not in ("deeper", "guesses", "prompts")}
        rec["runId"] = a.new
        if rec["id"] in parts:
            passages, _ = combined(items[rec["id"]], parts[rec["id"]], state)
            r = final.get(rec["id"])
            rec.update(outcome=r["outcome"] if r else "declined", reply=(r or {}).get("reply", ""),
                       cited="".join("[%d]" % n for n in (r or {}).get("cited", [])), chosen=passages,
                       multistep={"parts": parts[rec["id"]]})
        out.append(rec)
    shutil.copy(RUNS / a.base / "dataset.json", RUNS / a.new / "dataset.json")
    answerers.write_jsonl(RUNS / a.new / "results.jsonl", out)
    print("%d records (%d answered as parts) -> runs/%s/results.jsonl" % (len(out), len(parts), a.new))
    return 0


def cmd_replay(a, runner=None, sleep=None):
    """Replays NEW/STAGE-prompts.jsonl on the phone as answerers.py run does, into NEW/STAGE-replies.jsonl."""
    import adb
    runner = runner or adb.real_runner
    sleep = sleep or adb.time.sleep
    local = RUNS / a.new / ("%s-prompts.jsonl" % a.stage)
    run_id = "%s-%s" % (a.new, a.stage)
    adb.check_front(runner)
    runner(["shell", "rm", "-f", "%s/%s/done" % (adb.REMOTE, run_id), "%s/%s/failed" % (adb.REMOTE, run_id)])
    rc, o = runner(["push", str(local), adb.REMOTE + "/r0-prompts.jsonl"])
    if rc:
        raise SystemExit("adb push failed: " + o)
    rc, o = runner(["shell", "am", "start", "-S", "-n", adb.PKG + "/.eval.EvalActivity", "--es", "runId", run_id,
                    "--es", "replay", "r0-prompts.jsonl", "--es", "model", a.model, "--es", "backend", a.backend])
    if rc:
        raise SystemExit("could not start the eval activity: " + o)
    adb.wait_done(runner, run_id, sleep)
    rc, o = runner(["pull", "%s/%s/replay.jsonl" % (adb.REMOTE, run_id), str(RUNS / a.new / ("%s-replies.jsonl" % a.stage))])
    if rc:
        raise SystemExit("adb pull failed: " + o)
    print("%d replies -> runs/%s/%s-replies.jsonl" % (len(read(RUNS / a.new / ("%s-replies.jsonl" % a.stage))), a.new, a.stage))
    return 0


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    s = p.add_subparsers(dest="cmd", required=True)
    for name, f in (("split", cmd_split), ("combine", cmd_combine), ("build", cmd_build)):
        c = s.add_parser(name); c.add_argument("base"); c.add_argument("new"); c.set_defaults(f=f)
        if name == "split":
            c.add_argument("--prompt", default="v2", choices=sorted(SPLITS))
        else:
            c.add_argument("--strict", action="store_true", help="the split gate and the arithmetic in code")
    c = s.add_parser("hop"); c.add_argument("base"); c.add_argument("new"); c.add_argument("--round", type=int, required=True)
    c.set_defaults(f=cmd_hop)
    c = s.add_parser("replay"); c.add_argument("new"); c.add_argument("stage"); c.add_argument("--model", required=True)
    c.add_argument("--backend", default="gpu"); c.set_defaults(f=cmd_replay)
    a = p.parse_args(argv)
    return a.f(a)


if __name__ == "__main__":
    sys.exit(main())
