"""Read swipes out of the keyboard's heliboard.db (GESTURE_DATA, HeliBoard gesture-data schema).

One sample per recorded swipe (inputStyle TAIL_BATCH = 3): pointer path, keyboard size, the word
that actually got committed (`targetWord`: Google's pick or the user's correction) and Google's
ranked suggestions (to score the teacher against the same label).
"""
import json
import sqlite3
from dataclasses import dataclass

INPUT_STYLE_TAIL_BATCH = 3


@dataclass
class Swipe:
    row_id: int
    timestamp: int
    word: str                 # label, lowercase a-z only
    xs: list
    ys: list
    ts: list
    width: int
    height: int
    google: list              # Google's suggestions, best first (lowercased)


def load(db_path: str, min_points: int = 6) -> tuple[list[Swipe], dict]:
    con = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    stats = {"rows": 0, "swipes": 0, "kept": 0, "no_label": 0, "non_az_label": 0, "short": 0}
    out = []
    for row_id, ts, data in con.execute("SELECT ID, TIMESTAMP, DATA FROM GESTURE_DATA ORDER BY TIMESTAMP"):
        stats["rows"] += 1
        d = json.loads(data)
        if d.get("inputStyle") != INPUT_STYLE_TAIL_BATCH:
            continue
        stats["swipes"] += 1
        word = (d.get("targetWord") or "").lower()
        if not word:
            stats["no_label"] += 1
            continue
        if not word.isascii() or not word.isalpha() or len(word) < 2 or len(word) > 19:
            stats["non_az_label"] += 1
            continue
        pts = [p for p in d.get("gesture", []) if p.get("id", 0) == 0]
        if len(pts) < min_points:
            stats["short"] += 1
            continue
        kb = d["keyboardInfo"]
        out.append(Swipe(row_id, ts, word, [p["x"] for p in pts], [p["y"] for p in pts],
                         [p["millis"] for p in pts], kb["width"], kb["height"],
                         [s["word"].lower() for s in d.get("suggestions", [])]))
        stats["kept"] += 1
    con.close()
    return out, stats
