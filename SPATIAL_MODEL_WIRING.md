# Spatial Model — Wiring Status

## What exists and works

**`SpacialModelBuilder.kt`** (repo root — needs moving, see below)
Complete Welford-online Gaussian builder. Reads GESTURE_DATA JSON rows,
produces `Map<String, PerKeyGaussian>` — one bivariate Gaussian per key value,
with `logDensity(x, y)` for scoring a tap against that key. All 5 assumptions
verified, 3 bugs fixed. No wiring yet — computes only, touches nothing live.

**`GestureDataDao.getAllJsonData(context)`** — already exists.
Returns `Sequence<String>` of every JSON blob in GESTURE_DATA.
That's exactly what `SpatialModelBuilder.ingestRow()` needs. The data pipe is
already there — no new DAO code required.

---

## What's missing (3 gaps)

### Gap 1 — Tap/swipe distinction
`ingestRow(json, sourceIsTap: Boolean)` needs the caller to know if a row is
a tap (INPUT_STYLE_TYPING) or swipe (INPUT_STYLE_TAIL_BATCH). The DAO's
`COLUMN_SOURCE_ACTIVE` flag is unrelated (it's active vs background capture
mode, not typing style). **Check whether the JSON blob itself carries an
input-style field** — if GestureDataGathering serializes it in `GestureData`,
we can read it directly from the JSON. If not, we need to add a `COLUMN_STYLE`
to the DB or infer tap-vs-swipe from the trajectory length.

### Gap 2 — Worker to run the builder
Need a `SpatialModelWorker` (WorkManager `CoroutineWorker`, runs on charge/idle):
1. `GestureDataDao.getAllJsonData(context)` → feed each row to `SpatialModelBuilder`
2. `build()` → `Map<String, PerKeyGaussian>`
3. Serialize and persist (SharedPreferences as JSON, or a new `SPATIAL_MODEL`
   DB table with one row per key value)
4. Post a notification/flag so the keyboard picks up the new model on next load

### Gap 3 — Integration into the suggestion path
`TouchPositionCorrection.java` is the **wrong integration point** for this:
- It works per keyboard-row (coarser than per-key)
- X correction is already hardcoded to 0.0 ("obsolete")
- Format is `float[] xs/ys/radii` per row — incompatible with PerKeyGaussian

**Right approach — Kotlin-side SpatialScorer (bypass TouchPositionCorrection):**
At keystroke time, the keyboard has a tap (x, y) and a set of candidate keys.
Instead of routing through the JNI proximity path, add a Kotlin `SpatialScorer`
that loads the persisted Gaussians and scores each candidate via `logDensity`.
Wire it into wherever HeliBoard re-ranks candidates — likely
`InputLogic.java` → `Suggest.kt` → candidate reranking before the suggestion
strip is populated.

---

## Next steps in order

1. **Move the file:**
   ```
   mv SpacialModelBuilder.kt \
     app/src/main/java/com/termux/spectreboard/spectre/spatial/SpacialModelBuilder.kt
   mkdir -p app/src/main/java/com/termux/spectreboard/spectre/spatial/
   ```
   Package declaration already matches (`com.termux.spectreboard.spectre.spatial`).

2. **Check GestureData.kt** for an input-style field → resolves Gap 1.

3. **Write `SpatialModelWorker.kt`** — orchestrate read → build → persist.

4. **Write `SpatialScorer.kt`** — load persisted Gaussians, expose
   `score(keyValue: String, tapX: Float, tapY: Float): Double`.

5. **Wire `SpatialScorer` into `Suggest.kt`** — re-rank candidates using
   log-linear interpolation of LM score + spatial score.
