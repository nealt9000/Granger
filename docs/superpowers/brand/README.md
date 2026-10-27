# Brand art

Reference art and brand packages, one dated folder each, like the specs and plans. The direction is set in `docs/superpowers/specs/2026-10-23-brand-vision-design.md`.

| Folder | What | Status |
|---|---|---|
| `2026-10-16-neural-flame/` | The Neural Flame concept image (Gemini), the first icon's source | Retired by the 2026-10-23 brief |
| `2026-10-20-fan-mark/` | The fan-mark package (mark, lockup, icons, fonts, splash, `app/src` drop-in); its README says how it installs | Retired as a mark; its type choices (Spectral, Courier Prime) carry forward |
| `2026-10-23-personal-player-concept/` | The first personal-player concept (ElevenLabs) and its navy/parchment palette preview | Superseded by the field recorder the same day |
| `2026-10-23-field-recorder-concept/` | The field recorder (ElevenLabs, Neal's prompt in the brief §8) and `granger-palette-field-kit.png`, the field-kit palette on two phone screens | **Current reference**; not a shippable asset (generated; shows third-party marks) |
| `2026-10-26-mark-sketches/` | Neal's three favourite mark sketches (ElevenLabs): a book fused with a cassette and a squared G | The first mark (2026-10-26): sketch C and its clean redraw (`granger-mark-from-sketch-c.svg`, the overlay against the sketch, `redraw_sketch_c.py`). Replaced by the owl on 2026-10-27 |
| `2026-10-27-owl-mark/` | Neal's owl-in-the-G sketches (ElevenLabs): `sketch-owl-wing.png` (the pick) and `sketch-owl-perch.png` (kept for a notification cut) | **The chosen mark** (2026-10-27): `granger-mark-owl.svg` (eyes, pupils, body), the overlay against the sketch, previews, and the pipeline in `redraw/` (trace, smooth, fit_edges, construct, export). The app's drawables come from `tools/brand/make_drawables.py`. The sketches are generated, not shippable |
| `2026-10-27-tagline/` | The wordmark and tagline comparison boards (`wordmarks.png`: the stencil vs six faces; `taglines.png`: four lines under the Spectral lockup) and the scripts that drew them | **Chosen** (2026-10-27): GRANGER in Spectral caps (F) and *The world's knowledge. Yours to keep.* Brief §3 and §8 |
| `2026-10-27-palette/` | `palette-board.png`: the current palette and options A (the owl), B (the instrument) and C (Enamel Night) on the app's Home, player and Welcome, Field and Night; `variants.py` renders them through ThemeShots | **Chosen** (2026-10-27): A's Field with C's Night, on the phone. Brief §8 |

The Vitruvian draft (its own `tools/brand/` scripts, a traced mark, a Play icon; today's `tools/brand/` is the new mark's) was retired by the brief; its unmerged branch `p1-brand` was deleted on 2026-10-23.

Add a new package as `YYYY-MM-DD-<name>/`.
