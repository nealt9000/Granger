"""A pack's manifest: its title list and source ids, and the checks every entry must pass.

Each work is checked against its Gutenberg RDF record: public domain in the USA, in the manifest's language,
and a title that matches, so a mistyped id can't slip a different book into the pack. `published` is checked
against the US public-domain line: the year the reproduced edition or translation first appeared.

A manifest with `"checkDeaths": true` (Clásicos Granger) must also be public domain where its readers live:
every named author, translator and editor is listed in the work's `people` with the year they `died` and a
`diedSource`, and each died in `PD_DEATH_LINE` or earlier (Spain's life + 80 for authors who died before 1987,
stricter than Argentina's life + 70). An unknown year fails, except for a work published before 1800 that says
why in its `note`; an anonymous work has no `people` and a `note`.

An eBible work (`"source": "ebible"`, `"ebible": "<id>"`) is a modern translation its makers dedicated to
the public domain, so it skips the year line; its `licence` must quote that dedication with its URL, and the
eBible details page must still say "Public Domain".

A Standard Ebooks work (`"source": "standardebooks"`, `"se": "<author>/<title>[/<translator>]"`) must be a
released book (not one of SE's placeholder pages), carry SE's CC0 dedication in its `dc:rights`, and meet the
year line like a Gutenberg work: SE produces only what is public domain in the US, but the year is ours to check.
A Wikisource work (`"source": "wikisource"`, `"wikisource": "<page title>"`, `wsSkip` naming subpages to leave
out) must carry a US public-domain licence template on its page, and meet the year line unless that template
says it's a US government work.

A compiled work (`"source": "compiled"`) is one book made of `parts`, each with its own `title` and a source of
its own: a Gutenberg id (`"gutenberg"`, with an optional `select`, a CSS selector for the part of the page to
keep), a Wikisource page (`"wikisource"`), the text itself (`"html"`, for a line no source has on its own, with a
`licence` that names a US government source and its URL), or `pages`, a list of such parts shown under the
part's title. Each part passes its own source's rights check; the work's `published` is the year it belongs to.

Reading settings: `verse` turns verse layout on or off (the default follows the section), `lineNumbers` puts
line numbers in the margin or drops them, and `titleOverrides` replaces contents titles by page number.
`packSections` packs a too-big page's sections into even pages rather than one page each (a Bible's chapters).
`cutAfter` gives a Gutenberg work's last words, so whatever follows them (a publisher's catalogue) is cut.

Granger's own editions (editions.py): `rename` applies a name map from classics/names/ between two headings
(`{"names": "homer-greek", "from": "Book I", "to": "Footnotes"}`), and `editorNote` says so at the front and in the
colophon.
"""
import json
import re
import unicodedata
import xml.etree.ElementTree as ET
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

import editions

PD_LINE = 1930
PD_RIGHTS = "Public domain in the USA."
VERSE_SECTIONS = {"poetry", "drama", "libretti", "scripture"}
LINE_NUMBERS = ("margin", "drop")
PD_DEATH_LINE = 1945  # life + 80 leaves authors who died in 1945 or earlier free in 2026
EARLY_LINE = 1800  # before this, an unknown death year passes with a note
SLUG = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
STOP_WORDS = {"the", "a", "an", "of", "and", "or",
              "el", "la", "los", "las", "lo", "un", "una", "de", "del", "y", "o", "e"}
LANGUAGE_CODES = {"eng": "en", "spa": "es"}  # a manifest's ZIM language (ISO 639-3) as Gutenberg's 2-letter code
NS = {
    "dcterms": "http://purl.org/dc/terms/",
    "rdf": "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
    "pgterms": "http://www.gutenberg.org/2009/pgterms/",
}


@dataclass
class Person:
    name: str
    role: str
    died: int | None
    died_source: str


@dataclass
class PartSpec:
    """One part of a compiled work: its title and where its text comes from."""
    title: str
    source: str
    gutenberg: int | None = None
    wikisource: str | None = None
    ws_skip: list[str] = field(default_factory=list)
    select: str | None = None
    html: str | None = None
    licence: str | None = None
    pages: list["PartSpec"] = field(default_factory=list)


