#!/usr/bin/env python3
"""Fine-tune the swipe model on the user's own swipes; score it against Google (scope.md 2026-10-27).

Labels = GESTURE_DATA targetWord (the word that actually got committed). Held-out set = the newest
HOLDOUT fraction by time, never trained on. Reports top-1 word accuracy on that set for:
  google  — Google's first suggestion (the teacher, what the user sees today)
  base    — the shipped model (float twin of the ONNX, see onnx_import.py)
  tuned   — after fine-tuning
Usage: train.py --db heliboard.db [--out ~/kenlm/swipe/runs/<date>] [--epochs 6]
"""
import argparse
import datetime as dt
import json
import random
import time
from pathlib import Path

import numpy as np
import torch
from torch import nn

import gesture_db
from beam import Trie, decode
from features import extract, word_tokens
from model import EOS, MAX_WORD, PAD, SOS, SwipeModel

HERE = Path(__file__).resolve().parent
EN_VOCAB = HERE.parents[1] / "app/src/main/assets/dictionaries/en.txt"
BASE = Path.home() / "kenlm/swipe/base_from_onnx.pt"


def tensors(samples):
    traj, keys, lens, tgt_in, tgt_out = [], [], [], [], []
    for s in samples:
        f = extract(s.xs, s.ys, s.ts, s.width, s.height, s.top)
        if f is None:
            continue
        w = word_tokens(s.word)[:MAX_WORD - 1]
        i = np.full(MAX_WORD, PAD, np.int64); i[0] = SOS; i[1:len(w) + 1] = w
        o = np.full(MAX_WORD, -100, np.int64); o[:len(w)] = w; o[len(w)] = EOS
        traj.append(f[0]); keys.append(f[1]); lens.append(f[2]); tgt_in.append(i); tgt_out.append(o)
    return (torch.from_numpy(np.stack(traj)), torch.from_numpy(np.stack(keys)).long(),
            torch.tensor(lens), torch.from_numpy(np.stack(tgt_in)), torch.from_numpy(np.stack(tgt_out)))


def batches(T, bs, shuffle):
    idx = torch.randperm(len(T[0])) if shuffle else torch.arange(len(T[0]))
    for k in range(0, len(idx), bs):
        j = idx[k:k + bs]
        yield [t[j] for t in T]


def val_loss(model, T, device):
    model.eval(); tot = n = 0
    with torch.no_grad():
        for tr, ky, ln, ti, to in batches(T, 256, False):
            lp = model(tr.to(device), ky.to(device), ln.to(device), ti.to(device))
            tot += nn.functional.nll_loss(lp.flatten(0, 1), to.to(device).flatten(), ignore_index=-100,
                                          reduction="sum").item()
            n += (to != -100).sum().item()
    return tot / max(n, 1)


def top1(model, samples, trie, device, limit=None):
    model.eval(); hit = 0; preds = []
    for s in samples[:limit]:
        f = extract(s.xs, s.ys, s.ts, s.width, s.height, s.top)
        mem = model.encode(torch.from_numpy(f[0])[None].to(device), torch.from_numpy(f[1])[None].long().to(device),
                           torch.tensor([f[2]], device=device))
        c = decode(model, mem, torch.tensor([f[2]], device=device), trie, device)
        guess = c[0][0] if c else ""
        preds.append(guess); hit += guess == s.word
    return hit / max(len(preds), 1), preds


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True)
    ap.add_argument("--out", default=str(Path.home() / "kenlm/swipe/runs" / dt.date.today().isoformat()))
    ap.add_argument("--epochs", type=int, default=6)
    ap.add_argument("--lr", type=float, default=5e-5)
    ap.add_argument("--bs", type=int, default=64)
    ap.add_argument("--holdout", type=float, default=0.1)
    ap.add_argument("--eval-limit", type=int, default=None, help="cap beam-search eval samples (speed)")
    a = ap.parse_args()
    torch.manual_seed(0); random.seed(0)
    out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
    device = "cuda" if torch.cuda.is_available() else "cpu"

    swipes, stats = gesture_db.load(a.db)
    cut = int(len(swipes) * (1 - a.holdout))
    train_s, test_s = swipes[:cut], swipes[cut:]
    print(f"db: {stats} | train {len(train_s)} / held-out {len(test_s)} (newest)")

    # vocab the trie allows: shipped list + every a-z word the user has actually swiped (train part)
    vocab = sorted({w.strip().lower() for w in EN_VOCAB.read_text().splitlines()} | {s.word for s in train_s})
    trie = Trie(vocab)
    (out / "swipe_vocab.txt").write_text("\n".join(vocab) + "\n")

    scored = test_s[:a.eval_limit]   # google / base / tuned all scored on the same swipes
    google = sum(bool(s.google) and s.google[0] == s.word for s in scored) / max(len(scored), 1)
    in_list = sum(s.word in s.google for s in scored) / max(len(scored), 1)

    model = SwipeModel().to(device)
    model.load_state_dict(torch.load(BASE, map_location=device))
    t0 = time.time()
    base_acc, _ = top1(model, test_s, trie, device, a.eval_limit)
    print(f"held-out top-1: google {google:.3f} (label in google list {in_list:.3f}) | base model {base_acc:.3f}"
          f"  [{time.time() - t0:.0f}s]")

    Ttr, Tte = tensors(train_s), tensors(test_s)
    opt = torch.optim.AdamW(model.parameters(), lr=a.lr, weight_decay=0.01)
    best = (val_loss(model, Tte, device), 0)
    torch.save(model.state_dict(), out / "tuned.pt")
    print(f"epoch 0 val_loss {best[0]:.4f}")
    for ep in range(1, a.epochs + 1):
        model.train()
        for tr, ky, ln, ti, to in batches(Ttr, a.bs, True):
            lp = model(tr.to(device), ky.to(device), ln.to(device), ti.to(device))
            loss = nn.functional.nll_loss(lp.flatten(0, 1), to.to(device).flatten(), ignore_index=-100)
            opt.zero_grad(); loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 1.0); opt.step()
        vl = val_loss(model, Tte, device)
        print(f"epoch {ep} val_loss {vl:.4f}")
        if vl < best[0]:
            best = (vl, ep); torch.save(model.state_dict(), out / "tuned.pt")
    model.load_state_dict(torch.load(out / "tuned.pt", map_location=device))
    tuned_acc, preds = top1(model, test_s, trie, device, a.eval_limit)
    report = {"date": dt.datetime.now().isoformat(timespec="seconds"), "db_stats": stats,
              "train": len(train_s), "heldout": len(test_s), "best_epoch": best[1], "best_val_loss": best[0],
              "top1": {"google": google, "base": base_acc, "tuned": tuned_acc},
              "label_in_google_list": in_list, "eval_limit": a.eval_limit}
    (out / "report.json").write_text(json.dumps(report, indent=2))
    print(f"held-out top-1: google {google:.3f} | base {base_acc:.3f} | tuned {tuned_acc:.3f}  -> {out}")


if __name__ == "__main__":
    main()
