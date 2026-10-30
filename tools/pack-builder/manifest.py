"""A pack's manifest: its title list and source ids, and the checks every entry must pass.

Each Gutenberg work is checked against its RDF record: public domain in the USA, English, and a title that
matches, so a mistyped id can't slip a different book into the pack. `published` is checked against the
US public-domain line: the year the reproduced edition or translation first appeared.

An eBible work (`"source": "ebible"`, `"ebible": "<id>"`) is a modern translation its makers dedicated to
the public domain, so it skips the year line; its `licence` must quote that dedication with its URL, and the
eBible details page must still say "Public Domain".

Reading settings: `verse` turns verse layout on or off (the default follows the section), `lineNumbers` puts
line numbers in the margin or drops them, and `titleOverrides` replaces contents titles by page number.
`packSections` packs a too-big page's sections into even pages rather than one page each (a Bible's chapters).
`cutAfter` gives a Gutenberg work's last words, so whatever follows them (a publisher's catalogue) is cut.
"""
import json
import re
import unicodedata
import xml.etree.ElementTree as ET
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

PD_LINE = 1930
PD_RIGHTS = "Public domain in the USA."
VERSE_SECTIONS = {"poetry", "drama", "libretti", "scripture"}
LINE_NUMBERS = ("margin", "drop")
SLUG = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
STOP_WORDS = {"the", "a", "an", "of", "and", "or"}
NS = {
    "dcterms": "http://purl.org/dc/terms/",
    "rdf": "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
    "pgterms": "http://www.gutenberg.org/2009/pgterms/",
}


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

    @property
    def reads_as_verse(self) -> bool:
        return self.verse if self.verse is not None else self.section in VERSE_SECTIONS

    @property
    def where(self) -> str:
        if self.source == "ebible":
            return f"{self.slug} (ebible {self.ebible})"
        return f"{self.slug} (#{self.gutenberg})"


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
    works = [WorkSpec(gutenberg=int(w["gutenberg"]) if w.get("gutenberg") is not None else None, slug=w["slug"],
                      title=w["title"], author=w.get("author", ""), translator=w.get("translator"),
                      published=int(w["published"]), section=w["section"], split_at=w.get("splitAt"),
                      title_check=w.get("titleCheck", True), verse=w.get("verse"),
                      line_numbers=w.get("lineNumbers", "margin"), title_overrides=w.get("titleOverrides", {}),
                      source=w.get("source", "gutenberg"), ebible=w.get("ebible"), licence=w.get("licence"),
                      cut_after=w.get("cutAfter"), pack_sections=w.get("packSections", False))
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


def validate(m: Manifest, rdf_for: Callable[[int], Rdf],
             ebible_page_for: Callable[[str], bytes] | None = None) -> list[str]:
    errors = []
    sections = {section_id for section_id, _ in m.sections}
    slugs = Counter(w.slug for w in m.works)
    ids = Counter(w.gutenberg for w in m.works if w.source == "gutenberg")
    ebibles = Counter(w.ebible for w in m.works if w.source == "ebible")
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
        if w.source != "gutenberg" or w.gutenberg is None:
            errors.append(f"{where}: source must be gutenberg (with an id) or ebible, not {w.source!r}")
            continue
        if ids[w.gutenberg] > 1:
            errors.append(f"{where}: Gutenberg id used more than once")
            ids[w.gutenberg] = 1
        if w.published > PD_LINE:
            errors.append(f"{where}: published {w.published}, after the {PD_LINE} public-domain line")
        rdf = rdf_for(w.gutenberg)
        if " ".join(rdf.rights.split()) != PD_RIGHTS:
            errors.append(f"{where}: rights are {rdf.rights!r}")
        if "en" not in rdf.languages:
            errors.append(f"{where}: language is {rdf.languages}, not English")
        if w.title_check and not _words(w.title) <= _words(rdf.title):
            errors.append(f"{where}: title {w.title!r} doesn't match Gutenberg's {rdf.title!r}")
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
    overrides = w.title_overrides
    if not isinstance(overrides, dict) or not all(
            isinstance(k, str) and k.isdigit() and int(k) >= 1 and isinstance(v, str) and v.strip()
            for k, v in overrides.items()):
        errors.append(f"titleOverrides must map page numbers (\"1\", \"2\", ...) to titles, not {overrides!r}")
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
