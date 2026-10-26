# Brand art

Reference art and brand packages, one dated folder each, like the specs and plans. The direction is set in `docs/superpowers/specs/2026-10-23-brand-vision-design.md`.

| Folder | What | Status |
|---|---|---|
| `2026-10-16-neural-flame/` | The Neural Flame concept image (Gemini), the first icon's source | Retired by the 2026-10-23 brief |
| `2026-10-20-fan-mark/` | The fan-mark package (mark, lockup, icons, fonts, splash, `app/src` drop-in); its README says how it installs | Retired as a mark; its type choices (Spectral, Courier Prime) carry forward |
| `2026-10-23-personal-player-concept/` | The first personal-player concept (ElevenLabs) and its navy/parchment palette preview | Superseded by the field recorder the same day |
| `2026-10-23-field-recorder-concept/` | The field recorder (ElevenLabs, Neal's prompt in the brief §8) and `granger-palette-field-kit.png`, the field-kit palette on two phone screens | **Current reference**; not a shippable asset (generated; shows third-party marks) |
| `2026-10-26-mark-sketches/` | Neal's three favourite mark sketches (ElevenLabs): a book fused with a cassette and a squared G | **The chosen mark** (2026-10-26): sketch C, its clean redraw (`granger-mark-from-sketch-c.svg`, the overlay against the sketch, `redraw_sketch_c.py`). The app's drawables come from `tools/brand/make_drawables.py`. The sketches are generated, not shippable |

The Vitruvian draft (`tools/brand/`, traced mark, Play icon) was retired by the brief; its unmerged branch `p1-brand` was deleted on 2026-10-23.

Add a new package as `YYYY-MM-DD-<name>/`.
