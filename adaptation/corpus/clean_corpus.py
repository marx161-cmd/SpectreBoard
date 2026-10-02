#!/usr/bin/env python3
"""
One-time corpus cleaning pass for the GRU/LSTM/CIFG next-word LM project.

Run this ONCE on your raw concatenated ChatGPT + Gemini extracts (your
writing only, assistant turns already stripped). Output is a single
cleaned .txt file that the harness's data loader consumes directly —
the harness never re-cleans, so if you change cleaning logic later,
just re-run this and re-point the harness at the new file.

Usage:
    python clean_corpus.py --input raw_corpus.txt --output cleaned_corpus.txt

What this does, in order:
    1. Line-level near-dup removal (exact + near-duplicate via hashing)
    2. Strips/normalizes code blocks, file paths, shell-isms that would
       eat vocab/softmax budget for no next-word-prediction benefit
    3. Collapses excessive whitespace, normalizes unicode
    4. Filters degenerate lines (too short to carry signal, pure
       punctuation/emoji-only lines) — but keeps casual short lines
       like "lol ok" since that's genuinely your texting register
    5. Reports stats so you can sanity-check before training

Deliberately does NOT lowercase or strip emoji/casing — per the
decision that case and emoji are part of the actual signal we want
the model to learn for you specifically, not noise.
"""

import argparse
import hashlib
import re
import sys
import unicodedata
from collections import Counter
from pathlib import Path

# ---------------------------------------------------------------------------
# Patterns for stripping low-value, high-vocab-cost content
# ---------------------------------------------------------------------------

# Fenced code blocks (```...```), kept out entirely — a next-word LM on
# prose doesn't benefit from learning to predict shell flags or Python
# syntax token-by-token; that's KenLM/literal-lookup territory if anything.
CODE_FENCE_RE = re.compile(r"```.*?```", re.DOTALL)

# Inline code spans `like_this`
INLINE_CODE_RE = re.compile(r"`[^`\n]+`")

# Absolute-looking unix paths and home-relative paths, e.g. ~/homelab/parrot/
# or /mnt/user-data/outputs/foo.tflite — collapse to a placeholder token
# rather than deleting, so sentence structure around them survives.
PATH_RE = re.compile(r"~?(?:/[\w.\-]+){2,}/?")

# URLs
URL_RE = re.compile(r"https?://\S+")

# Long hex/hash-looking tokens (commit hashes, UUIDs) — pure noise for a
# word-level vocab, very low recurrence, pollutes the long tail.
HASH_RE = re.compile(r"\b[0-9a-f]{8,}\b", re.IGNORECASE)

# 3+ repeated whitespace/newlines
MULTI_WS_RE = re.compile(r"[ \t]{2,}")
MULTI_NL_RE = re.compile(r"\n{3,}")

PLACEHOLDER_PATH = "<PATH>"
PLACEHOLDER_URL = "<URL>"
PLACEHOLDER_HASH = "<HASH>"


def strip_code_and_noise(text: str) -> str:
    text = CODE_FENCE_RE.sub(" ", text)
    text = INLINE_CODE_RE.sub(" ", text)
    text = URL_RE.sub(PLACEHOLDER_URL, text)
    text = PATH_RE.sub(PLACEHOLDER_PATH, text)
    text = HASH_RE.sub(PLACEHOLDER_HASH, text)
    return text


def normalize_whitespace(text: str) -> str:
    text = unicodedata.normalize("NFC", text)
    text = MULTI_WS_RE.sub(" ", text)
    text = MULTI_NL_RE.sub("\n\n", text)
    return text.strip()


def line_signature(line: str) -> str:
    """
    Near-dup signature: lowercase, strip punctuation/whitespace runs,
    hash it. Two lines that differ only in casing/punctuation/spacing
    collapse to the same signature — catches the "same sentence typed
    slightly differently across many ChatGPT sessions" case without
    being so aggressive it merges genuinely different sentences.
    """
    norm = line.lower()
    norm = re.sub(r"[^\w\s]", "", norm)
    norm = re.sub(r"\s+", " ", norm).strip()
    return hashlib.sha1(norm.encode("utf-8")).hexdigest()


