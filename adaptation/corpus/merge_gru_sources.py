#!/usr/bin/env python3
"""
merge_gru_sources.py

Merges ChatGPT, Gemini and Claude user-text extractions into a single raw file
ready to feed into clean_corpus.py.

The only thing this does that clean_corpus.py does NOT:
  - Classifies triple-backtick blocks by content before stripping.
    Terminal output / shell sessions / package-manager dumps → dropped.
    Prose the user wrapped in backticks for formatting → kept (backticks
    stripped, text preserved).
    clean_corpus.py strips ALL fenced blocks indiscriminately; this fixes that.

Usage:
    python merge_gru_sources.py [--out OUT]

Then pipe through the existing cleaner:
    python gru-cifg-lm/scripts/clean_corpus.py \\
        --input merged_raw.txt --output corpus_for_gru.txt
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

# *_nl.txt sources come from the extractors' --keep-newlines mode: one message per
# line with line breaks escaped as a literal \n, so fenced blocks and pasted
# terminal lines can be classified line by line (2026-10-02). Gemini Takeout
# prompts are single-line already.
SOURCES = [
    Path.home() / "kenlm/GRU/source_export/extracted/user_nl.txt",
    Path.home() / "kenlm/out/gemini_user.txt",
    Path.home() / "kenlm/out/claude_user_nl.txt",
]

DEFAULT_OUT = Path.home() / "kenlm/out/merged_raw.txt"

# ── Terminal / code fingerprints ──────────────────────────────────────────────
# Checked per-line inside a fenced block. If enough lines match, block is noise.

_TERMINAL_LINE = re.compile(
    r"""(?x)
      [a-z0-9_.+-]+@[a-z0-9_-]+[:#~\$]     # shell prompt:  user@host:
    | ^\$\s                                   # bare $ command
    | ^>\s                                    # continuation prompt
    | Get:\d+\s+https?://                     # apt/pkg  Get:1 https://…
    | (?:Reading|Building|Setting\sup|Fetched|Unpacking|Preparing)
      \s+(?:package|dependency|to\s+unpack)  # apt output verbs
    | \bFetched\s+\d+\s*[kKmMgG][Bb]         # apt "Fetched 3 MB"
    | ^\s*(?:drwx|lrwx|-rw[xr-])            # ls -l permissions column
    | ^\s*(?:\+\+\+|---)\s+\S.*\d{4}         # unified diff header
    | ^\s*@@\s+-\d+,\d+\s+\+\d+             # diff hunk header
    | ^\s*(?:import\s+\w|from\s+\w+\s+import|def\s+\w+\s*\(|class\s+\w+[:(])
                                              # Python / JS top-level syntax
    | ^\s*\#include\s*[<"]                    # C/C++ include (# escaped: (?x) comment char)
    | ^\s*(?:public|private|protected)\s+(?:static\s+)?(?:class|void|int|String)
                                              # Java boilerplate
    """,
    re.MULTILINE,
)


def _fenced_block_is_noise(content: str) -> bool:
    """
    Return True if a fenced block's content looks like terminal output or code.

    Strategy: count terminal-fingerprint line matches; if ≥2 hits OR
    the non-alpha character density is very high, classify as noise.
    A single hit can be a false positive (user quoting a command inline);
    two or more almost certainly means a real session dump.
    """
    lines = content.splitlines()
    if not lines:
        return False

    hit_count = sum(1 for ln in lines if _TERMINAL_LINE.search(ln))
    if hit_count >= 2:
        return True

    # Density check: if >55 % of characters are non-alphabetic and
    # non-space, it's almost certainly code/config, not prose.
    total = len(content)
    if total == 0:
        return False
    alpha_space = sum(1 for c in content if c.isalpha() or c == " ")
    if alpha_space / total < 0.45:
        return True

    return False


_FENCE_RE = re.compile(r"```([^`]*)```", re.DOTALL)

# Pasted terminal output that shows up OUTSIDE fences (ble.sh status lines,
# Termux pkg progress), on top of _TERMINAL_LINE.
_EXTRA_NOISE_LINE = re.compile(
    r"^\s*(?:\[ble: [^\]]*\]"          # ble.sh status line
    r"|\[\*\] \(\d+\)"                  # pkg/apt mirror progress
    r"|\S+ in 🌐 \S+ in )"              # starship prompt: "user in 🌐 host in ~ on ☁️"
)


def unescape_newlines(line: str) -> str:
    r"""Undo the extractors' --keep-newlines escaping (\\ → \, \n → newline)."""
    return re.sub(r"\\(.)", lambda m: "\n" if m.group(1) == "n" else m.group(1), line)


def drop_terminal_lines(text: str) -> tuple[str, int]:
    """Drop individual pasted-terminal lines outside fences. Returns (text, dropped)."""
    kept, dropped = [], 0
    for ln in text.split("\n"):
        if _TERMINAL_LINE.search(ln) or _EXTRA_NOISE_LINE.search(ln):
            dropped += 1
        else:
            kept.append(ln)
    return "\n".join(kept), dropped


def process_message(msg: str) -> str:
    """
    Classify each fenced block in a message.
    Noise blocks → replaced with single space.
    Prose blocks → backticks stripped, text kept.
    Text outside backticks is always kept.
    """
    def replace_fence(m: re.Match) -> str:
        raw = m.group(1)
        # Strip optional language tag on the first line (```python, ```bash …)
        content = re.sub(r"^[a-zA-Z0-9+#-]*\n", "", raw, count=1)
        if _fenced_block_is_noise(content):
            return " "
        return content.strip()

    return _FENCE_RE.sub(replace_fence, msg)


def process_message_lines(msg: str) -> tuple[str, int]:
    """process_message for multi-line messages: fences first (judged on their own
    lines), then line-level terminal filtering of the remaining text, then flatten
    back to one line for the cleaner."""
    text = process_message(msg)
    text, dropped = drop_terminal_lines(text)
    return re.sub(r"\s+", " ", text).strip(), dropped


def main(out_path: Path = DEFAULT_OUT, sources: list[Path] | None = None) -> int:
    out_path.parent.mkdir(parents=True, exist_ok=True)

    total_in = 0
    total_out = 0
    total_noise_blocks = 0
    total_prose_blocks = 0
    total_dropped_lines = 0

    with out_path.open("w", encoding="utf-8") as fout:
        for src in sources or SOURCES:
            if not src.exists():
                print(f"WARNING: not found, skipping: {src}", file=sys.stderr)
                continue

            lines = src.read_text(encoding="utf-8", errors="ignore").splitlines()
            src_in = len(lines)
            src_out = 0

            multiline = src.name.endswith("_nl.txt")
            for line in lines:
                if multiline:
                    line = unescape_newlines(line)
                    processed, dropped = process_message_lines(line)
                    total_dropped_lines += dropped
                else:
                    # single physical lines (Gemini writes each prompt line separately)
                    processed, dropped = drop_terminal_lines(process_message(line).strip())
                    total_dropped_lines += dropped
                if processed:
                    fout.write(processed + "\n")
                    src_out += 1

                # Count blocks for reporting
                for m in _FENCE_RE.finditer(line):
                    raw = m.group(1)
                    content = re.sub(r"^[a-zA-Z0-9+#-]*\n", "", raw, count=1)
                    if _fenced_block_is_noise(content):
                        total_noise_blocks += 1
                    else:
                        total_prose_blocks += 1

            total_in += src_in
            total_out += src_out
            size_mb = src.stat().st_size / 1e6
            print(f"{src.name:40s}  {src_in:>6} lines in  →  {src_out:>6} lines out  ({size_mb:.1f} MB)")

    print()
    print(f"Fenced blocks classified:  {total_noise_blocks} noise dropped,  {total_prose_blocks} prose kept")
    print(f"Terminal lines dropped outside fences: {total_dropped_lines}")
    print(f"Total lines: {total_in} → {total_out}")
    print(f"Output: {out_path}  ({out_path.stat().st_size / 1e6:.1f} MB)")
    print()
    print("Next step:")
    print(f"  python ~/kenlm/GRU/gru-cifg-lm/scripts/clean_corpus.py \\")
    print(f"      --input {out_path} \\")
    print(f"      --output ~/kenlm/out/corpus_for_gru.txt")

    return 0


if __name__ == "__main__":
    import argparse
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT,
                    help=f"Output path (default: {DEFAULT_OUT})")
    ap.add_argument("--source", type=Path, action="append",
                    help="override SOURCES (repeatable); *_nl.txt files are treated as multi-line")
    args = ap.parse_args()
    raise SystemExit(main(args.out, args.source))
