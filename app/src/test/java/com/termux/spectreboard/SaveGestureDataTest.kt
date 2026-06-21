package com.termux.spectreboard

import com.termux.spectreboard.keyboard.Keyboard
import com.termux.spectreboard.keyboard.KeyboardLayoutSet
import com.termux.spectreboard.keyboard.internal.KeyboardParams
import com.termux.spectreboard.latin.LatinIME
import com.termux.spectreboard.latin.NgramContext
import com.termux.spectreboard.latin.SuggestedWords
import com.termux.spectreboard.latin.common.ComposedData
import com.termux.spectreboard.latin.common.InputPointers
import com.termux.spectreboard.latin.dictionary.Dictionary
import com.termux.spectreboard.latin.utils.SuggestionResults
import com.termux.spectreboard.latin.utils.WordData
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test

@RunWith(RobolectricTestRunner::class)
class SaveGestureDataTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)

    @Test fun allowedWordIsAllowed() {
        val wd = wordData(suggestion("hello", 15, "main"), suggestion("ok", 1, "main"))
        assert(wd.filterSuggestions(emptyList()).size == 1) // only up to the target word / top suggestion
        assert(!wd.isSavingOk(latinIME)) // always fails because dict hash doesn't match
    }

    @Test fun suggestionsAreRedacted() {
        val wd = wordData(suggestion("hello", 15), suggestion("ok", 1, "main"), suggestion("other", 0, "main"))
        wd.targetWord = "ok"
        val filtered = wd.filterSuggestions(emptyList())
        assert(filtered.size == 2)
        assert(filtered[0].mWord == "")
    }

    @Test fun blockedWordIsFiltered() {
        val wd = wordData(suggestion("hello", 15, "main"), suggestion("ok", 1, "main"))
        assert(wd.filterSuggestions(listOf("hello")).none { it.mWord.equals("hello", true) })
        assert(!wd.isSavingOk(latinIME)) // always fails because dict hash doesn't match
    }

    private fun suggestion(word: String, score: Int = 0, dict: String = "") =
        SuggestedWords.SuggestedWordInfo(word, "", score, 0, Dictionary.PhonyDictionary(dict), 0, 0)

    private fun wordData(vararg suggestions: SuggestedWords.SuggestedWordInfo) =
        WordData(
            null,
            SuggestionResults(18, false, false).apply {
                suggestions.forEach { add(it) }
            },
            cd, NgramContext.EMPTY_PREV_WORDS_INFO, kb, 0, false,
            suggestions.firstOrNull()
        )

    private val cd = ComposedData(InputPointers(1), false, "")

    private val kb = Keyboard(KeyboardParams().apply {
        GRID_HEIGHT = 1
        GRID_WIDTH = 1
        mId = KeyboardLayoutSet.getFakeKeyboardId(0)
    })
}
