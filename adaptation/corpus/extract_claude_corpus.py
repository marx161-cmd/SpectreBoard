#!/usr/bin/env python3
"""
Extract user-only and combined (user+assistant) clean text from a claude.ai
data export (conversations.json from the data-*.zip).

Outputs two files, one message per line (internal newlines collapsed to spaces,
fenced ``` blocks kept inline so merge_gru_sources.py can classify them):
  --user-out     : only the user's messages (your typing voice)
  --combined-out : user messages interleaved with Claude responses

Attachments (long pastes claude.ai turns into "Pasted text" files) are NOT
included — they're logs/code the user pasted, not their typing.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


def flatten(text: str, keep_newlines: bool = False) -> str:
    """Collapse whitespace to single spaces. With keep_newlines, line breaks survive
    as an escaped literal \\n (still one message per output line) so
    merge_gru_sources.py can filter fenced blocks / pasted terminal lines per line."""
    if not keep_newlines:
        return re.sub(r"\s+", " ", text).strip()
    lines = [re.sub(r"[^\S\n]+", " ", ln).strip() for ln in text.split("\n")]
    text = "\n".join(ln for ln in lines if ln)
    return text.replace("\\", "\\\\").replace("\n", "\\n")


def message_text(msg: dict, keep_newlines: bool = False) -> str:
    text = msg.get("text") or ""
    if not text:
        text = " ".join(
            p.get("text", "") for p in msg.get("content", []) if p.get("type") == "text"
        )
    return flatten(text, keep_newlines)


def main() -> int:
    ap = argparse.ArgumentParser(
        description="Extract clean text from a claude.ai export conversations.json"
    )
    ap.add_argument("input", help="Path to conversations.json from the claude.ai export")
    ap.add_argument(
        "--user-out",
        default="claude_user_only.txt",
        help="Output file for user-only text (default: claude_user_only.txt)",
    )
    ap.add_argument(
        "--combined-out",
        default="claude_combined.txt",
        help="Output file for combined user+assistant text (default: claude_combined.txt)",
    )
    ap.add_argument(
        "--min-chars",
        type=int,
        default=10,
        help="Minimum characters per message to keep (default: 10)",
    )
    ap.add_argument(
        "--keep-newlines",
        action="store_true",
        help="Keep line breaks as escaped \\n (for merge_gru_sources.py line-level filtering)",
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
    attachments_skipped = 0

    # Oldest conversation first, messages in their stored order.
    for conv in sorted(data, key=lambda c: c.get("created_at", "")):
        for msg in conv.get("chat_messages", []):
            text = message_text(msg, args.keep_newlines)
            if msg.get("sender") == "human":
                attachments_skipped += len(msg.get("attachments", []))
                if len(text) >= args.min_chars:
                    user_messages.append(text)
                    combined_messages.append(text)
            elif len(text) >= args.min_chars:
                combined_messages.append(text)

    user_out = Path(args.user_out)
    user_out.write_text("\n".join(user_messages) + "\n", encoding="utf-8")
    user_words = sum(len(m.split()) for m in user_messages)

    combined_out = Path(args.combined_out)
    combined_out.write_text("\n".join(combined_messages) + "\n", encoding="utf-8")
    combined_words = sum(len(m.split()) for m in combined_messages)

    print(f"Conversations processed: {len(data)}")
    print(f"Attachments skipped (pastes): {attachments_skipped}")
    print(f"User-only:      {user_out} ({len(user_messages):,} msgs, ~{user_words:,} words)")
    print(f"Combined:       {combined_out} ({len(combined_messages):,} msgs, ~{combined_words:,} words)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
