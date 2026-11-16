# Kiwix books, round 2: design (draft, 2026-11-16)

Neal, 2026-11-16: "a significant value-add to the kiwix catalog has been the couple that we re-presented as epubs, where
their zim pages they came from were just clunky containers for pdfs/ebooks that sometimes didn't even properly open in
Granger. Let's do an audit of the greater Kiwix catalog for any other packs that would benefit from presenting them as
books in a collection, then make a plan to update Granger to present them that way, along with a sensible upkeep plan
… Let's also consider any inconsistencies and unintuitive UX this creates along the way, and plan a solution that helps
improve Granger's UX as a whole."

Plan KB (2026-10-30) did this for packs that carry their own book list: 41 `gutenberg2zim` and 9 `nautiluszim`. It
left out the packs that are scraped websites (`zimit`), because the phone "couldn't list a pack's files". This round
picks those up. It also looks past Granger's three languages to the multilingual packs, and settles the upkeep.

## 1. The audit

**Method.** No pack was downloaded. All scripts and data are in `docs/superpowers/spikes/2026-11-16-kiwix-books-audit/`.
- `probe_all.py` read every pack in Kiwix's OPDS feed in en, es, sw or `mul`, including multi-language packs: 1,389 of
  3,655 entries. For each it took the `M/` metadata (Counter, Scraper) over HTTP range reads, with 0 errors.
- `docs_list.py` walked the directory of 35 candidates. For every PDF/EPUB entry it took the path, the ZIM title and
  the bytes in its cluster.
- `linktext.py` and `asb_count.py` read a few HTML pages to see where usable titles live.

**124 packs hold 3 or more PDFs/EPUBs.** 50 of them are the Gutenberg and nautilus packs, already done. Here is the rest,
by how well they would work as books.

### A. Containers: the HTML is only a wrapper around the books (do these)

| Pack | Docs | Size | In catalog | Notes |
|---|---|---|---|---|
| **African Storybook** `africanstorybook.org_mul_all` (Kolibri) | 5,165 EPUB | 8.3 GB | **no** (`mul`) | Levelled picture books for children, CC BY. **693 English, 427 Kiswahili**, ~100 other African languages, French. One HTML page per story gives title, summary, author, licence, language and level. The best find. |
| Military Medicine `irp.fas.org_en_military-medicine` | 24 PDF | 61 MB | yes, Medical | Pure container (2 HTML pages). US military medical manuals. Link text gives titles ("First Aid for Soldiers"). |
| SMBC COVID-19 Book Pack `smbc-comics.com_en_all` | 9 PDF | 107 MB | yes | Pure container: comic books. Link text is "Download PDF", so it needs a hand-written list. |
| Algorithms (Jeff Erickson) `jeffe.cs.illinois.edu_en_all` | 696 PDF | 492 MB | yes, Science | Pure container. The book (whole and by chapter) plus about 650 homework and lab sheets. Two groups: "The book" and "Course materials". |
| Milne Open Textbooks `milneopentextbooks.org_en_all` | 26 PDF | 403 MB | yes, Reference | The HTML is a catalogue of textbooks. The textbooks are the point. |
| Army Publishing `armypubs_en_all` | 5,768 PDF | 7.9 GB (7.2 GB PDF) | yes, How-to | HTML pages are tables of publications. Most files are small orders and memos. Filter to field manuals and training (FM, ATP, TC, TM: about 700 over 1 MB) and group by series. |
| Survivor Library `survivorlibrary.com_en_all` | 11,802 PDF | 210 GB | yes, How-to | Pure container, mostly scans of old books, 194 category pages. Few people will hold it, but it costs nothing extra once the mechanism exists. |

### B. Picture books stored as web pages (later)

- **Bookdash** `booksdash_en_all` (11 GB, in catalog): 750 books, each a web page of page images with an in-page
  reader, in about 11 South African languages. It needs turning into fixed-layout EPUBs on the Mac. Do it after A has
  proved the release path.

### C. Real websites that also hold useful documents (stay web, but show their files)

Ready.gov (401 info sheets), Tom Lehrer's Songs (159 lyric sheets), Wikibooks (293 printable books, 2.9 of 3.4 GB),
Marxists.org es (2,434 PDFs, not listed), CD3WD (77), Energypedia (62), Lua.org (278 workshop slides).
- Their pages are the content, so they stay in Browse.
- A **Files in this pack** list on the pack (§4.7) makes the documents findable without hunting for links.

