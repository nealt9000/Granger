#!/usr/bin/env python3
"""Builds Granger's own ZIM packs from public-domain Project Gutenberg texts.

  pack_builder.py validate --manifest classics/manifest.json
  pack_builder.py build    --manifest classics/manifest.json --out-dir ~/granger-build [--date YYYY-MM-DD]
  pack_builder.py describe ~/granger-build/granger_classics_en_<date>.zim --url <release url> \\
                           --out releases/granger_classics_en.json
  pack_builder.py build-epubs --manifest classics/manifest.json --out-dir ~/granger-build/classics-epub [--date D]
  pack_builder.py describe-epubs ~/granger-build/classics-epub --release-url-base <release download url> \\
                           --manifest classics/manifest.json     # writes releases/granger_classics_books.json

Clásicos Granger uses classics/manifest_es.json and --out releases/clasicos_granger_es_books.json.

`build` validates first and stops on any error. `describe` writes the release descriptor that
tools/catalog-gen turns into a catalog entry. Sources are cached in ~/.cache/granger/gutenberg/, and the
eBible Bibles (`"source": "ebible"` rows, converted by usfm.py) in ~/.cache/granger/ebible/; Standard Ebooks
(standardebooks.py) in ~/.cache/granger/standardebooks/ and Wikisource (wikisource.py) in
~/.cache/granger/wikisource/. `build-epubs --only slug,slug` builds just those works (a trial build).
"""
import argparse
import dataclasses
import datetime
import hashlib
import json
import sys
from pathlib import Path

from libzim.reader import Archive

import chapters
import compiled
import covers
import drama
import editions
import epubpack
import gutenberg
import manifest as manifests
import standardebooks
import textfix
import usfm
import verse
import wikisource
import zimpack
from manifest import Manifest, PartSpec, WorkSpec
from sources import (EBibleSource, GutenbergSource, StandardEbooksSource, WikisourceSource, ebible_details_url,
                     se_page_url, wikisource_url)


def _ebible_work(spec: WorkSpec, ebible: EBibleSource, language: str = "en") -> zimpack.Work:
    pages, skipped, unknown = usfm.convert(ebible.books(spec.ebible),
                                           zimpack.chapter_budget(spec.title, usfm.CSS, language))
    print(f"{spec.ebible:>8} {spec.slug:<40} {len(pages):>4} chapters  skipped {', '.join(skipped) or 'nothing'}")
    if unknown:
        print(f"WARNING {spec.where}: USFM markers dropped, text kept: {dict(unknown)}")
    return zimpack.Work(slug=spec.slug, title=spec.title, author=spec.author, translator=spec.translator,
                        published=spec.published, section=spec.section, chapters=pages, styles=usfm.CSS,
                        verse=spec.reads_as_verse, title_overrides=spec.title_overrides, language=language)


def _edit(spec: WorkSpec, body) -> None:
    """Granger's own edition of the text, when the row asks for one: the name map. (The editor's note goes in at
    write time, on the first page and in the colophon, so it can't change how the book splits into pages.)"""
    if spec.rename:
        names = editions.load_names(spec.rename["names"])
        start, stop = spec.rename.get("from"), spec.rename.get("to")
        counts = editions.rename(body, names, start, stop)
        left = editions.remaining(body, names, start, stop)
        if left:
            raise ValueError(f"names left after renaming: {dict(left)}")
        print(f"{spec.slug}: {sum(counts.values())} names given as {spec.rename['names']} "
              f"({', '.join(f'{k} {n}' for k, n in counts.most_common())})")


