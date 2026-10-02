#!/usr/bin/env python3
"""
adapt_pull.py — pull SpectreBoard AdaptationLog events from the Pixel and
rebuild the user's actually-typed text from them.

Step 2 of the personal adaptation pipeline (SpectreBoard scope.md, 2026-10-02).

1. Pull every events-*.jsonl from the IME's DE files dir over Tailscale ADB
   into adapt_pull/raw/ (re-pulled each run; today's file is still growing).
2. Replay commit/revert events into sentences:
     - commit  → append the committed word
     - revert  → replace the last word with what was typed; the re-commit of
                 that same typed word that usually follows is skipped
     - if ctx matches the sentence minus its last 1-3 words, those words were
       deleted and retyped → drop them and continue
     - if ctx contains words that were never committed as words (digits,
       symbols typed directly) and the sentence ends with the start of ctx,
       append the missing ctx words and continue
     - new sentence when ctx is just "<S>", after a >2 min gap, or when the
       logged context doesn't continue the current sentence (field switch)
3. Split into out/personal_typed.txt (training) and out/personal_heldout.txt
   (every ~10th sentence by stable hash — the eval set a rebuilt model must beat).

Raw logs include password/OTP fields (save-everything rule). The raw copies
keep them; the replayed training text skips events logged with "pw": true
(field info logged since 2026-10-02 15:46) so passwords never become suggestions.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from pathlib import Path

DEVICE = "100.69.13.12:5555"
REMOTE_DIR = "/data/user_de/0/com.termux.spectreboard/files/adapt"
HOME = Path.home() / "kenlm"
RAW_DIR = HOME / "adapt_pull/raw"
TRAIN_OUT = HOME / "out/personal_typed.txt"
HELDOUT_OUT = HOME / "out/personal_heldout.txt"

SENTENCE_GAP_MS = 120_000
HELDOUT_EVERY = 10
BOS = "<S>"


def adb(*args: str) -> str:
    return subprocess.run(["adb", "-s", DEVICE, *args], check=True,
                          capture_output=True, text=True).stdout


def pull_logs() -> list[Path]:
    subprocess.run(["adb", "connect", DEVICE], capture_output=True, text=True)
    listing = adb("shell", "su", "-c", f"'ls {REMOTE_DIR}'")
    names = sorted(n.strip() for n in listing.split() if n.strip().endswith(".jsonl"))
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    pulled = []
    for name in names:
        content = adb("shell", "su", "-c", f"'cat {REMOTE_DIR}/{name}'")
        dest = RAW_DIR / name
        dest.write_text(content, encoding="utf-8")
        pulled.append(dest)
    return pulled


def load_events(files: list[Path]) -> list[dict]:
    events = []
    for f in files:
        for line in f.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                events.append(json.loads(line))
            except json.JSONDecodeError:
                continue  # partially written last line
    events.sort(key=lambda e: e.get("t", 0))
    return events


# Punctuation typed directly (quotes, brackets, ...) sticks to words in the logged
# context ("\"forced") but not to the committed word (forced): compare without it.
_EDGE_PUNCT = "\"'“”‘’()[]{}<>.,!?:;*_-…"


def norm(words: list[str]) -> list[str]:
    return [w.strip(_EDGE_PUNCT).lower() for w in words]


def continues(sentence: list[str], ctx: str) -> bool:
    """Does the logged n-gram context match the tail of the current sentence?"""
    words = [w for w in ctx.split() if w != BOS]
    if not words:
        return False
    return norm(sentence[-len(words):]) == norm(words)


def fill_uncommitted(sentence: list[str], ctx: str) -> bool:
    """ctx = sentence tail + words that bypassed commitChosenWord (e.g. "5").
    Append those words if the sentence ends with the start of ctx."""
    words = [w for w in ctx.split() if w != BOS]
    for k in range(len(words) - 1, 0, -1):
        if norm(sentence[-k:]) == norm(words[:k]):
            sentence.extend(words[k:])
            return True
    return False


def replay(events: list[dict]) -> list[str]:
    sentences: list[str] = []
    cur: list[str] = []
    last_t = 0
    skip_recommit: str | None = None

    def flush():
        if cur:
            sentences.append(" ".join(cur))
            cur.clear()

    for ev in events:
        if ev.get("pw"):
            continue  # password field: kept in raw logs, never in training text
        t = ev.get("t", 0)
        ctx = ev.get("ctx", "")
        if ev.get("ev") == "revert":
            typed = ev.get("typed", "")
            committed = ev.get("committed", "").split()  # autocorrect may have split a word
            if committed and typed and cur[-len(committed):] == committed:
                cur[-len(committed):] = typed.split()
                skip_recommit = typed
            last_t = t
            continue
        if ev.get("ev") != "commit":
            continue
        word = ev.get("committed", "").strip()
        if not word:
            continue
        if skip_recommit is not None:
            redo = skip_recommit
            skip_recommit = None
            if word == redo and ev.get("typed") == redo:
                last_t = t
                continue
        if cur and ctx != BOS and not continues(cur, ctx):
            # deleted-and-retyped words: ctx matches the sentence without its tail
            for drop in range(1, min(3, len(cur) - 1) + 1):
                if continues(cur[:-drop], ctx):
                    del cur[-drop:]
                    break
            else:
                fill_uncommitted(cur, ctx)
        if ctx == BOS or (last_t and t - last_t > SENTENCE_GAP_MS) or (cur and not continues(cur, ctx)):
            flush()
        cur.extend(word.split())  # "younrecommended" → "you recommended" is two words
        last_t = t
    flush()
    return sentences


def is_heldout(sentence: str) -> bool:
    h = int(hashlib.sha1(sentence.encode("utf-8")).hexdigest(), 16)
    return h % HELDOUT_EVERY == 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--no-pull", action="store_true", help="replay already-pulled raw logs only")
    args = ap.parse_args()

    files = sorted(RAW_DIR.glob("events-*.jsonl")) if args.no_pull else pull_logs()
    events = load_events(files)
    sentences = replay(events)
    train = [s for s in sentences if not is_heldout(s)]
    heldout = [s for s in sentences if is_heldout(s)]

    TRAIN_OUT.parent.mkdir(parents=True, exist_ok=True)
    TRAIN_OUT.write_text("\n".join(train) + ("\n" if train else ""), encoding="utf-8")
    HELDOUT_OUT.write_text("\n".join(heldout) + ("\n" if heldout else ""), encoding="utf-8")

    commits = sum(1 for e in events if e.get("ev") == "commit")
    reverts = sum(1 for e in events if e.get("ev") == "revert")
    words = sum(len(s.split()) for s in sentences)
    print(f"Log files: {len(files)}  events: {len(events)} (commits {commits}, reverts {reverts})")
    print(f"Sentences: {len(sentences)} ({words} words) → train {len(train)}, held-out {len(heldout)}")
    print(f"  {TRAIN_OUT}\n  {HELDOUT_OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