@dataclass
class WorkSpec:
    gutenberg: int | None
    slug: str
    title: str
    author: str
    translator: str | None
    published: int
    section: str
    split_at: str | None
    title_check: bool
    verse: bool | None = None
    line_numbers: str = "margin"
    title_overrides: dict[str, str] = field(default_factory=dict)
    source: str = "gutenberg"
    ebible: str | None = None
    licence: str | None = None
    cut_after: str | None = None
    pack_sections: bool = False
    learner: bool = False
    people: list[Person] | None = None
    note: str = ""
    rename: dict | None = None
    editor_note: str = ""
    se: str | None = None
    wikisource: str | None = None
    ws_skip: list[str] = field(default_factory=list)
    parts: list[PartSpec] | None = None

    @property
    def reads_as_verse(self) -> bool:
        return self.verse if self.verse is not None else self.section in VERSE_SECTIONS

    @property
    def where(self) -> str:
        if self.source == "ebible":
            return f"{self.slug} (ebible {self.ebible})"
        if self.source == "standardebooks":
            return f"{self.slug} (se {self.se})"
        if self.source == "wikisource":
            return f"{self.slug} (wikisource {self.wikisource!r})"
        if self.source == "compiled":
            return f"{self.slug} (compiled)"
        return f"{self.slug} (#{self.gutenberg})"


@dataclass
class Manifest:
    name: str
    title: str
    description: str
    language: str
    sections: list[tuple[str, str]]
    works: list[WorkSpec]
    collection: str = "granger-classics"
    book_id_prefix: str = "gc-"
    domains: dict[str, str] | None = None  # section id -> readiness domain, when the manifest names one
    check_deaths: bool = False

    @property
    def code(self) -> str:
        """The 2-letter language code EPUBs, descriptors and Gutenberg's RDF use."""
        return language_code(self.language)


@dataclass
class Rdf:
    title: str
    languages: list[str]
    rights: str


def language_code(language: str) -> str:
    return LANGUAGE_CODES.get(language, language)


def _people(entries: list[dict] | None) -> list[Person] | None:
    if entries is None:
        return None
    return [Person(name=p["name"], role=p["role"], died=p.get("died"), died_source=p.get("diedSource", ""))
            for p in entries]


def _part(p: dict) -> PartSpec:
    source = p.get("source") or ("gutenberg" if p.get("gutenberg") is not None else
                                 "wikisource" if p.get("wikisource") else "text" if p.get("html") else
                                 "pages" if p.get("pages") else "")
    return PartSpec(title=p.get("title", ""), source=source,
                    gutenberg=int(p["gutenberg"]) if p.get("gutenberg") is not None else None,
                    wikisource=p.get("wikisource"), ws_skip=p.get("wsSkip", []), select=p.get("select"),
                    html=p.get("html"), licence=p.get("licence"), pages=[_part(q) for q in p.get("pages", [])])


def load(path: Path) -> Manifest:
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    works = [WorkSpec(gutenberg=int(w["gutenberg"]) if w.get("gutenberg") is not None else None, slug=w["slug"],
                      title=w["title"], author=w.get("author", ""), translator=w.get("translator"),
                      published=int(w["published"]), section=w["section"], split_at=w.get("splitAt"),
                      title_check=w.get("titleCheck", True), verse=w.get("verse"),
                      line_numbers=w.get("lineNumbers", "margin"), title_overrides=w.get("titleOverrides", {}),
                      source=w.get("source", "gutenberg"), ebible=w.get("ebible"), licence=w.get("licence"),
                      cut_after=w.get("cutAfter"), pack_sections=w.get("packSections", False),
                      learner=w.get("learner", False), people=_people(w.get("people")), note=w.get("note", ""),
                      rename=w.get("rename"), editor_note=w.get("editorNote", ""),
                      se=w.get("se"), wikisource=w.get("wikisource"), ws_skip=w.get("wsSkip", []),
                      parts=[_part(p) for p in w["parts"]] if w.get("parts") is not None else None)
             for w in data["works"]]
    domains = {s["id"]: s["domain"] for s in data["sections"] if s.get("domain")}
    return Manifest(name=data["name"], title=data["title"], description=data["description"],
                    language=data["language"], sections=[(s["id"], s["title"]) for s in data["sections"]],
                    works=works, collection=data.get("collection", "granger-classics"),
                    book_id_prefix=data.get("bookIdPrefix", "gc-"), domains=domains or None,
                    check_deaths=data.get("checkDeaths", False))


