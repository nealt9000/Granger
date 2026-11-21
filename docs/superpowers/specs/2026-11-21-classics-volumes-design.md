# Classics volumes: design (approved, 2026-11-21)

Neal, 2026-11-21: "there's a lot of interest in Granger Classics, but I'm finding most want specific categories vs the
whole set. Let's break up the Granger classics into logical volumes that can be downloaded as their own packs. We should
end up with a pragmatic design that allows users to get the whole, or select specific areas of interest within that
whole. Same for espanol."

## 1. Where things stand

- **Each book is already its own download.** Plan 6 replaced the Classics ZIM with one EPUB per book, and the catalog no
  longer lists the ZIM. Granger Classics is 437 books: 135 MB of EPUBs, or 257 MB with search vectors and link sidecars.
  Clásicos Granger is 185 books: 50 MB, or 94 MB with the sidecars.
- **Two ways to get them today.** You can tap Get on single books, which the Granger screen groups by `category`. Or you
  can tap Get all on the one collection preset (`granger-classics`, `clasicos-granger`). Nothing sits between the two.
- **The sections are lopsided.** English Novels & stories is 205 books and 144 MB, over half the set, while Opera
  libretti is 13 books. The Spanish manifest has a 2-book section (Escritura y religión) and a 5-book one (Lecturas para
  aprender).
- **The section is written into the files.** `epubpack` writes it as `dc:subject`, and `covers.cover_jpeg` uses it to
  draw the cover. Re-sectioning books would rebuild their EPUBs and change their sha256, so every phone would download
  them again and the vectors and link sidecars would have to be rebuilt.
- **There is a precedent.** African Storybook (Plan KB2) ships one "all books" preset plus one preset per level, linked
  by `setOf`. Its presets sit inside `bookSets` because released apps would mishandle them at the top level. That
  reason doesn't apply to Classics (§4).

## 2. The design in one paragraph

**A volume is a named list of book ids: a preset with `setOf: <collection>`.** It ships no new files and needs no new
release or hosting. Volumes **partition** the collection, so each book is in exactly one volume and the volumes add up
to the whole. A volume is assigned in the manifest, not in the EPUB, so no book is rebuilt. The catalog's `category`
for each Classics book becomes its **volume title**, which means every app version, released ones included, groups the
books by volume with no app change. Get all on the collection still gets the whole set. Get all on a volume gets just
that area. Single books still work as before.

Why not one ZIM or zip per volume? It would host every book twice, a book fix would mean re-uploading the bundle, and
the per-book vectors, links, updates and Library filing would all break. The per-book EPUBs already give everything a
"pack" would give.

## 3. The volumes

Partitioned by `tools/pack-builder/classics/manifest*.json`. The sizes include sidecars, measured against the current
catalog. The draft assignment, title by title, is in `docs/superpowers/spikes/2026-11-21-classics-volumes/` (`draft_volumes.py`, `volumes-draft.json`); the final one goes into the manifests (§5.1).

### Granger Classics (English): 10 volumes

| # | Volume | Books | Size | Made from |
|---|---|---|---|---|
| 1 | Scripture & religion | 20 | 29 MB | scripture (unchanged) |
| 2 | Philosophy | 71 | 40 MB | philosophy (unchanged) |
| 3 | Epic & poetry | 41 | 21 MB | poetry (unchanged) |
| 4 | Drama | 87 | 19 MB | drama (unchanged) |
| 5 | Opera libretti | 13 | 3 MB | libretti (unchanged) |
| 6 | Children's classics | 26 | 12 MB | novels: Alice, Oz, Pooh, Grimm, Andersen, Aesop, Heidi, Nancy Drew, Treasure Island… |
| 7 | Mystery, adventure & the fantastic | 47 | 26 MB | novels: Christie, Holmes, Poe, Dracula, Frankenstein, Wells, Verne, Dumas, London, Scott… |
| 8 | Novels: Britain & Ireland | 63 | 53 MB | novels: Austen, Dickens, the Brontës, Eliot, Hardy, Joyce, Woolf, Conrad… |
| 9 | Novels: America | 35 | 18 MB | novels: Twain, Melville, Hawthorne, Wharton, James, Fitzgerald, Hemingway, Faulkner, Cather… |
| 10 | Novels: Europe & the world | 34 | 35 MB | novels: Tolstoy, Dostoevsky, Hugo, Flaubert, Proust, Mann, Kafka, Cervantes, Genji… |
| | **All** | **437** | **257 MB** | |

