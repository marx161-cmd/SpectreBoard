# SpectreBoard Autocorrect Pipeline — End-to-End Flow

## 1. Tap Typing (single key presses)

```
User taps "r" → "e" → "c" → "i" → "e" → "v" → "e"
  │
  ▼
LatinIME.onCodeInput(code='r', ...)
  → Event.createSoftwareKeypressEvent('r', ...)
  → InputLogic.onEvent(event)

LatinIME.onCodeInput(code='e', ...)
  → InputLogic.onEvent(event)                          // composing word: "re"
  → WordComposer.processEvent(event)
  → InputLogicHandler → getSuggestedWords()
  → Suggest.getSuggestedWordsForNonBatchInput()
      │
      ├─ mDictionaryFacilitator.getSuggestionResults()   // JNI trie: spatial proximity search
      │
      ├─ PhoneticExpander.expand("recieve")              // Double Metaphone: "RSF" → receives, receive, ...
      │
      ├─ FuzzyExpander.expand("recieve")                 // Levenshtein ≤2 trie walk: recieve → receive
      │
      ├─ rerankCombined(suggestions)                     // Spatial+Gaussian + KenLM + GRU — combine & rank
      │
      └─→ SuggestedWords(["receive", "received", ...])   // shown in suggestion strip
```

## 2. Swipe / Gesture Typing

```
User drags finger: h → e → l → l → o
  │
  ▼
PointerTracker.onTouchEvent(MotionEvent)
  → GestureEnabler.shouldHandleGesture()                // gate: Google lib available OR neural toggle on
  → BatchInputArbiter.addMoveEventPoint(x, y, time)    // accumulates trajectory
  → sInGesture = true
  → (repeatedly during drag) InputLogicHandler.onUpdateBatchInput()
  → (at lift-off) InputLogicHandler.onEndBatchInput()
  │
  ▼
Suggest.getSuggestedWordsForBatchInput()   // inputStyle=TAIL_BATCH (only at lift-off)
  │
  ├─ mDictionaryFacilitator.getSuggestionResults()     // Google JNI: spatial trie on trajectory
  │
  ├─ NeuralGestureEngine.predict(coords, times, ...)   // CleverKeys ONNX: encoder→beam search→decoder
  │    │
  │    ├─ TrajectoryFeatureExtractor.extractFeatures()  // normalize, smooth, velocity, acceleration
  │    ├─ Encoder ONNX → memory tensor [1, 250, 256]
  │    ├─ BeamSearchDecoder.search(memory)              // width=6, trie masking, length-normalized
  │    └─→ ["hallo", "hello", "hall"]                   // CleverKeys top-3
  │
  ├─ Merge: Google candidates + CleverKeys top-3        // dedup, Google scored higher
  │
  ├─ rerankCombined(suggestionsContainer)               // Spatial trajectory + Gaussian per-key + KenLM + GRU
  │
  ├─ GestureAbLogger.log(googleTop, neuralTop, ...)     // persist to gesture_ab_log.jsonl
  │
  ├─ preferNextWordSuggestion()                         // promote alternatives that match next-word context
  │
  └─ lastBatchAlternatives = [alt1, alt2]               // store for post-swipe mixed suggestions
```

## 3. Auto-Correction Commit

```
Top suggestion "hello" is committed (wordComposer.commitWord)
  │
  ▼
InputLogic.commitChosenWord("hello", COMMIT_TYPE_DECIDED_WORD)
  │
  ├─ getTextWithSuggestionSpan(chosenWord, suggestions, commitType=DECIDED)
  │    └─ SuggestionSpan with FLAG_AUTO_CORRECTION         // marks word as auto-corrected in text field
  │
  ├─ mConnection.commitText(spannable, 1)                  // insert into editor, span persists
  │
  ├─ mLastComposedWord = new LastComposedWord(
  │        typedWord="hallo", committedWord="hello", ...)
  │
  ├─ mCorrectionHistory.push(mLastComposedWord)            // ring buffer: multi-word undo
  │
  ├─ CorrectionOverride log (if manual pick ≠ typed word)
  │
  └─ performUpdateSuggestionStripSync(INPUT_STYLE_TYPING)  // queue next-word predictions
```

## 4. Post-Swipe Mixed Suggestions

```
After swipe commit, next suggestion update fires
  │
  ▼
Suggest.getSuggestedWordsForNonBatchInput()               // typedWordString="" → predictions
  │
  ├─ getNextWordSuggestions(ngramContext)                  // "what comes after 'hello'?"
  │
  ├─ rerankCombined(suggestionsContainer)
  │
  ├─ Inject lastBatchAlternatives at position 1 and 3      // "was the swipe 'hallo' or 'hell'?"
  │    ├─ Slot 1: "hallo"  (swipe alternative)
  │    ├─ Slot 2: "world"  (next-word prediction)
  │    └─ Slot 3: "hell"   (2nd swipe alternative)
  │
  └─ lastBatchAlternatives = emptyList()                   // consumed
```

