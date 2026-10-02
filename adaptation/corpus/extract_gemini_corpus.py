#!/usr/bin/env python3
"""
Extract user-only and combined (user+assistant) clean text from Google
Takeout Gemini Apps My Activity.json.

Outputs two files:
  --user-out   : only the user's messages (your typing voice)
  --combined-out : user messages interleaved with Gemini responses
"""

from __future__ import annotations

import argparse
import json
import re
from html import unescape
from pathlib import Path

# Minimal known-safe HTML tag stripping for Gemini's output format.
# Gemini uses <p>, <strong>, <em>, <code>, <li>, <ul>, <ol>, <h3>, <blockquote>,
# <table>, <hr>, <br>, <thead>, <tbody>, <tr>, <td>, <th>, <a>.
# We strip ALL tags and keep the inner text.
TAG_RE = re.compile(r"<[^>]+>")

def strip_html(html: str) -> str:
    """Remove HTML tags and decode entities."""
    text = TAG_RE.sub(" ", html)
    text = unescape(text)
    text = re.sub(r"\s+", " ", text).strip()
    return text

def extract_entry(entry: dict) -> tuple[str | None, str | None]:
    """
    Return (user_text, assistant_text) or (None, None) for non-conversation entries.
    """
    title = entry.get("title", "")
    if not title.startswith("Prompted "):
        return None, None

    user_text = title.removeprefix("Prompted ").strip()
    if not user_text:
        return None, None

    html_items = entry.get("safeHtmlItem")
    if not html_items or not isinstance(html_items, list):
        return user_text, None

    assistant_parts = []
    for item in html_items:
        html = item.get("html", "")
        if html:
            stripped = strip_html(html)
            if stripped:
                assistant_parts.append(stripped)

    assistant_text = "\n".join(assistant_parts) if assistant_parts else None
    return user_text, assistant_text


def main() -> int:
    ap = argparse.ArgumentParser(
        description="Extract clean text from Gemini Takeout My Activity.json"
    )
    ap.add_argument(
        "input",
        help="Path to My Activity.json from Google Takeout",
    )
    ap.add_argument(
        "--user-out",
        default="gemini_user_only.txt",
        help="Output file for user-only text (default: gemini_user_only.txt)",
    )
    ap.add_argument(
        "--combined-out",
        default="gemini_combined.txt",
        help="Output file for combined user+assistant text (default: gemini_combined.txt)",
    )
    ap.add_argument(
        "--min-chars",
        type=int,
        default=10,
        help="Minimum characters per message to keep (default: 10)",
    )
    ap.add_argument(
        "--max-messages",
        type=int,
        default=0,
        help="Max messages to export (0 = all)",
    )
    args = ap.parse_args()

    input_path = Path(args.input)
    if not input_path.exists():
        print(f"File not found: {input_path}")
        return 1

    with open(input_path, encoding="utf-8") as f:
        data = json.load(f)

    user_messages: list[str] = []
    combined_messages: list[str] = []
    skipped = 0

    for i, entry in enumerate(data):
        if args.max_messages and i >= args.max_messages:
            break

        user_text, assistant_text = extract_entry(entry)
        if user_text is None:
            skipped += 1
            continue

        if len(user_text) >= args.min_chars:
            user_messages.append(user_text)

        if len(user_text) >= args.min_chars:
            combined_messages.append(user_text)

        if assistant_text and len(assistant_text) >= args.min_chars:
            combined_messages.append(assistant_text)

    # Write user-only
    user_out = Path(args.user_out)
    user_out.write_text("\n".join(user_messages) + "\n", encoding="utf-8")
    user_words = sum(len(m.split()) for m in user_messages)

    # Write combined
    combined_out = Path(args.combined_out)
    combined_out.write_text("\n".join(combined_messages) + "\n", encoding="utf-8")
    combined_words = sum(len(m.split()) for m in combined_messages)

    print(f"Entries processed: {len(data)}")
    print(f"Conversations extracted: {len(user_messages)}")
    print(f"Skipped (non-prompt/malformed): {skipped}")
    print(f"User-only:      {user_out} ({len(user_messages):,} msgs, ~{user_words:,} words)")
    print(f"Combined:       {combined_out} ({len(combined_messages):,} msgs, ~{combined_words:,} words)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
