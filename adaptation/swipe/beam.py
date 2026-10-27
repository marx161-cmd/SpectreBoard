"""Port of spectre/neural/BeamSearchDecoder.kt (beam 6, trie-masked, length norm alpha 1.4)."""
import math
import torch

from model import EOS, MAX_WORD, PAD, SOS
from features import char_token

BEAM_WIDTH, ALPHA, CONF_THRESHOLD = 6, 1.4, 0.01


class Trie:
    def __init__(self, words):
        self.words = set()
        self.next = {}
        for w in words:
            w = w.strip().lower()
            if len(w) < 2 or not (w.isascii() and w.isalpha()):
                continue
            self.words.add(w)
            for i in range(len(w)):
                self.next.setdefault(w[:i], set()).add(w[i])

    def mask(self, prefix: str, vocab: int = 30) -> torch.Tensor:
        m = torch.full((vocab,), float("-inf"))
        for c in self.next.get(prefix, ()):
            m[char_token(c)] = 0.0
        if prefix in self.words:
            m[EOS] = 0.0
        return m


def _norm(n_tokens: int) -> float:
    return (5.0 + n_tokens) ** ALPHA / 6.0 ** ALPHA


def _prefix(tokens) -> str:
    return "".join(chr(t - 4 + ord('a')) for t in tokens if t > EOS)


@torch.no_grad()
def decode(model, memory, src_len, trie: Trie, device) -> list[tuple[str, float]]:
    """memory [1, S, D]; returns [(word, confidence)] best first, like the phone."""
    beams = [([SOS], 0.0, False)]
    for step in range(MAX_WORD):
        active = [b for b in beams if not b[2]]
        if not active:
            break
        toks = torch.full((len(active), MAX_WORD), PAD, dtype=torch.long)
        for i, (t, _, _) in enumerate(active):
            toks[i, :len(t)] = torch.tensor(t[:MAX_WORD])
        lp = model.decode(memory.expand(len(active), -1, -1), toks.to(device),
                          src_len.expand(len(active))).float().cpu()
        cands = [b for b in beams if b[2]]
        for i, (t, score, _) in enumerate(active):
            pos = len(t) - 1
            logits = lp[i, pos] + trie.mask(_prefix(t))
            logits[SOS] = logits[PAD] = float("-inf")
            norm = torch.log_softmax(logits, -1)
            k = min(BEAM_WIDTH, int(torch.isfinite(norm).sum()))
            if k == 0:
                continue
            vals, idx = norm.topk(k)
            for v, j in zip(vals.tolist(), idx.tolist()):
                cands.append((t + [j], score - v, j == EOS))
        cands.sort(key=lambda b: b[1] / _norm(len(b[0])))
        if step >= 2:
            cands = [b for b in cands if math.exp(-b[1]) >= 1e-6]
        seen, beams = set(), []
        for b in cands:
            if len(beams) >= BEAM_WIDTH:
                break
            if tuple(b[0]) not in seen:
                seen.add(tuple(b[0]))
                beams.append(b)
        if beams and all(b[2] for b in beams):
            break
    out = []
    for t, score, _ in beams:
        w = _prefix(t)
        if not w:
            continue
        conf = math.exp(-(score / _norm(len(w))))
        if conf >= CONF_THRESHOLD:
            out.append((w, conf))
    return out
