# Custom Keyboard Project — Layout & Scope

*SpectreBoard. A HeliBoard fork that composes the best pieces of Gboard, HeliBoard, and the F-Droid layout keyboard into one self-owned, self-maintainable keyboard.*

Status: **Planning complete — Phase 1 imminent.** This doc is the scratchpad so nothing gets lost before implementation starts.

---

## 1. The Core Motivation

Gboard is ~95% perfect *for the things it does well*, but it has two fatal flaws and one missing trait:

- **Voice-to-text actively sabotages communication.** Not just word mangling — it does an aggressive post-processing pass that *deletes whole clauses* from longer (German-influenced) sentence chains. Whisper transcribes what was actually said; Gboard "corrects" meaning out of existence.
- **Closed and locked.** Can't swap the VTT for anything else. Disabling Gboard's VTT just *greys out the mic button* — Google refuses to hand the slot to a system voice provider. Pure anti-competitive lock-in.
- **Not extensible / not yours.** Can't patch behaviour, can't add features, can't tune the pipeline.

Patching the Gboard APK directly was considered and rejected: it's throwaway technical debt if a full custom keyboard is the real goal. Better to learn what's worth stealing from Gboard, then build it into something we own.

---

## 1b. Current Assets (Already Built — Not Theoretical)

Before discussing what needs building, here's what already exists and runs:

| Asset | Location | Status |
|---|---|---|
| **KenLM + engine** | `~/homelab/kenlm/` — `engine.py`, `kenboardctl`, IBus XML, custom corpus/dict | **Deployed.** Running as live desktop keyboard on the homelab. Not theoretical. |
| **GRU-CIFG-LM** | `~/homelab/kenlm/GRU/gru-cifg-lm-torch/` | **Trained.** ONNX-exported (59.9MB), 9-config hyperparameter sweep running. Perplexity/top-3 accuracy validated across PyTorch and TF ports. |
| **Gesture `.so`** | Saved locally from crDroid's system partition | **Saved.** crDroid dependency is a non-issue — the lib is preserved. Shipping strategy follows HeliBoard: plugin slot, no bundling. |
| **HeliBoard fork** | `~/homelab/SpectreBoard/` (this repo) | **Renamed, clean.** Package namespace kept as `helium314.keyboard.*` for now; display name + resources changed to SpectreBoard. |
| **Gesture data pipeline** | HeliBoard's `BackgroundGatheringCache` + `GESTURE_DATA` SQLite table | **Active (swipe-only).** Fields: raw coords, keyboard geometry, suggestions, target word. Privacy filters already in place. Tap data is gated out — widening the gate is Phase 1. |

## 2. Target Architecture (the "best of three" composite)

| Source | What we want from it |
|---|---|
| **HeliBoard** (fork base) | Scaffolding + the "roundabout" / stuck-on-top customization features. Open source, already the planned integration target. |
| **Gboard** | (a) autocorrect *quality*, (b) the crisp Pixel haptic/Actuator tuning, (c) the full statistical decoding pipeline as a reference design. |
| **F-Droid layout keyboard** (~400 layouts) | The layout system / breadth of keyboard layouts. |
| **Whisper** | Voice-to-text that doesn't butcher input. |
| **Own work** | The autocorrect model stack (gru-cifg-lm + KenLM), trained on personal corpus. |

---

## 3. The Decoding Pipeline — Gboard's Three Tiers

This is the model worth replicating. Everything runs inside a hard latency budget.

### Tier 1 — Spatial Touch Model (Noisy Channel)
- Treats finger taps as noisy observations, not exact key presses.
- Gaussian probability over touch coordinates per key.
- Learns systematic user offsets (e.g. consistently landing left of `P`), tap radius, typing speed.
- **Cheap compute.** Basically free once the other two tiers exist.

### Tier 2 — KenLM (classic n-gram tier)
- Heavily pruned 5-gram / 6-gram, 8-bit quantized (`-q 8 -b 8`).
- Deterministic, sub-millisecond, no hallucination.
- **Reality check:** KenLM alone gets ~5% of the way to Gboard-quality prediction, NOT 80%. It's the floor, not the engine.