def parse_rdf(data: bytes) -> Rdf:
    ebook = ET.fromstring(data).find("pgterms:ebook", NS)
    title = " ".join((ebook.findtext("dcterms:title", "", NS)).split())
    languages = [v.text.strip() for v in ebook.findall("dcterms:language/rdf:Description/rdf:value", NS)]
    return Rdf(title=title, languages=languages, rights=(ebook.findtext("dcterms:rights", "", NS)).strip())


def _words(text: str) -> set[str]:
    text = re.sub(r"[\u2010-\u2015\u2212]", " ", text)  # dashes part words, as a hyphen does ("I–VII")
    plain = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode().lower()
    return set(re.sub(r"[^a-z0-9]+", " ", plain).split()) - STOP_WORDS


@dataclass
class SeRecord:
    """What a Standard Ebooks page and its EPUB say about a book: whether it's only a placeholder, its title and
    its `dc:rights`."""
    placeholder: bool
    title: str = ""
    rights: str = ""
    years: list[int] = field(default_factory=list)  # the colophon's years for the text and its translation


SE_ID = re.compile(r"^[a-z0-9]+([-_][a-z0-9]+)*(/[a-z0-9]+([-_][a-z0-9]+)*){1,3}$")


def validate(m: Manifest, rdf_for: Callable[[int], Rdf],
             ebible_page_for: Callable[[str], bytes] | None = None,
             se_for: Callable[[str], SeRecord] | None = None,
             ws_licence_for: Callable[[str], object] | None = None) -> list[str]:
    """`ws_licence_for` gives a Wikisource page's US public-domain licence (with a `government` flag), or None."""
    errors = []
    sections = {section_id for section_id, _ in m.sections}
    slugs = Counter(w.slug for w in m.works)
    ids = Counter(w.gutenberg for w in m.works if w.source == "gutenberg")
    ebibles = Counter(w.ebible for w in m.works if w.source == "ebible")
    others = {"standardebooks": Counter(w.se for w in m.works if w.source == "standardebooks"),
              "wikisource": Counter(w.wikisource for w in m.works if w.source == "wikisource")}
    for w in m.works:
        where = w.where
        if not SLUG.match(w.slug):
            errors.append(f"{where}: slug must be lowercase words joined by hyphens")
        if slugs[w.slug] > 1:
            errors.append(f"{where}: slug used more than once")
            slugs[w.slug] = 1
        if w.section not in sections:
            errors.append(f"{where}: unknown section {w.section!r}")
        if w.split_at is not None and w.split_at not in ("h1", "h2", "h3", "h4"):
            errors.append(f"{where}: splitAt must be h1-h4 or null, not {w.split_at!r}")
        errors += [f"{where}: {e}" for e in _reading_errors(w)]
        if w.source == "ebible":
            errors += _validate_ebible(w, ebibles, ebible_page_for)
            continue
        if w.source == "standardebooks":
            errors += [f"{where}: {e}" for e in _se_errors(w, others[w.source], se_for)]
            continue
        if w.source == "wikisource":
            errors += [f"{where}: {e}" for e in _ws_errors(w.wikisource, w.published, others[w.source],
                                                           ws_licence_for)]
            continue
        if w.source == "compiled":
            errors += [f"{where}: {e}" for e in _compiled_errors(w, m, rdf_for, ws_licence_for)]
            continue
        if w.source != "gutenberg" or w.gutenberg is None:
            errors.append(f"{where}: source must be gutenberg (with an id), ebible, standardebooks, wikisource or "
                          f"compiled, not {w.source!r}")
            continue
        if ids[w.gutenberg] > 1:
            errors.append(f"{where}: Gutenberg id used more than once")
            ids[w.gutenberg] = 1
        if w.published > PD_LINE:
            errors.append(f"{where}: published {w.published}, after the {PD_LINE} public-domain line")
        rdf = rdf_for(w.gutenberg)
        if " ".join(rdf.rights.split()) != PD_RIGHTS:
            errors.append(f"{where}: rights are {rdf.rights!r}")
        if m.code not in rdf.languages:
            errors.append(f"{where}: language is {rdf.languages}, not {m.code!r}")
        if m.check_deaths:
            errors += _death_errors(where, w)
        if w.title_check and not _words(w.title) <= _words(rdf.title):
            errors.append(f"{where}: title {w.title!r} doesn't match Gutenberg's {rdf.title!r}")
    return errors