### D. Sheet music (out of scope, noted)

Mutopia (3,911), Celtic Scores (3,364), Partituras gratis (2,242), Music by Dalitz (441). These are scores, read as
pages, not books. They could become a "Scores" collection type later, using the same manifests.

### E. Stay as they are

The PDFs here repeat the pages or are attachments:
- Cheatography (27k PDF copies of its sheets), World Factbook, MedlinePlus, Encyclopédie de l'environnement,
  Wikiversity, Stacks project, Open Data Structures (the HTML is the book);
- Thales docs, Folgerpedia, Women's History, CDC Travel, LibreTexts, Open Music Theory, and every Wikipedia;
- Wikisource is books written as HTML pages. GC2's Wikisource importer already brings chosen works into the Classics.

**Not in our languages, noted:** Prunelle fr (3 packs, nautilus), BNR ebooks (fr, 7.9 GB), Bibliothèque Saint Libère
(fr, 27 GB), and Moldova's school textbooks (ro). The same mechanism would take them if Granger adds French.

## 2. What made the last round work, and what blocks this one

- Gutenberg and nautilus packs carry a machine-readable list (`full_by_title.js`, `database.js`). The phone reads it and
  makes shelf rows.
- **Scraped sites carry no list, and their PDFs have no titles in the ZIM.** Of 35 candidates, only the two MediaWiki
  packs name their PDFs, and then only with "null". Titles have to be put together from:
  - the page heading around the link;
  - the link text, which is sometimes good ("Recursion", "First Aid for Soldiers") and sometimes useless
    ("Download PDF", "Part 1");
  - the PDF's own Info title;
  - the file name.
  That is judgement work, so it belongs on the Mac, checked once, not repeated on every phone.
- **The phone can list a pack's files after all.** libkiwix 2.6.0, which Granger already ships, has `Archive.iterByPath()`
  and `getEntryByPath(int)`. KB's blocker is a missing wrapper in `ZimArchive`, not a missing capability. That gives a
  fallback for sideloaded packs.

## 3. Design: one mechanism, book manifests

**A book manifest is a small sidecar file per pack snapshot that says which entries are books and how to show them.**
It is built on the Mac by range reads, published like the `.gqid` QID indexes, and attached in the catalog to the
exact ZIM file (sha256).
- It is a new sidecar kind, `gbooks`, in `tools/sidecar/kinds.json`.
- The hold-back rule and the upkeep loop handle it as they handle `.gqid` (`snapshot-held-sidecar`, `rebuild-sidecar`).

```json
{ "kind": "gbooks", "version": 1, "zimName": "irp.fas.org_en_military-medicine", "sourceSha256": "…",
  "collection": { "title": "Military Medicine", "noun": "books", "provider": "Federation of American Scientists",
                  "licence": "…", "groups": [{"key": "handbooks", "title": "Handbooks"}, …] },
  "items": [ { "key": "sfhandbook", "path": "irp.fas.org/doddir/milmed/sfhandbook.pdf", "format": "pdf",
               "title": "Special Forces Medical Handbook", "author": "", "language": "en", "group": "handbooks",
               "order": 1, "bytes": 13265123, "pdfKind": "text|scan|comic", "parts": ["sfhandbook-pt1", …],
               "qid": null, "level": null } ] }
```

- **`key` is stable across snapshots.** It is a Kolibri node id, or a normalised path. The builder reads the previous
  manifest and keeps a book's key when its path moves (matched by title and size). Book ids are
  `book:kiwix:<zimName>/<key>`, so reading places, marks and tabs survive Kiwix updates. d0's note: stable ids, with
  language and QID where known, let these books join the Linked Library's "In your books" and Related later.
- **`parts`** folds "whole book" and "Part 1/2/3" into one row with parts, so the same text isn't listed four times.
- **`pdfKind`** comes from the same `PdfOrigin` test the phone uses. It tells the phone, before it copies anything out,
  whether a PDF can become an ebook (§4.4).