def _cleaned(spec: WorkSpec, source: GutenbergSource, se: StandardEbooksSource | None = None,
             ws: WikisourceSource | None = None) -> gutenberg.Cleaned:
    """The work's text as one cleaned body with its styles, whichever source it comes from (not eBible)."""
    if spec.source == "standardebooks":
        book = standardebooks.read((se or StandardEbooksSource()).epub(spec.se))
        return book.cleaned
    if spec.source == "wikisource":
        return wikisource.read(ws or WikisourceSource(), spec.wikisource, spec.ws_skip).cleaned
    if spec.source == "compiled":
        return _compiled(spec, source, ws)
    try:
        return gutenberg.clean(source.html(spec.gutenberg), spec.cut_after)
    except gutenberg.CleaningError as error:
        raise type(error)(f"{spec.slug} (#{spec.gutenberg}): {error}") from error


def _compiled(spec: WorkSpec, source: GutenbergSource, ws: WikisourceSource | None) -> gutenberg.Cleaned:
    styles: dict[str, None] = {compiled.CSS: None}

    def body(part: PartSpec):
        if part.source == "gutenberg":
            cleaned = gutenberg.clean(source.html(part.gutenberg))
            styles.setdefault(cleaned.styles, None)
            return compiled.select(cleaned.body, part.select) if part.select else cleaned.body
        if part.source == "wikisource":
            cleaned = wikisource.read(ws or WikisourceSource(), part.wikisource, part.ws_skip).cleaned
            styles.setdefault(cleaned.styles, None)
            return cleaned.body
        if part.source == "text":
            return compiled.text_part(part.html)
        return None

    parts = [compiled.Part(p.title, body(p), [compiled.Part(q.title, body(q)) for q in p.pages])
             for p in spec.parts]
    return gutenberg.Cleaned(body=compiled.assemble(parts), styles="\n".join(s for s in styles if s),
                             branding_removed=0)


def build(m: Manifest, source: GutenbergSource, out: Path, date: str, ebible: EBibleSource | None = None) -> Path:
    works = []
    for spec in m.works:
        if spec.source == "ebible":
            ebible = ebible or EBibleSource()
            work = _ebible_work(spec, ebible, m.code)
            works.append(dataclasses.replace(work, styles=work.styles + "\n" + verse.CSS))
            continue
        cleaned = _cleaned(spec, source)
        _edit(spec, cleaned.body)
        for notice in gutenberg.later_edition_notices(cleaned.body):
            print(f"WARNING {spec.slug} (#{spec.gutenberg}): later edition? {notice[:160]}")
        restructure(spec, cleaned.body, m.code)
        pages = chapters.split(cleaned.body, spec.split_at,
                               zimpack.chapter_budget(spec.title, cleaned.styles, m.code), pack=spec.pack_sections)
        print(f"{_label(spec):>6} {spec.slug:<40} {len(pages):>4} chapters  {cleaned.branding_removed} branding blocks")
        works.append(zimpack.Work(slug=spec.slug, title=spec.title, author=spec.author, translator=spec.translator,
                                  published=spec.published, section=spec.section, chapters=pages,
                                  styles=cleaned.styles, language=m.code))
    zim = Path(out) / f"{m.name}_{date}.zim"
    info = zimpack.PackInfo(name=m.name, title=m.title, description=m.description, language=m.language,
                            date=date, sections=m.sections)
    zimpack.write_zim(zim, info, works)
    print(f"{zim}: {zim.stat().st_size / 1e6:.1f} MB, {len(works)} works")
    return zim


class BuildError(Exception):
    """A work failed to build; the message starts with its slug and source id."""


