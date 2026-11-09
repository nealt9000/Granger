"""Plays that read as plays on a phone: who speaks, what they say, what happens, and where we are.

Gutenberg's plays carry their structure as typography, and every producer used its own (formatting audit
2026-11-09, §2): a name on its own line before the speech (`<p>HAMLET.<br>…`), a name in its own block
(`<p>TESMAN.</p><p>…`, a `<h5>`, a `p.char`), a run-in name (`MANDERS. I have…`, `Soc. Come now…`), a name
in a tag (`<i>Nora</i>. …`, `<span class="smcap">Gina</span> (…): …`), or any of these inside a `<pre>`.
None of it survived as structure, so names read like the speech around them, directions like speech, and an
act was a line of text with no place in the contents.

`structure` runs once per work on the cleaned body, before the chapter split, so promoted act and scene
headings drive the split and the contents. It marks:

- a speech: the block gets `gd-speech`; a name set on its own line or block becomes `span.gd-head` (a line of
  its own, holding `span.gd-who` and any direction that came with it) and the block gets `gd-above`; a run-in
  name becomes `span.gd-who` at the block's start and the block gets `gd-run`. Names in capitals are title-cased
  so small capitals render them evenly at the text's size.
- a stage direction: a block becomes `gd-sd` (italic, indented; `gd-own` when its italics are the source's own,
  so names it sets in roman stay roman), a bracketed direction inside a speech `span.gd-sdi`.
- acts and scenes: a block or `<pre>` line that is only "ACT II" or "SCENE I. A room…" becomes a heading
  (`h2.gd-act`, `h3.gd-scene`, or one level under the play's own act headings when it has them).
- a `<pre>` play (Marlowe, Jonson, Webster, Aristophanes, Gilbert): split at its blank lines into speeches,
  directions and headings; verse speeches become `.gv` lines here (with no gloss rule: `CARIOLA.   Never, my
  lord.` is a name and a speech, not a line and its gloss), prose speeches one paragraph each.

The cast is found first, from the labels that repeat: a name in a strong position (alone on its line, with
its full stop) counts once, a weaker one (no full stop, a run-in title-case word, a name in a tag) needs
three sightings. That keeps "O, …" and "I …" out. A work with fewer than `MIN_SPEECHES` speeches found is
left alone (the Sonnets, filed with the plays).

Per-work hints (manifest `drama`): `false` turns it off (Medea already reads well); `{"labels": "titlecase"}`
accepts run-in names in title case (`Soc.`, `Petra.`, `Florian:`); `{"prose": true}` reflows a `<pre>` play's
speeches as prose (Clouds is Hickie's prose, hard-wrapped); `{"labelsWithoutStop": true}` accepts a run-in
name in capitals with no stop after it (Blue Bird's `THE OAK Your father…`).
"""
import re
from collections import Counter
from dataclasses import dataclass, field

from bs4 import BeautifulSoup, NavigableString, PageElement, Tag

import verse

CSS = """
:root .gd-speech { margin: .9em 0 0; text-indent: 0; }
:root .gd-speech + .gd-speech, :root .gd-sd + .gd-speech { margin-top: .6em; }
:root .gd-who { font-weight: 700; font-style: normal; font-variant-caps: small-caps; letter-spacing: .03em; }
:root .gd-above > .gd-who:first-child { display: block; margin-bottom: .1em; }
:root .gd-head { display: block; text-indent: 0; padding-left: 0; margin: 0 0 .1em; }
:root .gd-run > .gd-who { margin-right: .3em; }
:root .gd-sd { font-style: italic; margin: .6em 0 .6em 1.5em; text-indent: 0; }
:root .gd-sd.gd-own { font-style: normal; }
:root .gd-sdi { font-style: italic; }
:root .gd-speech .gv-pre { margin: 0; }
:root .gd-act, :root .gd-scene { text-align: start; }
""".strip()