### Tier 3 — CIFG-LSTM (the neural "middle thing")
- This is where the actual intelligence lives.
- Coupled Input + Forget Gate LSTM: `f_t = 1 - i_t` → deletes ~25% of recurrent-layer params vs standard LSTM.
- Word-level, ~1.4M params, ~1.4MB at embedding size 96 + 8-bit quant (Gboard's spec).
- Understands grammar/context: dependent clauses, subject tracking, verb agreement.
- **Currently training the `gru-cifg-lm` harness on this exact architecture.**

### How they combine
- KenLM score + LSTM score, ensembled (weighted / log-linear), spatial model adjusts candidate plausibility.
- Rank candidates, return top-N.

---

## 4. Runtime / Performance Budget

- **Latency target: <50ms total, ideally 30–40ms.** 100ms starts feeling laggy.
- **On-device, no homelab dependency.** Tensor G5 via LiteRT is *fast* — compiled models finishing ~1–2s during heavy RAG indexing batches, so single-keystroke inference is well within budget (est. ~5–15ms for the LSTM, <1ms KenLM, spatial ~free).
- Export trained model → LiteRT (TFLite) → package into the keyboard.
- No network round-trip, no Tailscale dependency for typing path.

### Current training state (gru-cifg-lm)
- PyTorch GPU ~7× faster than TF CPU (303s vs 2107s per epoch); bottleneck is DataLoader CPU↔GPU batch copies, not model compute.
- Perplexity/top-3 accuracy sane and matching across implementations (port validated).
- ONNX export fixed (legacy exporter, `dynamo=False`, weights embedded inline → 59.9MB for 15M float32 params).
- **9-config sweep:** ~6h, GPU-bound, running overnight.

---

## 5. Gboard Reverse-Engineering Goals

If/when decompiling Gboard, the targets to extract or understand:

1. **VTT handler** — find where the mic button routes, the disable check that greys it out, and either redirect to Whisper or kill the proprietary path.
2. **Haptic implementation** — how it drives the Pixel Actuator (frequency/amplitude/timing patterns) to feel crisp. Replicate the *feel*, not the code.
3. **Spatial touch model** — the on-the-fly touch-coordinate capture and Gaussian modeling. Possibly extract the pipeline or just learn the approach.

### The "obfuscated blob → LLM" idea
- Feeding obfuscated *bytecode/logic* to an LLM to reverse-engineer control flow & architecture = feasible. Code is code; an LLM can identify input handling, state updates, routing, decision trees even with garbage variable names.
- Feeding the *model weight blob* to an LLM = much less useful. Weights are opaque numbers; an LLM can maybe ID the serialization format/metadata, but can't "understand" a weight matrix semantically.
- Extracting & directly reusing Gboard's CIFG-LSTM model file: blocked by likely obfuscation/encryption, proprietary serialization, vocab/tokenization mismatch, and licensing. Training our own is the cleaner path anyway.

### Gboard APK teardown findings (base APK, `arm64-v8a`)
- **`lib/arm64-v8a/` — 11 `.so` files total**, all small (10–50KB) except `libintegrated_shared_object.so` at **35.43MB**. That one file is almost certainly where the bulk of the ML runtime + model weights + spatial model live, bundled/compiled in rather than left as loose assets.
- Named libs of note: `libgboard_pipeline_jni.so` (likely tier orchestration/dispatch), `libexpressive_concepts_model_less_predictor_jni_native.so` (possibly the FST/Trie "model-less" deterministic tier), `libgrammar-checker_jni.so` (separate subsystem from next-word prediction), `libsurface_util_jni.so` (rendering, not interesting).
- **No standalone gesture/swipe `.so`** visible in the lib list — gesture recognition is either folded into `libintegrated_shared_object.so` or implemented mostly in Kotlin/Java (`classes.dex` through `classes4.dex`, multidex) with only heavy math delegated natively.
- **`assets/`** checked and ruled out as a source of model files: `assets/phenotype/` is just Google Phenotype experiment-config protobufs (feature flags, not ML), `assets/dexopt/` is ART baseline profiles (startup perf, not features), `assets/theme/` is emoji packs, loading animations (gif), and a `decision_rules.bin` of unconfirmed content. **No `.tflite`/vocab/dict files found at the asset level** — confirms models are compiled into the native blob, not droppable.
- **Conclusion: extracting Gboard's actual CIFG-LSTM/spatial model files directly is a dead end** without going deep into `libintegrated_shared_object.so` binary analysis. Not worth the effort — training our own (already in progress) is the right call.

---

## 5b. Gesture Typing — SOLVED ✅

**crDroid ships Google's proprietary swipe-typing library as a system lib** (leftover from the de-Googling process — ironic given crDroid's whole privacy-ROM premise, but convenient). Confirmed via root access (Termux) that this lib is present on-device independent of any Gboard APK.

