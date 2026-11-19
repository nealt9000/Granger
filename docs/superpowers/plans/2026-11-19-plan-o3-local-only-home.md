# Plan O3: Local only, and Home redesigned

2026-11-19 · spec `docs/superpowers/specs/2026-11-18-online-offline-design.md` §7 (Local only), §3 (marks) · builds on O1
and O2 (both merged and pushed) · online/offline canvas https://claude.ai/artifact/SXH5nLhtUHj5L8DwzwesWX · Explore's
design (EX round 2, direction D) `docs/superpowers/plans/2026-11-16-plan-ex-explore.md`

**Goal:** one switch shows exactly what this phone holds. Turn on Local only, or lose the signal, and Granger:
- greys out what it can't open on the screens you browse;
- fills the screens it volunteers (Home, Wander, the Timeline, games) with what it can;
- says, for anything greyed, what keeping it would take.

Home is rebuilt in Explore's editorial language at the same time, because Home is where most of the volunteered rules
land (Neal, 2026-11-19: Home needs a design refresh to align with Explore, as will the bookshelf).

The open findings from O1 and O2 are fixed along the way.

**Not in O3:**
- Outfitting and its counts (O4).
- First run, "Never go online" and the rest of Settings → Online (O5).
- The bookshelf's build (see builder's call 7).

Everything rides behind `Features.ONLINE`, as before.

## Builder's calls (for Neal's review)

1. **Real offline counts too.** A connection state, *reach*, combines the network policy with Android's connectivity
   (a validated internet connection):
   - **online**;
   - **no signal**;
   - **Local only**.

   The two offline states look the same. Only the banner's words differ:
   - "Local only. Showing what this phone holds. Turn off"
   - "No connection. Showing what this phone holds."
2. **The switch:**
   - a cloud-off button in the top bar of Home, Explore and Search;
   - a row in Settings → Online;
   - a quick settings tile.

   It stays on across restarts (the policy is already kept).
3. **Greyed means:**
   - the item keeps its place, its content at 38% opacity, with a small "Needs internet" label;
   - a plate keeps its picture when the picture file is already on the phone (Explore's cached thumbnails, Saved for
     now, kept);
   - otherwise it's set as a word plate.
4. **The screens Granger volunteers hide what can't open; they don't grey it out.**
   - Wander and the games draw only from articles that are on the phone or saved for now.
   - Continue lists only what opens.
   - When nothing qualifies, the card says so in one line, with the way to fix it ("Keep a few articles, or a pack, to
     Wander offline").
5. **The sheet for a greyed item ("what it would take"):**
   - **Keep next time online**, with the article's estimated size. The keep is queued: the O2 keep job, which waits
     while the gate is shut.
   - **The pack that holds it**, the smallest flavour, when one of our indexes says a catalog pack holds it.

   A queued article shows "Keeps when online" on its header. It gets no new mark.
6. **Home's design round comes first,** on a canvas, the way Explore's round 2 did. It needs:
   - Explore's type, plates and spreads;
   - Continue with its marks;
   - the volunteered feeds;
   - Home online beside Home in Local only.

   Neal picks a direction, then Task 8 builds it. Home's feeds also learn to draw from Wikipedia online (O2's open
   Task 9).
7. **The bookshelf's redesign is in the same design round** (the mockups only). Its build goes with O4, because
   Outfitting's "On this phone" and the shelf are the same question asked two ways.
8. **Online sources for every language Granger supports** (English and Spanish), not just the knowledge languages, so a
   tab or history row in Spanish still names and opens itself (O2 finding: Alhambra showed "Removed pack"). The small
   index set and Search stay per knowledge language.

## Tasks

### Task 0: branch

Branch `o3-local-only-home` from main (`b186bae6`), in worktree `.claude/worktrees/o3`. Then:
- `git submodule update --init`, plus the nested taglib `utfcpp` init;
- copy `local.properties`.

### Task 1: reach

- **`Reach`** (`app/granger/online/Reach.kt`) is a `StateFlow<ReachState>`:
  - ONLINE;
  - NO_SIGNAL;
  - LOCAL_ONLY;
  - NEVER.

  It comes from `NetworkPolicy.mode` and a `ConnectivityManager` default-network callback with
  `NET_CAPABILITY_VALIDATED`.
- **`ArticleAvailability`** gains `openable(state, reach)`: on the phone or saved for now, or anything when ONLINE.
- **Tests:**
  - the state table: each policy mode × connected / not connected;
  - `openable` for each availability × reach.

### Task 2: the switch

- **Top bars:** a cloud-off `IconButton` on Home, Explore and Search, toggling `NetworkPolicy` between ONLINE and
  LOCAL_ONLY. TalkBack reads "Local only, on" or "Local only, off".
