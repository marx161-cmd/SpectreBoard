/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package com.termux.spectreboard.latin.suggestions;

import com.termux.spectreboard.latin.SuggestedWords;

/**
 * An object that gives basic control of a suggestion strip and some info on it.
 */
public interface SuggestionStripViewAccessor {
    void setNeutralSuggestionStrip();
    void setSuggestions(final SuggestedWords suggestedWords);
    void showSuggestionStrip();
}
