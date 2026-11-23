# Plan O7: online loose ends and the unchecked walkthroughs

2026-11-19. Builds on O1–O6 and OB (all merged and pushed; candidate 1561 waits for Neal's upload). The online/offline
spec's build order ends at O6. Neal picked O7's scope (2026-11-19): **the loose ends logged in O2–O5, and the phone
checks every O plan skipped.**

**Goal:** the online/offline work is finished to tester standard:
- nothing half-greyed;
- no stale wording;
- no "Removed pack" where there isn't one;
- checked in Spanish, in Field, with TalkBack, and through the quick settings tile.

**Not in O7:**
- LibriVox and archive.org streaming.
- Store listing and site copy.
- Kiwix online (it waits on asking Kiwix).
- Room lists from records on Explore.

## The loose ends (from the O plans' results)

| # | What | Where it was logged |
|---|---|---|
| 1 | The topic page's Related tiles (and O6's "More in" tiles) don't grey offline: tapping one gives the "what it would take" sheet, but nothing says so first | O3 results |
| 2 | The privacy card's title, "Granger now reads online too", is written for people updating; a fresh install that skips Welcome sees it too | O5 results |
| 3 | The timeline lens can show a letter instead of a picture when its first mark loaded before the privacy line was OK'd | O5 results |
| 4 | An online article in a language that isn't a knowledge language (Alhambra, es) shows "Removed pack" in Continue | O2 results |
| 5 | Home's downloads card says "0 of 0" while the online index set comes down | O2 results |

## Builder's calls (for Neal's review)

1. **Greying on the topic page.** Related and "More in" tiles use the same rule as Explore plates: an article that won't
   open now is greyed, loses its picture unless saved, and carries "Needs internet".
   - Tapping one gives the existing "what it would take" sheet.
   - People and events in the time card follow the same rule.
2. **The privacy card's wording depends on who sees it.**
   - **An install that had Granger before 1545** keeps "Granger now reads online too". "Before" means it has reading
     history, packs or a finished onboarding from before the privacy mark existed.
   - **A fresh install that skipped Welcome** sees "Before you read online" over the same line.
   - The line itself, word for word from the spec, doesn't change.