## 5. Backspace Undo (Immediate)

```
User types space, then backspace on auto-corrected word
  │
  ▼
InputLogic.onEvent(backspace_event)
  → handleBackspaceEvent(event, ...)
  │
  ├─ mLastComposedWord.canRevertCommit()?                 // was last word auto-corrected?
  │    YES → revertCommit(inputTransaction)
  │    │
  │    ├─ Read mLastComposedWord.mTypedWord ("hallo")
  │    ├─ Delete "hello" from text field
  │    ├─ Unlearn "hello" from user dict
  │    ├─ Commit "hallo" (original typed word)
  │    ├─ Merge old SuggestionSpan into revert text
  │    └─ restartSuggestionsOnWordTouchedByCursor()
  │
  │    NO → normal backspace (delete one character)
```

## 6. Cursor-Tap Recorrection (Any Past Word)

```
User taps on a word they typed 3 sentences ago
  │
  ▼
LatinIME.onUpdateSelection(newSelStart, newSelEnd)
  → InputLogic.onUpdateSelection()
  → resetEntireInputState()                                // clear composing state
  → postResumeSuggestions(true)
  │
  ▼
restartSuggestionsOnWordTouchedByCursor()
  │
  ├─ mConnection.isCursorTouchingWord()?                  // is cursor on a word?
  │    YES → mConnection.getWordRangeAtCursor()            // get word boundaries
  │
  ├─ range.getSuggestionSpansAtWord()                     // check for SuggestionSpan at position
  │    │
  │    ├─ span HAS FLAG_AUTO_CORRECTION?
  │    │    → Extract original suggestions from span
  │    │    → Show "Undo auto-correct" in suggestion strip
  │    │
  │    └─ No span found?
  │         → mDictionaryFacilitator.getSuggestionResults() // fresh JNI dictionary lookup
  │
  ├─ PhoneticExpander.expand(word)                        // phonetic alternatives
  ├─ FuzzyExpander.expand(word)                           // edit-distance alternatives
  ├─ rerankCombined(suggestionsContainer)                 // rank everything
  │
  └─→ SuggestedWords(["original", "alt1", "alt2", ...])   // shown in suggestion strip
      │
      └─ User taps alternative → commitChosenWord(COMMIT_TYPE_MANUAL_PICK)
           │
           ├─ Log "CorrectionOverride: typed=X picked=Y"  // for retraining data
           └─ Push to CorrectionHistory                   // tracked for future undo
```

## 7. Complete Scoring Pipeline (rerankCombined)

```
rerankCombined(suggestionsContainer, composedData, ngramContext)
  │
  ├─ SpatialScorer.scoreAll(suggestions, composedData)
  │    └─ Trajectory: for each candidate, check swipe path against per-key Gaussians
  │       "Did your finger pass through 'e' region? What about 'a'?"
  │
  ├─ SpatialScorer.scoreKeys(suggestions)
  │    └─ Per-key precision: check each letter's QWERTY centroid against its Gaussian
  │       "How precisely do you tap 'e' vs 'a'? Tighter Gaussian = higher score"
  │
  ├─ Merge trajectory + key scores → combined spatial score
  │
  ├─ KenLmScorer.scoreAll(candidateWords, ngramContext)
  │    └─ N-gram language model: how likely is this word in context?
  │       "Is 'hello world' more common than 'hallo world'?"
  │
  ├─ GruScorer.scoreAll(suggestions, ngramContext)
  │    └─ GRU-CIFG neural LM: deeper context awareness
  │       "Given the last 5 words, which candidate makes most sense?"
  │
  └─ CombinedComparator.sort()
       └─ Band-preserving tiebreak:
            1. Dictionary score band (preserves JNI quality tiers)
            2. GRU neural score
            3. KenLM n-gram score
            4. Combined spatial score (trajectory + per-key)
            5. Original dictionary score
```

## Summary: What fires when

| Trigger | JNI Dict | Phonetic | Fuzzy | CleverKeys | Spatial | KenLM | GRU | History |
|---------|:--------:|:--------:|:-----:|:----------:|:-------:|:-----:|:---:|:-------:|
| Tap typing (live) | ✓ | ✓ | ✓ | ✗ | ✓ | ✓ | ✓ | ✗ |
| Swipe drag (live) | ✓ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ |
| Swipe lift (TAIL_BATCH) | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ | ✓ | push |
| Cursor-tap recorrection | ✓ | ✓ | ✓ | ✗ | ✓ | ✓ | ✓ | read |
| Post-swipe predictions | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ | ✓ | inject |
| Backspace undo | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | pop |
| Manual override | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | log |

Note: Swipe drag (UPDATE_BATCH) no longer fires CleverKeys, spatial, or KenLM — only at lift-off (TAIL_BATCH) to avoid lag.
