# Model Spike Results — Pixel 6

Date: 2026-10-15 · Device line: Google Pixel 6 · Android 17 · SoC Tensor · totalMem 7974752256 B · tier THINK_DEEPER · LiteRT-LM 0.18.0 · Embedder: EmbeddingGemma 2 Text 270M on LiteRT-LM `EmbeddingEngine` (CPU)

Method: `SpikeActivity` (debug build, commit 038c78b), each run from a force-stopped process, phone on a table, screen on, USB-powered. Run 1 = cold cache, run 2 = warm cache; decisions use run 2. Runs were back-to-back (CPU1, GPU1, CPU2, GPU2) with no cool-down, so run 2 decode rates likely include thermal throttling. No native crashes, OOM kills, or step errors in any run.

## Raw results

### Run 2 (warm) — used for decisions

| Backend | Step | Time (ms) | Peak RSS (MB) | TTFT (ms) | Chunks/s | Error |
| --- | --- | --- | --- | --- | --- | --- |
| CPU | load small | 798 | 593 | — | — |  |
| CPU | rewrite (small) | 5497 | 1501 | 1980 | 15.4 |  |
| CPU | answer (small) | 15448 | 2063 | 10261 | 13.9 |  |
| CPU | load embedder (small resident) | 444 | 2193 | — | — |  |
| CPU | embed query + 8 passages | 8723 | 2406 | — | — |  |
| CPU | answer (small + embedder resident) | 16435 | 2405 | 10951 | 13.1 |  |
| CPU | swap small->large | 1293 | 2085 | — | — |  |
| CPU | answer (large) | 75716 | 3572 | 61711 | 6.0 |  |
| CPU | swap large->small | 1937 | 3572 | — | — |  |

| Backend | Step | Time (ms) | Peak RSS (MB) | TTFT (ms) | Chunks/s | Error |
| --- | --- | --- | --- | --- | --- | --- |
| GPU | load small | 12903 | 2454 | — | — |  |
| GPU | rewrite (small) | 6025 | 1786 | 875 | 12.4 |  |
| GPU | answer (small) | 8815 | 1843 | 2830 | 12.0 |  |
| GPU | load embedder (small resident) | 500 | 1982 | — | — |  |
| GPU | embed query + 8 passages | 8720 | 2193 | — | — |  |
| GPU | answer (small + embedder resident) | 8734 | 2193 | 2646 | 11.8 |  |
| GPU | swap small->large | 15121 | 4738 | — | — |  |
| GPU | answer (large) | 45764 | 3360 | 19903 | 3.2 |  |
| GPU | swap large->small | 9740 | 3360 | — | — |  |

### Run 1 (cold)

| Backend | Step | Time (ms) | Peak RSS (MB) | TTFT (ms) | Chunks/s | Error |
| --- | --- | --- | --- | --- | --- | --- |
| CPU | load small | 11306 | 686 | — | — |  |
| CPU | rewrite (small) | 4824 | 1587 | 1349 | 15.6 |  |
| CPU | answer (small) | 13683 | 2143 | 9058 | 15.6 |  |
| CPU | load embedder (small resident) | 1504 | 2279 | — | — |  |
| CPU | embed query + 8 passages | 7084 | 2490 | — | — |  |
| CPU | answer (small + embedder resident) | 13848 | 2489 | 9154 | 15.3 |  |
| CPU | swap small->large | 36460 | 2166 | — | — |  |
| CPU | answer (large) | 80991 | 3690 | 68231 | 6.6 |  |
| CPU | swap large->small | 6044 | 3690 | — | — |  |

| Backend | Step | Time (ms) | Peak RSS (MB) | TTFT (ms) | Chunks/s | Error |
| --- | --- | --- | --- | --- | --- | --- |
| GPU | load small | 6438 | 2463 | — | — |  |
| GPU | rewrite (small) | 5026 | 1789 | 840 | 15.3 |  |
| GPU | answer (small) | 7399 | 1850 | 2727 | 15.4 |  |
| GPU | load embedder (small resident) | 305 | 1989 | — | — |  |
| GPU | embed query + 8 passages | 7945 | 2200 | — | — |  |
| GPU | answer (small + embedder resident) | 7501 | 2198 | 2465 | 14.3 |  |
| GPU | swap small->large | 14752 | 4380 | — | — |  |
| GPU | answer (large) | 38819 | 3330 | 18930 | 4.2 |  |
| GPU | swap large->small | 8258 | 3322 | — | — |  |

First-run load times (cold cache): CPU small 11306 ms, GPU small 6438 ms, CPU large 36460 ms (swap), GPU large 14752 ms (swap)

Embedder file hash: 2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb  embeddinggemma-2-text-270m.litertlm (pinned in `tools/spike/fetch-models.sh`, verified)

## Decisions
1. Backend: GPU — `answer (small)` TTFT 2830 ms on GPU vs 10261 ms on CPU, both without errors. The Pixel 6 Mali GPU works through OpenCL.
2. Model files for the catalog: `gemma-4-E2B-it-gpu.litertlm`, `gemma-4-E4B-it-gpu.litertlm`, `embeddinggemma-2-text-270m.litertlm`
3. Latency target: 9.0 s — `rewrite (small).millis` 6025 + `answer (small).ttftMs` 2830 = 8855 ms > 3000 ms, rounded up to the next 500 ms. Most of it is the rewrite: about 60 output tokens at ~12 tokens/s.
4. Embedder: co-resident — `answer (small + embedder resident)` had no error, and peak RSS 2193 MB is 27% of totalMem (limit 60%).
5. Think deeper on 8 GB phones: not viable — `swap small->large` took 15121 ms, over the 15000 ms limit (run 1: 14752 ms). Escalated to Neal before Plan 6.

## Findings outside the decision rules
- **Embedding is slow.** `embed query + 8 passages` took about 8.7 s on CPU, roughly 1 s per ~290-token passage. The spec reranks passages from about 8 articles, which is far more than 8 passages, so reranking as designed would take tens of seconds. The embedder ran on the CPU backend; the GPU backend was not tested.
- **The large model decodes slower on GPU than on CPU.** E4B decoded at 3.2–4.2 tokens/s on GPU vs 6.0–6.6 on CPU, while E2B ran at 12–15 tokens/s on both. On GPU, Think deeper takes about 15 s to swap plus about 20 s to the first token.
- **GPU load does not benefit from the warm cache.** GPU small load was 6.4 s cold and 12.9 s warm. CPU small load dropped from 11.3 s to 0.8 s.
- **Peak RSS while swapping to the large model on GPU is 4.4–4.7 GB** (55–59% of totalMem), so the old model's memory is not fully released before the new one loads.
- **RSS probably does not include GPU allocations** (left undecided in the review). Treat the GPU peak figures as lower bounds.
