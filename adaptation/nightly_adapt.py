#!/usr/bin/env python3
"""
nightly_adapt.py — daily SpectreBoard KenLM adaptation run (06:00, systemd user
timer `spectre-adapt.timer`). Personal adaptation pipeline, step 2
(SpectreBoard scope.md, 2026-10-02).

1. adapt_pull.py      — pull AdaptationLog events from the Pixel, replay typed text.
                        Phone unreachable → stop (nothing new to learn anyway).
2. kenlm_rebuild.py   — rebuild + score live vs new on the held-out sets.
3. Gate               — push only if the new model is no worse than live on every
                        held-out set with >= MIN_GATE_TOKENS tokens (smaller sets
                        are reported, not gated). No gated set → no push.
4. Push               — adb push to /data/local/tmp, then as root: copy to
                        .spectre_q8.blm.tmp, chown system, chmod 644, rename over
                        spectre_q8.blm (KenLmScorer hot-reloads on MOVED_TO).
                        Verify md5 on the phone, then update ~/kenlm/live/.

Rollback: push ~/kenlm/out_quant/spectre_q8.blm (June model) or an older
~/kenlm/rebuild/<date>/spectre_v2_q8.blm the same way.
"""

from __future__ import annotations

import datetime as dt
import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path

HOME = Path.home() / "kenlm"
DEVICE = "100.69.13.12:5555"
REMOTE_DIR = "/data/user_de/0/com.termux.spectreboard/files"
REMOTE_MODEL = "spectre_q8.blm"
LIVE = HOME / "live/spectre_q8.blm"
MIN_GATE_TOKENS = 200


def log(msg: str) -> None:
    print(f"[{dt.datetime.now():%Y-%m-%d %H:%M:%S}] {msg}", flush=True)


def md5(path: Path) -> str:
    h = hashlib.md5()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def adb(*args: str) -> str:
    return subprocess.run(["adb", "-s", DEVICE, *args], check=True,
                          capture_output=True, text=True).stdout


def gate(report: dict) -> tuple[bool, list[str]]:
    notes, gated, ok = [], 0, True
    for name, r in report["tests"].items():
        live, new = r["live"]["ppl_incl_oov"], r["new"]["ppl_incl_oov"]
        tokens = int(r["new"]["tokens"] or 0)
        if tokens < MIN_GATE_TOKENS:
            notes.append(f"{name}: {tokens} tokens < {MIN_GATE_TOKENS}, not gated (live {live:.1f}, new {new:.1f})")
            continue
        gated += 1
        better = new <= live
        ok &= better
        notes.append(f"{name}: live {live:.1f} → new {new:.1f} ({'ok' if better else 'WORSE'})")
    if gated == 0:
        notes.append("no held-out set large enough to gate on")
        ok = False
    return ok, notes


def push(model: Path) -> None:
    tmp_remote = "/data/local/tmp/spectre_adapt_push.blm"
    adb("push", str(model), tmp_remote)
    d = REMOTE_DIR
    adb("shell", "su", "-c",
        f"'cp {tmp_remote} {d}/.{REMOTE_MODEL}.tmp && chown system:system {d}/.{REMOTE_MODEL}.tmp"
        f" && chmod 644 {d}/.{REMOTE_MODEL}.tmp && mv {d}/.{REMOTE_MODEL}.tmp {d}/{REMOTE_MODEL}"
        f" && rm {tmp_remote}'")
    on_phone = adb("shell", "su", "-c", f"'md5sum {d}/{REMOTE_MODEL}'").split()[0]
    if on_phone != md5(model):
        raise RuntimeError(f"md5 mismatch after push: phone {on_phone}, local {md5(model)}")


def main() -> int:
    log("nightly adapt run start")
    try:
        subprocess.run([sys.executable, HOME / "adapt_pull.py"], check=True)
    except subprocess.CalledProcessError as e:
        log(f"pull failed (phone unreachable?): exit {e.returncode} — stopping")
        return 1

    subprocess.run([sys.executable, HOME / "kenlm_rebuild.py"], check=True,
                   stdout=subprocess.DEVNULL)
    work = HOME / "rebuild" / dt.date.today().isoformat()
    report = json.loads((work / "report.json").read_text())
    new_model = Path(report["model"])
    print((work / "report.txt").read_text(), flush=True)

    ok, notes = gate(report)
    for n in notes:
        log(n)
    if not ok:
        log("gate not passed — keeping live model")
        return 0

    try:
        push(new_model)
    except Exception as e:
        log(f"push failed: {e} — live model unchanged")
        return 1
    shutil.copy2(new_model, LIVE)
    log(f"pushed {new_model} (md5 {md5(new_model)}) — now live")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