- Dropped this lib straight into **HeliBoard's external gesture-typing-library plugin slot** (Settings → Advanced → "Load gesture typing library", expects an `arm64-v8a` `.so`).
- **Result: swipe typing in HeliBoard spells perfectly.** No extraction from a downloaded Gboard APK needed, no reverse-engineering required — it was already sitting on the phone.
- **The `.so` is saved locally** — no crDroid dependency risk. Shipping model follows HeliBoard: external plugin slot only, no proprietary bundling.
- This closes out the gesture-typing pillar of the original three-tier Gboard parity goal essentially for free.

---

## 5c. HeliBoard's Existing Gesture Data Collection Pipeline (NLNet research feature)

Confirmed via DeepSeek (root access through Termux) that HeliBoard **already has a touch-data collection pipeline built in** — this is what the "fixes to background gesture data gathering" changelog line in 4.0-alpha2 referred to. Built for an NLNet-funded "GestureTyping" research project, **not** related to spatial-model personalization on Google's side (different thing than initially assumed).

### Storage
- **In-memory** (`BackgroundGatheringCache`) — temporary holding until field change.
- **Persistent SQLite** (`heliboard.db`, table `GESTURE_DATA`) — rows with timestamp, word, source (active/background), and a JSON TEXT column.
- **Export** as `heliboard_data.gestures.json` inside a ZIP, shareable via email/file.

### Fields captured (JSON blob per gesture)
- `PointerData(id, x, y, millis)` — raw touch trajectory per pointer
- `KeyboardInfo(width, height, keys[])` — every key: left, width, top, height, value, alts
- `DictInfo(hash, type, language)` — which dictionaries were active
- `Suggestion(word, score)` — suggestion list and scores
- `targetWord` — the word actually committed
- `application, activeMode, uuid`

### Privacy filters already in place
No ngram context captured; skips password/email/incognito fields; redacts non-target suggestions in background mode.

### Consumers of the data
**None.** No feedback loop exists. Data is collected, stored, and exported purely for the NLNet research project — that's it. The feature is annotated as temporary ("will be removed once the project is finished," end date **Dec 1 2026**).

### Existing spatial correction (separate, unrelated system)
`TouchPositionCorrection` (`touch-position-correction.xml` → JNI → native) uses **static per-row hardcoded offsets**, not collected gesture data, not learned, not personalized. This is *not* a Gaussian/personalization system — it's a fixed lookup table. **We would be building the Gaussian-correction consumer from scratch; the data pipeline is already there to tap into, but nothing currently reads from it for spatial-model purposes.**

### The gate problem: swipe-only, not tap data
Both gates require `INPUT_STYLE_TAIL_BATCH`:

| Input type | Input style set | Captured? |
|---|---|---|
| Gesture/glide typing (`isGesture()`) | `INPUT_STYLE_TAIL_BATCH` | Yes |
| Tap typing (individual keys) | `INPUT_STYLE_TYPING` | No |
| Suggestion strip press | `INPUT_STYLE_NONE` | No |

Gate checked twice:
1. `Suggest.kt:337` — only adds to `BackgroundGatheringCache` when `INPUT_STYLE_TAIL_BATCH`.
2. `GestureDataGathering.kt:310` — `isSavingOk()` immediately returns `false` if not `INPUT_STYLE_TAIL_BATCH`.

**Key finding:** `INPUT_STYLE_TYPING` (regular tap typing) has access to the exact same raw coordinate structures — `InputPointers` (`mXCoordinates`, `mYCoordinates`, `mTimes`) — the data structure is already there, it's just filtered out before reaching storage. **To get tap data for the Gaussian correction model, both gates need modifying** to also allow `INPUT_STYLE_TYPING` through.

