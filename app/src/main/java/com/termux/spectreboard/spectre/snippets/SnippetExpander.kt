// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.spectre.snippets

import com.termux.spectreboard.keyboard.KeyboardActionListener
import com.termux.spectreboard.latin.InputAttributes

/**
 * Glue between the snippet set and the IME (scope.md 2026-11-21). Inline expansion itself
 * lives in InputLogic (tryExpandSnippet / revert on backspace) because it needs the composing
 * state; this object holds the policy and rendering.
 */
object SnippetExpander {
    /** never expand in password fields; TYPE_NULL editors (e.g. Termux) get key events, not text */
    @JvmStatic
    fun allowedIn(attrs: InputAttributes?): Boolean =
        attrs != null && !attrs.mIsPasswordField && !attrs.isTypeNull

    @JvmStatic
    fun set(): SnippetSet = SnippetStore.current()

    @JvmStatic
    fun render(snippet: Snippet): String =
        SnippetRenderer.render(snippet, SnippetStore.current().globalVars, System.currentTimeMillis())

    fun insertFromPanel(snippet: Snippet, listener: KeyboardActionListener) {
        listener.onTextInput(render(snippet))
    }
}