def _se_errors(w: WorkSpec, ids: Counter, se_for: Callable[[str], SeRecord] | None) -> list[str]:
    if not w.se or not SE_ID.match(w.se):
        return ["se must be \"author/title\" or \"author/title/translator\" in lowercase words and hyphens"]
    errors = []
    if ids[w.se] > 1:
        errors.append("Standard Ebooks id used more than once")
        ids[w.se] = 1
    if w.published > PD_LINE:
        errors.append(f"published {w.published}, after the {PD_LINE} public-domain line")
    if se_for is not None:
        record = se_for(w.se)
        if record.placeholder:
            errors.append("Standard Ebooks has only a placeholder page for it, with no ebook")
        else:
            rights = " ".join(record.rights.split())
            if "United States public domain" not in rights or "CC0" not in rights:
                errors.append(f"the EPUB's rights don't state US public domain and CC0: {rights[:120]!r}")
            if record.years and max(record.years) > PD_LINE:
                errors.append(f"Standard Ebooks dates the text or translation {max(record.years)}, after the "
                              f"{PD_LINE} public-domain line")
            if w.title_check and not _words(w.title) <= _words(record.title):
                errors.append(f"title {w.title!r} doesn't match Standard Ebooks' {record.title!r}")
    return errors


def _ws_errors(title: str | None, published: int, titles: Counter | None,
               licence_for: Callable[[str], object] | None) -> list[str]:
    if not title or not title.strip() or any(c in title for c in "#<>[]{}|"):
        return ["wikisource must be a page title"]
    errors = []
    if titles is not None and titles[title] > 1:
        errors.append("Wikisource page used more than once")
        titles[title] = 1
    if licence_for is None:
        return errors
    found = licence_for(title)
    if found is None:
        errors.append(f"Wikisource page {title!r} has no US public-domain licence template")
    elif not found.government and published > PD_LINE:
        errors.append(f"published {published}, after the {PD_LINE} public-domain line")
    return errors


def _compiled_errors(w: WorkSpec, m: Manifest, rdf_for: Callable[[int], Rdf],
                     licence_for: Callable[[str], object] | None) -> list[str]:
    if not w.parts:
        return ["a compiled work needs parts"]
    errors = []

    def check(part: PartSpec, where: str, nested: bool) -> None:
        if not part.title.strip():
            errors.append(f"{where}: every part needs a title")
        if part.source == "gutenberg":
            rdf = rdf_for(part.gutenberg)
            if " ".join(rdf.rights.split()) != PD_RIGHTS:
                errors.append(f"{where} (#{part.gutenberg}): rights are {rdf.rights!r}")
            if m.code not in rdf.languages:
                errors.append(f"{where} (#{part.gutenberg}): language is {rdf.languages}, not {m.code!r}")
        elif part.source == "wikisource":
            errors.extend(f"{where}: {e}" for e in _ws_errors(part.wikisource, w.published, None, licence_for))
        elif part.source == "text":
            licence = part.licence or ""
            if "government" not in licence.lower() or "https://" not in licence:
                errors.append(f"{where}: a part given as text needs a licence naming its US government source "
                              "and URL")
        elif part.source == "pages" and not nested:
            for i, page in enumerate(part.pages):
                check(page, f"{where} page {i + 1}", True)
        else:
            errors.append(f"{where}: a part's source must be gutenberg, wikisource, html or pages, not "
                          f"{part.source!r}")

    for i, part in enumerate(w.parts):
        check(part, f"part {i + 1} ({part.title})", False)
    return errors