def is_degenerate(line: str, min_chars: int = 3, min_alpha_ratio: float = 0.2) -> bool:
    """
    Filters lines too short/low-signal to carry next-word information.
    Deliberately permissive — "lol ok" survives (4 alpha chars / lowercase,
    passes both checks). Pure punctuation/emoji walls or single-char lines
    do not.
    """
    stripped = line.strip()
    if len(stripped) < min_chars:
        return True
    alpha_count = sum(1 for c in stripped if c.isalpha())
    if len(stripped) > 0 and (alpha_count / len(stripped)) < min_alpha_ratio:
        return True
    return False


def clean_corpus(input_path: Path, output_path: Path, min_chars: int) -> dict:
    raw_text = input_path.read_text(encoding="utf-8", errors="ignore")

    # Strip code/paths/urls/hashes globally first (these can span line
    # boundaries via fenced blocks), then split into lines for the rest.
    text = strip_code_and_noise(raw_text)
    text = normalize_whitespace(text)

    lines = [l for l in text.split("\n") if l.strip()]

    stats = {
        "raw_lines": len(lines),
        "removed_degenerate": 0,
        "removed_near_dup": 0,
        "kept": 0,
    }

    seen_signatures: set[str] = set()
    seen_counts: Counter = Counter()
    sig_example: dict[str, str] = {}
    kept_lines = []

    for line in lines:
        line = line.strip()

        if is_degenerate(line, min_chars=min_chars):
            stats["removed_degenerate"] += 1
            continue

        sig = line_signature(line)
        seen_counts[sig] += 1
        sig_example.setdefault(sig, line)
        if sig in seen_signatures:
            stats["removed_near_dup"] += 1
            continue
        seen_signatures.add(sig)

        kept_lines.append(line)

    stats["kept"] = len(kept_lines)

    # Surface the most over-repeated lines even after dedup-by-first-
    # occurrence, in case there's a recurring template/catchphrase worth
    # knowing about (we only dedup exact near-matches; a phrase repeated
    # with *different* surrounding wording each time will still show up
    # here as high frequency in the underlying text, just not collapsed).
    stats["top_repeated"] = [
        (count, sig_example[sig]) for sig, count in seen_counts.most_common(10)
    ]

    output_path.write_text("\n".join(kept_lines) + "\n", encoding="utf-8")
    return stats


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path, help="Raw concatenated corpus .txt")
    parser.add_argument("--output", required=True, type=Path, help="Path to write cleaned .txt")
    parser.add_argument(
        "--min-chars", type=int, default=3,
        help="Minimum line length to keep (default: 3, very permissive)",
    )
    args = parser.parse_args()

    if not args.input.exists():
        print(f"Input file not found: {args.input}", file=sys.stderr)
        sys.exit(1)

    print(f"Reading {args.input} ({args.input.stat().st_size / 1e6:.1f} MB)...")
    stats = clean_corpus(args.input, args.output, args.min_chars)

    print("\n--- Cleaning report ---")
    print(f"Raw lines:              {stats['raw_lines']:,}")
    print(f"Removed (degenerate):   {stats['removed_degenerate']:,}")
    print(f"Removed (near-dup):     {stats['removed_near_dup']:,}")
    print(f"Kept:                   {stats['kept']:,}")
    print(f"Output written to:      {args.output} ({args.output.stat().st_size / 1e6:.1f} MB)")
    print("\nTop repeated lines (count includes ALL occurrences before dedup —")
    print("first occurrence was kept, rest dropped):")
    for count, example in stats["top_repeated"]:
        if count > 1:
            preview = example if len(example) <= 70 else example[:67] + "..."
            print(f"  {count:>5}x  {preview}")
    print("\nDone. Review the output file before pointing the training harness at it.")


if __name__ == "__main__":
    main()
