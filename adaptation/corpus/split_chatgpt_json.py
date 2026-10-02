#!/usr/bin/env python3
import json, re, argparse, unicodedata, pathlib

def iter_msgs(obj):
    convs = obj
    if isinstance(obj, dict) and "conversations" in obj:
        convs = obj["conversations"]
    if not isinstance(convs, list):
        return
    for conv in convs:
        mapping = conv.get("mapping") if isinstance(conv, dict) else None
        if isinstance(mapping, dict):
            for node in mapping.values():
                msg = (node or {}).get("message") or {}
                role = (msg.get("author") or {}).get("role", "")
                content = msg.get("content") or {}
                parts = content.get("parts") or []
                text = "\n".join([p for p in parts if isinstance(p, str)]).strip()
                if role and text:
                    yield role, text
        msgs = conv.get("messages") if isinstance(conv, dict) else None
        if isinstance(msgs, list):
            for m in msgs:
                role = (m.get("author") or {}).get("role", m.get("role",""))
                c = m.get("content", "")
                if isinstance(c, dict):
                    c = "\n".join([p for p in c.get("parts",[]) if isinstance(p,str)])
                text = (c or "").strip()
                if role and text:
                    yield role, text

def clean(s, lower=False, strip_code=False, whitelist=False, keep_newlines=False):
    if not s: return ""
    # remove URLs/emails
    s = re.sub(r"(https?://\S+|\b[\w.%+-]+@[\w.-]+\.[A-Za-z]{2,}\b)", " ", s)
    # optionally strip code blocks/inline code
    if strip_code:
        s = re.sub(r"```.*?```", " ", s, flags=re.S)
        s = re.sub(r"`[^`]+`", " ", s)
    # normalize & whitespace
    s = unicodedata.normalize("NFKC", s)
    if keep_newlines:
        # one message per output line; line breaks escaped as literal \n so
        # merge_gru_sources.py can filter fences / pasted terminal lines per line
        lines = [re.sub(r"[^\S\n]+", " ", ln).strip() for ln in s.split("\n")]
        s = "\n".join(ln for ln in lines if ln)
        s = s.replace("\\", "\\\\").replace("\n", "\\n")
    else:
        s = re.sub(r"\s+", " ", s).strip()
    if lower:
        s = s.lower()
    if whitelist:
        # NOTE: '-' is at the END (or you can escape it as '\-')
        s = re.sub(r"[^0-9A-Za-zÄÖÜäöüß .,!?;:'\"()/%&+*=€$£@#\-]", " ", s)
        s = re.sub(r"\s+", " ", s).strip()
    return s

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    ap.add_argument("--outdir", required=True)
    ap.add_argument("--lower", action="store_true")
    ap.add_argument("--strip-code", action="store_true")
    ap.add_argument("--whitelist", action="store_true")
    ap.add_argument("--keep-newlines", action="store_true",
                    help="escape line breaks as \\n and write *_nl.txt instead of *.txt")
    args = ap.parse_args()

    data = json.load(open(args.input, "r", encoding="utf-8"))
    out = pathlib.Path(args.outdir); out.mkdir(parents=True, exist_ok=True)

    user_lines, asst_lines = [], []
    for role, text in iter_msgs(data):
        t = clean(text,
                  lower=args.lower,
                  strip_code=args.strip_code,
                  whitelist=args.whitelist,
                  keep_newlines=args.keep_newlines)
        if not t:
            continue
        if role == "user":
            user_lines.append(t)
        elif role == "assistant":
            asst_lines.append(t)

    sfx = "_nl" if args.keep_newlines else ""
    (out/f"user{sfx}.txt").write_text("\n".join(user_lines) + "\n", encoding="utf-8")
    (out/f"assistant{sfx}.txt").write_text("\n".join(asst_lines) + "\n", encoding="utf-8")
    (out/f"combined{sfx}.txt").write_text("\n".join(user_lines + asst_lines) + "\n", encoding="utf-8")

    print(f"user lines: {len(user_lines)}")
    print(f"assistant lines: {len(asst_lines)}")
    print(f"combined lines: {len(user_lines)+len(asst_lines)}")
    print(f"Wrote files in: {out}")

if __name__ == "__main__":
    main()
