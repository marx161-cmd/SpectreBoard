package com.termux.spectreboard

import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import com.termux.spectreboard.latin.spellcheck.AndroidSpellCheckerService
import com.termux.spectreboard.latin.spellcheck.AndroidSpellCheckerSession
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertNotEquals

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowInputMethodManager2::class,
    ShadowDictionaryFacilitatorImpl::class,
])
class SpellCheckerTest {
    private val checkService = Robolectric.setupService(AndroidSpellCheckerService::class.java)
    private val checker = AndroidSpellCheckerSession(checkService)

    @Test fun `number is not a typo`() {
        val result = checker.onGetSuggestions(TextInfo("123"), 2)
        assertNotEquals(SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO, result.suggestionsAttributes)
    }

    @Test fun `$ is not a typo`() {
        val result = checker.onGetSuggestions(TextInfo("$"), 2)
        assertNotEquals(SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO, result.suggestionsAttributes)
    }
}