MIN_SPEECHES = 20
_BLOCK_NAMES = {"p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "td", "li", "dd", "blockquote", "center"}
_INNER_BLOCKS = ["p", "div", "table", "ul", "ol", "dl", "blockquote", "pre", "h1", "h2", "h3", "h4", "h5", "h6",
                 "hr", "center", "section"]

# A name in capitals: words of capitals ("FIRST CLOWN", "MRS. ALVING", "KING OF FRANCE", "3 MEN").
_CAPS_WORD = r"[A-Z][A-ZÀ-ÖØ-Þ'’\-]*(?![a-zß-ÿ])"
CAPS_NAME = rf"(?:\d\s)?{_CAPS_WORD}(?:[.,]{{0,2}}[ \t]+(?:{_CAPS_WORD}|&|and)){{0,5}}"  # "RUD., SCH. and COL."
# A name in title case, accepted with the `titlecase` hint: "Soc.", "Strep.", "Mrs. Stockmann", "Cyr. & Flor".
TITLE_NAME = r"(?:\d\s)?[A-Z][a-zà-ÿ'’\-]{0,14}(?:\.?[ \t]+(?:&[ \t]+)?[A-Z][a-zà-ÿ'’\-]{0,14}){0,2}"
_SD = r"(?P<sd>\s*[\[(][^\[\]()]{1,120}[\])])?"
# Words that open a block in capitals or as a run-in label but never name a speaker.
NOT_NAMES = {"I", "A", "O", "ACT", "SCENE", "ENTER", "EXIT", "EXEUNT", "CURTAIN", "END", "THE END", "FINIS",
             "FLOURISH", "ALARUM", "ALARUMS", "SENNET", "MANET", "MANENT", "SONG", "DUET", "TRIO", "QUARTET",
             "RECITATIVE", "AIR", "FINALE", "NOTE", "NOTES", "CONTENTS", "PREFACE", "INTRODUCTION", "THE",
             "MR", "MRS", "DR", "ST", "SIR", "YES", "NO", "OH", "AH", "AY", "NAY", "WELL", "WHY", "WHAT", "THEN",
             "SO", "NOW", "COME", "GOOD", "VERY", "INDEED", "QUITE", "CERTAINLY", "NOTHING", "NEVER", "ALAS",
             "HUSH", "LOOK", "SEE", "HM", "HA", "HO", "OK", "AND", "BUT", "OR", "IF", "AS", "IN", "ON", "AT",
             "TO", "BY", "OF", "FOR", "IT", "HE", "SHE", "WE", "YOU", "THEY", "THIS", "THAT", "HOW", "WHO",
             "WHERE", "WHEN", "ALL'S", "PERSONS", "CHARACTERS", "DRAMATIS PERSONAE", "CAST", "COSTUMES",
             "TRANSLATOR'S NOTE", "TRANSLATOR’S NOTE", "ARGUMENT", "PROLOGUE", "EPILOGUE", "INDUCTION",
             "TABLEAU", "PART", "BOOK", "CHAPTER", "VOLUME", "FIN", "ATTO", "SCENA", "ACTUS", "THE CURTAIN",
             "CURTAIN FALLS", "QUICK CURTAIN", "SLOW CURTAIN", "DANCE", "MARCH", "EXEUNT OMNES"}
_ROMAN = re.compile(r"^[IVXLC]+$")

# "FIRST ACT", "Act the Second": turned around to the usual order before matching
_ORDINAL_FIRST = re.compile(r"^\[?(?P<n>FIRST|SECOND|THIRD|FOURTH|FIFTH|LAST|First|Second|Third|Fourth|Fifth|Last)\s+"
                            r"(?P<kind>ACT|Act|SCENE|Scene)\b|^\[?(?P<kind2>ACT|Act)\s+(?:THE|the)\s+(?P<n2>\w+)\b")
ACT_SCENE = re.compile(
    r"^\[?(?P<kind>ACT|Act|SCENE|Scene|ACTUS|Actus|ACTVS|Actvs|SCENA|Scena|SCÈNE|Scène)\.?\s+"
    r"(?P<n>[IVXLC]+\b|\d+\b|(?:THE\s+)?(?:FIRST|SECOND|THIRD|FOURTH|FIFTH|SIXTH|LAST|ONE|TWO|THREE|FOUR|FIVE)\b"
    r"|(?:the\s+)?(?:First|Second|Third|Fourth|Fifth|Sixth|Last|One|Two|Three|Four|Five)\b"
    r"|(?:PRIM|SECUND|TERTI|QUART|QUINT)[UV]S\b|(?:Prim|Secund|Terti|Quart|Quint)[uv]s\b)"
    r"(?P<rest>[^\n]{0,100})$")
# A one-word scene line ("SCENE.—Elsinore", "SCENE: A room") stays text: it's the play's setting, not a scene.

DIRECTION_CLASSES = re.compile(r"^(scenedesc|stage|stagedir|stage-dir|stagedirection|direct\d?|direction|regie|"
                               r"dir-[a-z]+|sd|didascalia)$", re.I)
SPEAKER_CLASSES = re.compile(r"^(char|character|charname|speaker|persona|name|spkr)$", re.I)
_DIRECTION_WORD = re.compile(r"^(Enter|Exit|Exeunt|Re-enter|Reenter|Re-Enter|Manet|Manent|Flourish|Alarum|Alarums|"
                             r"Sennet|Curtain|Exit\.|Exeunt\.)\b")
# a capital after the bracket, or two words or more (`[aside to MERYLL]`); a lone word in brackets in lower case
# is an editor's (`[flight,]`)
_INLINE_SD = re.compile(r"[\[(](?:(?=[A-Z])[^\[\]()\n]{2,200}|(?=[a-z]+\s+\S)[^\[\]()\n]{4,200})[\])]")
_DIGITS = re.compile(r"^[\[(]\s*[\divxlc*†‡§]+\s*[\])]$", re.I)
_PERSONAE = re.compile(r"dramatis|person|characters|\bcast\b", re.I)


@dataclass
class Hints:
    on: bool = True
    titlecase: bool = False
    prose: bool = False
    without_stop: bool = False

    @classmethod
    def parse(cls, value) -> "Hints":
        if value is False:
            return cls(on=False)
        if isinstance(value, dict):
            return cls(titlecase=value.get("labels") == "titlecase", prose=bool(value.get("prose")),
                       without_stop=bool(value.get("labelsWithoutStop")))
        return cls()


@dataclass
class Report:
    speeches: int = 0
    directions: int = 0
    headings: int = 0
    pre_plays: int = 0
    cast: list[str] = field(default_factory=list)
    skipped: str = ""


# --- labels -----------------------------------------------------------------------------------------------------

def _patterns(hints: Hints) -> tuple[re.Pattern, re.Pattern, re.Pattern | None]:
    """(a name alone on its line, a run-in name ending in a stop, a run-in name with no stop or None)."""
    name = rf"(?:{CAPS_NAME}|{TITLE_NAME})" if hints.titlecase else CAPS_NAME
    alone = re.compile(rf"^(?P<who>{name})(?P<stop>[.:,])?{_SD}\s*(?P<stop2>[.:])?\s*$")
    run = re.compile(rf"^(?P<who>{name}){_SD}?\s*(?P<stop>\.:|[.:])(?=[\s\[(“\"‘'—–-]|$)")
    bare = re.compile(rf"^(?P<who>{CAPS_NAME})\s+(?=[A-Z][a-z]|[‘“\"'(\[])") if hints.without_stop else None
    return alone, run, bare


def key(name: str) -> str:
    """A name's identity across its forms: "MRS. ALVING." and "Mrs. Alving" are one."""
    return " ".join(re.sub(r"[.:,]", " ", name).split()).upper()


def _plausible(name: str) -> bool:
    k = key(name)
    if not k or k in NOT_NAMES or _ROMAN.match(k.replace(" ", "")) or len(k) > 40 or not re.search(r"[A-Z]{2}", k.upper()):
        return False
    return not ACT_SCENE.match(name.strip())


def display(name: str) -> str:
    """A name in capitals in title case ("FIRST CLOWN." -> "First Clown."), others as they are."""
    if any(c.islower() for c in name) and not name.islower():
        return name
    small = {"of", "the", "and", "de", "du", "la", "le", "von", "van", "in", "to"}

    def word(m: re.Match) -> str:
        token = m.group(0)
        if m.start() > 0 and token.lower() in small:
            return token.lower()
        if _ROMAN.match(token) and len(token) <= 4 and m.start() > 0:
            return token
        return token[0].upper() + token[1:].lower()

    return re.sub(r"[^\W\d_][^\s.\-]*", word, name)


# --- the work's blocks ------------------------------------------------------------------------------------------

def _leaf_blocks(body: Tag) -> list[Tag]:
    """Text blocks holding no other blocks, in reading order; `<pre>`s separately."""
    return [b for b in body.find_all(_BLOCK_NAMES) if b.find(_INNER_BLOCKS) is None]


def _meaningful(nodes) -> list[PageElement]:
    return [n for n in nodes if not (isinstance(n, NavigableString) and not n.strip())
            and not (isinstance(n, Tag) and n.name in ("a", "span") and not n.get_text().strip()
                     and n.find(True) is None and n.name != "br")]


def _first_segment(block: Tag) -> tuple[list[PageElement], Tag | None]:
    """The block's children before its first own `<br>`, and that `<br>`."""
    seg = []
    for child in block.children:
        if isinstance(child, Tag) and child.name == "br":
            if _text(seg).strip():
                return seg, child
            seg.append(child)  # a leading br: the label hasn't started
            continue
        seg.append(child)
    return seg, None


def _first_segment_after(br: Tag) -> tuple[str, Tag | None]:
    """The text of the line after `br`, up to the next `<br>` (a cast list's second name)."""
    nodes = []
    for node in br.next_siblings:
        if isinstance(node, Tag) and node.name == "br":
            if _text(nodes).strip():
                return _flat(_text(nodes)), node
            continue
        nodes.append(node)
    return _flat(_text(nodes)), None


def _text(nodes) -> str:
    return "".join(n.get_text() if isinstance(n, Tag) else str(n) for n in nodes)


def _flat(text: str) -> str:
    return " ".join(text.split())


@dataclass
class _Found:
    block: Tag
    kind: str  # "line" (name on the block's first line), "block" (the whole block), "run", "tag", "bare"
    name: str
    strong: bool
    sd: str = ""


def _find(block: Tag, pats) -> _Found | None:
    alone, run, bare = pats
    classes = block.get("class") or []
    text = _flat(block.get_text())
    if not text or len(text) > 6000:
        return None
    if any(SPEAKER_CLASSES.match(c) for c in classes) and len(text) <= 60:
        m = alone.match(text) or re.match(r"^(?P<who>[^\[(]{1,40}?)(?P<stop>[.:])?(?P<sd>\s*[\[(][^\])]*[\])])?\s*[.:]?$",
                                          text)
        if m and m.group("who").strip():
            return _Found(block, "block", m.group("who"), True, m.group("sd") or "")
    if block.name in ("h1", "h2", "h3", "h4"):
        return None
    # the whole block is a name; a name set as a heading (Trojan Women's h5) is only weak evidence, since an
    # introduction's signature looks the same
    m = alone.match(text)
    if m and _plausible(m.group("who")):
        stop = bool(m.group("stop") or m.group("stop2")) and m.group("stop") != ","
        return _Found(block, "block", m.group("who"), stop and block.name not in ("h5", "h6"), m.group("sd") or "")
    seg, br = _first_segment(block)
    if br is not None:
        first = _flat(_text(seg))
        rest = _flat(_text(list(br.next_siblings)))
        m = alone.match(first)
        second, _ = _first_segment_after(br)
        if m and rest and _plausible(m.group("who")) and not alone.match(second):
            stop = bool(m.group("stop") or m.group("stop2"))
            return _Found(block, "line", m.group("who"), stop and m.group("stop") != ",", m.group("sd") or "")
        tag = _meaningful(seg)
        if (len(tag) == 1 and isinstance(tag[0], Tag) and tag[0].name in ("b", "strong", "i", "em", "span", "small")
                and rest):
            m = alone.match(_flat(tag[0].get_text()))
            if m and _plausible(m.group("who")):
                return _Found(block, "line", m.group("who"), True, m.group("sd") or "")
    first = _first_node(block)
    if isinstance(first, NavigableString):
        lead = str(first).lstrip()
        m = run.match(text)  # the block's text: a direction after the name may hold tags (`TRISTAN (<i>with effort</i>).`)
        if (m and _plausible(m.group("who")) and len(text) > len(_flat(m.group(0))) + 1
                and _flat(lead).startswith(_flat(m.group("who")))):
            return _Found(block, "run", m.group("who"), False, m.group("sd") or "")
        if bare is not None and (m := bare.match(lead)) and _plausible(m.group("who")):
            return _Found(block, "bare", m.group("who"), False)
    elif isinstance(first, Tag) and first.name in ("i", "em", "b", "strong", "span", "small"):
        name = _flat(first.get_text())
        after = first.next_sibling
        after_text = str(after) if isinstance(after, NavigableString) else ""
        if (name and len(name) <= 40 and not re.search(r"[!?;,]", name.rstrip(".:"))
                and (name.endswith((".", ":")) or re.match(r"^\s*[.:(\[]", after_text))
                and re.match(r"^[A-Za-zÀ-ÿ][\w'’ .&-]*$", name) and _plausible(name.upper())
                and len(text) > len(name) + 2 and len(name.split()) <= 4):
            return _Found(block, "tag", name.rstrip(".:"), False)
    return None


def in_cast(name: str, cast: set[str]) -> bool:
    """A cast name, or several sung together ("RUD., MAR. and COL.", each one a cast name)."""
    k = key(name)
    if k in cast:
        return True
    if " AND " not in f" {k} " and "&" not in k:
        return False
    parts = [p for p in re.split(r"\s+", k.replace("&", " ")) if p != "AND"]
    return len(parts) > 1 and all(p in cast for p in parts)


def _first_node(block: Tag) -> PageElement | None:
    """The block's first words or tag, looking inside a line wrapper (`div.stanza > span.i0 > span.smcap`)."""
    first = next(iter(_meaningful(block.children)), None)
    while (isinstance(first, Tag) and first.name == "span" and not first.get("id")
           and all(re.match(r"^i\d+$", c) for c in first.get("class") or ["x"])):
        first = next(iter(_meaningful(first.children)), None)
    return first


def _cast(found: list[_Found]) -> set[str]:
    """Names seen in a strong position at all, or three times in any. Once a work's speeches are known to take
    one form (three in five of its names, as `<i>Nora</i>.` in A Doll's House), a name in that form is strong
    too: the Porter speaks twice."""
    counts = Counter(key(f.name) for f in found)
    strong = {key(f.name) for f in found if f.strong}
    confirmed = [f for f in found if counts[key(f.name)] >= 3]
    kinds = Counter(f.kind for f in confirmed)
    for kind, n in kinds.items():
        if kind in ("tag", "run") and n >= 8 and n >= 0.6 * len(confirmed):
            strong |= {key(f.name) for f in found if f.kind == kind}
    return {k for k, n in counts.items() if k in strong or n >= 3}


# --- the rewrite -------------------------------------------------------------------------------------------------

def _soup(tag: Tag) -> BeautifulSoup:
    node = tag
    while node.parent is not None:
        node = node.parent
    return node if isinstance(node, BeautifulSoup) else BeautifulSoup("", "lxml")


_HOLDER = BeautifulSoup("", "lxml")


def _new(name: str, cls: str, text: str | None = None) -> Tag:
    tag = _HOLDER.new_tag(name, attrs={"class": cls})
    if text is not None:
        tag.string = text
    return tag


def _add_class(tag: Tag, *names: str) -> None:
    classes = [c for c in (tag.get("class") or []) if c not in names]
    tag["class"] = classes + list(names)


def _head(name: str, sd: str, stop: str, keep: list[Tag]) -> Tag:
    """`span.gd-head`: the speaker's name, any direction set with it, and ids or note links it carried."""
    head = _new("span", "gd-head")
    for tag in keep:
        head.append(tag)
    head.append(_new("span", "gd-who", display(name.strip()) + stop))
    if sd:
        head.append(" ")
        head.append(_new("span", "gd-sdi", sd.strip()))
    return head


def _kept(nodes) -> list[Tag]:
    """Ids and note links inside a label being rebuilt, so links to them still land."""
    out = []
    for node in nodes:
        if not isinstance(node, Tag):
            continue
        for tag in [node, *node.find_all(True)]:
            if tag.name == "a" and tag.get("href"):
                out.append(tag.extract())
            elif tag.get("id") and tag.name != "br":
                out.append(_HOLDER.new_tag("span", attrs={"id": tag["id"]}))
    return out


def _mark_line(f: _Found) -> None:
    """Name on the block's first line: that line becomes the head, the `<br>` after it goes."""
    seg, br = _first_segment(f.block)
    text = _flat(_text(seg))
    keep = _kept(seg)
    for node in seg:
        node.extract()
    br.extract()
    f.block.insert(0, _head(f.name, f.sd, text[-1] if text.endswith((".", ":")) else "", keep))
    _add_class(f.block, "gd-speech", "gd-above")


def _mark_run(f: _Found, pats) -> None:
    first = _first_node(f.block)
    if f.kind == "tag":
        after = first.next_sibling
        stop = ""
        if isinstance(after, NavigableString) and (m := re.match(r"^\s*([.:])", str(after))):
            stop = m.group(1)
            after.replace_with(NavigableString(str(after)[m.end():]))
        elif first.get_text().rstrip().endswith((".", ":")):
            stop = first.get_text().rstrip()[-1]
        who = _new("span", "gd-who", display(f.name.strip()) + stop)
        for tag in _kept([first]):
            who.insert(0, tag)
        first.replace_with(who)
    else:
        lead = str(first)
        offset = len(lead) - len(lead.lstrip())
        _, run, bare = pats
        m = (run if f.kind == "run" else bare).match(lead[offset:])
        if m is None:  # the direction or stop after the name is in later nodes: cut the name off the string
            name = re.match(r"\s*".join(map(re.escape, f.name.split())), lead[offset:])
            if name is None:
                return
            stop = re.match(r"[.:]", lead[offset + name.end():])
            end = name.end() + (1 if stop else 0)
            first.replace_with(_new("span", "gd-who", display(name.group(0)) + (stop.group(0) if stop else "")),
                               NavigableString(lead[offset + end:]))
            _add_class(f.block, "gd-speech", "gd-run")
            return
        who_text = display(m.group("who")) + (m.group("stop") if f.kind == "run" else "")
        rest = lead[offset + m.end():]
        sd = m.groupdict().get("sd")
        nodes: list[PageElement] = [_new("span", "gd-who", who_text)]
        if sd:
            # "VOLP [ASIDE.]:" — the direction sits between name and stop
            nodes[0].string = display(m.group("who"))
            nodes += [" ", _new("span", "gd-sdi", sd.strip()), m.group("stop") if f.kind == "run" else ""]
        nodes.append(NavigableString(" " + rest.lstrip() if rest.strip() else rest))
        first.replace_with(*[n if isinstance(n, PageElement) else NavigableString(n) for n in nodes if n != ""])
    _add_class(f.block, "gd-speech", "gd-run")


def _next_block(block: Tag) -> Tag | None:
    node = block.next_sibling
    while node is not None:
        if isinstance(node, Tag):
            if node.get_text().strip() or node.find(True) is not None and node.name != "a":
                return node
            if node.name not in ("a", "span", "br"):
                return node
        elif str(node).strip():
            return None
        node = node.next_sibling
    return None


def _mark_block(f: _Found, cast: set[str]) -> bool:
    """A block that is only a name joins the speech after it, or stays as a head line of its own."""
    text = _flat(f.block.get_text())
    stop = "." if text.endswith(".") else ":" if text.endswith(":") else ""
    keep = _kept(list(f.block.children))
    head = _head(f.name, f.sd, stop, keep)
    after = _next_block(f.block)
    if (after is not None and after.name in ("p", "div") and after.find(_INNER_BLOCKS) is None
            and not _VERSE_LINE_CLASSES & set(after.get("class") or [])  # one element a line: the name stays apart
            and not _is_label_block(after, cast) and not _is_direction_block(after)):
        after.insert(0, head)
        _add_class(after, "gd-speech", "gd-above")
        f.block.decompose()
        return True
    f.block.clear()
    f.block.append(head)
    if f.block.name in ("h1", "h2", "h3", "h4", "h5", "h6"):
        f.block.name = "p"  # a speaker set as a heading (Trojan Women's h5) is no heading
        f.block.attrs = {k: v for k, v in f.block.attrs.items() if k in ("id", "class")}
    f.block["class"] = ["gd-speech", "gd-above", "gd-label"]
    return True


_VERSE_LINE_CLASSES = {"line", "verse", "l", "tei-l", "i0", "i1", "i2", "vl"}


def _is_label_block(block: Tag, cast: set[str]) -> bool:
    text = _flat(block.get_text())
    return bool(text) and len(text) <= 60 and in_cast(re.sub(r"[\[(].*$", "", text), cast)


def _is_direction_block(block: Tag) -> bool:
    text = _flat(block.get_text())
    return bool(text) and (_bracketed(text) or bool(_DIRECTION_WORD.match(text)) and len(text) < 300)


def _bracketed(text: str) -> bool:
    if not text or text[0] not in "[(":
        return False
    close = "]" if text[0] == "[" else ")"
    body = text.rstrip(" .")
    if not body.endswith(close) and not (text[0] == "[" and "]" not in text):
        return False
    depth = 0
    for i, c in enumerate(body):
        depth += c in "[("
        depth -= c in "])"
        if depth == 0 and i < len(body) - 1:
            return False  # "(He goes.) Then speaks" closes early
    return True


def _mostly_italic(block: Tag) -> bool:
    total = len(re.sub(r"\s", "", block.get_text()))
    italic = sum(len(re.sub(r"\s", "", i.get_text())) for i in block.find_all(["i", "em"])
                 if i.find_parent(["i", "em"]) is None)
    return total > 3 and italic >= 0.85 * total


# --- acts and scenes ----------------------------------------------------------------------------------------------

def _normal_order(text: str) -> str:
    """"FIRST ACT" -> "ACT FIRST", "Act the Second" -> "Act Second", so one pattern reads them all."""
    m = _ORDINAL_FIRST.match(text)
    if not m:
        return text
    if m.group("kind"):
        return f"{m.group('kind')} {m.group('n')}{text[m.end():]}"
    return f"{m.group('kind2')} {m.group('n2')}{text[m.end():]}"


def act_scene(text: str) -> str | None:
    """"act" or "scene" when the text is only an act or scene label ("ACT II.", "SCENE I. A room in the castle.")."""
    m = ACT_SCENE.match(_normal_order(_flat(text)))
    if not m:
        return None
    if m.group("kind").lower().startswith("act") and not re.search(r"\bscen[ea]\b", m.group("rest"), re.I):
        return "act"
    return "scene"  # "[Act I. Scene 2.]" marks a scene


def _heading_levels(body: Tag) -> tuple[int, int]:
    """Levels for promoted act and scene labels: under the play's own act headings when it has some, else
    under the deepest heading that repeats (a collection's play titles), else h2 and h3."""
    acts = Counter(int(h.name[1]) for h in body.find_all(["h1", "h2", "h3", "h4", "h5"])
                   if act_scene(h.get_text()) == "act")
    scenes = Counter(int(h.name[1]) for h in body.find_all(["h1", "h2", "h3", "h4", "h5"])
                     if act_scene(h.get_text()) == "scene")
    if acts:
        act = acts.most_common(1)[0][0]
        return act, scenes.most_common(1)[0][0] if scenes else min(act + 1, 6)
    others = Counter(int(h.name[1]) for h in body.find_all(["h1", "h2", "h3", "h4"]) if h.get_text().strip())
    repeated = [level for level, n in others.items() if n >= 3]
    act = min(max(repeated) + 1, 5) if repeated and max(repeated) >= 2 else 2
    return act, min(act + 1, 6)


def _heading(kind: str, text: str, levels: tuple[int, int]) -> Tag:
    level = levels[0] if kind == "act" else levels[1]
    tag = _HOLDER.new_tag(f"h{level}", attrs={"class": f"gd-{kind}"})
    tag.string = quiet_label(text)
    return tag


def quiet_label(text: str) -> str:
    """"SCENE I. A room in the castle." -> "Scene I. A room in the castle."; "ACT FIRST." -> "Act First"."""
    text = _flat(text).strip("[] ")
    m = ACT_SCENE.match(_normal_order(text))
    if not m or _ORDINAL_FIRST.match(text):
        return display(text) if not any(c.islower() for c in text) else text
    kind = m.group("kind")
    words = display(kind) + " " + (m.group("n") if _ROMAN.match(m.group("n")) else display(m.group("n")))
    rest = m.group("rest")
    if rest and not any(c.islower() for c in rest):
        rest = display(rest)
    out = words + rest
    return out[:-1] if out.endswith(".") and not rest.strip(" .") else out


# --- <pre> plays ---------------------------------------------------------------------------------------------------

def _pre_lines(pre: Tag) -> list[list[PageElement]]:
    lines: list[list[PageElement]] = [[]]
    for child in list(pre.children):
        child.extract()
        if isinstance(child, NavigableString):
            for i, part in enumerate(str(child).split("\n")):
                if i:
                    lines.append([])
                if part:
                    lines[-1].append(NavigableString(part))
        else:
            lines[-1].append(child)
    return lines


def _chunks(lines: list[list[PageElement]]) -> list[list[list[PageElement]]]:
    chunks: list[list[list[PageElement]]] = [[]]
    for line in lines:
        if _text(line).strip():
            chunks[-1].append(line)
        elif chunks[-1]:
            chunks.append([])
    return [c for c in chunks if c]


def _split_label(line: list[PageElement], pats, cast: set[str] | None) -> tuple[str, str, str, list[PageElement]] | None:
    """(name, stop, direction, the line's remaining nodes) when the pre line starts with a speaker's name."""
    alone, run, bare = pats
    text = _text(line)
    stripped = text.strip()
    if not stripped:
        return None
    m = alone.match(_flat(stripped))
    if m and _plausible(m.group("who")) and (cast is None or in_cast(m.group("who"), cast)):
        return m.group("who"), (m.group("stop") or m.group("stop2") or "").replace(",", ""), m.group("sd") or "", []
    first = line[0] if line else None
    if not isinstance(first, NavigableString):
        return None
    lead = str(first)
    body = lead.lstrip()
    column = re.compile(rf"^(?P<who>{CAPS_NAME})(?P<stop>)[ \t]{{2,}}(?=\S)")  # "FAIRFAX   Why, sir…"
    for pattern in (run, bare, column):
        if pattern is None:
            continue
        m = pattern.match(body)
        if m and _plausible(m.group("who")) and (cast is None or in_cast(m.group("who"), cast)):
            # the words keep the name's column, so the speech's later lines don't read as indented
            rest = NavigableString(lead[:len(lead) - len(body)] + body[m.end():].lstrip())
            return m.group("who"), m.groupdict().get("stop") or "", m.groupdict().get("sd") or "", [rest, *line[1:]]
    return None


def _starts_lower(text: str) -> bool:
    s = text.lstrip(" \t\xa0'‘\"“(—-")
    return bool(s) and s[0].islower()


def _lead(text: str) -> int:
    return len(text) - len(text.lstrip(" \t\xa0"))


def _join_direction_lines(lines: list[list[PageElement]]) -> list[tuple[str, list[PageElement]]]:
    """A speech's lines as ("line"|"sd", nodes): a direction in brackets runs over lines until it closes; a direction
    set far right after the words ("…smile.       [Aside.") is cut off onto a line of its own."""
    out: list[tuple[str, list[PageElement]]] = []
    i = 0
    while i < len(lines):
        line = lines[i]
        text = _text(line)
        if (m := re.search(r"\S([ \t]{6,})([\[(][A-Z].*|(?:Exit|Exeunt|Dies|Dying)\b.{0,60})$", text)) \
                and all(isinstance(n, NavigableString) for n in line):
            out.append(("line", [NavigableString(text[:m.start(1)])]))
            line = [NavigableString(m.group(2))]
            text = m.group(2)
        stripped = text.strip()
        if _DIRECTION_WORD.match(stripped) and len(stripped) <= 80:
            out.append(("sd", line))
            i += 1
            continue
        if stripped[:1] in "[(" and len(stripped) > 2 and (stripped[1:2].isupper() or stripped[1:2] in "_<"):
            nodes = list(line)
            depth = stripped.count("[") + stripped.count("(") - stripped.count("]") - stripped.count(")")
            j = i
            while depth > 0 and j + 1 < len(lines) and j - i < 6:
                j += 1
                more = _text(lines[j])
                nodes += [NavigableString(" ")] + lines[j]
                depth += more.count("[") + more.count("(") - more.count("]") - more.count(")")
            if depth <= 0 or stripped.startswith("[") and depth == 1 and j == i:
                out.append(("sd", nodes))
                i = j + 1
                continue
        out.append(("line", line))
        i += 1
    return out


def _sd_span(nodes: list[PageElement]) -> Tag:
    span = _new("span", "gd-sdi")
    text = _flat(_text(nodes))
    if all(isinstance(n, NavigableString) for n in nodes):
        span.string = text
    else:
        for n in nodes:
            span.append(n.extract() if isinstance(n, Tag) and n.parent is not None else n)
        _trim(span)
    return span


def _trim(tag: Tag) -> None:
    strings = [s for s in tag.find_all(string=True)]
    if strings:
        strings[0].replace_with(NavigableString(str(strings[0]).lstrip()))
    strings = [s for s in tag.find_all(string=True)]
    if strings:
        strings[-1].replace_with(NavigableString(str(strings[-1]).rstrip()))
    for s in tag.find_all(string=True):
        if "\n" in s:
            s.replace_with(NavigableString(re.sub(r"\s+", " ", str(s))))


def _prose_paragraph(nodes_lines: list[list[PageElement]], cls: str) -> Tag:
    p = _HOLDER.new_tag("p", attrs={"class": cls})
    for k, line in enumerate(nodes_lines):
        if k:
            p.append(" ")
        line = list(line)
        if line and isinstance(line[0], NavigableString):
            line[0] = NavigableString(str(line[0]).lstrip())
        if line and isinstance(line[-1], NavigableString):
            line[-1] = NavigableString(str(line[-1]).rstrip())
        for n in line:
            p.append(n.extract() if isinstance(n, Tag) and n.parent is not None else n)
    return p


class _PrePlay:
    """Turns one `<pre>` of a play into blocks."""

    def __init__(self, pats, cast: set[str] | None, hints: Hints, levels: tuple[int, int]):
        self.pats, self.cast, self.hints, self.levels = pats, cast, hints, levels
        self.report = Report()
        self.width = 0

    def is_play(self, chunks) -> bool:
        """At least three speeches, and three in ten of the pre's paragraphs, or it's a cast list or a song."""
        speeches = 0
        for chunk in chunks:
            label = _split_label(chunk[0], self.pats, self.cast)
            if label is None:
                continue
            rest = label[3] if label[3] and _text(label[3]).strip() else (chunk[1:] and chunk[1])
            if rest and _split_label(rest if label[3] else chunk[1], self.pats, self.cast) is None:
                speeches += 1
            elif len(chunk) == 1:
                speeches += 0.5  # a name alone, its speech after a blank line (Frogs)
        if chunks and act_scene(_flat(_text(chunks[0][0]))):
            return True  # a scene's own pre (Phaedra: "SCENE II" / "HIPPOLYTUS, OENONE, THERAMENES")
        if len(chunks) <= 3:
            return speeches >= 1  # one speech to a pre
        return speeches >= 3 and speeches >= 0.3 * len(chunks)

    def blocks(self, pre: Tag) -> list[Tag] | None:
        lines = _pre_lines(pre)
        self.width = max((len(_text(line).rstrip()) for line in lines), default=0)
        chunks = self._split_at_names(_chunks(lines))
        if not self.is_play(chunks):
            for line in lines:  # put it back as it was
                pre.extend(line + [NavigableString("\n")])
            return None
        out: list[Tag] = []
        pending: tuple[str, str, str] | None = None
        last: Tag | None = None
        prose = self.hints.prose
        for chunk in chunks:
            while chunk and (kind := act_scene(first := _flat(_text(chunk[0])))) and len(first) <= 100:
                out.append(_heading(kind, first, self.levels))
                self.report.headings += 1
                chunk = chunk[1:]
                last = None
            if not chunk:
                continue
            label = _split_label(chunk[0], self.pats, self.cast)
            if label is not None and not label[3] and len(chunk) == 1:
                pending = label[:3]
                continue
            if label is not None:
                name, stop, sd, rest = label
                body = ([rest] if rest and _text(rest).strip() else []) + chunk[1:]
                last = self._speech(name, stop, sd, body, prose)
                out.append(last)
                continue
            text = _flat(_text([n for line in chunk for n in line]))
            if _bracketed(text) or _DIRECTION_WORD.match(text) and pending is None:
                p = _prose_paragraph(chunk, "gd-sd")
                _trim(p)
                out.append(p)
                self.report.directions += 1
                last = None
                continue
            if pending is not None:
                last = self._speech(*pending, chunk, prose)
                out.append(last)
                pending = None
                continue
            if prose or len(chunk) > 1 and self._reads_as_prose(chunk):
                p = _prose_paragraph(chunk, "")  # a speech's next paragraph, or a passage with no name
                del p["class"]
                _trim_inner(p)
                out.append(p)
                last = None
                continue
            if last is not None and "gv" in last.get("class", []):
                self._continue(last, chunk)
                continue
            out.append(self._lines(chunk, "gd-lines"))
            last = None
        if pending is not None:
            out.append(self._speech(*pending, [], prose))
        self.report.pre_plays += 1
        return out

    def _split_at_names(self, chunks):
        """A speech can start with no blank line before it (Frogs: `…not till I'm going to vomit.` / `DIO. Truly
        an exquisite joke…`): a chunk is cut where a line opens with a known name and a stop."""
        if self.cast is None:
            return chunks
        out = []
        for chunk in chunks:
            out.append([chunk[0]])
            for line in chunk[1:]:
                label = _split_label(line, self.pats, self.cast)
                if label is not None and label[1] and label[3] and _text(label[3]).strip():
                    out.append([line])
                else:
                    out[-1].append(line)
        return out

    def _reads_as_prose(self, body: list[list[PageElement]]) -> bool:
        pairs = list(zip(body, body[1:]))
        return bool(pairs) and sum(_starts_lower(_text(b)) for _, b in pairs) >= 0.5 * len(pairs)

    def _speech(self, name: str, stop: str, sd: str, body: list[list[PageElement]], prose: bool) -> Tag:
        self.report.speeches += 1
        head = _head(name, sd, stop, [])
        if body and (prose or self._reads_as_prose(body)):
            p = _prose_paragraph(body, "gd-speech gd-run")
            who = head.find("span", class_="gd-who")
            parts = [who.extract()]
            if head.find("span", class_="gd-sdi"):
                parts += [" ", head.find("span", class_="gd-sdi").extract()]
            parts.append(" ")
            for n in reversed(parts):
                p.insert(0, n if isinstance(n, PageElement) else NavigableString(n))
            _trim_inner(p)
            _wrap_inline_directions(p)
            return p
        div = self._lines(body, "gd-speech gd-above")
        div.insert(0, head)
        return div

    def _lines(self, body: list[list[PageElement]], cls: str) -> Tag:
        div = _HOLDER.new_tag("div", attrs={"class": cls.split() + ["gv", "gv-pre"]})
        self._fill(div, body)
        return div

    def _fill(self, div: Tag, body: list[list[PageElement]]) -> None:
        parts = _join_direction_lines(_join_wrapped(body, self.width))
        groups = []
        for kind, nodes in parts:
            if kind == "sd":
                lead = _lead(_text(nodes))
                groups.append([NavigableString(" " * lead), _sd_span(nodes)])
            else:
                groups.append(nodes)
        if not groups:
            return
        filled = [g for g in groups if _text(g).strip()]
        base = min((verse._lead(_text(g))[1] for g in filled), default=0)
        verse._fill(_HOLDER, div, groups, base, 0.5, gloss=False)
        for line in div.find_all("span", class_="gv-l", recursive=False):
            _wrap_inline_directions(line)

    def _continue(self, div: Tag, chunk: list[list[PageElement]]) -> None:
        """A blank line inside a speech (a new stanza or verse paragraph) keeps it in the speech."""
        tail = _HOLDER.new_tag("div")
        self._fill(tail, chunk)
        div.append("\n")
        div.append(_new("span", "gv-gap"))
        for child in list(tail.contents):
            div.append(child.extract())


def _join_wrapped(body: list[list[PageElement]], width: int = 0) -> list[list[PageElement]]:
    """Hard-wrapped lines joined back: a line that starts in lower case after a line that doesn't end a sentence
    and is set deeper, or follows a line that runs to the source's wrapping column (a prose speech in a verse play,
    `…you two couple, and get you to` / `the wells at Lucca…`)."""
    out: list[list[PageElement]] = []
    for line in body:
        text = _text(line)
        before = _text(out[-1]) if out else ""
        if out and _starts_lower(text) and (_lead(text) > _lead(before) + 1
                                            or width >= 50 and len(before.rstrip()) >= 0.85 * width) \
                and not re.search(r"[.!?]['’\"”)]*\s*$", before):
            out[-1] = out[-1] + [NavigableString(" ")] + [NavigableString(text.lstrip()) if isinstance(line[0], NavigableString) and i == 0 else n
                                                          for i, n in enumerate(line)]
            continue
        out.append(list(line))
    return out


def _trim_inner(p: Tag) -> None:
    for s in p.find_all(string=True):
        if "\n" in s or "  " in s:
            s.replace_with(NavigableString(re.sub(r"\s+", " ", str(s))))


# --- inline directions ---------------------------------------------------------------------------------------------

def _wrap_inline_directions(block: Tag) -> int:
    """`[Aside.]`, `(To Psyche)` in a speech's own words become `span.gd-sdi`; a note mark (`[12]`) or an editor's
    word in brackets (`[flight,]`, lower case) doesn't."""
    count = 0
    for string in list(block.find_all(string=True)):
        if string.parent is None or type(string) is not NavigableString:
            continue
        if string.find_parent(["i", "em", "a", "sup"]) is not None or string.find_parent(
                class_=["gd-who", "gd-sdi", "gv-n", "gd-head"]) is not None:
            continue
        text = str(string)
        pieces, at = [], 0
        for m in _INLINE_SD.finditer(text):
            if _DIGITS.match(m.group(0)):
                continue
            pieces += [NavigableString(text[at:m.start()]), _new("span", "gd-sdi", m.group(0))]
            at = m.end()
            count += 1
        if pieces:
            pieces.append(NavigableString(text[at:]))
            string.replace_with(*[p for p in pieces if not (isinstance(p, NavigableString) and not str(p))])
    return count + _wrap_bracket_runs(block) + _wrap_direction_lines(block)


def _wrap_direction_lines(block: Tag) -> int:
    """A line of a verse speech that is a direction (`[<i>She rises and gazes…</i>`, Murray's brackets often
    left open) becomes a `span.gd-sdi`."""
    count = 0
    segment: list[PageElement] = []
    for child in list(block.children) + [None]:
        if child is None or isinstance(child, Tag) and child.name == "br":
            text = _flat(_text(segment))
            if (text and (text[0] in "[(" and len(text) <= 300 and (_bracketed(text) or text[0] == "[" and "]" not in text)
                          or _DIRECTION_WORD.match(text) and len(text) <= 80)
                    and not _DIGITS.match(text) and not any(isinstance(n, Tag) and "gd-sdi" in (n.get("class") or [])
                                                            for n in segment)):
                nodes = _meaningful(segment)
                span = _new("span", "gd-sdi")
                nodes[0].insert_before(span)
                for n in nodes:
                    span.append(n.extract())
                count += 1
            segment = []
        elif not (isinstance(child, Tag) and {"gd-head", "gd-who"} & set(child.get("class") or [])):
            segment.append(child)
    return count


_OPEN = re.compile(r"[\[(](?=\s*$|\s*[A-Z])(?![^\[\]()]*[\])])[^\[\]()]*$")


def _wrap_bracket_runs(block: Tag) -> int:
    """A direction whose brackets hold tags (`[<i>Reads.</i>]`, `(<i>To the</i> PORTER)`): the run from the
    opening bracket to the closing one, among one parent's children and with no line break, becomes a
    `span.gd-sdi`."""
    count = 0
    for string in list(block.find_all(string=_OPEN)):
        if string.parent is None or string.find_parent(class_=["gd-who", "gd-sdi", "gd-head"]) is not None:
            continue
        m = _OPEN.search(str(string))
        close = "]" if m.group(0)[0] == "[" else ")"
        run, node, end = [], string.next_sibling, None
        while node is not None and len(run) < 12:
            if isinstance(node, Tag) and (node.name == "br" or node.find("br") is not None
                                          or node.name in _BLOCK_NAMES):
                break
            text = node.get_text() if isinstance(node, Tag) else str(node)
            if any(c in text for c in "[()]".replace(close, "")) and close not in text:
                break
            if close in text:
                end = node
                break
            run.append(node)
            node = node.next_sibling
        if end is None or not isinstance(end, NavigableString) or not run:
            continue
        inside = _flat(m.group(0) + _text(run) + str(end)[:str(end).index(close) + 1])
        if len(inside) > 240 or _DIGITS.match(inside):
            continue
        before, opening = str(string)[:m.start()], m.group(0)
        cut = str(end).index(close) + 1
        span = _new("span", "gd-sdi")
        span.append(NavigableString(opening))
        for n in run:
            span.append(n.extract())
        span.append(NavigableString(str(end)[:cut]))
        rest = NavigableString(str(end)[cut:])
        end.replace_with(rest)
        string.replace_with(*[n for n in (NavigableString(before), span) if not (isinstance(n, NavigableString) and not str(n))])
        count += 1
    return count


# --- the pass ------------------------------------------------------------------------------------------------------

def structure(body: Tag, hints: Hints | None = None, min_speeches: int = MIN_SPEECHES) -> Report:
    """Marks the play's speeches, directions, acts and scenes in place (see the module docstring)."""
    hints = hints or Hints()
    report = Report()
    if not hints.on:
        report.skipped = "off"
        return report
    if body.find(class_="gd-speech") is not None:  # Standard Ebooks: the source's own semantics, already mapped
        report.skipped = "speeches already marked by the source"
        return report
    pats = _patterns(hints)
    levels = _heading_levels(body)

    # the cast, from block labels and pre lines
    found = [f for b in _leaf_blocks(body) if (f := _find(b, pats)) is not None and not _in_toc(b)]
    pre_names: list[_Found] = []
    for pre in body.find_all("pre"):
        for chunk in _chunks(_pre_lines_copy(pre)):
            label = _split_label(chunk[0], pats, None)
            if label is not None:
                pre_names.append(_Found(pre, "pre", label[0], bool(label[1])))
    cast = _cast(found + pre_names)
    # a name seen once before the play's first repeated name is an introduction's signature ("EDWARD J.
    # O'BRIEN." / "February 23, 1911."), not a speaker
    counts = Counter(key(f.name) for f in found + pre_names)
    begun = next((i for i, f in enumerate(found) if counts[key(f.name)] >= 2), len(found))
    early = {key(f.name) for f in found[:begun] if counts[key(f.name)] == 1}
    cast -= early
    found = [f for f in found if in_cast(f.name, cast)]
    if len(found) + len([p for p in pre_names if in_cast(p.name, cast)]) < min_speeches:
        report.skipped = f"only {len(found)} speeches found"
        return report
    report.cast = sorted(cast)

    # <pre> plays first: their lines hold names, directions and act labels
    pre_play = _PrePlay(pats, cast, hints, levels)
    for pre in list(body.find_all("pre")):
        blocks = pre_play.blocks(pre)
        if blocks is not None:
            pre.replace_with(*blocks)
    report.speeches += pre_play.report.speeches
    report.directions += pre_play.report.directions
    report.headings += pre_play.report.headings
    report.pre_plays = pre_play.report.pre_plays

    # then speeches in blocks
    for f in found:
        if f.block.parent is None or f.block.decomposed or "gd-speech" in (f.block.get("class") or []):
            continue  # gone, or a name block before it already joined it
        if f.kind == "line":
            _mark_line(f)
        elif f.kind == "block":
            _mark_block(f, cast)
        else:
            _mark_run(f, pats)
        report.speeches += 1

    for pre in list(body.find_all("pre")):
        # a direction set as a pre of its own between speeches (Hedda: `[She goes to the glass door…`,
        # `TESMAN, with a letter in his hand, comes from the right` / `through the inner room.`)
        text = _flat(pre.get_text())
        if text and len(text) < 1500 and _after_a_speech(pre) and (
                text[0] in "[(" or _DIRECTION_WORD.match(text) or verse.reads_as_prose(_pre_lines_copy(pre))):
            lines = _pre_lines(pre)
            p = _prose_paragraph([line for line in lines if _text(line).strip()], "gd-sd")
            _trim(p)
            pre.replace_with(p)
            report.directions += 1

    report.speeches += _mark_abbreviations(body, cast, pats)

    # directions and act/scene labels, in the stretch where the speeches are
    speeches = body.find_all(class_="gd-speech")
    region = set()
    if speeches:
        started = False  # to the end: "[Exeunt omnes.]", "Curtain" come after the last speech
        firsts = {id(speeches[0])}
        firsts |= {id(h) for h in body.find_all(["h1", "h2", "h3", "h4", "h5", "h6"]) if act_scene(h.get_text())}
        # the cast list's heading: "(The action takes place in Helmer's house.)" follows it
        firsts |= {id(b) for b in _leaf_blocks(body) if len(_flat(b.get_text())) <= 40 and _PERSONAE.search(b.get_text())}
        for tag in body.descendants:
            if id(tag) in firsts:
                started = True
            if started and isinstance(tag, Tag):
                region.add(id(tag))

    for block in _leaf_blocks(body):
        if block.parent is None or "gd-speech" in (block.get("class") or []) or block.find_parent(class_="gd-speech"):
            continue
        if _in_toc(block):
            continue
        text = _flat(block.get_text())
        kind = act_scene(text)
        if kind and block.name not in ("h1", "h2", "h3", "h4", "h5", "h6", "td", "li"):
            block.replace_with(_heading(kind, text, levels))
            report.headings += 1
            continue
        if block.name in ("h1", "h2", "h3", "h4", "h5", "h6"):
            if kind:
                _quiet_heading(block)
            continue
        classes = block.get("class") or []
        if any(DIRECTION_CLASSES.match(c) for c in classes) or (
                "right" in classes and re.match(r"^\[?\s*(Exit|Exeunt|Exit\.)", text)):
            block["class"] = [c for c in classes if not DIRECTION_CLASSES.match(c) and c != "right"] + ["gd-sd"]
            if _mostly_italic(block):
                block["class"].append("gd-own")
            report.directions += 1
            continue
        if id(block) in region and block.name in ("p", "div"):
            _wrap_inline_directions(block)
        in_song = block.find_parent(class_=["poem", "stanza", "poetry", "verse"]) is not None or any(
            re.match(r"^i\d+$", c) for c in classes)  # a sung line set in italics is no direction
        if id(block) in region and block.name in ("p", "div") and text and (
                _bracketed(text) or (_DIRECTION_WORD.match(text) and len(text) < 300)
                or _mostly_italic(block) and not in_song):
            _add_class(block, "gd-sd")
            if _mostly_italic(block):
                _add_class(block, "gd-own")
            report.directions += 1
    for block in body.find_all(class_="gd-speech"):
        if block.name != "div" or "gv" not in (block.get("class") or []):
            _wrap_inline_directions(block)
    return report


_ABBREVIATION = re.compile(r"^(?P<who>[A-Z][A-Za-z’']{1,14}(?:[.,]?[ \t]+(?:and|&)?[ \t]*[A-Z][A-Za-z’']{1,14}){0,3})"
                           r"(?P<stop>[.:])(?=\s)")


def _mark_abbreviations(body: Tag, cast: set[str], pats) -> int:
    """Unmarked blocks that open with a cast name in title case or cut short (`Polly.`, `Tall.` for Tallyho,
    `Tris.` for Tristan): the libretti's songs use these where their dialogue has the full name in capitals."""
    count = 0
    starts = {k.split()[0] for k in cast if len(k.split()[0]) >= 3}

    def known(name: str) -> bool:
        k = key(name)
        return k in cast or all(any(c.startswith(w) and len(w) >= 3 for c in starts | set(cast))
                                for w in k.replace(" AND ", " ").replace("&", " ").split())

    for block in _leaf_blocks(body):
        if block.parent is None or "gd-speech" in (block.get("class") or []) or block.find_parent(class_="gd-speech"):
            continue
        first = next(iter(_meaningful(block.children)), None)
        if not isinstance(first, NavigableString):
            continue
        lead = str(first).lstrip()
        m = _ABBREVIATION.match(lead)
        if m and _plausible(m.group("who")) and known(m.group("who")) and len(_flat(block.get_text())) > m.end() + 1:
            first.replace_with(_new("span", "gd-who", display(m.group("who")) + m.group("stop")),
                               NavigableString(" " + lead[m.end():].lstrip()))
            _add_class(block, "gd-speech", "gd-run")
            count += 1
    return count


def _after_a_speech(tag: Tag) -> bool:
    """True when a marked speech comes before the tag (the play has begun)."""
    return tag.find_previous(class_="gd-speech") is not None


def _in_toc(block: Tag) -> bool:
    """A contents entry ("ACT I" as a link) is no act."""
    if "toc" in (block.get("class") or []) or block.find_parent(class_="toc") is not None:
        return True
    links = "".join(a.get_text() for a in block.find_all("a", href=True))
    return bool(links.strip()) and len(_flat(links)) >= 0.8 * len(_flat(block.get_text()))


def _quiet_heading(heading: Tag) -> None:
    """An act or scene heading's label in title case, whatever wraps it (`<h3><b>SCENE I. A room…</b></h3>`)."""
    strings = [s for s in heading.find_all(string=True) if s.strip()]
    if not strings:
        return
    first = strings[0]
    text = str(first)
    m = re.match(r"^(\s*\[?)(ACT|SCENE|ACTUS|SCENA)\b", text)
    if m and any(c.islower() for c in heading.get_text()):
        first.replace_with(NavigableString(m.group(1) + display(m.group(2)) + text[m.end():]))


def _pre_lines_copy(pre: Tag) -> list[list[PageElement]]:
    """The pre's lines as text only, for counting names without touching it."""
    return [[NavigableString(line)] for line in pre.get_text().split("\n")]
