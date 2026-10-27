"""Rebuild float PyTorch weights for SwipeModel from the shipped int8 ONNX pair.

No float checkpoint of the shipped model exists (harvest/CleverKeys-ML only has an unrelated
CTC model), so weights are dequantised straight out of app/src/main/assets/models/*.onnx, located
by the module paths that survive in the ONNX node names (/encoder/layers.N/linear1/MatMul, ...).
`python onnx_import.py` writes base.pt next to the models dir and prints a parity check.
"""
import sys
from pathlib import Path
import numpy as np
import onnx
import torch
from onnx import numpy_helper

from model import SwipeModel, DEC_LAYERS, ENC_LAYERS, D_MODEL

ASSETS = Path(__file__).resolve().parents[2] / "app/src/main/assets/models"
ENC = ASSETS / "swipe_encoder_android.onnx"
DEC = ASSETS / "swipe_decoder_android.onnx"


class Graph:
    def __init__(self, path: Path):
        self.g = onnx.load(str(path)).graph
        self.init = {i.name: numpy_helper.to_array(i) for i in self.g.initializer}
        self.by_name = {n.name: n for n in self.g.node}
        self.producer = {o: n for n in self.g.node for o in n.output}

    def _deq(self, base: str, axis: int | None = None, names: tuple | None = None) -> np.ndarray:
        qn, sn, zn = names or (base + "_quantized", base + "_scale", base + "_zero_point")
        q = self.init[qn].astype(np.float32)
        scale = self.init[sn].astype(np.float32)
        zp = self.init[zn].astype(np.float32)
        if scale.ndim and axis is not None:
            shape = [1] * q.ndim
            shape[axis] = -1
            scale, zp = scale.reshape(shape), zp.reshape(shape)
        return (q - zp) * scale

    def tensor(self, name: str) -> np.ndarray:
        """Float value of an initializer, or of a DequantizeLinear output / quantized weight."""
        if name in self.init and not name.endswith("_quantized"):
            return self.init[name].astype(np.float32)
        p = self.producer.get(name)
        if p is not None and p.op_type == "DequantizeLinear":
            axis = next((a.i for a in p.attribute if a.name == "axis"), 1)
            return self._deq("", axis, names=tuple(p.input[:3]))
        if name.endswith("_quantized"):
            return self._deq(name.removesuffix("_quantized"), 1)
        raise KeyError(name)

    def weight_of(self, node_name: str) -> np.ndarray:
        return self.tensor(self.by_name[node_name].input[1])


def t(a: np.ndarray) -> torch.Tensor:
    return torch.from_numpy(np.ascontiguousarray(a, dtype=np.float32))