def restructure(spec: WorkSpec, body, language: str = "en") -> None:
    """Structure the source only shows by typography, put back before the chapter split so headings it promotes
    drive the split and the contents: a text file's paragraphs, chapters and verse numbers (textfix.py), and a
    play's speeches, directions, acts and scenes (drama.py)."""
    if spec.textfix:
        fixed = textfix.restore(body)
        if fixed["split"] or fixed["joined"]:
            print(f"{'':>6} {spec.slug:<40} {fixed['split']} hidden paragraph starts restored, "
                  f"{fixed['joined']} page-split paragraphs joined")
    if spec.labels == "chapter-verse":
        promoted = textfix.promote(body)
        print(f"{'':>6} {spec.slug:<40} {promoted['chapters']} chapter labels, {promoted['verses']} verse numbers")
    if spec.verse_refs == "split":
        print(f"{'':>6} {spec.slug:<40} {textfix.verse_refs(body)} verse references set apart")
    marks = textfix.section_marks(body), textfix.note_brackets(body)
    if any(marks):
        print(f"{'':>6} {spec.slug:<40} {marks[0]} section numbers dropped, {marks[1]} note marks raised")
    if spec.note_markers:
        print(f"{'':>6} {spec.slug:<40} {textfix.note_markers(body, spec.note_markers)} note marks set apart")
    if spec.is_drama and language == "en":
        report = drama.structure(body, drama.Hints.parse(spec.drama))
        if report.skipped:
            print(f"{'':>6} {spec.slug:<40} drama pass skipped: {report.skipped}")
        else:
            print(f"{'':>6} {spec.slug:<40} {report.speeches} speeches, {report.directions} directions, "
                  f"{report.headings} act/scene headings, {len(report.cast)} speakers")


def _label(spec: WorkSpec) -> str:
    return {"standardebooks": "se", "wikisource": "ws", "compiled": "parts"}.get(spec.source, str(spec.gutenberg))


def _work(spec, source, ebible, language: str = "en", se=None, ws=None) -> zimpack.Work:
    if spec.source == "ebible":
        return _ebible_work(spec, ebible, language)
    cleaned = _cleaned(spec, source, se, ws)
    _edit(spec, cleaned.body)
    for notice in gutenberg.later_edition_notices(cleaned.body):
        print(f"WARNING {spec.where}: later edition? {notice[:160]}")
    restructure(spec, cleaned.body, language)
    pages = chapters.split(cleaned.body, spec.split_at, zimpack.chapter_budget(spec.title, cleaned.styles, language),
                           page_href=epubpack.chapter_href, pack=spec.pack_sections)
    print(f"{_label(spec):>6} {spec.slug:<40} {len(pages):>4} chapters  {cleaned.branding_removed} branding blocks")
    return zimpack.Work(slug=spec.slug, title=spec.title, author=spec.author, translator=spec.translator,
                        published=spec.published, section=spec.section, chapters=pages, styles=cleaned.styles,
                        line_numbers=spec.line_numbers, verse=spec.reads_as_verse,
                        title_overrides=spec.title_overrides, language=language,
                        drama=spec.is_drama and language == "en")


def _source_url(spec: WorkSpec) -> str:
    if spec.source == "ebible":
        return ebible_details_url(spec.ebible)
    if spec.source == "standardebooks":
        return se_page_url(spec.se)
    if spec.source == "wikisource":
        return wikisource_url(spec.wikisource)
    if spec.source == "compiled":
        first = spec.parts[0]
        return (wikisource_url(first.wikisource) if first.source == "wikisource"
                else f"https://www.gutenberg.org/ebooks/{first.gutenberg}")
    return f"https://www.gutenberg.org/ebooks/{spec.gutenberg}"


def _part_line(part: PartSpec, words: dict) -> str:
    if part.source == "gutenberg":
        return words["from_gutenberg"].format(id=part.gutenberg)
    if part.source == "wikisource":
        return words["from_wikisource"].format(url=wikisource_url(part.wikisource))
    if part.source == "text":
        return part.licence or ""
    return ""


def _source_line(spec: WorkSpec, language: str = "en") -> str:
    """The colophon's credit for where the text came from, in the book's language (a line per source)."""
    words = epubpack.labels(language)
    if spec.source == "ebible":
        return words["from_ebible"].format(id=spec.ebible)
    if spec.source == "standardebooks":
        return words["from_standardebooks"].format(url=se_page_url(spec.se))
    if spec.source == "wikisource":
        return words["from_wikisource"].format(url=wikisource_url(spec.wikisource))
    if spec.source == "compiled":
        lines = []
        for part in spec.parts:
            line = _part_line(part, words) if part.source != "pages" else ""
            if part.source == "pages":
                sources = [_part_line(page, words) for page in part.pages]
                ws_pages = [p for p in part.pages if p.source == "wikisource"]
                if ws_pages:
                    lines.append(f"{part.title}: " + words["from_wikisource_pages"].format(count=len(ws_pages)))
                lines += [f"{part.title}: {s}" for p, s in zip(part.pages, sources) if p.source != "wikisource"]
            elif line:
                lines.append(f"{part.title}: {line}")
        return "\n".join(lines)
    return words["from_gutenberg"].format(id=spec.gutenberg)


