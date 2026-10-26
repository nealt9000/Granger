# Plan V3: A Granger voice of our own — a medium Piper model fine-tuned from LJ Speech on LibriTTS-R

> **For agentic workers:** this is a spike with a trained artefact at the end. Work in a worktree named `trained-voice` off `main`. The app code is not touched here (the voice drops into V1's catalog path); the deliverables are a training recipe under `tools/voices/train/`, the voice itself, numbers from the Pixel 6, and Neal's listening verdict. Steps use checkbox (`- [ ]`) syntax for tracking. Coordinate the Pixel with the other sessions (see "The phone").

**Why.** Neal's listening (2026-10-21 and 2026-10-22, `docs/superpowers/spikes/2026-10-voice-spike-results.md`): the LibriTTS voices are the clear stand-out; `ljspeech` (shipping) is good but plain; the four medium LibriVox voices (`norman`, `kusal`, `kristin`, `john`) sound no better than `ljspeech`. The two LibriTTS voices Piper publishes can't ship as they are: `en_US-libritts_r-medium` is fine-tuned from `lessac` (Blizzard 2013 data, non-commercial), and `en_US-libritts-high` is clean (CC BY 4.0, trained from scratch) but eight times too slow on the Pixel 6 (real-time factor 0.99 with the screen on, 1.38 off; the bar is 0.5; its int8 build is slower still). Neal: "If we could split the quality/performance difference between libritts and ljspeech we'd be set."

**The idea.** `libritts_r-medium` is nothing but a medium-size Piper model fine-tuned on LibriTTS-R speakers; its only flaw is its base. Do the same fine-tune from the **`ljspeech-medium` checkpoint** (LJ Speech, public domain) on a few LibriTTS-R speakers (CC BY 4.0). The result has `ljspeech`'s size and speed (63 MB, RTF 0.12 on the Pixel 6), the LibriTTS sound, and clean provenance. This is exactly how most of Piper's English voices were made, with a different starting checkpoint.

**Goal.** One voice directory, `vits-piper-en_US-granger_libritts-medium` (model `.onnx`, its `.onnx.json`, `tokens.txt`, `espeak-ng-data/`, `MODEL_CARD`), with two or three named speakers (at least one female and one male), that Neal rates at or near `libritts_r` for naturalness and that measures RTF ≤ 0.5 with the screen off on the Pixel 6 through the real player. Then it goes into the catalog through V2's Task 3 (release asset on `voices-v1`, `curated.json`, re-sign), with the attribution Settings shows: "Trained on LibriTTS-R (CC BY 4.0) speakers …, fine-tuned from Piper's LJ Speech model (public domain)".

## Facts to start from

- **Checkpoints:** `https://huggingface.co/datasets/rhasspy/piper-checkpoints`, path `en/en_US/ljspeech/medium/` (a PyTorch Lightning `epoch=….ckpt`). Also `en/en_US/libritts_r/medium/` for reference only (lessac-derived: never ship anything trained from it).
- **Training code:** Piper's trainer, `piper_train` in `rhasspy/piper` (`src/python/`, `TRAINING.md`; the repo is archived but pinned installs work: Python 3.10, PyTorch Lightning 1.x, `piper-phonemize`), or the newer `rhasspy/piper1-gpl` (`python3 -m piper.train …`, GPL-3.0, fine for a GPL app) if it installs more cleanly. Use whichever runs; record the pins. The recipe in `TRAINING.md`: `piper_train.preprocess` (LJSpeech-style `metadata.csv` of `id|text` or `id|speaker|text` for multi-speaker, 22 050 Hz, `--language en-us`), `piper_train` with `--resume_from_checkpoint` (single-speaker) or `--resume_from_single_speaker_checkpoint` (a multi-speaker model from a single-speaker checkpoint: the `libritts_r-medium` recipe), `--quality medium`, then `piper_train.export_onnx` and the `config.json` copied beside the model as `<name>.onnx.json`.
- **Data:** LibriTTS-R, OpenSLR 141 (`train-clean-100` is ~6 GB; also on Hugging Face as `mythicinfinity/libritts_r`). 24 kHz; Piper medium wants 22 050 Hz (resample). The speakers Neal liked in `libritts_r-medium` are its speaker ids 0 (female) and 2 (male): their LibriTTS reader ids are in `en_US-libritts_r-medium.onnx.json` under `speaker_id_map` (download `vits-piper-en_US-libritts_r-medium.tar.bz2` from sherpa-onnx's `tts-models` release to read it). Pick speakers with the most audio (aim for 30–60 minutes each; LibriTTS-R readers in `train-clean-360` have up to a few hours).
- **Compute:** a single rented GPU (an A10 or T4 class on RunPod, Lambda or similar; Neal's account and card) runs a medium fine-tune of a few thousand utterances for ~1 000 epochs in a few hours, on the order of $10–20. The Mac Studio (M1) through PyTorch MPS is the fallback if the trainer's Lightning version supports it; expect days rather than hours, and check the first epochs' loss before committing to it. Checkpoint every epoch and keep the last few; the run must survive a disconnect.
- **How Piper's own voices were made:** the model cards say "Finetuned from U.S. English lessac voice (medium quality)" with, typically, a few hundred to ~2 000 epochs on 20–60 minutes of audio. The quality knee for a fine-tune is early; listen to an export at ~200 epochs before running longer.
- **Packing for the phone:** `tools/voices/pack_voice.py <id> --out <dir> --from <voice dir>` makes the catalog zip; for a listening copy, push the unpacked directory to `/sdcard/Android/data/app.granger/files/voices/<id>/` and it appears in Settings → Voice as "Granger voice (granger_libritts)". `espeak-ng-data/` and `tokens.txt` are the same as `ljspeech`'s (copy them from its directory: `vits-piper-en_US-ljspeech-medium.tar.bz2`).
- **Measuring:** `tools/spike/voice/run-voice.sh <label> [--screen-off] --ez service true --es voice granger:<id>` reads the three spike passages ×3 through the real player; `Voice: synth` lines give per-sentence RTF; the V2 scratch summary (`v2-rtf-summary.py`) or `tools/spike/voice/tabulate.py` tabulates. Pass: RTF ≤ 0.5 with the screen off.

## Decisions (defaults; Neal can change them)

1. **Multi-speaker, two or three speakers, one download** (the `libritts_r` recipe, `--resume_from_single_speaker_checkpoint`), so one catalog entry carries a female and a male voice and the picker V2 sketched gets its use. If the multi-speaker fine-tune won't converge, fall back to two single-speaker fine-tunes (`--resume_from_checkpoint`) and ship two entries.
2. **Speakers:** the readers behind `libritts_r-medium`'s speakers 0 and 2, if they have enough audio in LibriTTS-R; otherwise the closest-sounding readers with the most audio. Neal listens to a handful of raw LibriTTS-R samples before training if the choice is open.
3. **Licence line** in `MODEL_CARD` and `curated.json`: dataset LibriTTS-R, CC BY 4.0 (with the reader ids), base model Piper `en_US-ljspeech-medium` (public domain), training code GPL-3.0 / MIT as used, the result under CC BY 4.0's terms (attribution shown in Settings).
4. **Name:** `en_US-granger_libritts-medium`; directory `vits-piper-en_US-granger_libritts-medium`.
5. **Nothing of `lessac` or `libritts_r` goes into the training**, not as a checkpoint, not as data.

## The phone

The Pixel 6 is shared. The V2 session (granger-1d) holds it for a 4-thread `libritts-high` measurement and the V2 walkthrough; the R1 session (granger-0d) needs it for half an hour around 2026-10-23 morning. Ask on the cross-session channel before taking it, back up both databases before any install (`run-as app.granger`, copy `databases/*` into `files/db-backup-<date>/`), never `connectedDebugAndroidTest`, never while a personal app is in front. Listening needs no install: a pushed voice folder is enough.

## Tasks

### Task 1: The recipe runs end to end on a toy

- [x] `tools/voices/train/README.md` (what this is, pins, the exact commands) and `tools/voices/train/` scripts: fetch the checkpoint and the ljspeech voice (for `espeak-ng-data`/`tokens.txt`), fetch LibriTTS-R subsets, build the `metadata.csv` for chosen speakers at 22 050 Hz, preprocess, train, export, pack.
- [x] Prove the pipeline on the Mac with a few minutes of one speaker for a handful of epochs: it preprocesses, trains a step, exports an `.onnx` that sherpa-onnx loads (the app's `SherpaVoice.detect` wants `*.onnx`, `tokens.txt`, `espeak-ng-data/`). Commit the scripts.

### Task 2: Speakers and data

- [x] Map `libritts_r-medium`'s speakers 0 and 2 to LibriTTS-R reader ids; list each candidate reader's minutes in `train-clean-100/360`. Pick two or three (Decision 2); put raw samples in `/Users/s.nealthompson/Developer/Granger/voice-spike-2026-10-21/libritts-samples/` for Neal if the choice is open.
- [x] Build the dataset (resampled, trimmed, `id|speaker|text`), note minutes per speaker in the README.

### Task 3: The fine-tune

- [x] On the GPU (or MPS; record which and why): start from the `ljspeech-medium` checkpoint, multi-speaker (Decision 1), checkpoint each epoch. Export at ~200 epochs, listen (push to the phone), continue in steps; stop when another few hundred epochs stop improving. Keep the loss curve and the epoch chosen in the README.
- [x] Export the final model, pack the voice directory, push it to the phone.

### Task 4: Numbers and listening

- [x] RTF through the real player, screen on and off (`run-voice.sh`); it must come in like `ljspeech` (≈ 0.12–0.2). Record the table in `docs/superpowers/spikes/2026-10-voice-spike-results.md` under a "Trained voice (V3)" heading.
- [ ] Neal's ratings beside `ljspeech` and `libritts_r` (naturalness, names and numbers, pacing; books at 0.8× and 1.0×, an article), and one sentence with **Australia, Rosetta and area** in it: `ljspeech` ends such words with an "r" ("Australiar"), the phonemes are right, so the model is what must fix it. If it doesn't reach `libritts_r`, say what's short (more epochs, more data, another speaker) and whether another round is worth it.

### Task 5: Into the catalog (with the V2 session)

- [ ] `pack_voice.py` zip, its sha256 and sizes; upload to GitHub release `voices-v1` (`gh release upload`, Neal's word first); `curated.json` entry with the licence and attribution (Decision 3) and the named speakers; the V2 session's speaker plumbing (its Task 3) if the voice is multi-speaker; `catalog_gen.py build`, `sign`, `verify`, commit on `main`, push (Neal's word).
- [ ] The roadmap: a V3 row, and a note in "Carried forward from V2".