- **Built by extractors per pack shape, not per pack:**
  - `kolibri`: node pages give title, summary, author, licence, language and level from the breadcrumb.
  - `zimit-index`: link text plus nearest heading, the PDF Info title and the file name, ranked, with filters.
  - The existing `nautilus` and `gutenberg` lists keep working on the phone unchanged. They are already manifests in
    all but name.
  - Each pack's settings live in a checked-in `tools/bookpacks/book_packs.json`: extractor, include/exclude
    patterns, group rules, and a hand-written `overrides` map of key → title for small packs like SMBC.
- **Phone fallback, no manifest** (sideloaded, or the snapshot changed before a rebuild): list PDFs/EPUBs with
  `iterByPath`, titled from the file name. The collection header says "Titles from file names".

**African Storybook is distributed differently** (Decision 2). One 8.3 GB ZIM holding every language is a poor download
for the 1.1k books our users can read. Granger would publish per-language sets from it, made on the Mac, as a release
like the Classics:
- "African Storybook · English", about 1.1 GB, 693 books, in levels 1–5;
- "African Storybook · Kiswahili", about 0.7 GB, 427 books.
Each book is a catalog `books` entry with a stable id (`asb-en-<node>`). Get-one-book and Get-all both work, as they
do for the Classics. CC BY allows this with attribution, which goes in each book's colophon and the collection header.
The manifest builder already has every field, so the same extractor feeds both.

## 4. UX: what this exposes, and the fixes

A code survey (2026-11-16) mapped how a book reaches the reader from each of seven sources: Classics, Gutenberg,
nautilus, archive.org, imports, PDFs inside web packs, and an original-pages PDF. Adding more sources would multiply
the differences it found. So this round fixes them as one model:

**A book is a book, whoever made it.** It sits in one collection, opens, can be removed, keeps your place, and
searches the same way. The provider is a line of small print, not a separate world.

1. **Get more → Books is organised by what, not who.**
   - Today the screen is split by source: Granger Classics, Project Gutenberg, Kiwix libraries, Archive.org books.
   - It becomes one Books screen with shelves by kind:
     - Literature (the Classics and Gutenberg subjects);
     - Children's books (African Storybook, Prunelle, Bookdash later);
     - Manuals & references (Military Medicine, Army field manuals, Milne textbooks, the zimgit libraries, Survivor
       Library);
     - Learning (Algorithms and the like);
     - plus Search all books.
   - Each row says where it comes from: "From Kiwix · Project Gutenberg", "Granger Classics", "Archive.org".
   - Archive.org stays a search, under Search all books.
2. **One vocabulary.**
   - "Collection" for any group of books.
   - The count uses the manifest's `noun`: "books", or "documents" for leaflets and orders.
   - The same word is used in Get more, the collection header, the "Make all searchable" dialog and the Remove dialog.
   - This ends today's mismatch: "Kiwix libraries" in Get more, "Kiwix library · documents" in the header, "books" in
     the dialogs.
3. **Open and Remove mean the same everywhere.**
   - Every installed book or collection has Open. Today the Classics and archive.org texts have none, and Downloads →
     Open on a book pack shows its web pages, not the collection.
   - Any single book can be removed. For a pack book this deletes the copied-out file and hides the row, and "Show
     removed" brings it back.
   - Every Remove dialog says the same true thing: your place, bookmarks and highlights are kept. Get more's pack
     dialog currently says the opposite.
   - Removed pack books no longer leave "Removed book" tabs on the rail.
4. **One rule for PDFs.**
   - A PDF that is really text becomes an ebook (flowing text, Listen, in-book search), and its pages are one tap
     away ("Original pages").
   - Scans and comics stay pages.
   - Today this depends on the source: archive.org converts at install, imports and nautilus offer "Read as ebook",
     and pack documents can't convert at all.
   - **Settled by Decision 3:** "Read as ebook" stays a choice, offered for every text PDF from every source. The
     manifest's `pdfKind` (or `PdfOrigin` on the phone) hides it for scans and comics.
   - **Fixes a bug found in the survey (confirmed in code):** "Read as ebook" on a nautilus PDF repoints the row at
     `x.pdf.epub`. A later `PackBooks.restore` then copies the PDF into the `.epub` path and fails, and removing the
     pack leaves the PDF and its pages row behind.
