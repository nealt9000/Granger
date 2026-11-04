"""Where does the training memory go? Load E2B bf16 on MPS, one forward+backward on a long pair, report MPS allocations."""
import json, sys, time, torch
from transformers import AutoModelForCausalLM, AutoTokenizer
sys.path.insert(0, '.')
from train_lora import SNAP, END, load_pairs
def mem(tag):
    torch.mps.synchronize()
    print(f"{tag}: allocated {torch.mps.current_allocated_memory()/1e9:.1f} GB, driver {torch.mps.driver_allocated_memory()/1e9:.1f} GB", flush=True)
attn = sys.argv[1] if len(sys.argv) > 1 else None
tok = AutoTokenizer.from_pretrained(SNAP)
kw = {"dtype": torch.bfloat16}
if attn: kw["attn_implementation"] = attn
model = AutoModelForCausalLM.from_pretrained(SNAP, **kw).to("mps")
mem("after load")
dts = {}
for n, p in model.named_parameters(): dts[str(p.dtype)] = dts.get(str(p.dtype), 0) + p.numel()
print("param dtypes:", {k: f"{v/1e9:.2f}B" for k, v in dts.items()}, "attn:", getattr(model.config, "_attn_implementation", None) or getattr(model.config.get_text_config(), "_attn_implementation", None), flush=True)
train, _ = load_pairs("data/train.jsonl")
pair = max(train, key=lambda r: len(r["prompt"]))
prompt = tok.apply_chat_template([{"role": "user", "content": pair["prompt"]}], tokenize=False, add_generation_prompt=True)
p = tok(prompt, add_special_tokens=False)["input_ids"]; t = tok(pair["target"] + END, add_special_tokens=False)["input_ids"]
x = torch.tensor([p + t]).to("mps"); y = torch.tensor([[-100]*len(p) + t]).to("mps")
print("tokens", x.shape[1], flush=True)
from peft import LoraConfig, get_peft_model
import os
if os.environ.get("CKPT"): model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={"use_reentrant": False}); model.enable_input_require_grads()
model = get_peft_model(model, LoraConfig(r=16, lora_alpha=16, target_modules=r"model\.language_model\.layers\.\d+\.self_attn\.(q|k|v|o)_proj"))
def step_loss(x, y):
    n = int((y != -100).sum().item()) + 1
    logits = model(input_ids=x, use_cache=not os.environ.get("CKPT"), logits_to_keep=n).logits[0, :-1].float()
    return torch.nn.functional.cross_entropy(logits, y[0, -n + 1:])
with torch.no_grad():
    l = step_loss(x, y)
mem(f"after no-grad forward, reply logits only (loss {l.item():.3f})")
torch.mps.empty_cache()
model.train()
t0 = time.time()
loss = step_loss(x, y)
mem("after grad forward (checkpointed)")
loss.backward()
mem("after backward")
print(f"step time {time.time()-t0:.1f}s loss {loss.item():.3f}", flush=True)
