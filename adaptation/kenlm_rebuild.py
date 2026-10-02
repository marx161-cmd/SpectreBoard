#!/usr/bin/env python3
"""
kenlm_rebuild.py — rebuild the SpectreBoard KenLM model and compare it with the
live one. Step 2 of the personal adaptation pipeline (SpectreBoard scope.md,
2026-10-02). Nothing is pushed to the phone.

1. Hold out the newest 10% of Claude messages (the old model never saw them;
   the new one must not train on them) as an interim eval set.
2. Training corpus = merge + clean of ChatGPT + Gemini + Claude (older 90%),
   plus out/personal_typed.txt from adapt_pull.py.
3. Normalise everything the way the phone queries the model
   (KenLmScorer.kt lowercases context + candidates; NgramContext words carry no
   punctuation): lowercase, punctuation split off, apostrophes inside words
   kept, one sentence per line. The old model was trained on raw cased text
   with punctuation attached, so those entries could never match a query.
4. lmplz -o 4 → build_binary -q 8 -b 8 trie (QuantTrieModel, what the JNI loads).
5. Score the live model (~/kenlm/live/spectre_q8.blm = what's on the phone)
   and the new one with KenLM `query` on the held-out sets.

Output: ~/kenlm/rebuild/<YYYY-MM-DD>/ (spectre_v2_q8.blm, report.txt,
report.json, train/test text). The ARPA and merge intermediates are deleted.
Pushing is nightly_adapt.py's job, not this script's.
"""

from __future__ import annotations

import datetime as dt
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

HOME = Path.home() / "kenlm"
BIN = Path.home() / "opt/src/kenlm/build/bin"
CLEANER = HOME / "GRU/gru-cifg-lm/scripts/clean_corpus.py"
MERGE = HOME / "merge_gru_sources.py"
OLD_MODEL = HOME / "live/spectre_q8.blm"  # copy of the model currently on the phone

CHATGPT = HOME / "GRU/source_export/extracted/user_nl.txt"
GEMINI = HOME / "out/gemini_user.txt"
CLAUDE = HOME / "out/claude_user_nl.txt"
PERSONAL_TRAIN = HOME / "out/personal_typed.txt"
PERSONAL_HELDOUT = HOME / "out/personal_heldout.txt"

ORDER = 4
CLAUDE_TEST_FRACTION = 0.10

_PLACEHOLDER = re.compile(r"<[A-Z]+>")          # clean_corpus.py's <URL>/<PATH>/<HASH>
_SENT_SPLIT = re.compile(r"(?<=[.!?])\s+")
_TOKEN = re.compile(r"[^\W_]+(?:['’][^\W_]+)*")  # letters/digits, internal apostrophes kept


def normalize(line: str) -> list[str]:
    """One cleaned corpus line → phone-style sentences."""
    line = _PLACEHOLDER.sub(" ", line)
    out = []
    for sent in _SENT_SPLIT.split(line):
        toks = [t.replace("’", "'") for t in _TOKEN.findall(sent.lower())]
        if toks:
            out.append(" ".join(toks))
    return out


def normalize_file(src: Path, dest: Path) -> int:
    n = 0
    with dest.open("w", encoding="utf-8") as f:
        for line in src.read_text(encoding="utf-8").splitlines():
            for sent in normalize(line):
                f.write(sent + "\n")
                n += 1
    return n


def run(cmd: list, **kw) -> subprocess.CompletedProcess:
    print("+", " ".join(str(c) for c in cmd), flush=True)
    return subprocess.run([str(c) for c in cmd], check=True, **kw)


def merge_and_clean(sources: list[Path], work: Path, name: str) -> Path:
    merged = work / f"{name}_merged.txt"
    cleaned = work / f"{name}_clean.txt"
    run([sys.executable, MERGE, "--out", merged, *sum((["--source", s] for s in sources), [])],
        stdout=subprocess.DEVNULL)
    run([sys.executable, CLEANER, "--input", merged, "--output", cleaned], stdout=subprocess.DEVNULL)
    return cleaned


