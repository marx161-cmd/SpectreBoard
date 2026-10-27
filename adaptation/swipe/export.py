#!/usr/bin/env python3
"""Export tuned weights to the phone's ONNX contract (same names/dtypes/shapes as the shipped pair).

encoder: trajectory_features f32 [B,250,6], nearest_keys i32 [B,250], actual_length i32 [B]
         -> encoder_output f32 [B,250,256]
decoder: memory f32 [1|N,S,256], target_tokens i32 [N,20], actual_src_length i32 [1|N]
         -> log_probs f32 [N,20,30]      (memory/src_len broadcast over beams, like the shipped one)
Float32, not int8: the shipped int8 encoder rounds positions to ~13 levels (scope.md 2026-10-27).
Usage: export.py tuned.pt OUTDIR   (writes swipe_encoder_android.onnx + swipe_decoder_android.onnx)
"""
import sys
from pathlib import Path

import numpy as np
import torch
from torch import nn

from model import MAX_SEQ, MAX_WORD, SwipeModel


class Enc(nn.Module):
    def __init__(self, m): super().__init__(); self.m = m

    def forward(self, trajectory_features, nearest_keys, actual_length):
        return self.m.encode(trajectory_features, nearest_keys.long(), actual_length.long())


class Dec(nn.Module):
    def __init__(self, m): super().__init__(); self.m = m

    def forward(self, memory, target_tokens, actual_src_length):
        n = target_tokens.shape[0]
        memory = memory.expand(n, -1, -1)
        src = actual_src_length.long().expand(n)
        return self.m.decode(memory, target_tokens.long(), src)


def export(weights: Path, out: Path) -> tuple[Path, Path]:
    m = SwipeModel(dropout=0.0)
    m.load_state_dict(torch.load(weights, map_location="cpu"))
    m.eval()
    out.mkdir(parents=True, exist_ok=True)
    enc_p, dec_p = out / "swipe_encoder_android.onnx", out / "swipe_decoder_android.onnx"
    tr = torch.zeros(1, MAX_SEQ, 6); kk = torch.zeros(1, MAX_SEQ, dtype=torch.int32); ln = torch.tensor([10], dtype=torch.int32)
    torch.onnx.export(Enc(m), (tr, kk, ln), str(enc_p), opset_version=17, dynamo=False,
                      input_names=["trajectory_features", "nearest_keys", "actual_length"],
                      output_names=["encoder_output"],
                      dynamic_axes={"trajectory_features": {0: "batch"}, "nearest_keys": {0: "batch"},
                                    "actual_length": {0: "batch"}, "encoder_output": {0: "batch"}})
    mem = torch.zeros(1, MAX_SEQ, 256); tok = torch.zeros(3, MAX_WORD, dtype=torch.int32); tok[:, 0] = 2
    torch.onnx.export(Dec(m), (mem, tok, ln), str(dec_p), opset_version=17, dynamo=False,
                      input_names=["memory", "target_tokens", "actual_src_length"], output_names=["log_probs"],
                      dynamic_axes={"memory": {0: "mem_batch"}, "target_tokens": {0: "num_beams"},
                                    "actual_src_length": {0: "src_batch"}, "log_probs": {0: "num_beams"}})
    return enc_p, dec_p


def check(weights: Path, enc_p: Path, dec_p: Path) -> float:
    """Max |torch - onnxruntime| on a random swipe, beams sharing one memory like the phone."""
    import onnxruntime as ort
    m = SwipeModel(dropout=0.0); m.load_state_dict(torch.load(weights, map_location="cpu")); m.eval()
    rng = np.random.default_rng(0); L = 60
    tr = np.zeros((1, MAX_SEQ, 6), np.float32); tr[0, :L] = rng.random((L, 6))
    kk = np.zeros((1, MAX_SEQ), np.int32); kk[0, :L] = rng.integers(4, 30, L)
    ln = np.array([L], np.int32)
    tok = np.zeros((4, MAX_WORD), np.int32); tok[:, 0] = 2; tok[:, 1:3] = rng.integers(4, 30, (4, 2))
    e = ort.InferenceSession(str(enc_p), providers=["CPUExecutionProvider"])
    d = ort.InferenceSession(str(dec_p), providers=["CPUExecutionProvider"])
    om = e.run(None, {"trajectory_features": tr, "nearest_keys": kk, "actual_length": ln})[0]
    ol = d.run(None, {"memory": om, "target_tokens": tok, "actual_src_length": ln})[0]
    with torch.no_grad():
        pl = Dec(m)(torch.from_numpy(om), torch.from_numpy(tok), torch.from_numpy(ln)).numpy()
    return float(np.abs(pl[:, :3] - ol[:, :3]).max())


if __name__ == "__main__":
    w, o = Path(sys.argv[1]), Path(sys.argv[2])
    ep, dp = export(w, o)
    print("exported", ep, dp, "| max |torch-onnx| on beams sharing memory:", check(w, ep, dp))
