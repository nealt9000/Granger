"""Plan O6 Task 1: topic records from a small QID index built here (300 articles, so three blocks)."""
import json, os, sqlite3, struct, sys, tempfile, unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import build  # noqa: E402

FACETS = {"history": [{"id": "wars", "en": "Wars", "es": "Guerras", "code": 7}]}


def make_gqid(path, n=300):
    """Articles Q1..Qn (rank = qid). Q10 is a broad topic holding Q20 (kind), Q21 (link) and Q22 (person); Q30 holds
    Q20 too. Q20 has a subject, a room, coordinates, a link, a flag and an other-language title."""
    db = sqlite3.connect(path)
    db.executescript("""
        CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE articles(path TEXT PRIMARY KEY, qid INTEGER NOT NULL, rank INTEGER NOT NULL, other TEXT) WITHOUT ROWID;
        CREATE TABLE subjects(qid INTEGER NOT NULL, ord INTEGER NOT NULL, subject TEXT NOT NULL, PRIMARY KEY(qid, ord)) WITHOUT ROWID;
        CREATE TABLE facets(facet INTEGER NOT NULL, qid INTEGER NOT NULL, PRIMARY KEY(facet, qid)) WITHOUT ROWID;
        CREATE TABLE coords(qid INTEGER PRIMARY KEY, lat REAL, lon REAL);
        CREATE TABLE links(qid INTEGER NOT NULL, prop TEXT NOT NULL, value TEXT NOT NULL);
        CREATE TABLE flags(qid INTEGER PRIMARY KEY, kind INTEGER NOT NULL) WITHOUT ROWID;
        CREATE TABLE topics(qid INTEGER PRIMARY KEY, under INTEGER NOT NULL, inlinks INTEGER NOT NULL);
        CREATE TABLE topic_parts(topic INTEGER, part INTEGER, ord INTEGER, item INTEGER, grp INTEGER, text TEXT, note TEXT);
    """)
    db.execute("INSERT INTO meta VALUES ('facets', ?)", (json.dumps(FACETS),))
    db.executemany("INSERT INTO articles VALUES (?, ?, ?, ?)", [(f"A{q}", q, q, "Otro" if q == 20 else None) for q in range(1, n + 1)])
    db.execute("INSERT INTO subjects VALUES (20, 0, 'history')")
    db.execute("INSERT INTO facets VALUES (7, 20)")
    db.execute("INSERT INTO coords VALUES (20, 51.50001, -0.12)")
    db.execute("INSERT INTO links VALUES (20, 'P50', 'Q5')")
    db.execute("INSERT INTO flags VALUES (20, 1)")
    db.executemany("INSERT INTO topics VALUES (?, ?, ?)", [(10, 40, 900), (30, 5, 12)])
    db.executemany("INSERT INTO topic_parts VALUES (?, ?, ?, ?, ?, ?, ?)", [
        (10, 1, 0, None, 0, "Kinds", ""), (10, 2, 0, 20, 0, "", ""), (10, 4, 0, 21, -1, "", ""), (10, 6, 0, 22, -1, "", ""),
        (30, 2, 0, 20, 0, "", ""),
    ])
    db.commit()
    db.close()


class BuildTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.gqid = os.path.join(self.dir, "wikipedia_xx_test_2026-11.gqid")
        make_gqid(self.gqid)
        self.base = build.build(self.gqid, os.path.join(self.dir, "out"))
        self.reader = build.Reader(self.base)

    def test_directory_header_counts_and_source(self):
        self.assertEqual(self.reader.records, 300)
        self.assertEqual(len(self.reader.blocks), 3)
        self.assertEqual(self.reader.source, build.sha256(self.gqid))
        self.assertEqual([b[0] for b in self.reader.blocks], [1, 129, 257])

    def test_a_plain_article_and_the_block_edges(self):
        for q in (1, 128, 129, 256, 257, 300):
            r = self.reader.get(q)
            self.assertEqual((r["p"], r["r"]), (f"A{q}", q))
        self.assertEqual(set(self.reader.get(5)), {"p", "r"})

    def test_a_missing_qid_is_none(self):
        self.assertIsNone(self.reader.get(301))
        self.assertIsNone(self.reader.get(0))

    def test_everything_an_article_carries(self):
        r = self.reader.get(20)
        self.assertEqual(r["s"], ["history"])
        self.assertEqual(r["f"], ["history.wars"])
        self.assertEqual(r["c"], [51.5, -0.12])
        self.assertEqual(r["l"], [["P50", "Q5"]])
        self.assertEqual(r["x"], 1)
        self.assertEqual(r["o"], "Otro")
        # Held by Q10 and Q30, best known (highest rank) first.
        self.assertEqual(r["h"], [[30, "A30", 30, 0], [10, "A10", 10, 0]])
        self.assertEqual(r["hn"], 2)

    def test_a_broad_topic_carries_its_parts_resolved(self):
        r = self.reader.get(10)
        self.assertEqual(r["t"], [40, 900])
        self.assertEqual(r["parts"][0], [1, None, 0, "Kinds", ""])
        self.assertEqual(r["parts"][1], [2, [20, "A20", 20, 1], 0, "", ""])
        self.assertEqual([p[1][0] for p in r["parts"][1:]], [20, 21, 22])

    def test_a_corrupt_block_is_refused(self):
        _, off, _, _ = self.reader.blocks[1]
        with open(self.base + ".grec", "r+b") as f:
            f.seek(off + 40)
            b = f.read(1)
            f.seek(off + 40)
            f.write(bytes([b[0] ^ 0xFF]))
        with self.assertRaises(ValueError):
            self.reader.get(130)
        self.assertEqual(self.reader.get(1)["p"], "A1")


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--fixture":
        # The app's test fixture (Task 3): the same small index, built into the given folder.
        out = sys.argv[2]
        os.makedirs(out, exist_ok=True)
        g = os.path.join(tempfile.mkdtemp(), "wikipedia_xx_test_2026-11.gqid")
        make_gqid(g)
        build.build(g, out)
    else:
        unittest.main()
