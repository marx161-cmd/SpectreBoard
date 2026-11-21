// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.spectre.snippets

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.termux.spectreboard.keyboard.KeyboardActionListener
import com.termux.spectreboard.latin.InputAttributes
import com.termux.spectreboard.spectre.PublicBuild
import com.termux.spectreboard.spectre.exec.SpectreBoardExecutor
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Glue between the snippet set and the IME (scope.md 2026-11-21). Inline expansion itself
 * lives in InputLogic (tryExpandSnippet / revert on backspace / applySnippetAsync) because it
 * needs the composing state; this object holds the policy and rendering.
 *
 * Step 2: `shell` vars run Params.Cmd through SpectreBoardExecutor.capture (Termux, or comrade
 * with a leading `@comrade` / `remote:`). The snippet goes in at once with [PENDING] where each
 * command's output belongs; InputLogic swaps in the final text when every command answered
 * ([timeout] / [err] markers otherwise). Typing never waits.
 */
object SnippetExpander {
    const val PENDING = "⋯"
    private const val TIMEOUT_LOCAL_MS = 3000L
    private const val TIMEOUT_REMOTE_MS = 5000L

    /** InputLogic, so a panel insert can hand its async part over */
    interface Host {
        fun trackSnippetAsync(prepared: Prepared, shown: String)
    }
    @JvmStatic var host: Host? = null

    class Prepared(val snippet: Snippet, val shown: String, val asyncVars: List<SnippetVar>, val nowMillis: Long) {
        val isAsync get() = asyncVars.isNotEmpty()
    }

    /** never expand in password fields; TYPE_NULL editors (e.g. Termux) get key events, not text */
    @JvmStatic
    fun allowedIn(attrs: InputAttributes?): Boolean =
        attrs != null && !attrs.mIsPasswordField && !attrs.isTypeNull

    @JvmStatic
    fun set(): SnippetSet = SnippetStore.current()

    @JvmStatic
    fun prepare(context: Context, snippet: Snippet): Prepared {
        val globals = SnippetStore.current().globalVars
        val now = System.currentTimeMillis()
        val async = if (PublicBuild.enabled) emptyList() else SnippetRenderer.asyncVars(snippet, globals)
        val shown = SnippetRenderer.render(snippet, globals, now, Locale.getDefault()) { v ->
            when (v.type.lowercase(Locale.ROOT)) {
                "shell" -> if (PublicBuild.enabled) null else PENDING
                "clipboard" -> clipboardText(context)
                else -> null
            }
        }
        return Prepared(snippet, shown, async, now)
    }

    /** Runs the snippet's shell vars; [onDone] gets the final text on the main thread. */
    @JvmStatic
    fun resolve(context: Context, prepared: Prepared, onDone: (String) -> Unit) {
        val vars = prepared.asyncVars
        if (vars.isEmpty()) { mainHandler.post { onDone(prepared.shown) }; return }
        val results = ConcurrentHashMap<String, String>()
        val remaining = AtomicInteger(vars.size)
        val exec = executor(context)
        val globals = SnippetStore.current().globalVars
        vars.forEach { v ->
            val cmd = v.params["Cmd"]
            val finish = { out: String ->
                results[v.name] = out
                if (remaining.decrementAndGet() == 0) {
                    val text = SnippetRenderer.render(prepared.snippet, globals, prepared.nowMillis, Locale.getDefault()) { w ->
                        when (w.type.lowercase(Locale.ROOT)) {
                            "shell" -> results[w.name]
                            "clipboard" -> clipboardText(context)
                            else -> null
                        }
                    }
                    onDone(text)
                }
            }
            if (cmd.isNullOrBlank()) mainHandler.post { finish(SpectreBoardExecutor.ERR) }
            else exec.capture(cmd, TIMEOUT_LOCAL_MS, TIMEOUT_REMOTE_MS, finish)
        }
    }

    fun insertFromPanel(context: Context, snippet: Snippet, listener: KeyboardActionListener) {
        val prepared = prepare(context, snippet)
        listener.onTextInput(prepared.shown)
        if (prepared.isAsync) host?.trackSnippetAsync(prepared, prepared.shown)
    }

    private fun clipboardText(context: Context): String? = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    } catch (_: Exception) { null }

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var exec: SpectreBoardExecutor? = null
    private fun executor(context: Context) = exec ?: SpectreBoardExecutor(context.applicationContext).also { exec = it }
}
