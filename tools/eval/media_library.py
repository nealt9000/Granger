#!/usr/bin/env python3
"""Exports the media in a copy of the phone's library database, for `eval.py validate --media`.

  python3 -I media_library.py <library.db> [-o export.json]

Copy the database off the phone first (with the -wal and -shm beside it, so recent writes are seen), into a new folder:

  for f in library.db library.db-wal library.db-shm; do adb exec-out run-as app.granger cat databases/$f > DIR/$f; done

The export is `{"albums": {albumKey: title}, "audiobooks": {id: title}}`: the present (not MISSING) music albums, keyed as
Research's media cards key them (`album:<albumKey>`), and the present audiobooks by id. An album's title is its tag, else
(an untagged album, grouped by folder) its folder's name, as `Albums.title` shows it in the app.
"""
import argparse
import json
import sqlite3
import sys
from pathlib import Path

UNKNOWN_ALBUM = "Unknown album"


def album_title(album, folder):
    return (album or "").strip() or (folder or "").strip().rstrip("/").rsplit("/", 1)[-1].strip() or UNKNOWN_ALBUM


def export(db_path):
    c = sqlite3.connect("file:%s?mode=ro" % Path(db_path).resolve(), uri=True)
    try:
        albums = {}
        rows = c.execute("SELECT albumKey, album, folder FROM tracks WHERE audiobookId IS NULL AND status != 'MISSING' ORDER BY id")
        for key, album, folder in rows:
            title = album_title(album, folder)
            if key not in albums or (albums[key] == UNKNOWN_ALBUM and title != UNKNOWN_ALBUM):
                albums[key] = title
        books = dict(c.execute("SELECT id, title FROM audiobooks WHERE status != 'MISSING' ORDER BY id"))
    finally:
        c.close()
    return {"albums": albums, "audiobooks": books}


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("db")
    p.add_argument("-o", "--out", help="write the export here (default: print it)")
    a = p.parse_args(argv)
    text = json.dumps(export(a.db), indent=1, ensure_ascii=False, sort_keys=True) + "\n"
    if a.out:
        Path(a.out).write_text(text, encoding="utf-8")
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