def _reading_errors(w: WorkSpec) -> list[str]:
    errors = []
    if not (w.verse is None or isinstance(w.verse, bool)):
        errors.append(f"verse must be true, false or left out, not {w.verse!r}")
    if w.line_numbers not in LINE_NUMBERS:
        errors.append(f"lineNumbers must be one of {LINE_NUMBERS}, not {w.line_numbers!r}")
    if not isinstance(w.pack_sections, bool):
        errors.append(f"packSections must be true or false, not {w.pack_sections!r}")
    if w.cut_after is not None and not (isinstance(w.cut_after, str) and w.cut_after.strip()):
        errors.append(f"cutAfter must be the work's last words, not {w.cut_after!r}")
    if w.rename is not None:
        errors += _rename_errors(w.rename)
    if not isinstance(w.editor_note, str):
        errors.append(f"editorNote must be a sentence, not {w.editor_note!r}")
    elif w.rename is not None and not w.editor_note.strip():
        errors.append("a renamed text needs an editorNote saying so")
    overrides = w.title_overrides
    if not isinstance(overrides, dict) or not all(
            isinstance(k, str) and k.isdigit() and int(k) >= 1 and isinstance(v, str) and v.strip()
            for k, v in overrides.items()):
        errors.append(f"titleOverrides must map page numbers (\"1\", \"2\", ...) to titles, not {overrides!r}")
    return errors


def _rename_errors(rename) -> list[str]:
    if not isinstance(rename, dict) or set(rename) - {"names", "from", "to"} or "names" not in rename:
        return [f'rename must be {{"names": MAP, "from": HEADING, "to": HEADING}}, not {rename!r}']
    errors = [f"rename {k} must be a heading's text, not {rename[k]!r}" for k in ("from", "to")
              if k in rename and not (isinstance(rename[k], str) and rename[k].strip())]
    try:
        editions.load_names(rename["names"])
    except (ValueError, TypeError) as error:
        errors.append(str(error))
    return errors


def _validate_ebible(w: WorkSpec, ebibles: Counter, page_for: Callable[[str], bytes] | None) -> list[str]:
    errors = []
    if not w.ebible or not re.fullmatch(r"[a-z0-9-]+", w.ebible):
        return [f"{w.where}: ebible id must be lowercase letters, digits and hyphens"]
    if ebibles[w.ebible] > 1:
        errors.append(f"{w.where}: eBible id used more than once")
        ebibles[w.ebible] = 1
    if not w.licence or "public domain" not in w.licence.lower() or "https://" not in w.licence:
        errors.append(f"{w.where}: licence must quote the public-domain dedication and its URL")
    if page_for is not None:
        page = page_for(w.ebible).decode("utf-8", "replace").lower()
        if "public domain" not in page:
            errors.append(f"{w.where}: the eBible details page no longer says Public Domain")
    return errors


def _death_errors(where: str, w: WorkSpec) -> list[str]:
    """The life + 80 rule: every named person died by PD_DEATH_LINE, and the year is known and sourced."""
    if w.people is None:
        return [f"{where}: no people listed; name every author, translator and editor, or [] for an anonymous work"]
    if not w.people and not w.note:
        return [f"{where}: an anonymous work needs a note"]
    errors = []
    for p in w.people:
        if p.died is None:
            if not (w.published < EARLY_LINE and w.note):
                errors.append(f"{where}: {p.name} ({p.role}): death year unknown")
        elif p.died > PD_DEATH_LINE:
            errors.append(f"{where}: {p.name} ({p.role}) died {p.died}, after the {PD_DEATH_LINE} line")
        elif not p.died_source.startswith("https://"):
            errors.append(f"{where}: {p.name} ({p.role}): no diedSource")
    return errors
