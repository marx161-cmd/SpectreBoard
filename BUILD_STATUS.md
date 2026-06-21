# SpectreBoard — Build Status

**Built:** 2026-06-22  
**APK:** `app/build/outputs/apk/release/SpectreBoard_4.0-alpha2-release.apk` (24MB)  
**Installed on:** Pixel 10 Pro (`blazer`)  
**Signed with:** Termux suite key (`com.termux.*` ecosystem)

## What's done

### Project identity
- Forked from HeliBoard v4.0-alpha2 (`ec00934d`)
- Renamed to SpectreBoard (display name, APK output, all translations)
- Package: `com.termux.spectreboard` (application ID)
- Namespace: `com.termux.spectreboard.latin`

### Phase 1 — Data collection (complete)
- `/app/src/main/java/com/termux/spectreboard/latin/Suggest.kt:337` — widened to accept `INPUT_STYLE_TYPING` alongside `INPUT_STYLE_TAIL_BATCH`
- `/app/src/main/java/com/termux/spectreboard/latin/utils/GestureDataGathering.kt:310` — same gate widened
- Tap + swipe data now flows into `GESTURE_DATA` SQLite table

### Custom dictionary pipeline (complete)
- `~/homelab/kenlm/corpus2combined.py` — fixed: smart-quote tokenizer, artifact filtering, sentence-aware bigrams, `dictionary=` header
- `~/homelab/kenlm/extract_gemini_corpus.py` — parses Google Takeout `My Activity.json`, extracts user + assistant text
- `~/homelab/kenlm/extract_chatgpt_corpus.py` — parses ChatGPT `conversations.json`, extracts user + assistant text
- Combined corpus: ~2.1M user words + ~17.6M combined words across both services
- `.combined` → `.dict` compiled on Termux with dicttool
- `main_en_US.dict` (2.4MB, 80K words + 500K bigrams) bundled in `app/src/main/assets/dicts/`
- `main_de_DE.dict` (in progress — combined file built, dict compilation pending)

### Spatial model analysis (planning complete)
- `SpacialModelBuilder.kt` — verified all 5 assumptions against live code, fixed 3 bugs
- JSON field names, id-grouping collapse, package declaration all corrected

### Build infrastructure
- Release signing with Termux suite keystore (`~/.gradle/gradle.properties` → `TERMUX_*`)
- `namespace = "com.termux.spectreboard.latin"` — matches source tree, no import breakage
- `applicationId = "com.termux.spectreboard"` — shares Termux ecosystem identity

## Next steps

### Phase 2 — Spatial model (ready to start)
- Build Gaussian consumer reading `GESTURE_DATA` table
- Wire into `TouchPositionCorrection` → `ProximityInfo` → JNI path
- Replace static `touch-position-correction.xml` with live per-key gaussians

### Phase 3 — Neural stack (pending)
- GRU-CIFG-LM → TFLite → APK assets
- KenLM cross-compile for arm64
- Wire into HeliBoard's native suggest engine

### Parallel work
- Haptics (`VibrationEffect.EFFECT_CLICK` on `ACTION_DOWN`)
- ASK layout XML → HeliBoard format converter
- Whisper integration (whisper.cpp or LiteRT)
- Adaptive learning framework

## File locations

| What | Where |
|---|---|
| APK | `app/build/outputs/apk/release/SpectreBoard_4.0-alpha2-release.apk` |
| English dict | `app/src/main/assets/dicts/main_en_US.dict` |
| Theme | `app/src/main/assets/themes/pixelteal.json` |
| Backup | `HeliBoard_backup_2026-06-21.zip` |
| Overview doc | `SpectreBoard-Overview.md` |
| Spatial model | `SpacialModelBuilder.kt` (repo root, needs moving to source) |
| Corpus tools | `~/homelab/kenlm/corpus2combined.py`, `extract_*_corpus.py` |
| Built .combined files | `~/homelab/kenlm/out/` |