3. **The timeline lens refetches its pictures once the privacy line is seen**, as Search re-asks when Local only goes off
   (O5's fix). Marks with a letter get their picture the next time the lens shows, with no restart.
4. **Any Wikipedia language Granger has (English, Spanish) opens online,** even when it isn't one of your knowledge
   languages.
   - Continue, history and the rail name it "Wikipedia (Spanish)" / "Wikipedia (inglés)".
   - Search, Explore and the index set stay with the knowledge languages, as now.
   - "Removed pack" is kept for a pack that really was removed.
5. **The online index set stays out of Home's downloads card.** The spec says it comes down quietly. The card counts
   only what you chose to download. The index set still shows in Downloads, and in Outfitting's On this phone.
6. **The walkthroughs,** on the Pixel 7 (release build) unless noted:
   - **Spanish:** the app in Spanish, through every screen the O plans touched: Welcome, Outfit, Explore, a topic page
     (with its room line), Keep and its hint, Local only and its hint, Outfitting, the shelf, Settings → Online, Never go
     online.
   - **Field:** the same path in the Field theme.
   - **TalkBack by tree** (me): every new control has a label and a sensible reading order. The trail, "More in", the
     hints, Welcome's buttons, the Outfit footer and the marks are checked through the accessibility tree.
   - **TalkBack by ear** (Neal, about ten minutes, with a checklist I write): Welcome, a topic page, Keep, Local only. A
     tree can't tell whether something sounds right.
   - **The quick settings tile** (Pixel 6, debug): add it, toggle it, and check that the banner, the top-bar button and
     Settings follow; Never go online shows it locked.
   - **Clear Saved for now** (Pixel 6): saved pages, pictures and topic-record blocks go; a page seen before then greys
     in Local only.
   - Anything the walkthroughs find is fixed in this plan if it's small, or listed for Neal if it isn't.
7. **The Pixel 6 is set right first.** It holds a debug build pointed at a catalog on the Mac (from O6); a normal debug
   build goes back on before anything else.

## Tasks

### Task 0: branch and the Pixel 6

- Branch `o7-loose-ends` from main `314a3be5`, in worktree `.claude/worktrees/o7`.
- Install a normal debug build on the Pixel 6 (check `lastUpdateTime` and the database version first).

### Task 1: greying on the topic page (call 1)

- `RelatedTile` and the time card's rows use `rememberOpenable`. When it says no: grey, no picture unless saved, a
  "Needs internet" label, and a tap opens `WouldTakeSheet`.
- **Tests:** offline, a Related tile and a "More in" tile are greyed with the label and open the sheet; online they're
  unchanged.

### Task 2: the privacy card's wording (call 2)

- An `installedBefore` mark, set once at first start after this build from history, packs or onboarding.
- The title picked from it, in en and es.
- **Tests:** a fresh install sees "Before you read online"; an older one sees today's title.

### Task 3: the timeline lens after OK (call 3)

- `TimelineModel` listens for the privacy line turning seen and refetches the pictures it couldn't get.
- **Tests:** a lens mark without a picture gets one once the privacy line is seen.

### Task 4: other-language Wikipedia online (call 4)

- `OnlineSources` makes an archive for any language it has (en, es) on demand.
- Pack labels for online sources name the language when it isn't the app's.
- **Tests:** an es article in history with only English as a knowledge language opens online and is labelled
  "Wikipedia (Spanish)", not "Removed pack".

### Task 5: the downloads card (call 5)

- Home's downloads card leaves out online index-set records.
- **Tests:** with only the index set downloading, the card is absent; with a pack too, it counts the pack alone.

### Task 6: the walkthroughs (call 6)

- **Builds:** a release build on the Pixel 7 (installed over 1561's local build, same key) and a debug build on the
  Pixel 6.
- **Order:** Spanish, then Field, then TalkBack by tree, then the tile, then Clear. Each finding is fixed or listed.
- **For Neal:** the TalkBack checklist.

### Task 7: merge and the candidate

- Full suite, then a fast-forward merge to main.
- Update the roadmap.
- A new candidate only if Neal wants one before uploading 1561.

## Order

Tasks 1–5 are independent and small; then the walkthroughs, which test them too.

## Results (2026-11-23)

- **Tasks 1–5:** done, with tests. Merged to main by fast-forward onto `fbc68437`; full JVM suite (`test testAndroidHostTest jvmTest`) green.
- **Task 6, Pixel 6 (debug):** the tile and Clear Saved for now passed; phone restored (online, tile removed).
- **Task 6, Pixel 7 (release 1807, installed over 1625 with its data, 2026-11-23):** passed. No sidecar or time index
  changed after launch and no `-journal` files. Spanish (app language only): Home, Search, the topic
  page, "Más en …", "Temas cercanos", the Local only banner, all Spanish; "Wikipedia (inglés)" / "Wikipedia (Spanish)"
  labels right in both languages. Local only on Lake Chad: nothing greyed wrongly (all neighbours are on the phone).
  Field: Home, Search, the topic page fine. TalkBack by tree: every tappable item labelled. Phone restored (theme
  Follow the phone, app language default, Local only off).
- **TalkBack by ear:** Neal's checklist, `docs/friend-testing/talkback-by-ear-o7.md`.

### Findings, not fixed

1. The tile under Never go online looks dimmed rather than "on and locked"; a lit tile with a "Locked" subtitle is an option.
2. Leaving Never go online returns to Online, not to Local only if that was on before.
3. Clear Saved for now has no confirm step (left: it's a cache).
4. History doesn't grey online-only pages under Local only; opening one shows "Needs internet".
5. A leftover "last thing you were reading isn't available" toast seen once from the I3 build.
6. Search results shift under the finger as slower sections load (tapped "Lago Turkana", opened "Parques nacionales del lago Turkana").
7. With the app in Spanish, Search under Local only says "Nada en tu biblioteca" for a title the English pack on the phone has (knowledge languages follow the app language); a hint naming the other-language pack would help.
8. The whole rail is one tappable node carrying every rail label as well as its own items: listen for a double read in the by-ear check.
9. A title search row reads "Best of Wikipedia · Also in Best of Wikipedia" (Lake Mega Chad).
10. Ready offline counted a Spanish online article as working offline (Saved for now) but showed no subject bar for it.
