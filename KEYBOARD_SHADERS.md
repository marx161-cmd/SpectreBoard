# Keyboard shaders — how to mess with them

Written 2026-10-23. This is the SpectreBoard live keyboard surface (`scope.md`, entry
"2026-10-23 — Keyboard shader surface"). The keyboard's own rendered pixels are the shader
input, so keys **and letters** are warped for real.

## Where they live

| File | What it is |
|---|---|
| `~/shaders/keyboard/keyboard.agsl` | the live shader (hot-reloaded within ~1 s) |
| `~/shaders/keyboard/common.agsl` | optional shared helpers, pulled in with `//#include common.agsl` |
| `~/shaders/keyboard/keyboard_error.txt` | `OK … uniforms=[…]` or the compiler error |
| `~/shaders/keyboard/keyboard_status.txt` | `live=` bands, `bass=`, `onset=`, `beatOn=`, `hits=` |
| `~/shaders/keyboard/beat_status.txt` | beat-map match: session / go / bpm / count |

Edit from Termux — same UID as the keyboard, no root, no adb. Save, then type: a saved change
is picked up on the next key press (or the next time the keyboard opens) without restarting
the IME. If nothing changes, `cat ~/shaders/keyboard/keyboard_error.txt`.

A broken shader never breaks the keyboard: the effect is removed and the stock drawing stays
until the file compiles again.

## Turning it off

```
mv ~/shaders/keyboard/keyboard.agsl ~/shaders/keyboard/keyboard.agsl.off
```

Move it back to turn it on. (No IME restart needed either way.)

## What the shader gets

Declare only what you use — the module only sets uniforms the file declares.

| Uniform | Meaning |
|---|---|
| `shader iContent` | the real keyboard (keys + letters), premultiplied. `iContent.eval(px)` |
| `float2 iResolution` | keyboard wrapper size in pixels |
| `float iTime` | seconds, one clock for the whole app |
| `float4 iBands[27]` | 108 raw aligned bands, flattened (`iBands[b>>2][b&3]`) |
| `float4 iBandsDb[27]` | 108 cooked bands (desktop semantics, 0..1) |
| `float u_rms … u_brilliance` | the 8 named ranges, `u_centroid`, `u_flux`, `u_onset` |
| `float u_onset_smooth` | 150 ms EMA of onset |
| `float u_onset_accum` | integral of onset (unbounded; drives speed by adding) |
| `float u_bass_accum` | integral of bass³ (unbounded) |
| `float iLive` | 1 while JDSP's meter is publishing (music playing) |
| `float iBeatOn` | 1 when a cached beat map or the desktop go signal is driving |
| `float iBeatPhase` | 0..1 through the current beat (0 = on the beat) |
| `float iBeatCount` | running beat count (mod 1024) |
| `float iBpm`, `iBarPhase`, `iBeatsPerBar` | tempo / bar position |
| `float4 iHit[4]` | last 4 key rects in effect pixels: `x, y, w, h` |
| `float iHitAge[4]` | seconds since each of those keys went down |
| `float iHitN` | how many of the 4 slots are valid |

Beat priority is the same as qs-shader-live: **cached map → desktop go → live**. When
`iBeatOn = 0`, use `u_onset`/`u_flux`/`u_bass` instead of a phase.

Typical pulse:

```glsl
float kick = iBeatOn * exp(-6.0 * iBeatPhase);

float hit = 0.0;
for (int i = 0; i < 4; i++) {
    if (float(i) >= iHitN) break;
    float age = iHitAge[i];
    hit += exp(-age * 4.5);
}
```

## AGSL notes

- Entry point is `half4 main(float2 fc)`; `fc` is in effect pixels, y down.
- Colours are **premultiplied**: return `half4(rgb * a, a)`.
- `iContent.eval()` returns premultiplied too.
- One pass, no buffers. Keep it cheap: this is a full keyboard of text, and the loop only
  runs while music is live or a key was pressed in the last 1.2 s.
- PowerVR (Pixel 10 Pro) compile > 10 s is an ANR: keep the shader small. A first compile is
  done at load time; a failure goes to `keyboard_error.txt`.

## Good knobs in the shipped shader

- `LOCAL_R = 0.30` — bubble radius as a fraction of the keyboard height (~1.5–2 rows).
  This is the "how local is the glitch" knob.
- `WAVE_SPEED = 0.20`, `WAVE_WIDTH = 0.030` — how far/fast the front travels inside the bubble.
- `amp = (0.010 + 1.10 * wave) * localMask` — damage strength; `localMask` is what clamps it
  to the pressed key.
- `step(0.90 - 0.14 * wave, rnd)` — how many rows tear (lower the base for more).
- `step(0.990 - 0.10 * wave, ch)` — how many pixel blocks corrupt.
- `ca = (3.5 * wave + 0.8 * beatDrive) * localMask …` — chromatic split, in pixels.
- Teal/magenta at the junk/scar edge — the accent colours.

Everything else is off at rest: there is no grid, no edge glow, no ambient drift (neon
linework was tried on 2026-10-23 and removed by the user — the black backdrop is the look).
