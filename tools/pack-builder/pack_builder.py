#!/usr/bin/env python3
"""Builds Granger's own ZIM packs from public-domain Project Gutenberg texts.

  pack_builder.py validate --manifest classics/manifest.json
  pack_builder.py build    --manifest classics/manifest.json --out-dir ~/granger-build [--date YYYY-MM-DD]
  pack_builder.py describe ~/granger-build/granger_classics_en_<date>.zim --url <release url> \\
                           --out releases/granger_classics_en.json

`build` validates first and stops on any error. `describe` writes the release descriptor that
tools/catalog-gen turns into a catalog entry. Sources are cached in ~/.cache/granger/gutenberg/.
"""
import argparse
import datetime
import hashlib
import json
import sys
from pathlib import Path

from libzim.reader import Archive

import chapters
import gutenberg
import manifest as manifests
import zimpack
from manifest import Manifest
from sources import GutenbergSource


def build(m: Manifest, source: GutenbergSource, out: Path, date: str) -> Path:
    works = []
    for spec in m.works:
        try:
            cleaned = gutenberg.clean(source.html(spec.gutenberg))
        except gutenberg.NotAGutenbergBook as error:
            raise gutenberg.NotAGutenbergBook(f"{spec.slug} (#{spec.gutenberg}): {error}") from error
        pages = chapters.split(cleaned.body, spec.split_at)
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


def _validate(m: Manifest, source: GutenbergSource) -> list[str]:
    return manifests.validate(m, lambda book_id: manifests.parse_rdf(source.rdf(book_id)))


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("validate"); p.add_argument("--manifest", required=True)
    p = sub.add_parser("build"); p.add_argument("--manifest", required=True); p.add_argument("--out-dir", required=True)
    p.add_argument("--date", default=datetime.date.today().isoformat())
    p = sub.add_parser("describe"); p.add_argument("zim"); p.add_argument("--url", required=True)
    p.add_argument("--out", required=True)
    args = parser.parse_args(argv)

    if args.command in ("validate", "build"):
        m, source = manifests.load(Path(args.manifest)), GutenbergSource()
        errors = _validate(m, source)
        for error in errors:
            print(error, file=sys.stderr)
        print(f"{args.manifest}: {len(m.works)} works, {len(errors)} errors")
        if errors:
            raise SystemExit(1)
        if args.command == "build":
            out_dir = Path(args.out_dir).expanduser()
            out_dir.mkdir(parents=True, exist_ok=True)
            build(m, source, out_dir, args.date)
    elif args.command == "describe":
        descriptor = describe(Path(args.zim).expanduser(), args.url)
        Path(args.out).write_text(json.dumps(descriptor, indent=2) + "\n", encoding="utf-8")
        print(f"wrote {args.out}")


if __name__ == "__main__":
    main(sys.argv[1:])
