import json
import sqlite3

import media_library


def db(tmp_path):
    f = tmp_path / "library.db"
    c = sqlite3.connect(f)
    c.execute("CREATE TABLE tracks (id TEXT, album TEXT, albumKey TEXT, folder TEXT, audiobookId TEXT, status TEXT, title TEXT)")
    c.execute("CREATE TABLE audiobooks (id TEXT, title TEXT, status TEXT, description TEXT)")
    c.executemany("INSERT INTO tracks VALUES (?,?,?,?,?,?,?)", [
        ("t1", "The Wall", "pink floyd|the wall", "Music/Wall/", None, "OK", "Mother"),
        ("t2", "", "|unknown|Music/Holst - The Planets (LSO 1922-24)/", "Music/Holst - The Planets (LSO 1922-24)/", None, "OK", "Mars"),
        ("t3", "Gone", "x|gone", "Music/Gone/", None, "MISSING", "Lost"),
        ("t4", "Chapter 1", "kipling|jungle", "Audiobooks/Jungle/", "audiobook:PHONE:jungle", "OK", "Mowgli"),
    ])
    c.executemany("INSERT INTO audiobooks VALUES (?,?,?,?)", [
        ("audiobook:PHONE:jungle", "The Jungle Book", "OK", ""), ("audiobook:PHONE:old", "Old", "MISSING", "")])
    c.commit()
    c.close()
    return f


def test_export_lists_present_albums_and_audiobooks_by_key(tmp_path):
    assert media_library.export(db(tmp_path)) == {
        "albums": {"pink floyd|the wall": "The Wall", "|unknown|Music/Holst - The Planets (LSO 1922-24)/": "Holst - The Planets (LSO 1922-24)"},
        "audiobooks": {"audiobook:PHONE:jungle": "The Jungle Book"},
    }


def test_cli_writes_the_export(tmp_path):
    out = tmp_path / "export.json"
    assert media_library.main([str(db(tmp_path)), "-o", str(out)]) == 0
    assert json.loads(out.read_text())["audiobooks"] == {"audiobook:PHONE:jungle": "The Jungle Book"}