Calls behind it:
- **Novels are split by interest, not by date.** People ask for "the kids' books", "the mysteries" or "the Russians",
  not for "1850–1880". Genre and children's books come out first, and the literary novels that remain split by region,
  which matches how readers and syllabi group them.
- **Opera libretti stay their own volume** (Neal). The five volumes that were already sections keep their exact names,
  so their translations keep working.
- **Borderline books go to the volume people would look in first.** *A Christmas Carol* and *Treasure Island* go to
  Children's. *Jekyll and Hyde*, *Ivanhoe* and *The Last of the Mohicans* go to Adventure. *Little Women* goes to
  Children's, not America. The manifest makes each such call visible, one field per work.

### Clásicos Granger (Spanish): 8 volumes

| # | Volume | Books | Size | Made from |
|---|---|---|---|---|
| 1 | Lecturas fáciles e infantiles | 13 | 5 MB | lecturas + infantil |
| 2 | Cuentos y novelas cortas | 26 | 11 MB | cuentos |
| 3 | Novelas | 43 | 27 MB | novelas |
| 4 | Poesía | 17 | 6 MB | poesia |
| 5 | Teatro | 14 | 5 MB | teatro |
| 6 | Clásicos universales en español | 34 | 18 MB | universales |
| 7 | Ensayo, pensamiento y religión | 17 | 9 MB | ensayo + escritura |
| 8 | Historia y crónicas | 21 | 14 MB | historia (unchanged) |
| | **Todo** | **185** | **94 MB** | |

- The two smallest sections fold into their nearest neighbours: learners' readers with children's books (both are
  "easy reading"), and the 2 scripture books with essays and thought.
- The `learner` flag stays a per-book badge. A "for learners" volume would overlap the others and break the partition.

## 4. Compatibility: one catalog, every app version

- **Released apps:** the Granger screen lists every top-level preset whose items are all books (`SourceViewModel`
  `presetList`), each with its own Get all. So they get working volume rows, plus the volume grouping through
  `category`. Under Books → Literature they show one row per preset, which is up to 10 rows for one collection. That's
  cluttered but it works, and it lasts only until people update. A volume preset's items are all top-level `books`, so
  the "empty set" problem that kept African Storybook's levels inside `bookSets` can't happen here.
- **Current app:** it reads `setOf` and nests the volumes under their collection (§5.3).
- **Unknown `category` strings:** `KiwixList` translates the known category names to string resources, and a new
  volume title shows as its catalog text until it's added there.
- **`domain` and `subjects`** are still worked out from the original **section**, not the volume title, so readiness
  and Explore don't move. For example, Children's classics stays `literature`, and Scripture stays `thought` and
  `religion`.

## 5. Changes

### 5.1 Pack builder (`tools/pack-builder`)
- Manifest: add a top-level `volumes: [{id, title, i18n?}]` in screen order, and a `volume` field on each work. When a
  work has no `volume` field, it takes a volume with the same id as its section, so drama → `drama` unless reassigned.
  Validation fails if a volume has no works, a work names an unknown volume, or two volumes share an id.
- `describe-epubs`: lists the books in volume order, so the Granger screen and the Library's category chips follow it.
- `describe-epubs`: write `volume` (the title) beside `section` on each book, and the `volumes` list on the descriptor.
  It only re-hashes the existing files, so no EPUB is rebuilt.
- Tests in `tests/test_manifest.py` and `tests/test_pack_builder.py`.

