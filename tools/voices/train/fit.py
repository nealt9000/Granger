#!/usr/bin/env python3
"""`python -m piper.train fit …` with the Granger voice's checkpoints (Plan V3); train.sh runs it.

piper1-gpl's entry point also checkpoints on "val_mos" (UTMOS, which it fetches through torch.hub with
trust_repo=True); with `--model.mos_metric none` that callback raises at the end of the first epoch, and LightningCLI
always appends `trainer_defaults` callbacks, so they can't be swapped from the command line. This is the same CLI
with these callbacks instead: the five best by val_mel, `last.ckpt` every epoch (what a resume needs), and a snapshot
every 100 epochs (GRANGER_SNAPSHOT_EVERY) that is never deleted, for listening at ~200, ~300, … (Task 3).

Lightning 2.6 writes a callback's `save_last` only in a step where that callback saved something else, so on the
val_mel callback `last.ckpt` went stale whenever val_mel didn't improve (40 minutes behind at epoch 703); a callback of
its own with no monitor saves it every epoch, overwriting the one file.
"""
import logging
import os

import torch
from lightning.pytorch.callbacks import Callback, ModelCheckpoint
from piper.train.__main__ import VitsLightningCLI
from piper.train.vits.dataset import VitsDataModule
from piper.train.vits.lightning import VitsModel


class EmptyMpsCache(Callback):
    """On MPS the process grew ~0.1 GB an epoch (21 GB → 33 GB by epoch 136) without this: the allocator keeps the
    blocks of every batch shape it has seen. Emptying the cache once an epoch costs nothing measurable."""

    def on_train_epoch_end(self, trainer, pl_module):
        if torch.backends.mps.is_available():
            torch.mps.empty_cache()


def main():
    logging.basicConfig(level=logging.INFO)
    torch.backends.cuda.matmul.allow_tf32 = True
    torch.backends.cudnn.allow_tf32 = True
    callbacks = [
        ModelCheckpoint(
            monitor="val_mel",
            mode="min",
            save_top_k=5,
            filename="epoch={epoch}-val_mel={val_mel:.4f}",
            auto_insert_metric_name=False,
        ),
        ModelCheckpoint(filename="last", save_top_k=1, enable_version_counter=False),
        ModelCheckpoint(
            every_n_epochs=int(os.environ.get("GRANGER_SNAPSHOT_EVERY", "100")),
            save_top_k=-1,
            filename="snapshot-epoch={epoch}",
            auto_insert_metric_name=False,
        ),
        EmptyMpsCache(),
    ]
    VitsLightningCLI(VitsModel, VitsDataModule, trainer_defaults={"max_epochs": -1, "callbacks": callbacks})


if __name__ == "__main__":
    main()