def build_epubs(m: Manifest, source: GutenbergSource, out: Path, date: str,
                ebible: EBibleSource | None = None, se: StandardEbooksSource | None = None,
                ws: WikisourceSource | None = None, only: set[str] | None = None) -> list[Path]:
    """One EPUB 3 per work: `<out>/<prefix><slug>_<date>.epub`. Any failure names the work. `only` limits the build
    to those slugs."""
    sections = dict(m.sections)
    files = []
    for spec in m.works:
        if only is not None and spec.slug not in only:
            continue
        if spec.source == "ebible":
            ebible = ebible or EBibleSource()
        try:
            work = _work(spec, source, ebible, m.code, se, ws)
            meta = epubpack.BookMeta(
                id=epubpack.book_id(spec.slug, m.book_id_prefix), title=epubpack.display_title(work, m.code),
                author=spec.author, translator=spec.translator, published=spec.published,
                section_title=sections[spec.section], language=m.code, source_url=_source_url(spec), date=date,
                source_line=_source_line(spec, m.code), collection=m.title, editor_note=spec.editor_note)
            path = Path(out) / f"{meta.id}_{date}.epub"
            cover = covers.cover_jpeg(spec.title, spec.author, spec.translator, spec.section, sections[spec.section],
                                      wordmark=m.title.upper(),
                                      translated_by=epubpack.labels(m.code)["translated_by"].lower(), language=m.code)
            epubpack.write_epub(path, work, meta, cover)
        except Exception as error:
            raise BuildError(f"{spec.where}: {error}") from error
        files.append(path)
    return files


def describe_epubs(directory: Path, url_base: str, m: Manifest) -> dict:
    """The release descriptor for the EPUBs `build_epubs` wrote into `directory`."""
    sections = dict(m.sections)
    books, dates = [], set()
    for spec in m.works:
        book = epubpack.book_id(spec.slug, m.book_id_prefix)
        found = sorted(Path(directory).glob(f"{book}_????-??-??.epub"))
        if len(found) != 1:
            raise SystemExit(f"{book}: expected one EPUB in {directory}, found {len(found)}")
        path = found[0]
        dates.add(path.stem.rsplit("_", 1)[1])
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        title = epubpack.display_title(zimpack.Work(spec.slug, spec.title, spec.author, spec.translator,
                                                    spec.published, spec.section, []), m.code)
        entry = {"id": book, "title": title, "author": spec.author, "translator": spec.translator,
                 "published": spec.published, "section": sections[spec.section], "language": m.code,
                 "sizeBytes": path.stat().st_size, "sha256": digest,
                 "urls": [f"{url_base.rstrip('/')}/{path.name}"]}
        if m.domains and spec.section in m.domains:  # read by catalog-gen; the English manifest names none
            entry["domain"] = m.domains[spec.section]
        if spec.learner:
            entry["learner"] = True
        books.append(entry)
    if len(dates) != 1:
        raise SystemExit(f"EPUBs from several build dates: {sorted(dates)}")
    return {"releaseDate": dates.pop(), "collection": m.collection, "books": books}


def describe(zim: Path, url: str) -> dict:
    archive = Archive(zim)
    digest = hashlib.sha256()
    with open(zim, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)

    def meta(name):
        return archive.get_metadata(name).decode()

    return {
        "id": meta("Name"),
        "description": meta("Description"),
        "language": meta("Language"),
        "sizeBytes": zim.stat().st_size,
        "sha256": digest.hexdigest(),
        "urls": [url],
        "snapshotDate": meta("Date"),
        "hasFulltextIndex": archive.has_fulltext_index,
        "zimUuid": str(archive.uuid),
        "zimName": meta("Name"),
    }


