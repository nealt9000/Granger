# Training the Granger voice

How `en_US-granger_libritts-medium` is made (Plan V3, `docs/superpowers/plans/2026-10-22-plan-v3-trained-voice.md`): a
medium Piper model fine-tuned from Piper's **`en_US-ljspeech-medium`** checkpoint (LJ Speech, public domain) on a few
**LibriTTS-R** readers (CC BY 4.0). Nothing of `lessac` or `libritts_r` goes in, as weights or as data.

The scripts:

| Script | Does |
|---|---|
| `fetch.py base` | the `lj-med_1000.ckpt` checkpoint (sha256-checked), its `config.json`/`MODEL_CARD`, and sherpa-onnx's ljspeech voice (for `espeak-ng-data/`) |
| `fetch.py libritts <subsets…>` | LibriTTS-R subsets from OpenSLR 141 (md5-checked), the docs and the failed-restoration lists |
| `build_dataset.py list` | each reader's gender, name and usable minutes, for choosing speakers |
| `build_dataset.py build` | the chosen readers at 22 050 Hz with `metadata.csv` (`file.wav\|reader\|text`) |
| `train.sh` | piper1-gpl's `fit` (through `fit.py`), warm-started from ljspeech; rerun to resume from `last.ckpt` |
| `fit.py` | piper1-gpl's CLI with our checkpoints: 5 best by `val_mel`, `last.ckpt`, a kept snapshot every 100 epochs |
| `export_voice.py` | the ONNX with sherpa-onnx's metadata, `.onnx.json`, `tokens.txt`, `espeak-ng-data/`, `MODEL_CARD` |
| `say.py` | speaks a voice directory through sherpa-onnx (the app's 1.13.8) to WAVs, per speaker, for listening |

Then `tools/voices/pack_voice.py <id> --out <dir> --from <voice dir>` makes the catalog zip (`tools/voices/README.md`).

## Setup (pins)

Work outside the repo; the corpus and runs are tens of GB. `W` below is the work directory.

```
mkdir -p $W/src && git -C $W/src clone https://github.com/OHF-Voice/piper1-gpl.git   # at 619f513 (2026-10-17)
/opt/homebrew/bin/python3.11 -m venv $W/venv
$W/venv/bin/pip install -e "$W/src/piper1-gpl[train]"
# monotonic alignment (what build_monotonic_align.sh does, without sourcing a venv)
cd $W/src/piper1-gpl/src/piper/train/vits/monotonic_align && mkdir -p monotonic_align \
  && $W/venv/bin/cythonize -i core.pyx && mv core*.so monotonic_align/
# the espeak-ng bridge (the editable install doesn't build it)
$W/venv/bin/pip install scikit-build && cd $W/src/piper1-gpl && $W/venv/bin/python setup.py build_ext --inplace
```

Pins that worked (macOS 26, M2 Max): Python 3.11, piper1-gpl `619f513`, torch 2.14.1, lightning 2.6.6, onnx 1.23.2,
onnxruntime 1.31.0, librosa 0.11.0, numpy 2.4.6.

**macOS path-length trap.** `build_ext` compiles espeak-ng, which copies its data path into a 160-byte buffer; under a
long work directory the path is longer and the build dies with `SIGTRAP` at "Compile intonations". Build from a short
copy (`rsync -a --exclude _skbuild $W/src/piper1-gpl/ ~/pb/`, `build_ext --inplace` there), then copy
`src/piper/espeakbridge.so` and `src/piper/espeak-ng-data/` back and delete the copy.

**Why piper1-gpl, not `rhasspy/piper`.** The old trainer wants Python 3.10 and Lightning 1.x. piper1-gpl runs on
current torch/Lightning (with MPS), loads the old Lightning 1.9.5 checkpoint, and its `--model.warmstart_ckpt` (copy
every parameter whose shape matches, fresh optimizer) does what the old `--resume_from_single_speaker_checkpoint`
did: the new speaker embedding and the speaker-conditioning layers start fresh, everything else comes from ljspeech.
It is GPL-3.0, which suits a GPL app.

## Facts that shaped the recipe

- **Phonemes.** The ljspeech checkpoint was trained with espeak voice `en` (British phonemes: `ɒ`, `ə`, no rhotic r).
  LibriTTS-R readers are American, so the fine-tune uses `en-us`, and the exported `.onnx` says `voice=en-us`.
  This is the likely cause of ljspeech's "Australiar", "Rosettar" (roadmap, carried forward from V2): under `en`,
  "Rosetta" ends `-tə` and "water" ends `-tə` too, though LJ says the r, so the model learned that a final `ə`
  sometimes carries one; under `en-us` they are `-ɾə` and `-ɾɚ`. The fine-tune has to unlearn the base's habit, so
  `say.py`'s text has Rosetta, Australia and area, and the listening checks them. The
  phoneme id maps line up: ljspeech's 157 ids are an exact prefix of piper1-gpl's 166 (the extra 9 are diphthongs),
  and both have 256 symbols, so the embedding carries over unchanged.
- **Never pass `--data.vowel_clusters`.** It merges diphthongs into single tokens (`aɪ`); sherpa-onnx tokenises
  espeak's output one codepoint at a time from `tokens.txt` and would never produce them. sherpa-onnx also refuses a
  `tokens.txt` with such a line (`Error when reading tokens at Line aɪ 161`), so `export_voice.py` writes only the
  single-codepoint ones: ljspeech's 157 plus four (`̝ ̊ ɝ ʷ`) the base never saw.
- **Checkpoints.** piper1-gpl's entry point adds a `val_mos` checkpoint (UTMOS via `torch.hub`, `trust_repo=True`); with
  the metric off it raises after the first epoch, and LightningCLI always appends the entry point's callbacks, so
  `fit.py` runs the same CLI with our own. UTMOS stays off: it would run third-party hub code.
- **Size.** Multi-speaker adds speaker conditioning to the four flow steps (`gin_channels` 512, ~3 M parameters), so the
  `.onnx` is 77 MB against ljspeech's 63.5 MB (as `libritts_r-medium`). It acts on one vector per utterance; RTF
  on the Mac is unchanged.
- **sherpa-onnx metadata.** sherpa reads `model_type=vits`, `comment=piper`, `language`, `voice`, `has_espeak`,
  `n_speakers`, `sample_rate` from the `.onnx` (as on its `en_US-ljspeech-medium.onnx`); `export_voice.py` adds them.
  It exports with the TorchScript exporter (`dynamo=False`): torch ≥ 2.9 defaults to dynamo.
- **Learning-rate decay.** piper1-gpl derives the decay from `max_epochs` unless told; `train.sh` pins Piper's
  per-epoch `0.999875` so a run can be extended without changing its schedule.
- **Speakers.** In the `en_US-libritts_r-medium` Neal liked (piper-voices, sherpa's copy), speaker 0 is LibriTTS reader
  **3922** (Ashley Candland, F) and speaker 2 is **4535** (Brett W. Downey, M), both in `train-clean-360`. (The
  `libritts_r/medium` in `piper-checkpoints` is a different, newer model with numeric speaker names.)

## Commands

```
W=/Users/s.nealthompson/Developer/Granger/voice-train-2026-10-22
export PIPER_PYTHON=$W/venv/bin/python
python3 -I tools/voices/train/fetch.py base --work $W
python3 -I tools/voices/train/fetch.py libritts --work $W train_clean_100 train_clean_360
$PIPER_PYTHON tools/voices/train/build_dataset.py list --libritts $W/libritts_r --readers 3922 4535
$PIPER_PYTHON tools/voices/train/build_dataset.py build --libritts $W/libritts_r --out $W/dataset/<name> \
    --readers 3922 4535 --max-minutes 60
tools/voices/train/train.sh $W <name> <run> 2 --trainer.accelerator gpu   # or mps
$PIPER_PYTHON tools/voices/train/export_voice.py --checkpoint $W/runs/<run>/…/last.ckpt \
    --config $W/dataset/<name>/config.json --base $W/base --out $W/out
```

`train.sh` takes extra LightningCLI arguments after the speaker count (`--trainer.max_epochs`, `--data.batch_size`,
`--trainer.accelerator`); rerun with the same run name to resume after a disconnect.

## Toy run (Task 1, 2026-10-22)

Two `train-clean-100` readers, 40 (F, 3.0 min) and 27 (M, 2.8 min), 52 utterances; Mac (M2 Max), MPS, batch 8,
3 epochs, stopped after epoch 0 and resumed. The warm-start copied all 784 of ljspeech's parameters (the 20 speaker
ones started fresh); the resume restored from `last.ckpt` and skipped the warm-start; `val_mel` 0.607 → 0.580 → 0.614.
The export loads in sherpa-onnx 1.13.8 and speaks both speakers at RTF 0.05 on the Mac (ljspeech: 0.05);
`pack_voice.py --from` packs it (80.0 MB zip, 95.1 MB unpacked, 359 files). MPS ran 1.5–2.7 s a step at batch 8.

## The data (Task 2, 2026-10-22)

| Speaker id | Reader | | In LibriTTS-R | Restoration failed | Kept (1–15 s) | Utterances |
|---|---|---|---|---|---|---|
| 0 | 3922 Ashley Candland | F | 32.3 min | 0.4 min | 29.4 min | 501 before the length filter |
| 1 | 4535 Brett W. Downey | M | 20.6 min | 0.6 min | 19.2 min | 422 before the length filter |

840 utterances, 48.6 minutes, `dataset/granger-v1` (`build_dataset.py build --subsets train-clean-360 --readers 3922 4535
--max-minutes 60`). Both are all `train-clean-360` has of them; 4535 is at the low end of Piper's 20–60 minutes. If
he comes out weaker than 3922, the next reader by ear and minutes is 8699 (Jessica Atha, F, 25.4 min); 6701 (Dayle, M,
19.6 min) is no longer. Raw clips for listening: `voice-spike-2026-10-21/libritts-samples/` (four per reader, 24 kHz,
with their text).

## Round 1 (Task 3, 2026-10-22/23): 2 readers, Mac

`granger-v1` from `lj-med_1000.ckpt`, MPS (M2 Max; the rented GPU wasn't needed to learn the recipe), batch 16,
~72 s an epoch, stopped at epoch 703 (snapshot 699 kept). `val_mel`: 0.451 at epoch 200, 0.426 at 600, 0.423 at 686.

Neal on the phone and Mac WAVs: the tone is `libritts_r`'s; "Australia" is right and "Rosetta" nearly; it runs faster
than `libritts_r`. What it lacks is **rhythm and emphasis**: books less natural than wikis, word stress often
awkward ("It was the best of times, it was the worst of times": speaker 0 gets the first half, speaker 1 neither),
speaker 1 (19 minutes) behind speaker 0 (29). `noise_scale_w` 1.2 read better than 0.6/0.8/1.0 for both
(`say.py --noise-w`; the app uses sherpa's 0.8). Epochs past ~200 mostly cleaned the audio: rhythm and stress come
from the duration and pitch the model learns, and 49 minutes from two people is little to learn them from.

## Round 2: many readers, rented GPU

The lever `libritts_r` had: many readers. `granger-v2` is 42 LibriTTS-R readers, 7.5 hours, 5 352 utterances: 3922
and 4535 whole as speakers 0 and 1, then the 20 men and 20 women with the most usable `train-clean` audio, 10 minutes
each (`build_dataset.py build --readers 3922 4535 3307:10 6209:10 …`, the list in `dataset/granger-v2/minutes.tsv`).
The shipped voice keeps speakers 0 and 1; the other 40 teach timing and stress.

It warm-starts from round 1's epoch 699, widened to 42 speakers (`widen_speakers.py`), so both voices and the `en-us`
adaptation carry over and only the 40 new embedding rows start fresh. `fit.py` writes `last.ckpt` every epoch (Lightning
2.6 wrote it only when `val_mel` improved, so a disconnect could have lost an hour).

On RunPod (2026-10-23/24) the `/workspace` network volume survives the pod but refuses `chmod` (git, editable
installs) and Unix sockets (DataLoader workers), and it's slow for small files. So the venv, dataset and cache go on the
container disk, which a stop or restart wipes, and only `runs/` lives on the volume, linked in:

```
# the box: runpod/pytorch:…-ubuntu2404 with an empty Container Start Command (one pod came with a crypto miner there)
ssh <box> 'mkdir -p /root/vt/train /root/vt/dataset /root/vt/base /workspace/vt/runs && ln -sfn /workspace/vt/runs /root/vt/runs'
scp tools/voices/train/*.py tools/voices/train/*.sh <box>:/root/vt/train/
ssh <box> 'bash /root/vt/train/pod_setup.sh /root/vt'    # ~10 min; 3.11 through uv where apt has none
COPYFILE_DISABLE=1 tar --no-xattrs -C $W/dataset -cf - granger-v2 | ssh <box> 'tar -C /root/vt/dataset -xf -'
scp $W/base/granger-v1-e699-42spk.ckpt <box>:/root/vt/base/     # the first leg only; later legs resume from runs/
# on the box, in tmux
GRANGER_WARMSTART=/root/vt/base/granger-v1-e699-42spk.ckpt GRANGER_SNAPSHOT_EVERY=25 \
PIPER_PYTHON=/root/vt/venv/bin/python /root/vt/train/train.sh /root/vt granger-v2 granger-v2 42 \
    --trainer.accelerator gpu --data.batch_size 32 --data.num_workers 8 --trainer.max_epochs 600 \
    2>&1 | tee -a /workspace/vt/granger-v2.log
```

Snapshots come back to the Mac (`scp`) and export there with `export_voice.py --keep 2`: speakers 0 and 1 only.

### Round 2 results (2026-10-23/24, RTX 4090)

~84 s an epoch (150 batches of 32, ~2 it/s, GPU at 100%, 23.7 GB), so ~43 epochs an hour; a checkpoint is 0.9 GB (the
optimizer and discriminators ride along). `val_mel` on the 42-reader validation set (not comparable with round 1's):
0.459 at epoch 25, 0.448 at 75, 0.440 at 200, 0.438 at 250, 0.4305 at 356, 0.4286 at 523, 0.4259 at 579.

Neal's listening: epoch 25 lost clarity and mispronounced (42 voices sharing the model), back by 75; 125 better, speaker
1's gap more pitch inflection than rhythm; 250 to 356 a clear step for both; 600 the best so far and still improving,
speaker 0 ahead but the gap closing. `noise_scale_w` 1.2 sounded worse than 0.8 on round 2 (round 1 preferred it).

Epoch 600 packs to 80.1 MB (`pack_voice.py`, 77 MB ONNX, RTF ~0.048 on the Mac).

A third leg (2026-10-24, another 4090, ~104 s an epoch) took it from 600 to 900: `val_mel` 0.4248 at 606, 0.4240 at
639, 0.4229 at 721, 0.4220 at 731, **0.4205 at 796**, then nothing lower to 900 (0.4209 at 888). Neal: 700 a clear step
for speaker 1 over 600, a slight one for speaker 0. **Shipped: `epoch=796-val_mel=0.4205`** for both speakers (797 epochs
of round 2 on 699 of round 1), exported with `--speakers 3922=Maggie 4535=Martin` and packed to 80,117,618 bytes, sha256 `02612501…f97bc0`
(release `voices-v1`). The pod removed itself at the end
(`runpodctl remove pod`, with a read/write API key on the pod).
