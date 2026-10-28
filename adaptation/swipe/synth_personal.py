#!/usr/bin/env python3
"""Synthetic swipes of the user's own words on the user's own keyboard layouts (training data).

Words are drawn from the user's text corpus (count ** ALPHA, so frequent words dominate without
drowning the rest); each one is swiped on the keyboardInfo of a random real recorded swipe, so the
key geometry and the layout mix match the phone. Path shape is fitted to the real GESTURE_DATA swipes
(10-28): ~164 ms and ~13 points per letter at 9 ms steps, letter hits scattered 0.30 x 0.25 key
around the centre (base model reads these at 40%, real swipes 43%), Catmull-Rom curves between them. Repeated letters ("ll") are one stop.
"""
import random
import re
from collections import Counter
from pathlib import Path

import numpy as np

from features import qwerty_band
from gesture_db import Swipe

CORPORA = [Path.home() / "kenlm/out/corpus_v2.txt", Path.home() / "kenlm/out/personal_typed.txt"]
ALPHA = 0.5
_WORD = re.compile(r"[a-z]+")


def word_counts(paths=CORPORA, min_count=3):
    c = Counter()
    for p in paths:
        if p.exists():
            with open(p, errors="ignore") as f:
                for line in f:
                    c.update(_WORD.findall(line.lower()))
    return {w: n for w, n in c.items() if n >= min_count and 2 <= len(w) <= 19}


def _catmull(p, steps):
    """Points along a Catmull-Rom spline through p (k x 2); steps[i] samples on segment i."""
    p = np.vstack([p[0], p, p[-1]])
    out = []
    for i in range(1, len(p) - 2):
        p0, p1, p2, p3 = p[i - 1], p[i], p[i + 1], p[i + 2]
        for t in np.linspace(0, 1, steps[i - 1], endpoint=False):
            t2, t3 = t * t, t * t * t
            out.append(0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2
                              + (-p0 + 3 * p1 - 3 * p2 + p3) * t3))
    out.append(p[-2])
    return np.array(out)


def swipe(word, kb, rng: random.Random, row_id=-1):
    keys = {k["value"].lower(): k for k in kb.get("keys", []) if len(k.get("value", "")) == 1}  # shifted: A-Z
    stops = [c for i, c in enumerate(word) if i == 0 or c != word[i - 1]]
    if any(c not in keys for c in stops):
        return None
    ctl = []
    for c in stops:
        k = keys[c]
        ctl.append((k["left"] + k["width"] / 2 + rng.gauss(0, 0.30 * k["width"]),
                    k["top"] + k["height"] / 2 + rng.gauss(0, 0.25 * k["height"])))
    ctl = np.array(ctl)
    total_ms = 164 * len(word) * rng.lognormvariate(0, 0.3)
    n_pts = max(6, int(total_ms / 9))
    if len(ctl) == 1:  # one-letter path (e.g. "aa"): a short wiggle on the key
        ctl = np.vstack([ctl, ctl + [rng.gauss(0, 15), rng.gauss(0, 15)]])
    seg = np.linalg.norm(np.diff(ctl, axis=0), axis=1) ** 0.6 + 1e-3   # corners slow the finger
    steps = np.maximum(1, np.round(seg / seg.sum() * (n_pts - 1)).astype(int))
    pts = _catmull(ctl, steps) + np.array([[rng.gauss(0, 3), rng.gauss(0, 3)] for _ in range(steps.sum() + 1)])
    pts[:, 0] = np.clip(pts[:, 0], 0, kb["width"] - 1); pts[:, 1] = np.clip(pts[:, 1], 0, kb["height"] - 1)
    ts = [int(i * 9 + rng.randint(0, 2)) for i in range(len(pts))]
    top, h = qwerty_band(kb["keys"], kb["height"])
    return Swipe(row_id, 0, word, [int(x) for x in pts[:, 0]], [int(y) for y in pts[:, 1]], ts,
                 kb["width"], h, top, [])


def make(n, layouts, counts, seed=0):
    """n synthetic swipes; layouts = keyboardInfo dicts of real swipes (sampled as they occur)."""
    rng = random.Random(seed)
    words = list(counts)
    weights = [counts[w] ** ALPHA for w in words]
    out = []
    while len(out) < n:
        for w in rng.choices(words, weights, k=n - len(out)):
            s = swipe(w, rng.choice(layouts), rng)
            if s is not None:
                out.append(s)
    return out