def build(dec_out_proj_transposed: bool = True) -> SwipeModel:
    m = SwipeModel()
    sd = {}
    e, d = Graph(ENC), Graph(DEC)

    sd["traj_proj.weight"] = t(e.weight_of("/traj_proj/MatMul").T)
    sd["traj_proj.bias"] = t(e.tensor("m.traj_proj.bias"))
    sd["kb_embedding.weight"] = t(e.tensor("m.kb_embedding.weight"))
    for k in ("weight", "bias"):
        sd[f"encoder_norm.{k}"] = t(e.tensor(f"m.encoder_norm.{k}"))
    for i in range(ENC_LAYERS):
        p, n = f"encoder.layers.{i}", f"/encoder/layers.{i}"
        sd[f"{p}.self_attn.in_proj_weight"] = t(e.weight_of(f"{n}/self_attn/MatMul").T)
        sd[f"{p}.self_attn.in_proj_bias"] = t(e.tensor(f"m.{p}.self_attn.in_proj_bias"))
        sd[f"{p}.self_attn.out_proj.weight"] = t(e.weight_of(f"{n}/self_attn/Gemm"))  # Gemm transB=1
        sd[f"{p}.self_attn.out_proj.bias"] = t(e.tensor(f"m.{p}.self_attn.out_proj.bias"))
        for lin in ("linear1", "linear2"):
            sd[f"{p}.{lin}.weight"] = t(e.weight_of(f"{n}/{lin}/MatMul").T)
            sd[f"{p}.{lin}.bias"] = t(e.tensor(f"m.{p}.{lin}.bias"))
        for nm in ("norm1", "norm2"):
            for k in ("weight", "bias"):
                sd[f"{p}.{nm}.{k}"] = t(e.tensor(f"m.{p}.{nm}.{k}"))

    sd["char_embedding.weight"] = t(d._deq("m.char_embedding.weight"))
    for i in range(DEC_LAYERS):
        p, n = f"decoder.layers.{i}", f"/decoder/layers.{i}"
        sd[f"{p}.self_attn.in_proj_weight"] = t(d.weight_of(f"{n}/self_attn/MatMul_quant").T)
        sd[f"{p}.self_attn.in_proj_bias"] = t(d.tensor(f"m.{p}.self_attn.in_proj_bias"))
        q_w = d.weight_of(f"{n}/multihead_attn/MatMul_quant").T      # [256,256]
        kv_w = d.weight_of(f"{n}/multihead_attn/MatMul_1_quant").T   # [512,256]
        sd[f"{p}.multihead_attn.in_proj_weight"] = t(np.concatenate([q_w, kv_w]))
        sd[f"{p}.multihead_attn.in_proj_bias"] = t(np.concatenate([
            d.tensor(f"{n}/multihead_attn/Slice_3_output_0"),
            d.tensor(f"{n}/multihead_attn/Slice_4_output_0")]))
        for att in ("self_attn", "multihead_attn"):
            w = d.weight_of(f"{n}/{att}/Gemm_MatMul_quant")
            sd[f"{p}.{att}.out_proj.weight"] = t(w.T if dec_out_proj_transposed else w)
            sd[f"{p}.{att}.out_proj.bias"] = t(d.tensor(f"m.{p}.{att}.out_proj.bias"))
        for lin in ("linear1", "linear2"):
            sd[f"{p}.{lin}.weight"] = t(d.weight_of(f"{n}/{lin}/MatMul_quant").T)
            sd[f"{p}.{lin}.bias"] = t(d.tensor(f"m.{p}.{lin}.bias"))
        for nm in ("norm1", "norm2", "norm3"):
            for k in ("weight", "bias"):
                sd[f"{p}.{nm}.{k}"] = t(d.tensor(f"m.{p}.{nm}.{k}"))
    sd["output_proj.weight"] = t(d.weight_of("/output_proj/MatMul_quant").T)
    sd["output_proj.bias"] = t(d.tensor("m.output_proj.bias"))
    sd["pe"] = t(d.tensor("m.pe"))

    missing, unexpected = m.load_state_dict(sd, strict=False)
    assert not unexpected, unexpected
    assert not missing, missing
    return m.eval()


def parity(m: SwipeModel, n_cases: int = 8, seed: int = 0) -> dict:
    """Compare against onnxruntime on random padded inputs (shipped shapes: 250 / 20)."""
    import onnxruntime as ort
    rng = np.random.default_rng(seed)
    enc = ort.InferenceSession(str(ENC), providers=["CPUExecutionProvider"])
    dec = ort.InferenceSession(str(DEC), providers=["CPUExecutionProvider"])
    agree = total = 0
    mem_err, lp_err = [], []
    for _ in range(n_cases):
        L = int(rng.integers(20, 200))
        traj = np.zeros((1, 250, 6), np.float32)
        traj[0, :L, :2] = rng.random((L, 2))
        traj[0, :L, 2:] = rng.normal(0, 0.5, (L, 4))
        keys = np.zeros((1, 250), np.int32)
        keys[0, :L] = rng.integers(4, 30, L)
        lens = np.array([L], np.int32)
        w = int(rng.integers(2, 12))
        tok = np.zeros((1, 20), np.int32)
        tok[0, 0] = 2
        tok[0, 1:w] = rng.integers(4, 30, w - 1)
        o_mem = enc.run(None, {"trajectory_features": traj, "nearest_keys": keys, "actual_length": lens})[0]
        o_lp = dec.run(None, {"memory": o_mem, "target_tokens": tok, "actual_src_length": lens})[0]
        with torch.no_grad():
            p_mem = m.encode(t(traj), torch.from_numpy(keys).long(), torch.from_numpy(lens).long())
            p_lp = m.decode(t(o_mem), torch.from_numpy(tok).long(), torch.from_numpy(lens).long())
        mem_err.append(float(np.abs(p_mem.numpy()[0, :L] - o_mem[0, :L]).mean()))
        lp_err.append(float(np.abs(p_lp.numpy()[0, :w] - o_lp[0, :w]).mean()))
        agree += int((p_lp.numpy()[0, :w].argmax(-1) == o_lp[0, :w].argmax(-1)).sum())
        total += w
    return {"enc_mean_abs_err": float(np.mean(mem_err)), "dec_logprob_mean_abs_err": float(np.mean(lp_err)),
            "dec_argmax_agreement": agree / total}


if __name__ == "__main__":
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else None
    for flag in (True, False):
        r = parity(build(flag))
        print(f"dec out_proj transposed={flag}: {r}")
    if out:
        torch.save(build(True).state_dict(), out)
        print("saved", out)