### Resulting plan for the spatial model (Tier 1)
1. **Swipe-derived data**: free right now — read `heliboard.db`'s `GESTURE_DATA` table (or hook `BackgroundGatheringCache` directly), no patching required.
2. **Tap-derived data**: requires widening the two `INPUT_STYLE_TAIL_BATCH` gates (`Suggest.kt:337`, `GestureDataGathering.kt:310`) to also fire on `INPUT_STYLE_TYPING`.
3. **The consumer** (Gaussian-update logic reading this data to build/refine the spatial model) doesn't exist yet anywhere in HeliBoard — this is net-new work, but sits on top of an existing, privacy-filtered, already-working collection pipeline rather than needing data collection built from zero.
4. No conflict with HeliBoard's own roadmap expected — the NLNet feature has no consumer and sunsets Dec 1 2026, so repurposing/extending the pipeline post-sunset (or alongside it) should be uncontroversial.

---

## 5d. Layout Sourcing — AnySoftKeyboard (ASK)

Investigated ASK as a potential source for the "400+ layouts" goal (originally going to be sourced from an F-Droid keyboard with broad layout support).

- **Not a code/architecture merge target.** Different language (Java, older codebase) vs HeliBoard (Kotlin). Gesture typing in ASK is a built-in implementation, not a pluggable `.so` slot — the crDroid-lib trick does not transfer.
- **What's actually useful: layout data only.** ASK's `LanguagePack` repo (one repo, branches per language) holds XML keyboard layout definitions — rows, keys, alternates/long-press chars — plus a dictionary compilation toolchain (`makedict.jar`, frequency-annotated XML → binary dictionary).
- **Licensing:** Apache 2.0. The "contributors grant copyright to the ASK maintainer" clause governs *contributing back to ASK's repo* — it does not restrict using their published, Apache-licensed layout XML in our own fork. Attribution to ASK (and HeliBoard, and whoever else) is the only real obligation, and that's fine — no concern about credit either way.
- **The real work:** ASK's layout XML schema ≠ HeliBoard's layout schema. Plan is:
  1. Clone the `LanguagePack` repo (one clone = all language branches).
  2. Write a converter: ASK layout XML → HeliBoard layout format.
  3. Batch-run across all desired language branches.
- **Status: about to be tackled.** HeliBoard fork is sitting on the homelab; intel above is being handed to DeepSeek to start on the conversion script and see where it gets stuck.

---

## 6. Adaptive Learning (concept — not committed)

The keyboard *could* keep improving from real usage. Different tiers, different cadences (cadences are illustrative, not final):

- **Spatial model** — cheap, low-risk. Frequent updates (e.g. nightly/weekly). Online incremental Gaussian update is even possible (`μ_new = α·μ_old + (1-α)·obs`), but a batch "learn from today" pass while charging avoids any typing-path latency cost. Must guard against learning from intentional typos.
- **KenLM** — rebuild n-gram corpus including recent text. Moderate cadence (e.g. monthly).
- **CIFG-LSTM** — the expensive one. Options: full retrain (overkill), LoRA/QLoRA adaptation (elegant), light SGD passes (fast, risk to stability). Occasional cadence.

**Privacy note:** adaptive learning implies storing typed text locally for training. Privacy-preserving (stays on device) but means a growing local archive of typed content — decide retention/scope.

---

## 7. Implementation Plan — 3 Phases (ordered by dependency + immediate payoff)

### Phase 1 — Data Faucet (minutes of work, starts paying immediately)
- **Goal:** Start accruing tap training data passively while building everything else.
- **Change:** Widen two `INPUT_STYLE_TAIL_BATCH` gates (`Suggest.kt:337`, `GestureDataGathering.kt:310`) to also accept `INPUT_STYLE_TYPING`.
- **Impact:** The pipeline is already there — swipe data flows today, tap data just needs the gate widened. Two lines changed, data starts accumulating in `GESTURE_DATA` immediately.
- **Status:** [x] Done. `Suggest.kt:337` + `GestureDataGathering.kt:310` — both now accept `INPUT_STYLE_TYPING` alongside `INPUT_STYLE_TAIL_BATCH`.

### Phase 2 — Spatial Model (days, no ML runtime needed)
- **Goal:** Personalized per-key tap correction using live gaussian models from own data.
- **Build:** Gaussian consumer reading `GESTURE_DATA` table. Compute per-key μ_x, μ_y, σ from accumulated tap/swipe coordinates. Pure coordinate math, no model inference.
- **Wire:** HeliBoard's `TouchPositionCorrection.java` → `ProximityInfo.java` → JNI → `touch_position_correction_utils.h` currently uses static XML lookup (`touch-position-correction.xml`, hardcoded per-row offsets). Replace with live gaussians computed from `GESTURE_DATA`.
- **Impact:** Highest-impact change for least effort. Personalized touch accuracy without touching the heavy neural models.
- **Status:** [ ] Consumer + wiring both need building.

