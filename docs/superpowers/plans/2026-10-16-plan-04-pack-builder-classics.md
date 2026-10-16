# Granger Plan 4: Pack Builder + Granger Classics — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build our own ZIM packs from public-domain source texts, ship the first one (Granger Classics) as a GitHub release asset, and list it in the signed catalog and the Starter preset so the app can download, read and full-text search it.

**Architecture:** `tools/pack-builder` (Python) reads a reviewed manifest of Project Gutenberg book ids, fetches each book's HTML and RDF record from a Gutenberg mirror into a local cache, strips every trace of Project Gutenberg branding, splits each book into chapter entries, and writes one ZIM with python-libzim with the full-text index on. A `describe` command turns the built ZIM into a release descriptor (`tools/pack-builder/releases/<name>.json`: uuid, name, date, size, sha256, URL). `tools/catalog-gen` learns a second kind of pack entry that comes from such a descriptor instead of Kiwix's OPDS feed. No app code changes: the app already downloads any https pack URL, verifies its sha256 and registers the ZIM.

**Tech Stack:** Python 3.12, `libzim` 3.13.1 (python-libzim, same as `tools/test-zim`), `beautifulsoup4` 4.15.0 + `lxml` 6.1.3 (HTML parsing), `pytest` 8.4.2. Existing `tools/catalog-gen` (`cryptography` 50.0.2). Hosting: GitHub Releases on `nealt9000/Granger`.

**Spec:** `docs/superpowers/specs/2026-10-15-granger-v1-design.md` (build-order milestone 4; sections "Content rules", "Catalog categories", "Catalog", "Tests" → pack-builder tests). Roadmap and Plan 3 carry-overs: `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`.

**Scope change from the roadmap:** the OpenStax physics pack is **dropped from this plan**. OpenStax relicensed *University Physics* (all three volumes) to CC BY-NC-SA 4.0 (archive record revised 2026-06-11), which breaks the content rule. Neal's decision (2026-10-16): skip it for now; choosing a physics text needs its own curation research spike. Task 7 records this in the spec and roadmap.

## Global Constraints

- **Content: public domain or commercially redistributable only. Works published in the US in 1930 or earlier are public domain as of 2026; later works need a license.** For a translation, the year that counts is the translation's first publication, not the original work's.
- **Gutenberg branding stripped.** No built entry may contain `Project Gutenberg`, `gutenberg.org` or `gutenberg.net` (case-insensitive). Johannes Gutenberg the printer may still appear in a text. The pack never credits Project Gutenberg either: their trademark license allows redistribution free of their terms only once every reference is removed.
- **The Granger Classics manifest (title list + source ids) lives in the repo and is reviewed before each build** (Task 5 checkpoint).
- Classics contents per spec: King James Bible; a public-domain English Quran translation (Rodwell/Palmer; Pickthall 1930 if verified); Plato, Aristotle, Marcus Aurelius and core philosophy; complete Shakespeare; public-domain opera libretti; a few hundred essential novels and plays from antiquity to 1930. **Target size 300–600 MB.**
- Pack-builder ZIMs have the full-text index on (`config_indexing(True, "eng")`) and all mandatory ZIM metadata: `Name`, `Title`, `Creator`, `Publisher`, `Date`, `Description` (≤ 80 chars), `Language` = `eng`, plus a 48×48 PNG illustration.
- Pages are plain HTML + CSS with **no JavaScript and no external resources** (the reader blocks network loads; the Ray Charles `$ is not defined` failure from Plan 3 is the cautionary case). Source images are dropped, so the pack is text-only.
- Catalog pack entry fields stay exactly as the app parses them: `id, title, description, category, language, sizeBytes, sha256, urls[], snapshotDate (YYYY-MM-DD), license, hasFulltextIndex, topics[], zimUuid, zimName`. `zimUuid` must be the built ZIM's real UUID, because the app matches installed packs on it (`ItemStatus.kt`).
- Hosting: the spec's "we host only a signed catalog JSON" gains one exception, packs we build, published as GitHub release assets (≤ 2 GiB per asset) at `https://github.com/nealt9000/Granger/releases/download/<tag>/<file>`. Never commit ZIM files.
- Fetch sources from the mirror `https://aleph.pglaf.org/cache/epub/<id>/` (files `pg<id>-images.html` and `pg<id>.rdf`), never by crawling www.gutenberg.org (their robot policy). At most one request per second; everything is cached under `~/.cache/granger/gutenberg/`.
- Python tools run in a venv under `/tmp` (as `tools/test-zim` and `tools/catalog-gen` do): `python3 -m venv /tmp/packenv && /tmp/packenv/bin/pip install -r tools/pack-builder/requirements.txt`. Tests run from the tool's directory: `cd tools/pack-builder && /tmp/packenv/bin/python -m pytest -q`.
- The catalog **private** key stays at `~/.config/granger/catalog-signing.key` and is never committed. Pushing to `main` and creating a GitHub release are outward-facing: ask Neal before each.
- Every commit message ends with the line `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Gutenberg HTML that isn't the modern template.** Older files have no `pg-start-separator`/`pg-end-separator` divs, only `*** START OF THIS PROJECT GUTENBERG EBOOK … ***` text inside a `<p>` or `<pre>`, and some have the license before the start marker and again after the end marker. The cleaner must cut at whichever markers exist, and refuse a book that has neither rather than ship the license text → `test_old_text_markers_are_cut`, `test_book_without_markers_is_rejected` (Task 1).
2. **A book with one giant section** (KJV-size files, plays split only by act, a novel with no headings at all) must still come out as reader-sized pages ≤ 150 KB, never one 4 MB entry that the WebView and Ask chunker choke on → `test_oversized_part_is_split_at_the_next_level`, `test_book_without_headings_splits_by_size` (Task 2).
3. **Footnote and cross-reference links across chapter splits** (`href="#note12"` pointing into another chapter, links to `pg123-images.html#x`) must land on the right chapter, and a link to a missing anchor is unwrapped rather than left broken → `test_cross_chapter_anchor_is_rewritten`, `test_unknown_anchor_is_unwrapped` (Task 2).
4. **Non-ASCII and entity-laden titles and headings** (`Æneid`, `Café`, em dashes, `&amp;`) appear correctly in ZIM titles, the TOC and search suggestions, while paths stay ASCII slugs → `test_non_ascii_titles_survive` (Task 3).
5. **A build interrupted or failing partway** (mirror down, one book 404s, Ctrl-C) names the failing work, leaves no half-written `.zim` at the output path, and leaves no truncated file in the cache, so a rerun resumes from what was fetched → `test_failed_fetch_names_the_work_and_leaves_no_zim` (Task 4), `test_cache_writes_are_atomic` (Task 4). For the catalog side: a release URL that serves a different size than the descriptor fails the catalog build → `test_release_size_mismatch_fails` (Task 6).