5. **Places survive updates for every pack document.**
   - PDFs opened from inside web packs are keyed by pack UUID (`book:pack:<uuid>/…`), so every Kiwix snapshot update
     deletes their place, marks, tabs and history.
   - Key them by ZIM name as nautilus does, and adopt the old rows (the KB Decision 5 pattern).
6. **Search shows a book once, as a book.**
   - A book pack's ZIM is still searched as a pack, so a Gutenberg book's HTML page can appear as an "Article" hit
     beside its shelf row.
   - Hits from book-pack ZIMs that map to a shelf row show as that book. The rest are dropped.
   - "Searchable in full" becomes the default state and isn't shown. Only the exceptions are marked: "Titles only
     until opened", "A scan: titles only".
7. **Files in this pack.**
   - Every web pack with documents (class C) gets a "Files" row on its pack page.
   - It comes from a manifest where one exists, otherwise from `iterByPath`.
   - Tapping a file opens it as today, but as a proper row (point 5).
8. **Collections list what they hold.**
   - A Gutenberg book held by both a subject pack and `gutenberg_en_all` is filed only under the subject, so "Every
     English book" leaves it out.
   - Membership becomes per pack, not one filing per book.
9. **Small print.**
   - `ebook_book_gone` says "download it again from Granger", even for imports and pack books.
   - `ReaderControls`' note that PDF pages are "found from the library index" isn't true for pack documents.
   - Both are in the LL session's reader files, so hand them over rather than editing them.

## 5. Upkeep

The loop's rules hold: scripts decide, an agent does one checkable job, and the human gates stay human (sign, upload,
push, phone).

| Event | Detected by | Finding kind | Who |
|---|---|---|---|
| A listed book pack gets a new snapshot | `build --previous` (exists) | `snapshot-held-sidecar` until its `.gbooks` is rebuilt | agent runs `rebuild-sidecar` (CPU, range reads, minutes); Neal uploads + signs |
| The rebuilt manifest drifts: books lost (keys gone), counts move >10%, titles falling back to file names >5%, extractor error | `tools/bookpacks/check.py` against the previous manifest | `bookpack-drift` (new) | Neal; the pack stays held, the safe default |
| A pack newly looks like a book bundle | `upkeep.py check` runs `zim_probe` on new and changed packs and applies the audit's rules (doc count, doc byte share, HTML count, scraper) | `bookpack-candidate` (new) | Neal says yes/no; yes adds a `book_packs.json` row, then the agent builds |
| A Granger-published set (African Storybook) has a new Kiwix snapshot | the same probe, with the ZIM's `Date` | `asb-snapshot` → `rebuild-release` playbook | agent rebuilds and diffs; Neal uploads (batched ~10 files per upload, resumable, per d0) |
| Phone meets a pack without a manifest | app fallback (`iterByPath`) | none; it still works | none |

- **Why sidecars and not the catalog itself:** African Storybook's list is about 1.5 MB and Survivor Library's 3 MB.
  The catalog stays small and signed. Manifests are tied to one file by sha256, exactly like `.gqid`.
- **Cadence:** Kiwix re-scrapes these packs rarely. African Storybook's snapshot is 2025-01, Military Medicine's
  2024. Expect a handful of rebuilds a year.
- **Audit refresh:** the full probe (1,389 packs) takes about 20 minutes with 16 workers. Run it quarterly, or when the
  feed grows by more than 5%, and diff the classes.

## 6. Decisions (Neal, 2026-11-16)

1. **Round one:** African Storybook, Military Medicine, SMBC, Algorithms, Milne **and Survivor Library**. Army
   Publishing waits for a second pass.
2. **African Storybook ships as Granger-published per-language sets** (English and Kiswahili), not as the 8.3 GB ZIM.
3. **Keep "Read as ebook" as a choice.** No automatic conversion for pack books. §4.4 becomes this one rule: a PDF
   opens as pages; every text PDF, from any source including pack documents, offers "Read as ebook" and keeps
   "Original pages"; scans and comics don't offer it (`pdfKind` / `PdfOrigin` decides). With no "Ebook ready" chip,
   the LL reader files aren't touched. Open question for later: archive.org still converts at install (Plan IA),
   which is the one exception left.
4. **Books screen by kind (§4.1): yes.**
5. **Readiness:** the new manual collections stay counted in their domains, as the zimgit packs are: yes.
