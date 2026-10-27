#!/usr/bin/env python3
"""Write app/src/test/resources/swipe_features_golden.json: inputs + features.py output.
NeuralFeatureParityTest runs the Kotlin TrajectoryFeatureExtractor on the same inputs and must
match — keeps the training-side port and the phone in lockstep."""
import json
import random
from pathlib import Path

from features import extract
from synth import path

OUT = Path(__file__).resolve().parents[2] / "app/src/test/resources/swipe_features_golden.json"
rng = random.Random(7)
cases = []
# (top, height): whole keyboard, and a letter-row band below a number row (y shifted by top)
for i, word in enumerate(["hello", "the", "keyboard", "swipe", "qp", "ab" * 140]):   # last one > 250 points
    top, height = (0.0, 810.0) if i % 2 == 0 else (149.25, 439.5)
    pts = path(word, rng)
    xs, ys, ts = [p["x"] for p in pts], [round(p["y"] * height / 810.0 + top) for p in pts], [p["millis"] + rng.randint(0, 3) for p in pts]
    traj, keys, n = extract(xs, ys, ts, 1080, height, top)
    cases.append({"word": word, "xs": xs, "ys": ys, "ts": ts, "width": 1080.0, "height": height, "top": top,
                  "length": int(n), "traj": traj.ravel().tolist(), "keys": keys.tolist()})
OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(cases))
print("wrote", OUT, len(cases), "cases")