def _se_record(se: StandardEbooksSource, se_id: str) -> manifests.SeRecord:
    if standardebooks.is_placeholder(se.page(se_id)):
        return manifests.SeRecord(placeholder=True)
    book = standardebooks.read(se.epub(se_id))
    return manifests.SeRecord(placeholder=False, title=book.title, rights=book.rights, years=book.years)


def _validate(m: Manifest, source: GutenbergSource, ebible: EBibleSource,
              se: StandardEbooksSource | None = None, ws: WikisourceSource | None = None,
              only: set[str] | None = None) -> list[str]:
    se, ws = se or StandardEbooksSource(), ws or WikisourceSource()
    if only is not None:
        m = dataclasses.replace(m, works=[w for w in m.works if w.slug in only])
    return manifests.validate(m, lambda book_id: manifests.parse_rdf(source.rdf(book_id)), ebible.details,
                              lambda se_id: _se_record(se, se_id),
                              lambda title: wikisource.licence(ws.parse(title)))


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("validate"); p.add_argument("--manifest", required=True)
    p = sub.add_parser("build"); p.add_argument("--manifest", required=True); p.add_argument("--out-dir", required=True)
    p.add_argument("--date", default=datetime.date.today().isoformat())
    p = sub.add_parser("build-epubs"); p.add_argument("--manifest", required=True); p.add_argument("--out-dir", required=True)
    p.add_argument("--date", default=datetime.date.today().isoformat())
    p.add_argument("--only", help="comma-separated slugs: validate and build only these (a trial build)")
    p = sub.add_parser("describe-epubs"); p.add_argument("directory"); p.add_argument("--release-url-base", required=True)
    p.add_argument("--manifest", required=True)
    p.add_argument("--out", default=str(Path(__file__).parent / "releases" / "granger_classics_books.json"))
    p = sub.add_parser("describe"); p.add_argument("zim"); p.add_argument("--url", required=True)
    p.add_argument("--out", required=True)
    args = parser.parse_args(argv)

    if args.command in ("validate", "build", "build-epubs"):
        m, source, ebible = manifests.load(Path(args.manifest)), GutenbergSource(), EBibleSource()
        only = set(args.only.split(",")) if getattr(args, "only", None) else None
        if only and only - {w.slug for w in m.works}:
            raise SystemExit(f"--only names unknown slugs: {sorted(only - {w.slug for w in m.works})}")
        errors = _validate(m, source, ebible, only=only)
        for error in errors:
            print(error, file=sys.stderr)
        print(f"{args.manifest}: {len(m.works)} works, {len(errors)} errors")
        if errors:
            raise SystemExit(1)
        if args.command in ("build", "build-epubs"):
            out_dir = Path(args.out_dir).expanduser()
            out_dir.mkdir(parents=True, exist_ok=True)
            if args.command == "build":
                build(m, source, out_dir, args.date, ebible)
            else:
                files = build_epubs(m, source, out_dir, args.date, ebible, only=only)
                print(f"{out_dir}: {len(files)} EPUBs, {sum(f.stat().st_size for f in files) / 1e6:.1f} MB")
    elif args.command == "describe-epubs":
        descriptor = describe_epubs(Path(args.directory).expanduser(), args.release_url_base,
                                    manifests.load(Path(args.manifest)))
        Path(args.out).write_text(json.dumps(descriptor, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"wrote {args.out}: {len(descriptor['books'])} books")
    elif args.command == "describe":
        descriptor = describe(Path(args.zim).expanduser(), args.url)
        Path(args.out).write_text(json.dumps(descriptor, indent=2) + "\n", encoding="utf-8")
        print(f"wrote {args.out}")


if __name__ == "__main__":
    main(sys.argv[1:])