---

## File Structure

```
Granger/
├── tools/pack-builder/
│   ├── pack_builder.py          CLI: validate | build | describe
│   ├── gutenberg.py             Cut one book's HTML down to the work itself; strip branding and media
│   ├── chapters.py              Split a cleaned body into chapter pages; rewrite anchors across pages
│   ├── zimpack.py               Lay out pages (main page, about, book pages, chapters, CSS) and write the ZIM
│   ├── manifest.py              Load a manifest; validate it against Gutenberg RDF records
│   ├── sources.py               Cached, rate-limited fetches from the Gutenberg mirror
│   ├── requirements.txt         libzim, beautifulsoup4, lxml, pytest
│   ├── classics/manifest.json   The Granger Classics title list (Task 5; reviewed before each build)
│   ├── releases/                Release descriptors read by catalog-gen (Task 7 writes the first)
│   └── tests/
│       ├── conftest.py          puts tools/pack-builder on sys.path
│       ├── fixtures/            hand-made Gutenberg-style HTML and RDF files
│       └── test_*.py
├── tools/catalog-gen/
│   ├── catalog_gen.py           + packs from release descriptors (Task 6)
│   ├── curated.json             + Granger Classics, + Starter preset item (Task 7)
│   └── test_catalog_gen.py
├── catalog/catalog.json(.sig)   regenerated and re-signed (Task 7)
└── docs/…                       spec + roadmap updates (Task 7)
```

ZIM layout (all paths ASCII; relative links only):

| Path | Content | `FRONT_ARTICLE` |
| --- | --- | --- |
| `index` (main path) | Pack title, one-line intro, sections in manifest order, each listing its works as `Title — Author` links | yes |
| `about` | What the pack is, the 1930 public-domain line, one table row per work: title, author, translator, year | yes |
| `<slug>` | Book page: title, author, translator, year, section, numbered chapter list | yes |
| `<slug>/<NNN>` | Chapter page, `NNN` = 001, 002, …: header link back to the book, prev/next links, the chapter HTML | no |
| `res/classics.css` | One shared stylesheet | no |

Chapter page title (`<title>` and ZIM title) is `<Book title> — <chapter title>`; book page title is the book title.

---

### Task 1: Gutenberg cleaner

**Files:**
- Create: `tools/pack-builder/requirements.txt`, `tools/pack-builder/gutenberg.py`, `tools/pack-builder/tests/conftest.py`, `tools/pack-builder/tests/test_gutenberg.py`
- Create fixtures: `tools/pack-builder/tests/fixtures/pg-modern.html` (modern template: `pg-header` section, `pg-start-separator` / `pg-end-separator` divs, `pg-footer` section, a `<style>` in `<head>`, five `<h2>` chapters, one `<img>`, one `<a href="https://example.com/">`, one body paragraph "Produced by Jane Doe and the Online Distributed Proofreading Team at https://www.pgdp.net (This file was produced from images generously made available by The Internet Archive)" and one "This eBook is for the use of anyone anywhere… Project Gutenberg License…" paragraph after the start marker), `pg-old.html` (no separator divs; `*** START OF THIS PROJECT GUTENBERG EBOOK OLD BOOK ***` and `*** END OF THIS PROJECT GUTENBERG EBOOK OLD BOOK ***` as text inside `<p>`/`<pre>`; license text before and after), `pg-nomarkers.html`

**Interfaces:**
- Produces: `gutenberg.clean(html: bytes) -> Cleaned`; `@dataclass Cleaned(body: bs4.Tag, styles: str, branding_removed: int)` where `body` is a detached `<div>` holding only the work's content and `styles` is the concatenated text of the source's `<style>` elements; `class NotAGutenbergBook(ValueError)`; `BRANDING = re.compile(r"project gutenberg|gutenberg\.(org|net)", re.I)`.

- [ ] **Step 1: Write the failing tests**

```python
def test_modern_template_keeps_only_the_work():
    c = gutenberg.clean(fixture("pg-modern.html"))
    text = c.body.get_text()
    assert "Chapter 1" in text and "Chapter 5" in text
    assert not gutenberg.BRANDING.search(str(c.body))
    assert "START OF THE PROJECT" not in text and "pgdp.net" not in str(c.body)
    assert c.branding_removed >= 1

def test_media_scripts_and_external_links_are_removed():
    body = str(gutenberg.clean(fixture("pg-modern.html")).body)
    assert "<img" not in body and "<script" not in body and "<link" not in body
    assert "example.com" not in body          # the link is unwrapped, its text kept
    assert "the link text" in body             # the fixture's external link reads "the link text"

def test_styles_are_kept_without_imports_or_urls():
    styles = gutenberg.clean(fixture("pg-modern.html")).styles
    assert ".stanza" in styles                 # the fixture's style block defines .stanza
    assert "@import" not in styles and "url(" not in styles

def test_old_text_markers_are_cut():
    c = gutenberg.clean(fixture("pg-old.html"))
    assert "First line of the old book." in c.body.get_text()
    assert not gutenberg.BRANDING.search(str(c.body))

def test_book_without_markers_is_rejected():
    with pytest.raises(gutenberg.NotAGutenbergBook):
        gutenberg.clean(fixture("pg-nomarkers.html"))
```

