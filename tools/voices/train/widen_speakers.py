#!/usr/bin/env python3
"""Gives a multi-speaker checkpoint room for more speakers, keeping the ones it has (Plan V3, round 2).

    widen_speakers.py <checkpoint> <num speakers> <out.ckpt>

Round 2 trains on many LibriTTS-R readers, with the two Granger readers still speakers 0 and 1. piper1-gpl's
warm-start copies only parameters whose shape matches, so from round 1's 2-speaker checkpoint it would drop the
speaker embedding (`model_g.emb_g.weight`, speakers × 512) and both trained voices with it. This writes a checkpoint
whose embedding has <num speakers> rows: round 1's rows first, the rest drawn as `nn.Embedding` draws them (normal,
std 1) and scaled to the trained rows' mean norm. Everything else is copied unchanged; only `state_dict` is kept,
which is all the warm-start reads. Pass the result to train.sh as GRANGER_WARMSTART.
"""
import argparse

import torch

KEY = "model_g.emb_g.weight"


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("checkpoint")
    p.add_argument("speakers", type=int)
    p.add_argument("out")
    p.add_argument("--seed", type=int, default=0)
    a = p.parse_args()

    state = torch.load(a.checkpoint, map_location="cpu", weights_only=False)["state_dict"]
    old = state[KEY]
    if a.speakers <= old.shape[0]:
        raise SystemExit(f"{a.checkpoint} already has {old.shape[0]} speakers")
    torch.manual_seed(a.seed)
    new = torch.randn(a.speakers - old.shape[0], old.shape[1], dtype=old.dtype)
    new *= old.norm(dim=1).mean() / new.norm(dim=1, keepdim=True)
    state[KEY] = torch.cat([old, new])
    torch.save({"state_dict": state}, a.out)
    print(f"{a.out}: {KEY} {tuple(old.shape)} -> {tuple(state[KEY].shape)}, {len(state)} tensors")


if __name__ == "__main__":
    main()
