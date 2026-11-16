# `.gbooks`: the book manifest sidecar (Plan KB2, schema 1)

A `.gbooks` file says which entries of one Kiwix pack are books and how to show them as a collection. It is a
read-only SQLite file, named after its source (`<zim stem>.gbooks`, e.g. `irp.fas.org_en_military-medicine_2026-10.gbooks`).
It keeps the sidecar contract of the Linked Library spec §5.1 (`tools/sidecar/meta.py`), so the catalog, the hold-back
rule and the upkeep loop treat it like `.gqid` and `.glink`. It is valid for the exact ZIM whose sha256 is in
`meta.sourceSha256`.

Built by `tools/bookpacks/build.py` from range reads (nothing downloaded whole), with each pack's settings in
`book_packs.json`. Checked by `tools/bookpacks/check.py`.

## Tables

```sql
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
-- kind=gbooks, schemaVersion=1, sourceZim, sourceSha256, builder, builderVersion, builtAt (the contract)

CREATE TABLE collection(key TEXT PRIMARY KEY, value TEXT NOT NULL);
-- title, noun ("books" | "documents"), provider, licence, shelf ("literature" | "children" | "manuals" | "learning"),
-- sourceDate (the ZIM's Date), zimName

CREATE TABLE groups(name TEXT PRIMARY KEY, ord INTEGER NOT NULL);
-- section headers in order ("Level 1", "Firearms Manuals", "The book"); items with group_name '' come first

CREATE TABLE items(
  key TEXT PRIMARY KEY,      -- stable across snapshots: a Kolibri node id, or the path without scheme or volatile query
  path TEXT NOT NULL,        -- the entry's path in the ZIM (namespace C), as libzim finds it
  format TEXT NOT NULL,      -- pdf | epub
  title TEXT NOT NULL,
  title_source TEXT NOT NULL,-- override | path | link | block | page | pdf | heading | filename (filename = weakest)
  author TEXT, description TEXT, licence TEXT,
  language TEXT,             -- BCP-47 where known ("en", "sw"), else the source's own name ("Lusoga")
  group_name TEXT NOT NULL DEFAULT '',
  ord INTEGER NOT NULL,      -- order within the collection
  bytes INTEGER NOT NULL,    -- the entry's size (exact in shared clusters, the cluster's size otherwise)
  pdf_kind TEXT,             -- text | scan | unknown; null for an EPUB. The phone's PdfOrigin decides when unknown
  part_of TEXT,              -- key of the whole file this one is a part of
  work_key TEXT, work_title TEXT, part_no INTEGER,  -- parts of a work with no whole file: one row with parts
  qid TEXT                   -- the work's Wikidata QID, when known (Linked Library: "In your books", Related)
);
```

## Reading it on the phone (Plan KB2 Phase 2)

- Book id: `book:kiwix:<zimName>/<key>`. Places, marks and tabs survive a new snapshot as long as the key stays.
- One row per item, except:
  - items with `part_of` sit under their whole;
  - items sharing a `work_key` show as one row titled `work_title`, with parts in `part_no` order.
- Sections come from `groups` in `ord` order. A collection with more than one `language` gets a language chip.

## Changing the schema

Add columns freely; a phone ignores what it doesn't read. Bump `schemaVersion` (and the app's
`SidecarEntry.KNOWN["gbooks"]`) only when a phone reading the old layout would show something wrong.