def query(model: Path, text: Path) -> dict:
    out = run([BIN / "query", "-v", "summary", model], stdin=text.open(),
              capture_output=True, text=True).stdout
    stats = {}
    for key, pat in {
        "ppl_incl_oov": r"Perplexity including OOVs:\s*([\d.e+-]+|inf)",
        "ppl_excl_oov": r"Perplexity excluding OOVs:\s*([\d.e+-]+|inf)",
        "oovs": r"^OOVs:\s*(\d+)",
        "tokens": r"^Tokens:\s*(\d+)",
    }.items():
        m = re.search(pat, out, re.MULTILINE)
        stats[key] = float(m.group(1)) if m else None
    return stats


def main() -> int:
    work = HOME / "rebuild" / dt.date.today().isoformat()
    work.mkdir(parents=True, exist_ok=True)

    # 1. split Claude chronologically (extractor writes oldest first)
    claude_lines = CLAUDE.read_text(encoding="utf-8").splitlines()
    cut = int(len(claude_lines) * (1 - CLAUDE_TEST_FRACTION))
    claude_train = work / "claude_train_nl.txt"
    claude_test = work / "claude_test_nl.txt"
    claude_train.write_text("\n".join(claude_lines[:cut]) + "\n", encoding="utf-8")
    claude_test.write_text("\n".join(claude_lines[cut:]) + "\n", encoding="utf-8")

    # 2. merge + clean
    train_clean = merge_and_clean([CHATGPT, GEMINI, claude_train], work, "train")
    test_clean = merge_and_clean([claude_test], work, "claude_test")

    # 3. normalise phone-style
    train_txt = work / "train.txt"
    n_train = normalize_file(train_clean, train_txt)
    if PERSONAL_TRAIN.exists():
        with train_txt.open("a", encoding="utf-8") as f:
            for line in PERSONAL_TRAIN.read_text(encoding="utf-8").splitlines():
                for sent in normalize(line):
                    f.write(sent + "\n")
                    n_train += 1
    tests = {"claude_newest_10pct": work / "test_claude.txt"}
    normalize_file(test_clean, tests["claude_newest_10pct"])
    if PERSONAL_HELDOUT.exists() and PERSONAL_HELDOUT.read_text().strip():
        tests["personal_heldout"] = work / "test_personal.txt"
        normalize_file(PERSONAL_HELDOUT, tests["personal_heldout"])

    # 4. train + binarize
    arpa = work / "spectre_v2.arpa"
    blm = work / "spectre_v2_q8.blm"
    tmp = work / "tmp"
    tmp.mkdir(exist_ok=True)
    run([BIN / "lmplz", "-o", ORDER, "-S", "40%", "-T", tmp, "--text", train_txt, "--arpa", arpa],
        stderr=subprocess.DEVNULL)
    run([BIN / "build_binary", "-q", "8", "-b", "8", "trie", arpa, blm], stdout=subprocess.DEVNULL)

    # 5. compare
    lines = [f"KenLM rebuild {dt.datetime.now():%Y-%m-%d %H:%M}",
             f"train sentences: {n_train:,}  ({train_txt})",
             f"live: {OLD_MODEL} ({OLD_MODEL.stat().st_size / 1e6:.1f} MB)",
             f"new: {blm} ({blm.stat().st_size / 1e6:.1f} MB)", ""]
    results = {"model": str(blm), "live": str(OLD_MODEL), "tests": {}}
    for name, path in tests.items():
        old, new = query(OLD_MODEL, path), query(blm, path)
        results["tests"][name] = {"live": old, "new": new}
        lines.append(f"[{name}] {int(new['tokens'] or 0):,} tokens")
        for k in ("ppl_incl_oov", "ppl_excl_oov", "oovs"):
            lines.append(f"  {k:14s} live {old[k]:>10.1f}   new {new[k]:>10.1f}")
        lines.append("")
    report = "\n".join(lines)
    (work / "report.txt").write_text(report + "\n", encoding="utf-8")
    (work / "report.json").write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")

    # large intermediates (ARPA ~170 MB/night); model + report + train/test text stay
    arpa.unlink(missing_ok=True)
    shutil.rmtree(tmp, ignore_errors=True)
    for f in work.glob("*_merged.txt"):
        f.unlink()
    print(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
