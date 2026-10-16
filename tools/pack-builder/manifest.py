"""A pack's manifest: its title list and Gutenberg source ids, and the checks every entry must pass.

Each work is checked against its Gutenberg RDF record: public domain in the USA, English, and a title that
matches, so a mistyped id can't slip a different book into the pack. `published` is checked against the
US public-domain line: the year the reproduced edition or translation first appeared.
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
SLUG = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
STOP_WORDS = {"the", "a", "an", "of", "and", "or"}
NS = {
    "dcterms": "http://purl.org/dc/terms/",
    "rdf": "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
    "pgterms": "http://www.gutenberg.org/2009/pgterms/",
}


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


@dataclass
class Manifest:
    name: str
    title: str
    description: str
    language: str
    sections: list[tuple[str, str]]
    works: list[WorkSpec]


@dataclass
class Rdf:
    title: str
    languages: list[str]
    rights: str


def load(path: Path) -> Manifest:
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    works = [WorkSpec(gutenberg=int(w["gutenberg"]), slug=w["slug"], title=w["title"], author=w.get("author", ""),
                      translator=w.get("translator"), published=int(w["published"]), section=w["section"],
                      split_at=w.get("splitAt"), title_check=w.get("titleCheck", True))
             for w in data["works"]]
    return Manifest(name=data["name"], title=data["title"], description=data["description"],
                    language=data["language"], sections=[(s["id"], s["title"]) for s in data["sections"]],
                    works=works)


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
        if "en" not in rdf.languages:
            errors.append(f"{where}: language is {rdf.languages}, not English")
        if w.title_check and not _words(w.title) <= _words(rdf.title):
            errors.append(f"{where}: title {w.title!r} doesn't match Gutenberg's {rdf.title!r}")
    return errors
