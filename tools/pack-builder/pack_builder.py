#!/usr/bin/env python3
"""Builds Granger's own ZIM packs from public-domain Project Gutenberg texts.

  pack_builder.py validate --manifest classics/manifest.json
  pack_builder.py build    --manifest classics/manifest.json --out-dir ~/granger-build [--date YYYY-MM-DD]
  pack_builder.py describe ~/granger-build/granger_classics_en_<date>.zim --url <release url> \\
                           --out releases/granger_classics_en.json
  pack_builder.py build-epubs --manifest classics/manifest.json --out-dir ~/granger-build/classics-epub [--date D]
  pack_builder.py describe-epubs ~/granger-build/classics-epub --release-url-base <release download url> \\
                           --manifest classics/manifest.json     # writes releases/granger_classics_books.json

`build` validates first and stops on any error. `describe` writes the release descriptor that
tools/catalog-gen turns into a catalog entry. Sources are cached in ~/.cache/granger/gutenberg/, and the
eBible Bibles (`"source": "ebible"` rows, converted by usfm.py) in ~/.cache/granger/ebible/.
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
import covers
import epubpack
import gutenberg
import manifest as manifests
import usfm
import verse
import zimpack
from manifest import Manifest, WorkSpec
from sources import EBibleSource, GutenbergSource, ebible_details_url


def _ebible_work(spec: WorkSpec, ebible: EBibleSource) -> zimpack.Work:
    pages, skipped, unknown = usfm.convert(ebible.books(spec.ebible), zimpack.chapter_budget(spec.title, usfm.CSS))
    print(f"{spec.ebible:>8} {spec.slug:<40} {len(pages):>4} chapters  skipped {', '.join(skipped) or 'nothing'}")
    if unknown:
        print(f"WARNING {spec.where}: USFM markers dropped, text kept: {dict(unknown)}")
    return zimpack.Work(slug=spec.slug, title=spec.title, author=spec.author, translator=spec.translator,
                        published=spec.published, section=spec.section, chapters=pages, styles=usfm.CSS,
                        verse=spec.reads_as_verse, title_overrides=spec.title_overrides)


def build(m: Manifest, source: GutenbergSource, out: Path, date: str, ebible: EBibleSource | None = None) -> Path:
    works = []
    for spec in m.works:
        if spec.source == "ebible":
            ebible = ebible or EBibleSource()
            work = _ebible_work(spec, ebible)
            works.append(dataclasses.replace(work, styles=work.styles + "\n" + verse.CSS))
            continue
        try:
            cleaned = gutenberg.clean(source.html(spec.gutenberg), spec.cut_after)
        except gutenberg.CleaningError as error:
            raise type(error)(f"{spec.slug} (#{spec.gutenberg}): {error}") from error
        for notice in gutenberg.later_edition_notices(cleaned.body):
            print(f"WARNING {spec.slug} (#{spec.gutenberg}): later edition? {notice[:160]}")
        pages = chapters.split(cleaned.body, spec.split_at, zimpack.chapter_budget(spec.title, cleaned.styles),
                               pack=spec.pack_sections)
        print(f"{spec.gutenberg:>6} {spec.slug:<40} {len(pages):>4} chapters  {cleaned.branding_removed} branding blocks")
        works.append(zimpack.Work(slug=spec.slug, title=spec.title, author=spec.author, translator=spec.translator,
                                  published=spec.published, section=spec.section, chapters=pages,
                                  styles=cleaned.styles))
    zim = Path(out) / f"{m.name}_{date}.zim"
    info = zimpack.PackInfo(name=m.name, title=m.title, description=m.description, language=m.language,
                            date=date, sections=m.sections)
    zimpack.write_zim(zim, info, works)
    print(f"{zim}: {zim.stat().st_size / 1e6:.1f} MB, {len(works)} works")
    return zim


class BuildError(Exception):
    """A work failed to build; the message starts with its slug and source id."""


def _work(spec, source, ebible) -> zimpack.Work:
    if spec.source == "ebible":
        return _ebible_work(spec, ebible)
    cleaned = gutenberg.clean(source.html(spec.gutenberg), spec.cut_after)
    for notice in gutenberg.later_edition_notices(cleaned.body):
        print(f"WARNING {spec.slug} (#{spec.gutenberg}): later edition? {notice[:160]}")
    pages = chapters.split(cleaned.body, spec.split_at, zimpack.chapter_budget(spec.title, cleaned.styles),
                           page_href=epubpack.chapter_href, pack=spec.pack_sections)
    print(f"{spec.gutenberg:>6} {spec.slug:<40} {len(pages):>4} chapters  {cleaned.branding_removed} branding blocks")
    return zimpack.Work(slug=spec.slug, title=spec.title, author=spec.author, translator=spec.translator,
                        published=spec.published, section=spec.section, chapters=pages, styles=cleaned.styles,
                        line_numbers=spec.line_numbers, verse=spec.reads_as_verse,
                        title_overrides=spec.title_overrides)


def _source_url(spec: WorkSpec) -> str:
    if spec.source == "ebible":
        return ebible_details_url(spec.ebible)
    return f"https://www.gutenberg.org/ebooks/{spec.gutenberg}"


def _source_line(spec: WorkSpec) -> str:
    if spec.source == "ebible":
        return f"From eBible.org ({spec.ebible})"
    return f"From Project Gutenberg ebook #{spec.gutenberg}"


def build_epubs(m: Manifest, source: GutenbergSource, out: Path, date: str,
                ebible: EBibleSource | None = None) -> list[Path]:
    """One EPUB 3 per work: `<out>/gc-<slug>_<date>.epub`. Any failure names the work."""
    sections = dict(m.sections)
    files = []
    for spec in m.works:
        if spec.source == "ebible":
            ebible = ebible or EBibleSource()
        try:
            work = _work(spec, source, ebible)
            meta = epubpack.BookMeta(
                id=epubpack.book_id(spec.slug), title=epubpack.display_title(work), author=spec.author,
                translator=spec.translator, published=spec.published, section_title=sections[spec.section],
                language="en", source_url=_source_url(spec), date=date, source_line=_source_line(spec))
            path = Path(out) / f"{meta.id}_{date}.epub"
            cover = covers.cover_jpeg(spec.title, spec.author, spec.translator, spec.section, sections[spec.section])
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
        book = epubpack.book_id(spec.slug)
        found = sorted(Path(directory).glob(f"{book}_????-??-??.epub"))
        if len(found) != 1:
            raise SystemExit(f"{book}: expected one EPUB in {directory}, found {len(found)}")
        path = found[0]
        dates.add(path.stem.rsplit("_", 1)[1])
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        title = epubpack.display_title(zimpack.Work(spec.slug, spec.title, spec.author, spec.translator,
                                                    spec.published, spec.section, []))
        books.append({"id": book, "title": title, "author": spec.author, "translator": spec.translator,
                      "published": spec.published, "section": sections[spec.section], "language": "en",
                      "sizeBytes": path.stat().st_size, "sha256": digest,
                      "urls": [f"{url_base.rstrip('/')}/{path.name}"]})
    if len(dates) != 1:
        raise SystemExit(f"EPUBs from several build dates: {sorted(dates)}")
    return {"releaseDate": dates.pop(), "collection": "granger-classics", "books": books}


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


def _validate(m: Manifest, source: GutenbergSource, ebible: EBibleSource) -> list[str]:
    return manifests.validate(m, lambda book_id: manifests.parse_rdf(source.rdf(book_id)), ebible.details)


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("validate"); p.add_argument("--manifest", required=True)
    p = sub.add_parser("build"); p.add_argument("--manifest", required=True); p.add_argument("--out-dir", required=True)
    p.add_argument("--date", default=datetime.date.today().isoformat())
    p = sub.add_parser("build-epubs"); p.add_argument("--manifest", required=True); p.add_argument("--out-dir", required=True)
    p.add_argument("--date", default=datetime.date.today().isoformat())
    p = sub.add_parser("describe-epubs"); p.add_argument("directory"); p.add_argument("--release-url-base", required=True)
    p.add_argument("--manifest", required=True)
    p.add_argument("--out", default=str(Path(__file__).parent / "releases" / "granger_classics_books.json"))
    p = sub.add_parser("describe"); p.add_argument("zim"); p.add_argument("--url", required=True)
    p.add_argument("--out", required=True)
    args = parser.parse_args(argv)

    if args.command in ("validate", "build", "build-epubs"):
        m, source, ebible = manifests.load(Path(args.manifest)), GutenbergSource(), EBibleSource()
        errors = _validate(m, source, ebible)
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
                files = build_epubs(m, source, out_dir, args.date, ebible)
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
