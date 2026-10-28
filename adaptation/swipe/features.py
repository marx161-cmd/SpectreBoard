"""Exact port of spectre/neural/TrajectoryFeatures.kt (+ Tokenizer.kt), float32 like the phone.

Training data must be turned into model inputs the same way the keyboard does it live, or the
model learns a different input distribution than it sees on the phone.
"""
import numpy as np

from model import MAX_SEQ, UNK

SMOOTHING_WINDOW = 3
# insertion order matters for nearest-key ties — same order as the Kotlin mapOf
QWERTY = {
    'q': (0.05, 0.0), 'w': (0.15, 0.0), 'e': (0.25, 0.0), 'r': (0.35, 0.0), 't': (0.45, 0.0),
    'y': (0.55, 0.0), 'u': (0.65, 0.0), 'i': (0.75, 0.0), 'o': (0.85, 0.0), 'p': (0.95, 0.0),
    'a': (0.10, 0.5), 's': (0.20, 0.5), 'd': (0.30, 0.5), 'f': (0.40, 0.5), 'g': (0.50, 0.5),
    'h': (0.60, 0.5), 'j': (0.70, 0.5), 'k': (0.80, 0.5), 'l': (0.90, 0.5),
    'z': (0.15, 1.0), 'x': (0.25, 1.0), 'c': (0.35, 1.0), 'v': (0.45, 1.0), 'b': (0.55, 1.0),
    'n': (0.65, 1.0), 'm': (0.75, 1.0),
}
_KEY_CH = list(QWERTY)
_KEY_POS = np.array([QWERTY[c] for c in _KEY_CH], np.float32)


def char_token(c: str) -> int:
    return ord(c) - ord('a') + 4 if 'a' <= c <= 'z' else UNK


def word_tokens(word: str) -> list[int]:
    return [char_token(c) for c in word.lower()]


def qwerty_band(keys, full_height: float):
    """Letter-row band (top, height) in px from keyboardInfo keys, = NeuralShadow.qwertyBand (f32, same order)."""
    f32 = np.float32
    q = next((k for k in keys if k.get("value") in ("q", "Q")), None)   # Q/M: shifted layout
    m = next((k for k in keys if k.get("value") in ("m", "M")), None)
    if q is None or m is None:
        return f32(0), f32(full_height)
    qc = f32(q["top"]) + f32(q["height"]) / f32(2)
    mc = f32(m["top"]) + f32(m["height"]) / f32(2)
    row = (mc - qc) / f32(2)
    if row <= 0:
        return f32(0), f32(full_height)
    top = qc - row / f32(2)
    return (f32(0), f32(3) * row + top) if top < 0 else (top, f32(3) * row)


def extract(xs, ys, ts, width: float, height: float, top: float = 0.0):
    """Raw pointer coords (px) + millis -> (traj [MAX_SEQ,6] f32, keys [MAX_SEQ] i32, length).
    y is normalised over the letter rows: `top`/`height` = qwerty_band(), as on the phone."""
    n = min(len(xs), len(ts))
    if n < 3:
        return None
    f32 = np.float32
    x = np.clip(np.asarray(xs[:n], f32) / f32(width), 0, 1).astype(f32)
    y = np.clip((np.asarray(ys[:n], f32) - f32(top)) / f32(height), 0, 1).astype(f32)
    # smoothing: mean over [i-1, i+1] clamped to the ends (window 3)
    if n >= SMOOTHING_WINDOW:
        h = SMOOTHING_WINDOW // 2
        sx, sy = np.empty(n, f32), np.empty(n, f32)
        for i in range(n):
            st, en = max(0, i - h), min(n - 1, i + h)
            sx[i] = f32(x[st:en + 1].sum(dtype=f32) / f32(en - st + 1))
            sy[i] = f32(y[st:en + 1].sum(dtype=f32) / f32(en - st + 1))
        x, y = sx, sy
    t = np.asarray(ts[:n], np.int64)
    dt = np.zeros(n, f32)
    dt[1:] = np.maximum((t[1:] - t[:-1]).astype(f32), f32(1e-6))
    vx, vy, ax, ay = (np.zeros(n, f32) for _ in range(4))
    vx[1:] = (x[1:] - x[:-1]) / dt[1:]
    vy[1:] = (y[1:] - y[:-1]) / dt[1:]
    ax[1:] = (vx[1:] - vx[:-1]) / dt[1:]
    ay[1:] = (vy[1:] - vy[:-1]) / dt[1:]
    feats = np.stack([x, y] + [np.clip(a, -10, 10) for a in (vx, vy, ax, ay)], 1).astype(f32)
    d = ((np.stack([x, y], 1)[:, None, :] - _KEY_POS[None]) ** 2).sum(-1)
    keys = np.array([char_token(_KEY_CH[i]) for i in d.argmin(1)], np.int32)

    length = min(n, MAX_SEQ)
    traj = np.zeros((MAX_SEQ, 6), f32)
    traj[:length] = feats[:length]
    kk = np.full(MAX_SEQ, keys[min(n, MAX_SEQ) - 1], np.int32)   # pad with the last key
    kk[:length] = keys[:length]
    return traj, kk, length
