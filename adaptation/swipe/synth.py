#!/usr/bin/env python3
"""Fake heliboard.db (real GESTURE_DATA schema) with synthetic swipes — pipeline test only.

Paths run through key centres of a 1080x810 three-letter-row QWERTY with jitter; a fake "Google"
is wrong ~15% of the time. Numbers from this data mean nothing about real accuracy.
Usage: synth.py OUT.db [N]
"""
import json
import random
import sqlite3
import sys
from pathlib import Path

EN = Path(__file__).resolve().parents[2] / "app/src/main/assets/dictionaries/en.txt"
ROWS = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]
W, H, KW = 1080, 810, 108


def centre(c):
    for r, row in enumerate(ROWS):
        if c in row:
            return (row.index(c) + 0.5 + r * 0.5) * KW, (r + 0.5) * H / 3
    raise KeyError(c)


def path(word, rng):
    pts, t = [], 0
    cs = [centre(c) for c in word]
    for (x0, y0), (x1, y1) in zip(cs, cs[1:] + [cs[-1]]):
        for k in range(8):
            f = k / 8
            pts.append({"id": 0, "x": int(x0 + (x1 - x0) * f + rng.gauss(0, 12)),
                        "y": int(y0 + (y1 - y0) * f + rng.gauss(0, 12)), "millis": t})
            t += 10
    return pts


def main():
    out, n = sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 2000
    rng = random.Random(0)
    words = [w for w in EN.read_text().split()[:3000] if w.isalpha() and w.isascii() and 2 <= len(w) <= 12]
    Path(out).unlink(missing_ok=True)
    con = sqlite3.connect(out)
    con.execute("CREATE TABLE GESTURE_DATA (ID INTEGER PRIMARY KEY, TIMESTAMP INTEGER, WORD TEXT, "
                "EXPORTED INTEGER, SOURCE_ACTIVE INTEGER, DATA TEXT)")
    for i in range(n):
        w = rng.choice(words)
        sugg = [w] + rng.sample(words, 4)
        if rng.random() < 0.15:
            sugg[0], sugg[1] = sugg[1], sugg[0]
        d = {"targetWord": w, "inputStyle": 3, "gesture": path(w, rng),
             "keyboardInfo": {"width": W, "height": H, "keys": []},
             "suggestions": [{"word": s, "score": 1000 - j} for j, s in enumerate(sugg)]}
        con.execute("INSERT INTO GESTURE_DATA VALUES (?,?,?,?,?,?)", (i, 1_790_000_000_000 + i * 1000, w, 0, 0, json.dumps(d)))
    con.commit(); con.close()
    print("wrote", out, n)


if __name__ == "__main__":
    main()