### 5.2 Catalog generator (`tools/catalog-gen`)
- For a books row whose descriptor has `volumes`: set `category` to the book's volume title, and keep `domain` and
  `subjects` from `section`.
- Emit one preset per volume after the collection preset: `<collection>-<volume id>`, titled `"<Collection>: <Volume>"`,
  with `setOf: <collection>`, the row's `language`, and i18n from the manifest. This is the same shape `level_presets`
  produces, so generalise that function into `sub_presets` and use it for both.
- Check that the volumes partition the collection: every book appears exactly once.
- Tests in `test_catalog_gen.py`.

### 5.3 App (`core/catalog-api`, `core/screens`, Android and iOS screens)
- `Preset` gains `setOf: String = ""`, which older catalogs leave blank.
- `SourceViewModel.nestVolumes`: on the Granger screen each collection row is followed by its volumes, in catalog
  order. A volume row is titled by its own part ("Drama", "Teatro" on a Spanish phone), shows the same count and status
  lines preset rows already show, and has its own Get all, which goes through the existing `getPreset`. Books →
  Literature shows only the collection rows. `PresetRow.setOf` marks a volume, and Android indents it.
- The five new volume titles are added to `KiwixList`'s category→resource map and to the en/es strings, and
  `make_text_ids.py` regenerates `ScreenTextIds.kt` and `ScreenText.swift`.
- **Deferred:**
  - Tapping a volume row to jump to its books. The rows sit directly above sections with the same names in the same
    order.
  - The iPhone indent. The iPhone already gets the nesting and short titles from the shared view model, but reading
    `setOf` in Swift needs a new GrangerCore release.
  - A "10 volumes" note on the Books row.

### 5.4 Presenting the downloaded set (Neal: "if the whole is downloaded, they are categorized like this")
The Library's collection screen takes each Granger book's category from the catalog, in catalog order
(`CollectionModel.facts`), not from the EPUB's `dc:subject`. With `category` set to the volume title and the books in
volume order, a downloaded collection, whole or in part, shows its volumes as its category chips on every app
version. The Library needs no code change.

### 5.5 Onboarding by volume (Neal agreed to the follow-on)
- Curated presets and onboarding use cases may name `"@<collection>/<volume id>"`. The generator **expands it to that
  volume's book ids** in the emitted catalog. Released apps already resolve plain book ids and gather them into one
  collection line, so nothing breaks for them.
- Faith & philosophy takes `@granger-classics/scripture` and `@granger-classics/philosophy` (69 MB, not 257 MB), and
  in Spanish `@clasicos-granger/ensayo`.
- The app titles a collection line that holds only some volumes by those volumes: "Granger Classics: Scripture &
  religion, Philosophy".
- Readiness needs no change. `"@granger-classics"` in a domain already means only that domain's books (`thought` gets
  Scripture and Philosophy), and a volume token there fails the build.

### 5.6 Not in this round
- **Removing a volume** ("Remove these 47 books"). For now books are removed one at a time or from the Library.

## 6. Rollout
1. Add the manifest volumes and the builder changes, then run `describe-epubs` against the existing
   `classics-2026-11-09` files. The release is unchanged.
2. Run the catalog generator, then `check-links`, then sign, then push `catalog/`. This is the next upkeep run. From
   that moment released apps see the volumes. The descriptors on branch `cv-classics-volumes` are already re-described:
   every book keeps its sha256, size and URL.
3. Ship the app change (§5.3). It works without the new catalog too, since `setOf` defaults to blank.

## 7. Calls
1. Novels split by interest (Neal, 2026-11-21).
2. Opera libretti stay their own volume (Neal, 2026-11-21).
3. Downloaded Classics are grouped by volume in the Library too (Neal, 2026-11-21).
4. Onboarding by volume is in this round (Neal, 2026-11-21).
5. The catalog goes out first (Neal, 2026-11-21): there are very few testers now. The app change, and the GrangerCore
   release the iPhone pins, must land before the iPhone debug build is shared more widely, because the pinned core
   predates this work and would show every volume as its own row.