- [ ] **Step 2: Run them and confirm they fail**

Run: `cd tools/pack-builder && /tmp/packenv/bin/python -m pytest tests/test_gutenberg.py -q`
Expected: FAIL (`ModuleNotFoundError: gutenberg`)

- [ ] **Step 3: Implement `clean` in `tools/pack-builder/gutenberg.py`**

Parse with `BeautifulSoup(html, "lxml")`. Cut points: everything strictly after `#pg-start-separator` and strictly before `#pg-end-separator` when both exist; otherwise the elements after the one whose text matches `r"\*\*\*\s*START OF (THE|THIS) PROJECT GUTENBERG"` and before the one matching `r"\*\*\*\s*END OF (THE|THIS) PROJECT GUTENBERG"` (a marker inside a `<pre>` cuts within that element's text). Neither pair → `NotAGutenbergBook`. Then decompose `script, link, img, svg, object, iframe, form, audio, video, picture`; unwrap `<a>` whose `href` starts with a scheme (`http:`, `https:`, `mailto:`…); decompose any `p, div, pre, blockquote, table, li` whose text matches `BRANDING` or contains `pgdp.net` and that has no heading inside it (count these in `branding_removed`). Strip `@import` lines and `url(...)` declarations from styles. `requirements.txt`: `libzim==3.13.1`, `beautifulsoup4==4.15.0`, `lxml==6.1.3`, `pytest==8.4.2`.

- [ ] **Step 4: Run them and confirm they pass**

Run: `cd tools/pack-builder && /tmp/packenv/bin/python -m pytest tests/test_gutenberg.py -q`
Expected: 5 passed

- [ ] **Step 5: Commit**

```bash
git add tools/pack-builder
git commit -m "Pack builder: cut Gutenberg HTML down to the work and strip its branding"
```

---

### Task 2: Chapter splitter

**Files:**
- Create: `tools/pack-builder/chapters.py`, `tools/pack-builder/tests/test_chapters.py`

**Interfaces:**
- Consumes: `Cleaned.body` (Task 1). Tests build bodies with `BeautifulSoup(html, "lxml").body`, no fixture files needed.
- Produces: `chapters.split(body: bs4.Tag, split_at: str | None = None, max_bytes: int = 150_000) -> list[Chapter]`; `@dataclass Chapter(title: str, html: str)`. Chapter *i* (0-based) is served at `<slug>/{i+1:03d}`, so `html` already has its internal links rewritten to sibling names like `004#note12`. `def page_name(index: int) -> str` returns `f"{index + 1:03d}"`.

- [ ] **Step 1: Write the failing tests**

```python
def test_splits_at_the_first_heading_level_used_three_times():
    chapters = split(body("<h1>Book</h1>" + "".join(f"<h2>Chapter {i}</h2><p>{'x ' * 50}</p>" for i in range(1, 6))))
    assert [c.title for c in chapters] == [f"Chapter {i}" for i in range(1, 6)]

def test_short_front_matter_merges_into_the_first_chapter():
    chapters = split(body("<p>By A. Author</p>" + "".join(f"<h2>C{i}</h2><p>t</p>" for i in range(3))))
    assert len(chapters) == 3 and "By A. Author" in chapters[0].html

def test_long_front_matter_becomes_its_own_page():
    chapters = split(body(f"<p>{'preface ' * 60}</p>" + "".join(f"<h2>C{i}</h2><p>t</p>" for i in range(3))))
    assert chapters[0].title == "Front matter" and len(chapters) == 4

def test_split_at_override_wins():
    html = "".join(f"<h2>Act {a}</h2>" + "".join(f"<h3>Scene {s}</h3><p>t</p>" for s in range(3)) for a in range(3))
    assert len(split(body(html), split_at="h3")) == 9

def test_oversized_part_is_split_at_the_next_level():
    big = "".join(f"<h3>Sub {i}</h3><p>{'w' * 60_000}</p>" for i in range(4))
    html = "<h2>Big</h2>" + big + "<h2>Small</h2><p>t</p><h2>Last</h2><p>t</p>"
    chapters = split(body(html), max_bytes=150_000)
    assert all(len(c.html.encode()) <= 150_000 for c in chapters)
    assert [c.title for c in chapters[:4]] == [f"Big — Sub {i}" for i in range(4)]

def test_book_without_headings_splits_by_size():
    chapters = split(body("".join(f"<p>{'z' * 1000}</p>" for i in range(400))), max_bytes=150_000)
    assert len(chapters) == 3 and chapters[0].title == "Part 1"
    assert all(len(c.html.encode()) <= 150_000 for c in chapters)

def test_cross_chapter_anchor_is_rewritten():
    html = ('<h2>One</h2><p><a href="#n1">1</a> <a href="pg9-images.html#n1">again</a></p>'
            '<h2>Two</h2><p>t</p><h2>Notes</h2><p id="n1">Note one.</p>')
    chapters = split(body(html))
    assert 'href="003#n1"' in chapters[0].html and chapters[0].html.count('003#n1') == 2

def test_same_chapter_anchor_stays_local():
    chapters = split(body('<h2>A</h2><p><a href="#x">x</a></p><p id="x">t</p><h2>B</h2><h2>C</h2>'))
    assert 'href="#x"' in chapters[0].html

def test_unknown_anchor_is_unwrapped():
    chapters = split(body('<h2>A</h2><p><a href="#gone">kept text</a></p><h2>B</h2><h2>C</h2>'))
    assert "kept text" in chapters[0].html and "gone" not in chapters[0].html

def test_heading_titles_are_whitespace_collapsed_and_capped():
    chapters = split(body("<h2>  CHAPTER\n  I.<br/>Loomings  </h2><p>t</p>" + "<h2>B</h2><h2>C</h2>"))
    assert chapters[0].title == "CHAPTER I. Loomings"
```

- [ ] **Step 2: Run them and confirm they fail**

Run: `cd tools/pack-builder && /tmp/packenv/bin/python -m pytest tests/test_chapters.py -q`
Expected: FAIL (`ModuleNotFoundError: chapters`)

- [ ] **Step 3: Implement `split` in `tools/pack-builder/chapters.py`**

The split level is `split_at` if given, else the smallest *n* in 1–4 whose `h{n}` occurs at least 3 times anywhere in the body (count by tag name; heading elements nested in wrapper divs count, and a split heading inside a wrapper splits at that wrapper's top-level ancestor under `body`). Content before the first split heading becomes a "Front matter" page when its text is over 200 characters, otherwise it's prepended to the first chapter. A part over `max_bytes` (UTF-8 bytes of its serialized HTML) is split again at the next heading level found inside it, with titles `"<parent> — <child>"`; a part with no deeper heading, or a body with no qualifying level, is cut between top-level block elements into runs under `max_bytes`, titled `"<parent> (part k)"`, or `"Part k"` when there's no parent title. Titles: `" ".join(heading.get_text(" ").split())`, cut to 120 characters. After splitting, map every `id`/`name` attribute to its page index. Rewrite each relative `href` ending in `#frag` (with or without a file part): same page → `#frag`, other page → `f"{page_name(j)}#frag"`, unknown → unwrap the `<a>`.

- [ ] **Step 4: Run them and confirm they pass**

Run: `cd tools/pack-builder && /tmp/packenv/bin/python -m pytest tests/test_chapters.py -q`
Expected: 10 passed

- [ ] **Step 5: Commit**

```bash
git add tools/pack-builder/chapters.py tools/pack-builder/tests/test_chapters.py
git commit -m "Pack builder: split books into chapter pages and keep cross-chapter links working"
```

---

### Task 3: ZIM writer

**Files:**
- Create: `tools/pack-builder/zimpack.py`, `tools/pack-builder/tests/test_zimpack.py`

**Interfaces:**
- Consumes: `Chapter` (Task 2).
- Produces:
  - `@dataclass Work(slug: str, title: str, author: str, translator: str | None, published: int, section: str, chapters: list[Chapter], styles: str = "")`
  - `@dataclass PackInfo(name: str, title: str, description: str, language: str, date: str, sections: list[tuple[str, str]])` (`sections` = `(id, title)` in display order; `date` = `YYYY-MM-DD`)
  - `write_zim(out: Path, info: PackInfo, works: list[Work]) -> None`, which writes `out.with_name(out.name + ".tmp")` and renames it to `out` only after the creator closes, so a failure leaves no `out`.
  - `solid_png(size: int, rgb: tuple[int, int, int]) -> bytes`, a valid PNG built with `zlib` + `struct` and no extra dependency.
  - `_add_work(creator: Creator, work: Work) -> None` adds one book page and its chapters; `write_zim` calls it once per work (the failure test patches it).

- [ ] **Step 1: Write the failing tests**

Test module constants `INFO` (`name="granger_test_pack"`, two sections) and `WORKS`: `moby-dick` ("Moby-Dick", Herman Melville, 1851, two chapters, the second containing the word `leviathan`) and `aeneid` ("The Æneid", Virgil, translated by John Dryden, 1697, one chapter titled `"Café — Book I"`). Helper `pack(tmp_path)` writes them and opens the result with `libzim.reader.Archive`.

```python
def test_entries_and_main_page(tmp_path):
    a = pack(tmp_path)
    assert a.main_entry.get_item().path == "index"
    for path in ["index", "about", "moby-dick", "moby-dick/001", "moby-dick/002", "res/classics.css"]:
        assert a.has_entry_by_path(path)
    assert a.get_metadata("Name").decode() == "granger_test_pack"
    assert a.get_metadata("Language").decode() == "eng"
    assert a.has_illustration(48)

def test_full_text_index_finds_chapter_text(tmp_path):
    a = pack(tmp_path)
    assert a.has_fulltext_index
    hits = list(Searcher(a).search(Query().set_query("leviathan")).getResults(0, 10))
    assert "moby-dick/002" in hits

def test_title_suggestions_list_books_not_chapters(tmp_path):
    a = pack(tmp_path)
    hits = list(SuggestionSearcher(a).suggest("moby").getResults(0, 10))
    assert "moby-dick" in hits and not any("/" in h for h in hits)

def test_non_ascii_titles_survive(tmp_path):
    a = pack(tmp_path)
    assert a.get_entry_by_path("aeneid").title == "The Æneid"
    assert a.get_entry_by_path("aeneid/001").title == "The Æneid — Café — Book I"
    assert "The Æneid" in bytes(a.get_entry_by_path("index").get_item().content).decode()

def test_chapter_pages_link_back_and_across(tmp_path):
    html = bytes(pack(tmp_path).get_entry_by_path("moby-dick/001").get_item().content).decode()
    assert 'href="../moby-dick"' in html and 'href="002"' in html
    assert 'href="../res/classics.css"' in html
    assert "<script" not in html and "http" not in html

def test_about_lists_every_work_with_its_year(tmp_path):
    about = bytes(pack(tmp_path).get_entry_by_path("about").get_item().content).decode()
    assert "Herman Melville" in about and "1851" in about and "translated by" in about.lower()

def test_failed_write_leaves_nothing_at_out(tmp_path, monkeypatch):
    out = tmp_path / "p.zim"
    monkeypatch.setattr(zimpack, "_add_work", lambda *a: (_ for _ in ()).throw(RuntimeError("boom")))
    with pytest.raises(RuntimeError):
        zimpack.write_zim(out, INFO, WORKS)
    assert not out.exists()

def test_solid_png_is_a_png():
    png = zimpack.solid_png(48, (90, 60, 30))
    assert png.startswith(b"\x89PNG\r\n\x1a\n") and struct.unpack(">II", png[16:24]) == (48, 48)
```

- [ ] **Step 2: Run them and confirm they fail**

Run: `cd tools/pack-builder && /tmp/packenv/bin/python -m pytest tests/test_zimpack.py -q`
Expected: FAIL (`ModuleNotFoundError: zimpack`)

- [ ] **Step 3: Implement `zimpack.py`**

Follow `tools/test-zim/make_test_zim.py` for the `Creator` / `Item` / `Hint` pattern, with `Creator(tmp).config_indexing(True, "eng")` and `set_mainpath("index")`. Every page is a full HTML5 document with `<meta charset="utf-8">`, `<meta name="viewport" content="width=device-width, initial-scale=1">`, `<title>`, a link to `res/classics.css` (relative to the page's depth) and, on chapter pages, the work's `styles` inside a `<style>` element. Escape all manifest text with `html.escape`. Mark book, `index` and `about` pages `FRONT_ARTICLE: True` and chapters `False`. Metadata: `Name`, `Title`, `Description`, `Language`, `Date` from `PackInfo`, `Creator` = `"Granger"`, `Publisher` = `"Granger"`; illustration `solid_png(48, (90, 60, 30))`. The CSS is a short readable serif stylesheet (max-width 40em, comfortable line height, `.nav` prev/next bar, light and `prefers-color-scheme: dark` colors). The main page shows a sentence saying every work was published in 1930 or earlier, and links to `about`.

- [ ] **Step 4: Run them and confirm they pass**

Run: `cd tools/pack-builder && /tmp/packenv/bin/python -m pytest tests/test_zimpack.py -q`
Expected: 8 passed

- [ ] **Step 5: Commit**

```bash
git add tools/pack-builder/zimpack.py tools/pack-builder/tests/test_zimpack.py
git commit -m "Pack builder: write a full-text-indexed ZIM with book, chapter, index and about pages"
```

---

### Task 4: Manifest, sources and the CLI

**Files:**
- Create: `tools/pack-builder/manifest.py`, `tools/pack-builder/sources.py`, `tools/pack-builder/pack_builder.py`
- Create: `tools/pack-builder/tests/test_manifest.py`, `tools/pack-builder/tests/test_sources.py`, `tools/pack-builder/tests/test_pack_builder.py`
- Create fixtures: `tests/fixtures/pg2800.rdf` (trimmed real record: title "The Koran (Al-Qur'an)", language `en`, rights "Public domain in the USA."), `tests/fixtures/pg-copyrighted.rdf` (rights "Copyrighted. Read the copyright notice inside this book for details.")

**Interfaces:**
- Consumes: `gutenberg.clean` (Task 1), `chapters.split` (Task 2), `zimpack.Work`, `PackInfo`, `write_zim` (Task 3).
- Produces:
  - Manifest JSON (Task 5 fills it in):
    ```json
    {"name": "granger_classics_en", "title": "Granger Classics", "language": "eng",
     "description": "Scripture, philosophy, drama, libretti and novels to 1930",
     "sections": [{"id": "scripture", "title": "Scripture & religion"}],
     "works": [{"gutenberg": 2800, "slug": "koran-rodwell", "title": "The Koran", "author": "",
                "translator": "J. M. Rodwell", "published": 1861, "section": "scripture",
                "splitAt": null, "titleCheck": true, "note": ""}]}
    ```
  - `manifest.load(path: Path) -> Manifest`; `@dataclass Manifest(name, title, description, language, sections: list[tuple[str, str]], works: list[WorkSpec])`; `@dataclass WorkSpec(gutenberg: int, slug: str, title: str, author: str, translator: str | None, published: int, section: str, split_at: str | None, title_check: bool)`
  - `manifest.parse_rdf(data: bytes) -> Rdf`; `@dataclass Rdf(title: str, languages: list[str], rights: str)`
  - `manifest.validate(m: Manifest, rdf_for: Callable[[int], Rdf]) -> list[str]` returns one error line per problem and is empty when the manifest is valid. `PD_LINE = 1930`.
  - `sources.GutenbergSource(cache_dir: Path = Path("~/.cache/granger/gutenberg").expanduser(), fetch: Callable[[str], bytes] = http_get, delay_s: float = 1.0)` with `.html(book_id: int) -> bytes` and `.rdf(book_id: int) -> bytes`; `MIRROR = "https://aleph.pglaf.org/cache/epub"`; `class FetchError(RuntimeError)`, whose message names the book id and URL.
  - `pack_builder.build(m: Manifest, source: GutenbergSource, out: Path, date: str) -> Path` and `pack_builder.describe(zim: Path, url: str) -> dict`
  - CLI: `pack_builder.py validate --manifest M`, `pack_builder.py build --manifest M --out-dir D [--date YYYY-MM-DD]` (writes `D/<name>_<date>.zim`), `pack_builder.py describe ZIM --url URL --out tools/pack-builder/releases/<name>.json`

- [ ] **Step 1: Write the failing tests**

```python
# test_manifest.py
def test_valid_manifest_has_no_errors(): assert validate(manifest(), rdf_ok) == []
def test_published_after_1930_is_an_error():
    assert any("1931" in e for e in validate(manifest(published=1931), rdf_ok))
def test_copyrighted_rights_is_an_error():
    assert any("rights" in e for e in validate(manifest(), lambda _: parse_rdf(fixture("pg-copyrighted.rdf"))))
def test_non_english_is_an_error():
    assert any("language" in e for e in validate(manifest(), lambda _: Rdf("The Koran", ["fr"], PD)))
def test_title_mismatch_is_an_error_unless_disabled():
    rdf = lambda _: Rdf("Moby Dick; Or, The Whale", ["en"], PD)
    assert validate(manifest(title="The Koran"), rdf)
    assert validate(manifest(title="The Koran", title_check=False), rdf) == []
def test_duplicate_slug_or_id_unsafe_slug_and_unknown_section_are_errors():
    assert len(validate(manifest_with_problems(), rdf_ok)) == 4
def test_parse_rdf_reads_title_language_rights():
    assert parse_rdf(fixture("pg2800.rdf")) == Rdf("The Koran (Al-Qur'an)", ["en"], "Public domain in the USA.")

# test_sources.py
def test_second_read_comes_from_cache(tmp_path):
    calls = []; s = GutenbergSource(tmp_path, fetch=lambda u: calls.append(u) or b"<html/>", delay_s=0)
    s.html(10); s.html(10)
    assert calls == ["https://aleph.pglaf.org/cache/epub/10/pg10-images.html"]
def test_cache_writes_are_atomic(tmp_path):
    def broken(url): raise OSError("reset")
    with pytest.raises(FetchError, match="10"):
        GutenbergSource(tmp_path, fetch=broken, delay_s=0).html(10)
    assert not any(p.is_file() for p in tmp_path.rglob("*"))

# test_pack_builder.py  (fake source serves fixtures/pg-modern.html for every id)
def test_build_writes_a_branding_free_zim(tmp_path):
    zim = build(manifest_of_two(), fake_source(), tmp_path, "2026-10-16")
    assert zim.name == "granger_test_2026-10-16.zim"
    a = Archive(zim)
    for i in range(a.all_entry_count):
        item = a._get_entry_by_id(i).get_item()
        if item.mimetype.startswith("text/"):
            assert not BRANDING.search(bytes(item.content).decode()), item.path
def test_failed_fetch_names_the_work_and_leaves_no_zim(tmp_path):
    with pytest.raises(FetchError, match="2800"):
        build(manifest_of_two(), failing_source(on=2800), tmp_path, "2026-10-16")
    assert list(tmp_path.glob("*.zim")) == []
def test_describe_reports_what_the_catalog_needs(tmp_path):
    zim = build(manifest_of_two(), fake_source(), tmp_path, "2026-10-16")
    d = describe(zim, "https://github.com/nealt9000/Granger/releases/download/classics-2026-10-16/" + zim.name)
    assert d["id"] == d["zimName"] == "granger_test"
    assert d["zimUuid"] == str(Archive(zim).uuid) and d["snapshotDate"] == "2026-10-16"
    assert d["sizeBytes"] == zim.stat().st_size and d["sha256"] == hashlib.sha256(zim.read_bytes()).hexdigest()
    assert d["hasFulltextIndex"] is True and d["language"] == "eng" and len(d["urls"]) == 1
```

- [ ] **Step 2: Run them and confirm they fail**

Run: `cd tools/pack-builder && /tmp/packenv/bin/python -m pytest -q`
Expected: the new test files fail on missing modules; Tasks 1–3 tests still pass.

- [ ] **Step 3: Implement `manifest.py`, `sources.py`, `pack_builder.py`**

Validation rules, one error each: `published > PD_LINE`; `rights` not starting with `"Public domain in the USA"`; `"en"` not in `languages`; when `title_check` is on, the manifest title's significant words (lowercase, ignoring `the a an of and or`) must all appear in the RDF title; slugs must match `^[a-z0-9]+(-[a-z0-9]+)*$` and be unique; Gutenberg ids unique; `section` must be a declared section id; `splitAt` must be null or `h1`–`h4`. Parse RDF with `xml.etree` (namespaces `dcterms`, `rdf`, `pgterms`; the language is at `dcterms:language/rdf:Description/rdf:value`). `GutenbergSource` sleeps `delay_s` before each network fetch, never before a cached read, and writes via a `.tmp` file plus `os.replace`; any exception from `fetch` becomes `FetchError(f"book {id}: {url}: {cause}")`. `build` fetches and cleans every work before it calls `write_zim` (so a fetch failure happens before the ZIM exists), prints one line per work (`id slug chapters branding_removed`) and the final size, and uses `PackInfo(date=date)`. `describe` opens the ZIM with `libzim.reader.Archive` and returns `id`, `description`, `language`, `sizeBytes`, `sha256`, `urls: [url]`, `snapshotDate` (from `Date`), `hasFulltextIndex`, `zimUuid`, `zimName`. The module docstring shows the three commands, like `catalog_gen.py`'s.

- [ ] **Step 4: Run all pack-builder tests**

Run: `cd tools/pack-builder && /tmp/packenv/bin/python -m pytest -q`
Expected: all pass (5 + 10 + 8 + 7 + 2 + 3 = 35)

- [ ] **Step 5: Commit**

```bash
git add tools/pack-builder
git commit -m "Pack builder: manifest validation against Gutenberg records, cached mirror fetches, build and describe"
```

---

### Task 5: The Granger Classics manifest (review checkpoint)

**Files:**
- Create: `tools/pack-builder/classics/manifest.json`

**Interfaces:**
- Consumes: the manifest format and `pack_builder.py validate` (Task 4).

The manifest is content, not code: the deliverable is a list that validates cleanly against live Gutenberg records and that Neal has reviewed.

- [ ] **Step 1: Write `classics/manifest.json`**

Header: `name` `granger_classics_en`, `title` `Granger Classics`, `language` `eng`, `description` `Scripture, philosophy, drama, libretti and novels to 1930`. Sections in this order, with rough counts that sum to about 300 works:

| id | title | ~count | Must include |
| --- | --- | --- | --- |
| `scripture` | Scripture & religion | 12 | King James Bible; Quran: Rodwell (1861, Gutenberg 2800) and Palmer (1880) if it validates, Pickthall (1930) only if Gutenberg has it and it validates; Bhagavad Gita (Arnold 1885); Dhammapada (Müller); Tao Te Ching (Legge or Giles); Confucian Analects (Legge) |
| `philosophy` | Philosophy | 40 | Plato (Jowett: Republic, Apology, Crito, Phaedo, Symposium, Phaedrus, Meno, Gorgias, Laws), Aristotle (Nicomachean Ethics, Politics, Poetics), Marcus Aurelius (Meditations, Long), Epictetus, Seneca, Lucretius, Boethius, Augustine's Confessions, Aquinas (selection if present), Descartes, Spinoza (Ethics), Hobbes (Leviathan), Locke, Hume, Kant (Critique of Pure Reason, Meiklejohn), Mill, Nietzsche (pre-1930 translations), Machiavelli, Rousseau, Montaigne (Cotton/Florio), Pascal |
| `poetry` | Epic & poetry | 25 | Homer (Iliad, Odyssey; Butler or Chapman/Pope), Virgil's Aeneid, Ovid's Metamorphoses, Beowulf, Dante (Longfellow), Chaucer, Milton (Paradise Lost), Spenser, Goethe's Faust, Whitman (Leaves of Grass), Dickinson |
| `drama` | Drama | 75 | **every Shakespeare play** as its own work (the individual-play ebooks, about 37) plus the Sonnets; Aeschylus, Sophocles, Euripides, Aristophanes; Marlowe, Jonson, Molière, Racine, Goethe, Ibsen, Chekhov, Wilde, Shaw (plays published by 1930), Synge |
| `libretti` | Opera libretti | 12 | sourced from rows of `https://www.gutenberg.org/cache/epub/feeds/pg_catalog.csv` whose Subjects mention `Librettos` or `Operas`; Gilbert & Sullivan, Wagner's Ring (pre-1930 English translation), Mozart/Da Ponte, Verdi, Bizet where present |
| `novels` | Novels & stories | 140 | the standard canon to 1930: Austen, the Brontës, Dickens, Eliot, Hardy, Thackeray, Trollope, Defoe, Swift, Fielding, Sterne, Scott, Stevenson, Conrad, Kipling, Wells, Doyle, Twain, Melville, Hawthorne, Poe, James, Wharton, Cather, London, Tolstoy, Dostoevsky, Turgenev, Gogol, Hugo, Dumas, Flaubert, Balzac, Zola, Cervantes, Kafka (pre-1930 translations only), Joyce (Dubliners, Portrait), Woolf (pre-1930 US publication only), Fitzgerald (The Great Gatsby, 1925) |

Source every id from the catalog CSV (download it once to the scratchpad, never commit it): pick the row whose title and author match and whose language is `en`. Where several editions exist, prefer the one whose CSV title has no "Illustrated"/"Abridged" and that is the full work. `published` is the year the reproduced edition or translation first appeared, from the book's front matter in the cached HTML when the CSV doesn't say; use `note` for anything a reviewer should know (e.g. "Rodwell 1861; Margoliouth intro 1909"). Set `titleCheck: false` only where the Gutenberg title differs legitimately, and say why in `note`.

- [ ] **Step 2: Validate against the live records**

Run: `cd tools/pack-builder && /tmp/packenv/bin/python pack_builder.py validate --manifest classics/manifest.json`
Expected: `classics/manifest.json: N works, 0 errors` (it fetches ~300 RDF files at 1/s into the cache, about 5 minutes the first time). Fix every error by correcting the entry or dropping the work.

- [ ] **Step 3: Commit**

```bash
git add tools/pack-builder/classics/manifest.json
git commit -m "Granger Classics manifest: N works across six sections, validated against Gutenberg records"
```

- [ ] **Step 4: Checkpoint: Neal reviews the list**

Stop and show Neal the per-section counts, the full title list grouped by section (title, author, translator, year), every `note`, and which Quran translations made it in. The spec requires this review before each build. Apply his changes, re-run Step 2 and amend the commit. Do not start Task 7 until he approves.

---

### Task 6: Catalog entries from release descriptors

**Files:**
- Modify: `tools/catalog-gen/catalog_gen.py` (`build`, new `release_entry`, new `http_head`)
- Modify: `tools/catalog-gen/test_catalog_gen.py`

**Interfaces:**
- Consumes: the descriptor dict `describe` writes (Task 4): `id, description, language, sizeBytes, sha256, urls, snapshotDate, hasFulltextIndex, zimUuid, zimName`.
- Produces: a curated pack shaped `{"release": "tools/pack-builder/releases/granger_classics_en.json", "category": "...", "license": "...", "title": "..."}` becomes a catalog pack entry with exactly the existing keys (`topics: []`, `title`/`category`/`license` from the curated row). `release_entry(curated: dict, head: Callable[[str], int] = http_head, root: Path = REPO_ROOT) -> dict`; `http_head(url) -> int` returns the final `Content-Length` after redirects; `REPO_ROOT = Path(__file__).resolve().parents[2]`. `build(curated, fetch=http_get, now=None, head=http_head)`.

- [ ] **Step 1: Write the failing tests**

```python
def test_release_pack_comes_from_its_descriptor(tmp_path):
    write_descriptor(tmp_path / "rel.json", sizeBytes=123)
    entry = cg.release_entry(RELEASE_ROW | {"release": "rel.json"}, head=lambda url: 123, root=tmp_path)
    assert entry["id"] == "granger_classics_en" and entry["category"] == "Literature & Drama"
    assert set(entry) == set(cg.pack_entry(CURATED_KIWIX_ROW, fake_fetch))  # same keys as a Kiwix pack (existing fakes)
    assert entry["topics"] == []

def test_release_size_mismatch_fails(tmp_path):
    write_descriptor(tmp_path / "rel.json", sizeBytes=123)
    with pytest.raises(SystemExit, match="123"):
        cg.release_entry(RELEASE_ROW | {"release": "rel.json"}, head=lambda url: 999, root=tmp_path)

def test_release_with_non_https_url_fails(tmp_path):
    write_descriptor(tmp_path / "rel.json", urls=["http://example.com/x.zim"])
    with pytest.raises(SystemExit):
        cg.release_entry(RELEASE_ROW | {"release": "rel.json"}, head=lambda url: 123, root=tmp_path)

def test_build_mixes_kiwix_and_release_packs(tmp_path): ...
    # curated with one Kiwix row (existing OPDS/META4 fakes) and one release row; both ids in the catalog
```

- [ ] **Step 2: Run them and confirm they fail**

Run: `cd tools/catalog-gen && /tmp/catenv/bin/python -m pytest -q` (create `/tmp/catenv` from `tools/catalog-gen/requirements.txt` if missing)
Expected: the 4 new tests fail; existing tests pass.

- [ ] **Step 3: Implement**

`build` sends rows with a `release` key to `release_entry` and all others to `pack_entry`. The existing id/duplicate/preset checks then cover both kinds. `http_head` uses `urllib.request.Request(url, method="HEAD")` with the existing User-Agent (urllib follows the GitHub → objects.githubusercontent.com redirect). Update the module docstring with one line on release packs.

- [ ] **Step 4: Run them and confirm they pass**

Run: `cd tools/catalog-gen && /tmp/catenv/bin/python -m pytest -q`
Expected: all pass

- [ ] **Step 5: Commit**

```bash
git add tools/catalog-gen
git commit -m "Catalog: list packs we build from their release descriptors, checked against the hosted size"
```

---

### Task 7: Build, publish and list Granger Classics

**Files:**
- Create: `tools/pack-builder/releases/granger_classics_en.json`
- Modify: `tools/catalog-gen/curated.json`, `catalog/catalog.json`, `catalog/catalog.json.sig`
- Modify: `docs/superpowers/specs/2026-10-15-granger-v1-design.md`, `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`, `README.md`

**Interfaces:**
- Consumes: everything above. Requires Neal's approved manifest (Task 5 Step 4).

- [ ] **Step 1: Build**

Run: `cd tools/pack-builder && /tmp/packenv/bin/python pack_builder.py build --manifest classics/manifest.json --out-dir ~/granger-build`
Expected: one line per work, ending `~/granger-build/granger_classics_en_<date>.zim: <size>`. **Size check:** 300–600 MB is the target. Under 300 MB is acceptable (text-only); over 600 MB means trimming the novels section with Neal. Look at the per-work lines for anything with 1 chapter or > 400 chapters and fix its `splitAt` before going on.

- [ ] **Step 2: Inspect the build on the desktop**

Run a quick script in `/tmp/packenv` (scratchpad, not committed) that opens the ZIM and prints: entry count, `has_fulltext_index`, the full-text hits for `whale`, `Elsinore` and `the quality of mercy`, the suggestions for `ham`, and whether any `text/html` entry matches `gutenberg.BRANDING`.
Expected: index present; `whale` hits Moby-Dick chapters, `Elsinore` hits Hamlet, the mercy line hits The Merchant of Venice; `ham` suggests Hamlet; zero branding matches.

- [ ] **Step 3: Publish the release (ask Neal first)**

Tag `classics-<date>`, asset `granger_classics_en_<date>.zim`. `gh` isn't installed on this machine. Either Neal creates the release at `https://github.com/nealt9000/Granger/releases/new` and uploads the file, or, with his OK, install `gh`, have him run `! gh auth login`, then `gh release create classics-<date> ~/granger-build/granger_classics_en_<date>.zim --title "Granger Classics <date>" --notes "Public-domain classics pack for Granger."`.
Verify resumable download through the redirect:
Run: `curl -sIL https://github.com/nealt9000/Granger/releases/download/classics-<date>/granger_classics_en_<date>.zim | grep -i content-length | tail -1` → the ZIM's byte size; and `curl -s -L -r 100-199 -o /dev/null -w "%{http_code} %{size_download}\n" <same url>` → `206 100`.

- [ ] **Step 4: Describe and list it**

Run: `cd tools/pack-builder && /tmp/packenv/bin/python pack_builder.py describe ~/granger-build/granger_classics_en_<date>.zim --url <release url> --out releases/granger_classics_en.json`
Add to `curated.json` `packs`: `{"release": "tools/pack-builder/releases/granger_classics_en.json", "category": "Literature & Drama", "license": "Public domain (US)", "title": "Granger Classics"}`. Add `granger_classics_en` to the `starter` preset's `items`.
Run, from the repo root: `/tmp/catenv/bin/python tools/catalog-gen/catalog_gen.py build --curated tools/catalog-gen/curated.json --out catalog/catalog.json && /tmp/catenv/bin/python tools/catalog-gen/catalog_gen.py sign --key ~/.config/granger/catalog-signing.key catalog/catalog.json && /tmp/catenv/bin/python tools/catalog-gen/catalog_gen.py verify --public-key catalog/public-key.txt catalog/catalog.json`
Expected: `catalog/catalog.json: 12 packs, 3 models`, `wrote catalog/catalog.json.sig`, `signature OK`. The rebuild also refreshes every Kiwix pin. Check `git diff catalog/catalog.json` shows only the new entry, the preset change, and Kiwix snapshot bumps, if any.

- [ ] **Step 5: Docs**

Spec: in "Decisions → Hosting", add that packs Granger builds are hosted as GitHub release assets. In the "Catalog categories" table, change the OpenStax row to say University Physics became CC BY-NC-SA 4.0 (2026-06), so it's out, and a physics text is chosen in a later curation spike. Add the physics choice to "Open items". Roadmap: Plan 4 → **Done** with this file, renamed to "Pack builder + Granger Classics"; under a new "Carried forward from Plan 4" heading add: the physics pack spike; rebuilding Classics means a new release tag + `describe` + catalog re-sign (the Kiwix-only monthly regen leaves it alone); the Gutenberg cache lives at `~/.cache/granger/gutenberg/`; the size Step 1 measured. README: one line for `tools/pack-builder`.

- [ ] **Step 6: Commit, and push with Neal's OK**

```bash
git add tools/pack-builder/releases tools/catalog-gen/curated.json catalog docs README.md
git commit -m "Publish Granger Classics: release descriptor, catalog entry, Starter preset; drop OpenStax for now"
```
Push to `main` only after Neal agrees. The app reads the catalog from `main`. Wait a few minutes after the push before checking on the phone: raw.githubusercontent.com caches the `.json` and `.sig` separately (Plan 3 note).

- [ ] **Step 7: Check it on the Pixel 6**

`./gradlew installDebug`, then on the phone: Downloads lists Granger Classics under Literature & Drama with the measured size, and Starter includes it. Download it over Wi-Fi. Midway, `adb shell svc wifi disable` then `enable` again: the download resumes from the same `.part` size against the GitHub URL. Once installed: the Library shows the pack with its icon. The main page lists six sections with no blank layout. Title search `Hamlet` finds the play. Full-text search `Elsinore` finds Hamlet chapters. A chapter's prev/next links and its link back to the book work. A footnote link in a book that has notes (e.g. the KJV or a Jowett dialogue) jumps to the note. Readiness shows the pack verified with its snapshot date. Record anything that fails as a fix task before marking the plan done.
