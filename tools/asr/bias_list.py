"""Plan A0: the biasing lists the replay passes to the recognizer, from a copy of the phone's library.db.

    adb exec-out run-as app.granger cat databases/library.db > /some/dir/library.db   (and -wal, -shm beside it)
    python3 tools/asr/bias_list.py /some/dir/library.db tools/asr/out

writes bias-library.json (pack, book, author, album and artist names; short forms, no duplicates) and
bias-names.json (the 20 names in phrases.json). Push both to files/asr/ on the phone.
"""
import json
import re
import sqlite3
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent


def short_title(title: str) -> str:
    """The part of a title a person says: no subtitle, no bracketed translator, no file-name underscores."""
    t = title.replace("_", " ")
    t = re.split(r"\s*[:(\[]|\s+[—–-]\s+|\s+/\s+", t, maxsplit=1)[0]
    return re.sub(r"\s+", " ", t).strip(" .;,")


def short_author(author: str) -> str:
    """'George T. (George Titus) Ferris' → 'George T. Ferris'."""
    return re.sub(r"\s+", " ", re.sub(r"\([^)]*\)", "", author)).strip(" .;,")


def library_strings(db: Path) -> list[str]:
    con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    rows = []
    rows += [short_title(t) for (t,) in con.execute("select title from packs")]
    rows += [short_title(t) for (t,) in con.execute("select title from books")]
    rows += [short_author(a) for (a,) in con.execute("select author from books")]
    rows += [short_title(t) for (t,) in con.execute("select distinct album from tracks")]
    for (artists,) in con.execute("select distinct artists from tracks"):
        rows += [short_author(a) for a in re.split(r"[;,]", artists)]
    seen, out = set(), []
    for r in rows:
        k = r.lower()
        if len(r) >= 3 and k not in seen and k not in {"various", "unknown"} and not re.fullmatch(r"[\d\s\W]+", r):
            seen.add(k)
            out.append(r)
    return out


def main() -> None:
    db, out_dir = Path(sys.argv[1]), Path(sys.argv[2])
    out_dir.mkdir(parents=True, exist_ok=True)
    phrases = json.loads((HERE / "phrases.json").read_text())["phrases"]
    names = [p["name"] for p in phrases if p["kind"] == "name"]
    library = library_strings(db)
    for n in names:  # the names are in the library list too, as a person would say them
        if n.lower() not in {s.lower() for s in library}:
            library.append(n)
    (out_dir / "bias-library.json").write_text(json.dumps(library, ensure_ascii=False, indent=0))
    (out_dir / "bias-names.json").write_text(json.dumps(names, ensure_ascii=False, indent=0))
    print(f"bias-library.json {len(library)} strings, bias-names.json {len(names)}")


if __name__ == "__main__":
    main()