- **Settings → Online** gets a Local only switch row (beside O2's rows).
- **Quick settings tile:** `LocalOnlyTileService`, declared in the manifest, showing its state.
- **Tests:**
  - the button flips the policy;
  - the tile's state follows the policy (Robolectric).

### Task 3: the banner

- A thin bar under the top bar, shell-wide, while reach isn't ONLINE.
- Field and Night colours, 40 dp, one line.
- Its text is one of the two from builder's call 1. "Turn off" is a button only in Local only.
- It never covers content; screens pad for it.
- **Tests:** the text for each state, and Turn off.

### Task 4: browse screens grey out

- **Where:**
  - Explore's landing (the featured spread, the plates in doors);
  - room and subject spreads, the A–Z, broad topic spreads;
  - the topic page's Related and people;
  - Search results.
- **How:**
  - a `Greyed` modifier, plus the label;
  - plates use the picture-on-phone rule;
  - a tap opens the sheet (Task 6) instead of the article.
- **Search:** when not ONLINE, local results only, then one line: "Online results appear when you're connected."
- **Get books:** Keep buttons are disabled with "Needs internet", and Read is hidden.
- **Tests:**
  - plate greying by availability × reach;
  - Search's line;
  - Get books' buttons.

### Task 5: volunteered screens keep to what opens

- **Wander:** starts and walks only over openable articles. A node that can't open isn't drawn.
- **Games:** deal only openable items when not ONLINE.
- **Timeline glance:** shows only marks whose article opens.
- **Continue:** hides tabs that don't open.
- **Home feeds:** draw only openable items.
- **When empty:** each card's one-line note (builder's call 4).
- **Tests:** each source filtered by a fake availability; the empty notes.

### Task 6: "What it would take"

- **The sheet:** a greyed item's tap opens it, with two choices (builder's call 5):
  - "Keep next time online · about 1.9 MB", which queues the O2 `KeepWorker` for one article;
  - "Get Wikipedia Top · 2.7 GB" (which pack and flavour come from the indexes holding its QID, and the catalog).
- **The queued state:** the topic page header's Keep button says "Keeps when online". Cancel unqueues.
- **Tests:**
  - the sheet's choices from fake indexes and catalog;
  - queueing;
  - the queued header.

### Task 7: Home and the bookshelf, the design round (canvas)

- **Before the round:** an interview with Neal (as EX round 2 had), covering:
  - what Home is for now that Explore exists;
  - which feeds earn their place;
  - how Continue, Wander and the games sit;
  - the shelf's job beside Outfitting.
- **On a new canvas, with real Pixel data:**
  - two or three Home directions, each shown online and in Local only;
  - one bookshelf direction (builder's call 7).
- **Neal picks;** the brief and the pick go in this plan, then Task 8 builds Home.

**Interview (Neal, 2026-11-19):**
- **Home is your desk:** personal and short. Continue first, then a few things picked for you today. Explore holds the
  browsing.
- **Sections that stay:** Continue and the Timeline glance. Play and Wander leave Home. Games get their own rail icon
  once they're designed and fleshed out (later work).
- **A re-envisioned Readiness on Home** that flows into the online/offline design: a clear breakdown of what Granger has
  offline, and suggestions that make it easy to fill the gaps by getting packs. This is Outfitting's (O4) view, which
  Home shows. The mockups draw it now; O4 builds its full screen.
- **Feeds:** keep add-your-own feeds but show at most two, styled as Explore plates.
- **The bookshelf is your reading shelf:** books and collections you read, as covers, in Explore's style. Storage and
  file details move to Outfitting.

### Task 8: Home, built

- **The picked direction,** in Explore's components:
  - `Plates` and `Spread`;
  - `SpreadType`;
  - the editorial landing's rhythm.
- **Feeds online:** feeds draw from Wikipedia online when no pack covers them: On this day from the online source,
  Random from a subject from the online index.
- **Volunteered rules** from Task 5 throughout.
- **The O2 findings:**
  - the Home downloads card says "Wikipedia online index · 5 of 19 MB" (not "0 of 0") for the online index set;
  - online sources for every supported language (builder's call 8).
- **Tests:** Home's model for each section, online and in Local only; the downloads card's label.

### Task 9: Pixel 6 pass

- **Setup:** install with `adb -s` on the Pixel only, checking `lastUpdateTime` and the DB version (17) first.
- **The switch,** from all three places, and the tile.
- **Explore and Search in Local only:** greyed items and the sheet; queue a keep, turn online back on, and it's kept.
- **Real no signal:** airplane mode, which needs Neal's OK.
- **Home** in both states, in English and Spanish, Night and Field.
- **TalkBack by ear** on the banner, the switch and the greyed labels.
- **Restore the phone afterwards.**

### Task 10: merge

- Run the full suite, then merge to main as a fast-forward.
- Update the roadmap.
- Push when Neal asks.

## Order

1. Tasks 1–3 first: reach, the switch, the banner.
2. Start the Task 7 interview and canvas alongside them, since it needs Neal's time.
3. Then 4–6, the grey-outs and the sheet.
4. Then 8 once Neal has picked.
5. Then the Pixel pass.
