#!/usr/bin/env python3
"""Behaviour LoRA for Ask's answer model (Plan R2, 2026-11-04): rank-16 attention adapter on Gemma 4 E2B, trained on
data/train.jsonl (pairs.py), merged into the weights and saved as an HF checkpoint for litert-torch export_hf.

  train_lora.py --out DIR [--epochs 2] [--lr 1e-4] [--rank 16] [--max-tokens 2600] [--dry-run]

Follows toy_adapter.py (2026-11-02): PEFT on MPS, bf16, q/k/v/o of the language model only, use_cache=True in the
training forward (Gemma 4 shares K/V across layers 15-34 only when a cache exists). Loss on the reply tokens only.
A tenth of the pairs are held back for a loss number; they are tuning items, not dev or held-out.

Memory (2026-11-04 probe, memprobe.py): the model is 10.2 GB in bf16, a plain forward of a 2,088-token pair peaks at
20 GB and a forward with gradients ran past 31 GB and swamped a 69 GB Mac (60 GB footprint, 43 GB swapped). Fixes:
logits only for the reply positions (the 262k-word vocabulary makes full-prompt logits 2 GB in fp32), which brings a
step to 30 GB peak and 5.4 s; --checkpoint adds gradient checkpointing (13.6 GB, 7.7 s) but has to run without the K/V
cache, so the shared-K/V layers take a path inference never takes (toy_adapter.py's note), so it is off by default.
Run with PYTORCH_MPS_HIGH_WATERMARK_RATIO=0.7 PYTORCH_MPS_LOW_WATERMARK_RATIO=0.6 so an overrun fails, not swaps.
"""
import argparse
import glob
import json
import random
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
SNAP = glob.glob("/Users/s.nealthompson/.cache/huggingface/hub/models--google--gemma-4-E2B-it/snapshots/*/")[0]
END = "<turn|>"