### Phase 3 — Neural Stack (weeks)
- **Goal:** KenLM + GRU-CIFG-LM scoring wired into HeliBoard's suggestion pipeline.
- **Training/export:**
  - GRU-CIFG-LM → TFLite → APK assets. LiteRT interpreter invoked at keystroke time.
  - KenLM: cross-compile for arm64 or Android-compatible equivalent. Load .arpa/.binary from APK assets.
  - Custom dictionary already built in `~/homelab/kenlm/`.
- **Integration (the real architectural work):** HeliBoard's native suggest engine (`suggest/core/`) is space-model + dictionary-based, not n-gram + LSTM. Options:
  - Route suggestions through the neural stack *instead of* the native engine, or
  - Run neural stack *alongside* native and ensemble/re-rank.
- **Ensemble logic:** KenLM score ⊕ LSTM score → ranked candidates → spatial model adjusts plausibility (log-linear interpolation weights, beam search params, candidate pruning).
- **Status:** [ ] Pending. Models trained and ONNX-exported; TFLite conversion, cross-compilation, and suggest-engine integration all TBD.

---

## 8. Parallel / Post-Phase Work

### Remaining work slots in after these three pillars

| Item | Phase dependency | Notes |
|---|---|---|
| Haptics (`VibrationEffect.EFFECT_CLICK`/`EFFECT_TICK`) | None | Standard public API, just implementation |
| ASK layout converter | None | Mechanical XML transform, can run in parallel with any phase |
| Whisper integration | Done | on-device strict G5 encoder worker (whisper-base, 512-dim) + ORT FP32 decoder |
| Adaptive learning | Post-Phase 3 | LoRA/QLoRA for LSTM, corpus rebuild for KenLM, online Gaussian update for spatial |

### Open Decisions / TODO

- [ ] Pick the winning config from the gru-cifg-lm sweep → that's the Tier-3 model.
- [x] ~~Decide: extract Gboard spatial model code, or build spatial model fresh from own typing data.~~ → Gboard extraction ruled out (buried in 35MB native blob, not worth it). Building fresh on top of HeliBoard's collection pipeline.
- [x] ~~Gesture typing~~ → SOLVED. crDroid's Google swipe lib saved locally + HeliBoard's plugin slot.
- [x] ~~Widen the two `INPUT_STYLE_TAIL_BATCH` gates (`Suggest.kt:337`, `GestureDataGathering.kt:310`) — **Phase 1.**~~ Done.
- [ ] Build Gaussian consumer + wire into `TouchPositionCorrection` path — **Phase 2.**
- [ ] GRU-CIFG-LM → TFLite, KenLM → arm64, wire into suggest pipeline — **Phase 3.**
- [ ] Confirm crDroid + APatch can run a self-signed keyboard cleanly (no conflict w/ existing IME slots).
- [x] ~~Whisper integration: on-device (whisper.cpp / LiteRT) vs endpoint.~~ → Done with strict G5 encoder worker, FP32 whisper-base (512-dim, 47MB G5 encoder, 300MB FP32 decoder via optimum/ONNX Runtime).
- [ ] Decide adaptive-learning scope & local text retention policy.
- [ ] Build ASK layout XML → HeliBoard layout format converter.
- [ ] Wire in Gboard-style haptics (`VibrationEffect.EFFECT_CLICK`/`EFFECT_TICK` fired at `ACTION_DOWN`).
- [x] ~~Name the project.~~ → SpectreBoard.

### Key Integration Challenge
HeliBoard's native suggest engine (`suggest/core/`, JNI) is space-model + dictionary-based — a fundamentally different architecture from the planned KenLM + LSTM ensemble. Replacing it outright means rebuilding the suggestion strip routing and candidate ranking in Java/Kotlin. Running the neural stack alongside it (dual path → re-rank) may be lower risk but means two full scoring passes per keystroke. This is the architectural crux of Phase 3 and merits a dedicated design session before touching code.

---

*Workflow reminder: explore for a few days, build the mental model, then touch code.*
