"""PyTorch twin of the shipped CleverKeys swipe model (app assets/models/swipe_*_android.onnx).

Architecture = harvest/CleverKeys/model/train_character_model.py CharacterLevelSwipeModel at the
shipped size (model_config.json): d_model 256, 8 heads, 6 encoder + 4 decoder layers, ff 1024,
max_seq_len 250, max_word_len 20, 30-token char/key vocab. The forward pass follows the ONNX
graph exactly: traj_proj ++ kb_embedding -> LayerNorm -> +pe -> encoder (padding from
actual_length); char_embedding * sqrt(d) + pe -> decoder (causal + pad + memory masks) ->
output_proj -> log_softmax.
"""
import math
import torch
from torch import nn

D_MODEL, NHEAD, ENC_LAYERS, DEC_LAYERS, FF = 256, 8, 6, 4, 1024
MAX_SEQ, MAX_WORD, VOCAB, TRAJ_DIM = 250, 20, 30, 6
PAD, UNK, SOS, EOS = 0, 1, 2, 3


def sinusoidal_pe(max_len: int, d: int) -> torch.Tensor:
    pe = torch.zeros(max_len, d)
    pos = torch.arange(max_len).unsqueeze(1).float()
    div = torch.exp(torch.arange(0, d, 2).float() * -(math.log(10000.0) / d))
    pe[:, 0::2] = torch.sin(pos * div)
    pe[:, 1::2] = torch.cos(pos * div)
    return pe.unsqueeze(0)


class SwipeModel(nn.Module):
    def __init__(self, dropout: float = 0.1):
        super().__init__()
        self.traj_proj = nn.Linear(TRAJ_DIM, D_MODEL // 2)
        self.kb_embedding = nn.Embedding(VOCAB, D_MODEL // 2)
        self.encoder_norm = nn.LayerNorm(D_MODEL)
        self.register_buffer("pe", sinusoidal_pe(MAX_SEQ, D_MODEL))
        self.encoder = nn.TransformerEncoder(
            nn.TransformerEncoderLayer(D_MODEL, NHEAD, FF, dropout, batch_first=True),
            ENC_LAYERS, enable_nested_tensor=False)
        self.char_embedding = nn.Embedding(VOCAB, D_MODEL)
        self.decoder = nn.TransformerDecoder(
            nn.TransformerDecoderLayer(D_MODEL, NHEAD, FF, dropout, batch_first=True), DEC_LAYERS)
        self.output_proj = nn.Linear(D_MODEL, VOCAB)
        self.emb_scale = math.sqrt(D_MODEL)

    @staticmethod
    def length_mask(lengths: torch.Tensor, size: int) -> torch.Tensor:
        """True = padding (ignored), the PyTorch key_padding_mask convention."""
        return torch.arange(size, device=lengths.device).unsqueeze(0) >= lengths.unsqueeze(1)

    def encode(self, traj, keys, lengths):
        x = torch.cat([self.traj_proj(traj), self.kb_embedding(keys)], dim=-1)
        x = self.encoder_norm(x) + self.pe[:, :traj.shape[1]]
        return self.encoder(x, src_key_padding_mask=self.length_mask(lengths, traj.shape[1]))

    def decode(self, memory, tokens, src_lengths):
        """tokens [B, T] (SOS first, PAD-filled) -> log_probs [B, T, VOCAB]."""
        T = tokens.shape[1]
        y = self.char_embedding(tokens) * self.emb_scale + self.pe[:, :T]
        causal = torch.triu(torch.full((T, T), float("-inf"), device=tokens.device), 1)
        out = self.decoder(y, memory, tgt_mask=causal,
                           tgt_key_padding_mask=tokens.eq(PAD),
                           memory_key_padding_mask=self.length_mask(src_lengths, memory.shape[1]))
        return torch.log_softmax(self.output_proj(out), dim=-1)

    def forward(self, traj, keys, lengths, tokens):
        return self.decode(self.encode(traj, keys, lengths), tokens, lengths)
