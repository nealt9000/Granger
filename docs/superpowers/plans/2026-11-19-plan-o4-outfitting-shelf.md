# Plan O4: Outfitting, and the reading shelf

2026-11-19 · spec `docs/superpowers/specs/2026-11-18-online-offline-design.md` §8 (Outfitting replaces Readiness) · the
readiness spec it supersedes in part `docs/superpowers/specs/2026-10-21-home-readiness-onboarding-design.md` (§3–§4
domains stand) · O3's Home and shelf canvas https://claude.ai/artifact/WwYDrBRKUzjb9Q1QQd27bD (shelf S1; Home's Ready
offline, which Outfitting opens from)

**Goal:** one screen answers "what can this phone do with no internet, and what should I add?":
- your own use first;
- then the nine domains a phone needs when nothing else is there;
- then everything on the phone, with sizes, Saved for now, and the file checks.

And the Library becomes your reading shelf (S1): the books and collections you read, as covers in Explore's style. Its
storage and file details move to Outfitting.

**Not in O4:**
- First run, "Never go online" and the rest of Settings → Online (O5).
- The remote big indexes (O6).

Everything online-related stays behind `Features.ONLINE`. Outfitting itself replaces Readiness for everyone, because
its domains and file checks are today's.

## Builder's calls (for Neal's review)

1. **Outfitting takes Readiness's place.** It keeps the same rail entry and Settings row, and Home's "See everything on
   this phone" opens it. The Readiness screen and its route are removed. Its model (domains, signals, file checks) is
   reused, not rewritten.
2. **Order, top to bottom:**
   1. **Try Local only:** the switch, with one line about what it does.
   2. **Your use:** O3's Ready offline at full size, every subject rather than five, with up to three suggestions ranked
      by how much of your recent use each covers per MB.
   3. **Ready for anything:** today's nine domains with their bars and signals, under that plain heading.
   4. **On this phone:** kept items (packs, books, kept articles, the online index files) with sizes; Saved for now with
      its cap and Clear; storage by source.
   5. **Files:** today's file check rows.
3. **Suggestions** are drawn from the catalog's Wikipedia flavours (Top, Top 1M, subject packs) and from "Keep the N
   you read". Each one's coverage is how many of your recent online-only reads its index holds, by QID.
   - A pack whose index isn't on the phone can't be checked by QID. It's counted by its subject, and labelled "about".
4. **Kept articles count toward no domain,** as the spec says: domains stay Granger's baseline. They do count in "Your
   use".
5. **Updates:** a kept article whose revision is behind live Wikipedia shows as an update row in On this phone, only
   when online. It's never updated automatically.
   - The check runs at most once a day, 20 revisions a request (Task 4).
6. **The reading shelf (S1):**
   - "Reading now": one large cover plus two smaller ones, with progress and marks.
   - Collections as fanned stacks.
   - "Finished" as a row of small covers.
   - A footer line pointing to Outfitting for sizes and files.

   Today's collections, filing and organise dialogs stay, reached from each collection. The shelf's storage rows and
   the "Downloads" link move to Outfitting.
7. **Book covers** use the books' own covers where they have them, and S1's coloured title blocks otherwise.
8. **The shelf in Local only** hides books it can't open (none today: every book on the shelf is a file, and books
   saved for now open offline). So it needs no grey-out; the rule is written down for when streaming comes.

## Tasks

### Task 0: branch

Branch `o4-outfitting-shelf` from main (`e412f106`), in worktree `.claude/worktrees/o4`, with the submodules and
`local.properties` as before.

### Task 1: Outfitting's model

- **`OutfittingModel`** combines four inputs:
  - O3's `readyOffline()`, extended with every subject;
  - the readiness report (domains, signals);
  - the on-this-phone inventory (packs, books, kept articles, index sets, Saved for now);
  - the file checks.
- **The inventory** (`OnPhone`): a row for each kept thing (title, kind, bytes, source). Totals by source come from
  the existing storage summary.
- **Suggestions:** up to three, ranked by recent reads covered per GB (builder's call 3).
- **Tests:**
  - suggestion ranking with fake catalogs and reads;
  - inventory totals;
  - kept articles counted in "Your use" and in no domain.

### Task 2: the Outfitting screen

- The five sections in Explore's type, as on the canvas's B direction (Home's Ready offline is its top half).
- Rows open what they name: a pack to its source screen, a book to the reader, a kept article to its topic page.
- Remove (with a confirm) on kept articles and packs, as today's Library does for packs.
- It replaces `ReadinessScreen` at its route; the rail entry and the Settings row are renamed "Outfitting" (English and
  Spanish, a Spanish word to settle).
- **Tests:** each section's rows; Remove; navigation from Home's "See everything on this phone".

### Task 3: Home's strip and links

- Home's Ready offline links to Outfitting.
- The old Readiness strip (shown when there's no history yet) becomes the spec's one line: "Everything online, nothing
  kept yet ›" or "12 of 40 recent reads work offline ›".

### Task 4: updates for kept articles

- **Once a day while online,** the kept articles' revisions are checked in batches of 20 (`action=query&prop=revisions`,
  `rvprop=ids`, one request per batch), and newer ones are listed.
- **"Update"** re-keeps one; "Update all" keeps them in one background job.
- **Tests:** the batch parse, the daily rule, and an update re-keeping.

### Task 5: the reading shelf (S1)

- `LibraryScreen` becomes the shelf:
  - Reading now (from book positions, most recent first);
  - collections as fanned stacks (three covers offset);
  - Finished (books whose position is at the end);
  - the footer to Outfitting.
- Explore's `SpreadType`, `Eyebrow` and the plate's framing.
- Collection screens, filing and import keep working: reached from a stack, and from ⋯ for import and organise.
- Storage rows and the Downloads link move to Outfitting (Task 2).
- **Tests:**
  - the shelf model: reading now, finished, collections, from fake books and positions;
  - the screen's sections;
  - import and organise still reachable.

### Task 6: Pixel 6 pass

- **Setup:** install with `adb -s` on the Pixel only, checking `lastUpdateTime` and the DB version (17) first.
- **Walk through:**
  - Outfitting from the rail, Settings and Home;
  - each section, a suggestion, Remove on a kept article;
  - Local only from the switch at the top;
  - the shelf with your books: Reading now, collections, Finished;
  - Spanish, Night and Field;
  - TalkBack by ear on the domain bars and the shelf.
- **Restore the phone afterwards.**

### Task 7: merge

- Run the full suite, then merge to main as a fast-forward.
- Update the roadmap.
- Push when Neal asks.

## Order

1. Tasks 1–3, Outfitting.
2. Task 5, the shelf.
3. Task 4, updates; it's small and can slip to O5 if needed.
4. Then the Pixel pass.
