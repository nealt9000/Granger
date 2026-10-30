"""A pack's manifest: its title list and Gutenberg source ids, and the checks every entry must pass.

Each work is checked against its Gutenberg RDF record: public domain in the USA, in the manifest's language,
and a title that matches, so a mistyped id can't slip a different book into the pack. `published` is checked
against the US public-domain line: the year the reproduced edition or translation first appeared.

A manifest with `"checkDeaths": true` (Clásicos Granger) must also be public domain where its readers live:
every named author, translator and editor is listed in the work's `people` with the year they `died` and a
`diedSource`, and each died in `PD_DEATH_LINE` or earlier (Spain's life + 80 for authors who died before 1987,
stricter than Argentina's life + 70). An unknown year fails, except for a work published before 1800 that says
why in its `note`; an anonymous work has no `people` and a `note`.
"""
import json
import re
import unicodedata
import xml.etree.ElementTree as ET
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

PD_LINE = 1930
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
class WorkSpec:
    gutenberg: int
    slug: str
    title: str
    author: str
    translator: str | None
    published: int
    section: str
    split_at: str | None
    title_check: bool
    learner: bool = False
    people: list[Person] | None = None
    note: str = ""


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


def load(path: Path) -> Manifest:
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    works = [WorkSpec(gutenberg=int(w["gutenberg"]), slug=w["slug"], title=w["title"], author=w.get("author", ""),
                      translator=w.get("translator"), published=int(w["published"]), section=w["section"],
                      split_at=w.get("splitAt"), title_check=w.get("titleCheck", True),
                      learner=w.get("learner", False), people=_people(w.get("people")), note=w.get("note", ""))
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
    plain = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode().lower()
    return set(re.sub(r"[^a-z0-9]+", " ", plain).split()) - STOP_WORDS


def validate(m: Manifest, rdf_for: Callable[[int], Rdf]) -> list[str]:
    errors = []
    sections = {section_id for section_id, _ in m.sections}
    slugs = Counter(w.slug for w in m.works)
    ids = Counter(w.gutenberg for w in m.works)
    for w in m.works:
        where = f"{w.slug} (#{w.gutenberg})"
        if not SLUG.match(w.slug):
            errors.append(f"{where}: slug must be lowercase words joined by hyphens")
        if slugs[w.slug] > 1:
            errors.append(f"{where}: slug used more than once")
            slugs[w.slug] = 1
        if ids[w.gutenberg] > 1:
            errors.append(f"{where}: Gutenberg id used more than once")
            ids[w.gutenberg] = 1
        if w.section not in sections:
            errors.append(f"{where}: unknown section {w.section!r}")
        if w.split_at is not None and w.split_at not in ("h1", "h2", "h3", "h4"):
            errors.append(f"{where}: splitAt must be h1-h4 or null, not {w.split_at!r}")
        if w.published > PD_LINE:
            errors.append(f"{where}: published {w.published}, after the {PD_LINE} public-domain line")
        rdf = rdf_for(w.gutenberg)
        if not rdf.rights.startswith("Public domain in the USA"):
            errors.append(f"{where}: rights are {rdf.rights!r}")
        if m.code not in rdf.languages:
            errors.append(f"{where}: language is {rdf.languages}, not {m.code!r}")
        if m.check_deaths:
            errors += _death_errors(where, w)
        if w.title_check and not _words(w.title) <= _words(rdf.title):
            errors.append(f"{where}: title {w.title!r} doesn't match Gutenberg's {rdf.title!r}")
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