def load_pairs(path, seed=11, val_share=0.1):
    rows = [json.loads(l) for l in open(path, encoding="utf-8") if l.strip()]
    random.Random(seed).shuffle(rows)
    n = int(len(rows) * val_share)
    return rows[n:], rows[:n]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default=str(HERE / "data" / "train.jsonl"))
    ap.add_argument("--out", required=True)
    ap.add_argument("--epochs", type=int, default=2)
    ap.add_argument("--lr", type=float, default=1e-4)
    ap.add_argument("--rank", type=int, default=16)
    ap.add_argument("--accum", type=int, default=4)
    ap.add_argument("--max-tokens", type=int, default=2600)
    ap.add_argument("--save-every", type=int, default=0, help="also save the adapter every N optimizer steps")
    ap.add_argument("--dry-run", action="store_true", help="tokenize only and report lengths")
    ap.add_argument("--checkpoint", action="store_true", help="gradient checkpointing (less memory, no K/V cache in training)")
    a = ap.parse_args()

    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer

    tok = AutoTokenizer.from_pretrained(SNAP)
    train, val = load_pairs(a.data)

    def encode(pair):
        prompt = tok.apply_chat_template([{"role": "user", "content": pair["prompt"]}], tokenize=False, add_generation_prompt=True)
        p = tok(prompt, add_special_tokens=False)["input_ids"]
        t = tok(pair["target"] + END, add_special_tokens=False)["input_ids"]
        if len(p) + len(t) > a.max_tokens:
            return None
        return torch.tensor([p + t]), torch.tensor([[-100] * len(p) + t])

    enc_train = [(r, encode(r)) for r in train]
    enc_val = [(r, encode(r)) for r in val]
    dropped = sum(e is None for _, e in enc_train + enc_val)
    lens = sorted(e[0].shape[1] for _, e in enc_train + enc_val if e is not None)
    print(f"pairs train {len(train)} val {len(val)}; dropped over {a.max_tokens} tokens: {dropped}; "
          f"tokens median {lens[len(lens)//2]} p90 {lens[int(len(lens)*0.9)]} max {lens[-1]}", flush=True)
    print("template head:", repr(tok.apply_chat_template([{"role": "user", "content": "Q"}], tokenize=False, add_generation_prompt=True)))
    if a.dry_run:
        return
    enc_train = [(r, e) for r, e in enc_train if e is not None]
    enc_val = [(r, e) for r, e in enc_val if e is not None]

    from peft import LoraConfig, get_peft_model

    dev = "mps"
    model = AutoModelForCausalLM.from_pretrained(SNAP, dtype=torch.bfloat16).to(dev)
    model.config.use_cache = not a.checkpoint
    cfg = LoraConfig(r=a.rank, lora_alpha=a.rank, lora_dropout=0.0,
                     target_modules=r"model\.language_model\.layers\.\d+\.self_attn\.(q|k|v|o)_proj")
    if a.checkpoint:
        model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={"use_reentrant": False})
        model.enable_input_require_grads()
    model = get_peft_model(model, cfg)
    model.print_trainable_parameters()

    def step_loss(x, y):
        """Cross-entropy over the reply tokens only: logits for the last reply-length positions, nothing for the prompt."""
        n = int((y != -100).sum().item()) + 1
        logits = model(input_ids=x, use_cache=not a.checkpoint, logits_to_keep=n).logits[0, :-1].float()
        return torch.nn.functional.cross_entropy(logits, y[0, -n + 1:])
    params = [p for p in model.parameters() if p.requires_grad]
    opt = torch.optim.AdamW(params, lr=a.lr, weight_decay=0.0)
    steps = a.epochs * len(enc_train) // a.accum
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: min(1.0, (s + 1) / 20) * max(0.05, 1 - s / max(steps, 1)))

    def val_loss():
        model.eval()
        tot, n = 0.0, 0
        with torch.no_grad():
            for _, (x, y) in enc_val:
                tot += step_loss(x.to(dev), y.to(dev)).item()
                n += 1
        model.train()
        return tot / max(n, 1)

    print(f"val loss before: {val_loss():.4f}", flush=True)
    model.train()
    t0, step, seen = time.time(), 0, 0
    for epoch in range(a.epochs):
        order = list(range(len(enc_train)))
        random.Random(100 + epoch).shuffle(order)
        run = 0.0
        for i, idx in enumerate(order):
            x, y = enc_train[idx][1]
            try:
                loss = step_loss(x.to(dev), y.to(dev))
                (loss / a.accum).backward()
            except RuntimeError as e:  # MPS out of memory on a long pair: drop it, keep the run (2026-11-04: the allocator creeps)
                if "out of memory" not in str(e):
                    raise
                print(f"OOM on {enc_train[idx][0]['id']} ({x.shape[1]} tokens); skipped", flush=True)
                opt.zero_grad()
                torch.mps.empty_cache()
                continue
            run += loss.item()
            seen += 1
            if seen % a.accum == 0:
                torch.nn.utils.clip_grad_norm_(params, 1.0)
                opt.step()
                sched.step()
                opt.zero_grad()
                torch.mps.empty_cache()  # the caching allocator otherwise grows 30 -> 36 GB over ~30 steps and dies at the cap
                step += 1
                if a.save_every and step % a.save_every == 0:
                    model.save_pretrained(str(Path(a.out) / "adapter"))
                if step % 10 == 0:
                    el = time.time() - t0
                    print(f"epoch {epoch} step {step}/{steps} loss {run / a.accum / 10:.4f} lr {sched.get_last_lr()[0]:.2e} "
                          f"{el:.0f}s, ~{el / step * (steps - step) / 60:.0f} min left", flush=True)
                    run = 0.0
        print(f"epoch {epoch} done: val loss {val_loss():.4f} after {time.time() - t0:.0f}s", flush=True)
        model.save_pretrained(str(Path(a.out) / "adapter"))

    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    model.save_pretrained(str(out / "adapter"))
    model.eval()
    for r, (x, _) in enc_val[:4]:
        prompt = tok.apply_chat_template([{"role": "user", "content": r["prompt"]}], tokenize=False, add_generation_prompt=True)
        ids = tok(prompt, return_tensors="pt", add_special_tokens=False).to(dev)
        with torch.no_grad():
            gen = model.generate(**ids, max_new_tokens=120, do_sample=False)
        print(f"\n--- {r['id']} [{r['kind']}]\nTARGET: {r['target'][:300]}\nMODEL:  {tok.decode(gen[0, ids['input_ids'].shape[1]:], skip_special_tokens=True).strip()[:300]}", flush=True)
    merged = model.merge_and_unload()
    merged.save_pretrained(str(out / "merged"), safe_serialization=True)
    tok.save_pretrained(str(out / "merged"))
    print("saved", out / "merged", f"in {time.time() - t0:.0f}s total")


if __name__ == "__main__":
    main()
