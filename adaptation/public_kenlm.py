#!/usr/bin/env python3
"""Public KenLM for the `public` test build (SpectreBoard scope.md 2026-10-27).

Takes the newest nightly training text (~/kenlm/rebuild/<date>/train.txt, already
phone-normalised by kenlm_rebuild.py) and trains a model that is safe to ship:
  - a word survives only if it occurs >= MIN_COUNT times (one-off secrets, random
    tokens and pasted hashes fall out; rare real words fall back to the dictionary)
  - mixed letter+digit words >= 6 chars, digit runs >= 6, hex blobs and homelab
    identifiers (tailnet, MAC-named Wi-Fi iface) are dropped
  - runs of >= 3 short numeric-ish tokens (what IPs / MACs tokenise into) are dropped
  - every drop splits the sentence, so no n-gram spans a removed token
Then lmplz -o 4 + build_binary -q 8 -b 8 trie, same as the nightly.

Output: ~/kenlm/public/spectre_public_q8.blm (+ vocab.txt, report.txt)
"""
import collections, re, subprocess, sys, tempfile
from pathlib import Path

HOME = Path.home() / "kenlm"
BIN = Path.home() / "opt/src/kenlm/build/bin"
OUT = HOME / "public"
MIN_COUNT = 3
ORDER = "4"

_MIXED = re.compile(r"(?=.*[a-z])(?=.*\d).{6,}")
_DIGITS = re.compile(r"\d{6,}")
_HEX = re.compile(r"(?=.*\d)[0-9a-f]{8,}")
_SHORT_NUMERIC = re.compile(r"(?=.*\d)[0-9a-f]{1,3}")
_DENY = re.compile(r"^tail[0-9a-f]{6}$|^wlx[0-9a-f]*$")  # Tailscale tailnet ids, MAC-named Wi-Fi ifaces


def bad_shape(tok: str) -> bool:
    return bool(_MIXED.fullmatch(tok) or _DIGITS.fullmatch(tok) or _HEX.fullmatch(tok)
                or _DENY.search(tok))


def segments(toks: list[str], keep) -> list[list[str]]:
    """Split a sentence at dropped tokens and at runs of >= 3 short numeric-ish tokens."""
    numeric = [bool(_SHORT_NUMERIC.fullmatch(t)) for t in toks]
    drop = [not keep(t) for t in toks]
    i = 0
    while i < len(toks):
        if numeric[i]:
            j = i
            while j < len(toks) and numeric[j]:
                j += 1
            if j - i >= 3:
                for k in range(i, j):
                    drop[k] = True
            i = j
        else:
            i += 1
    out, cur = [], []
    for t, d in zip(toks, drop):
        if d:
            if cur: out.append(cur); cur = []
        else:
            cur.append(t)
    if cur: out.append(cur)
    return out


def main() -> int:
    nightly = sorted(p for p in (HOME / "rebuild").iterdir() if (p / "train.txt").exists())[-1]
    src = nightly / "train.txt"
    lines = src.read_text(encoding="utf-8").splitlines()
    counts = collections.Counter(t for l in lines for t in l.split())
    keep_words = {w for w, c in counts.items() if c >= MIN_COUNT and not bad_shape(w)}
    keep = keep_words.__contains__

    OUT.mkdir(parents=True, exist_ok=True)
    train = OUT / "train_public.txt"
    n = 0
    with train.open("w", encoding="utf-8") as f:
        for l in lines:
            for seg in segments(l.split(), keep):
                f.write(" ".join(seg) + "\n"); n += 1

    arpa, blm = OUT / "spectre_public.arpa", OUT / "spectre_public_q8.blm"
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run([BIN / "lmplz", "-o", ORDER, "-S", "40%", "-T", tmp, "--text", train,
                        "--arpa", arpa], check=True, stderr=subprocess.DEVNULL)
    subprocess.run([BIN / "build_binary", "-q", "8", "-b", "8", "trie", arpa, blm],
                   check=True, stdout=subprocess.DEVNULL)
    arpa.unlink()
    (OUT / "vocab.txt").write_text("\n".join(sorted(keep_words)) + "\n", encoding="utf-8")
    report = (f"source {src}\nwords in source {len(counts)}  kept {len(keep_words)}  "
              f"min_count {MIN_COUNT}\nsentences out {n}\nmodel {blm} {blm.stat().st_size} bytes\n")
    (OUT / "report.txt").write_text(report)
    print(report, end="")
    return 0


if __name__ == "__main__":
    sys.exit(main())
