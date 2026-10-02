# Personal adaptation pipeline

SpectreBoard learns from its single user's own typing: the keyboard logs what
gets typed, corrected and reverted, and comrade (the homelab workstation)
rebuilds the KenLM n-gram model from it every morning and pushes it back to the
phone. Decisions and their history live in [`../scope.md`](../scope.md)
("Personal adaptation pipeline", 2026-10-02 onwards).

Single-user device: **nothing is privacy-filtered at collection**. Personal
models and logs never go into this repo or the public HF model repo — only code.

## Status (2026-10-02)

| Step | What | State |
|---|---|---|
| 1 | Persist training signal on the phone | **done** |
| 2 | Nightly KenLM rebuild on comrade | **done, runs daily 06:00** |
| 3 | Learned reranker weights (replace the fixed dict→GRU→KenLM→spatial order) | not started — needs a few days of logs |
| 4 | Spatial label hygiene | partly (reverted autocorrects relabel their tap rows) |
| 5 | GRU continued training | not started, lowest priority |

## On the phone (app code)

| Piece | Where | What |
|---|---|---|
| Adaptation log | `spectre/adapt/AdaptationLog.kt`, hooks in `InputLogic.commitChosenWord` / `revertCommit`, `Suggest.rerankCombined` | One JSON line per event to `/data/user_de/0/com.termux.spectreboard/files/adapt/events-YYYY-MM-DD.jsonl`. `commit`: type (typed/pick/auto/cancel), typed vs committed, n-gram context, separator, swipe flag, field info (`pkg`, `itype`, `pw`, `nolearn`) and the top-10 candidates with their dict / GRU / KenLM / spatial scores. `revert`: backspace undid an autocorrect. |
| Gesture data | `latin/utils/GestureDataGathering.kt`, `GESTURE_DATA` table in `databases/heliboard.db` | Every tap-typed and swiped word with its touch points. HeliBoard's privacy gates are removed (discard-by-default, app allowlist, official-dictionary hash check, password/email/incognito/contacts filters, redaction). Tap rows are created in `Suggest`'s non-batch path and saved on commit, labelled with the committed word; a revert relabels them with the typed word. |
| Spatial model | `spectre/spatial/` | Per-key Gaussians built from tap rows (≥50 new rows, on settings reload). Swipe rows are ignored by design. |
| KenLM hot-reload | `spectre/KenLmScorer.kt` | A `FileObserver` reloads `spectre_q8.blm` when a new file is renamed over it (`MOVED_TO`). |

## On comrade (this folder)

Code lives here; `~/kenlm/` holds symlinks to it plus all data. The systemd
units in `~/.config/systemd/user/` are symlinks to `systemd/`.

| File | Job |
|---|---|
| `nightly_adapt.py` | The 06:00 run: pull → rebuild → gate → push → verify md5 → update `~/kenlm/live/spectre_q8.blm`. Log: `~/kenlm/rebuild/nightly.log`. |
| `adapt_pull.py` | Pulls `events-*.jsonl` (→ `~/kenlm/adapt_pull/raw/`) and replays them into typed text: commits append, reverts restore the typed word, deleted-and-retyped words are dropped, digits/symbols typed outside word commits are recovered from the context, password-field events are skipped. Writes `~/kenlm/out/personal_typed.txt` and `personal_heldout.txt` (every ~10th sentence by stable hash). `--no-pull` replays local copies. |
| `kenlm_rebuild.py` | Holds out the newest 10% of Claude messages, merges + cleans ChatGPT + Gemini + Claude (older 90%) + personal text, **normalises it the way the phone queries the model** (lowercase, punctuation split off, apostrophes inside words kept, sentence per line; emoji dropped on purpose), runs `lmplz -o 4` + `build_binary -q 8 -b 8 trie`, scores live vs new with `query`. Output: `~/kenlm/rebuild/<date>/` (`spectre_v2_q8.blm`, `report.txt`, `report.json`, train/test text). |
| `corpus/split_chatgpt_json.py` | ChatGPT export → user messages (all branches). `--keep-newlines` writes `*_nl.txt` with line breaks escaped as `\n`. |
| `corpus/extract_claude_corpus.py` | claude.ai export → user messages, pasted attachments excluded. Same `--keep-newlines`. |
| `corpus/extract_gemini_corpus.py` | Google Takeout Gemini activity → user prompts. |
| `corpus/merge_gru_sources.py` | Merges the sources; drops terminal/code fenced blocks and pasted terminal lines (judged per line). `--source` overrides the source list. |
| `corpus/clean_corpus.py` | Placeholder-izes URLs/paths/hashes, dedups lines. |
| `systemd/spectre-adapt.{service,timer}` | Daily 06:00 user timer (`Persistent=true`; user linger is on). |

KenLM tools: `~/opt/src/kenlm/build/bin/` (`lmplz`, `build_binary`, `query`).

### Gate

A held-out set counts once it has ≥200 tokens. The new model is pushed only if
its perplexity is **no worse** than the live model's on every counted set
(ties push, so new typing gets picked up). Initial numbers (newest 10% of
Claude messages, 28k tokens): June model 863 → v2 312.

### Push / rollback

`adb push` to `/data/local/tmp`, then as root: copy to
`files/.spectre_q8.blm.tmp`, `chown system:system`, `chmod 644`, `mv` over
`files/spectre_q8.blm`. The IME logs `KenLmScorer: model reloaded … ok=true`.
To roll back, push an older `.blm` the same way — every day's model stays in
`~/kenlm/rebuild/<date>/`; the June model is `~/kenlm/out_quant/spectre_q8.blm`.

### Manual runs

```bash
systemctl --user start spectre-adapt.service   # the full nightly run, now
python3 ~/kenlm/adapt_pull.py                   # just pull + replay
python3 ~/kenlm/kenlm_rebuild.py                # just rebuild + compare (no push)
```

## Things found on the way (2026-10-02)

- The spatial tier **never had data**: tap rows were never collected (the
  06-22 "widen tap gates" change sat in the swipe-only path) and three
  inherited privacy gates blocked swipes too.
- The June KenLM model was trained on raw cased text with punctuation attached,
  but `KenLmScorer` lowercases context and candidates — ~59k cased unigrams
  could never match.
- `merge_gru_sources.py`'s `_TERMINAL_LINE` is a `(?x)` regex; an unescaped
  `#include` turned `#` into a comment and made it match every line.
- The ChatGPT corpus comes from `split_chatgpt_json.py`, not
  `extract_chatgpt_corpus.py` (which pairs messages and loses about half).
- `~/kenlm/spectre_scorer/` is a stale copy of the JNI code; the real one is
  `scorer/cpp/` in this repo.
