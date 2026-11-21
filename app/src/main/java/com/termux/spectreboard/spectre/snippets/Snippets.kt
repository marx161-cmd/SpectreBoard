// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.spectre.snippets

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.util.Calendar
import java.util.Locale

/**
 * Text snippets imported from an Expandroid (Espanso-style) export (scope.md 2026-11-21).
 * Pure Kotlin, no Android types, so parsing / matching / rendering is unit-testable.
 *
 * Export shape: {"globalVars":[...], "matches":[{"Trigger", "Replace", "Vars":[{"Name","Type","Params":{...}}],
 * "Form", "Form_Fields", "Word"}]}. Keys are matched case-insensitively so a lower-case
 * Espanso file ("trigger"/"replace"/"vars") works too.
 */
data class SnippetVar(
    val name: String,
    val type: String,
    val params: Map<String, String?>,
)

data class Snippet(
    val trigger: String,
    val replace: String,
    val vars: List<SnippetVar>,
    val word: Boolean,
) {
    /** one-line preview for the panel: placeholders kept, line breaks shown as ⏎ */
    val preview: String get() = replace.replace("\r\n", "⏎").replace('\n', '⏎')
}

class SnippetMatch(val snippet: Snippet, val typedTrigger: String)

class SnippetSet(val snippets: List<Snippet>, val globalVars: List<SnippetVar> = emptyList()) {
    val maxTriggerLength = snippets.maxOfOrNull { it.trigger.length } ?: 0
    private val byLength = snippets.groupBy { it.trigger.length }.toSortedMap(compareByDescending { it })

    val isEmpty get() = snippets.isEmpty()

    /**
     * [text] = the text before the cursor including the character just typed. Returns the
     * snippet whose trigger [text] ends with, longest trigger first. Case-insensitive, so an
     * auto-capitalised first letter still expands. Word=true triggers only match when the
     * character before the trigger is not a letter/digit (or the trigger starts the text).
     */
    fun match(text: CharSequence): SnippetMatch? {
        if (snippets.isEmpty() || text.isEmpty()) return null
        for ((len, candidates) in byLength) {
            if (len > text.length || len == 0) continue
            val tail = text.subSequence(text.length - len, text.length).toString()
            val snippet = candidates.firstOrNull { it.trigger == tail }
                ?: candidates.firstOrNull { it.trigger.equals(tail, ignoreCase = true) }
                ?: continue
            if (snippet.word && text.length > len && Character.isLetterOrDigit(text[text.length - len - 1])) continue
            return SnippetMatch(snippet, tail)
        }
        return null
    }

    companion object {
        val EMPTY = SnippetSet(emptyList())
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** throws on malformed input; callers keep the old set in that case */
        fun parse(text: String): SnippetSet {
            val root = json.parseToJsonElement(text)
            val matches = when (root) {
                is JsonObject -> root.field("matches") as? JsonArray
                is JsonArray -> root
                else -> null
            } ?: throw IllegalArgumentException("no \"matches\" array")
            val globals = ((root as? JsonObject)?.field("globalVars") as? JsonArray)?.mapNotNull { parseVar(it) } ?: emptyList()
            val snippets = matches.mapNotNull { m ->
                if (m !is JsonObject) return@mapNotNull null
                val trigger = m.string("Trigger") ?: return@mapNotNull null
                if (trigger.isEmpty()) return@mapNotNull null
                val replace = m.string("Replace") ?: ""
                val vars = (m.field("Vars") as? JsonArray)?.mapNotNull { parseVar(it) } ?: emptyList()
                val word = (m.field("Word") as? JsonPrimitive)?.booleanOrNull ?: false
                Snippet(trigger, replace, vars, word)
            }
            // a later duplicate trigger wins, like re-defining it
            val dedup = LinkedHashMap<String, Snippet>()
            snippets.forEach { dedup[it.trigger] = it }
            return SnippetSet(dedup.values.toList(), globals)
        }

        private fun parseVar(e: JsonElement): SnippetVar? {
            if (e !is JsonObject) return null
            val name = e.string("Name") ?: return null
            val type = e.string("Type") ?: ""
            val params = (e.field("Params") as? JsonObject)?.entries?.associate { (k, v) -> k to v.asText() } ?: emptyMap()
            return SnippetVar(name, type, params)
        }

        private fun JsonObject.field(key: String): JsonElement? =
            this[key] ?: entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

        private fun JsonObject.string(key: String): String? = field(key)?.asText()

        private fun JsonElement.asText(): String? = when (this) {
            is JsonNull -> null
            is JsonPrimitive -> contentOrNull
            else -> toString() // e.g. Choices array: keep as JSON text
        }
    }
}

/**
 * Renders a snippet's Replace text. `{{name}}` placeholders are filled from the snippet's vars
 * (then global vars). Supported now: `date` (strftime Format + Offset seconds), `echo`
 * (Params.Echo). `shell` and anything unknown are left to [resolver]; if it returns null the
 * placeholder stays as typed in the export.
 */
object SnippetRenderer {
    private val placeholder = Regex("""\{\{\s*([A-Za-z0-9_.\-]+)\s*}}""")

    fun render(
        snippet: Snippet,
        globals: List<SnippetVar>,
        nowMillis: Long,
        locale: Locale = Locale.getDefault(),
        resolver: (SnippetVar) -> String? = { null },
    ): String {
        if (!snippet.replace.contains("{{")) return snippet.replace
        val vars = HashMap<String, SnippetVar>()
        globals.forEach { vars[it.name] = it }
        snippet.vars.forEach { vars[it.name] = it }
        return placeholder.replace(snippet.replace) { m ->
            val v = vars[m.groupValues[1]] ?: return@replace m.value
            (resolveSync(v, nowMillis, locale) ?: resolver(v) ?: m.value)
        }
    }

    /** names of vars that [render] can't fill synchronously (shell commands etc.) */
    fun asyncVars(snippet: Snippet, globals: List<SnippetVar>): List<SnippetVar> {
        if (!snippet.replace.contains("{{")) return emptyList()
        val vars = HashMap<String, SnippetVar>()
        globals.forEach { vars[it.name] = it }
        snippet.vars.forEach { vars[it.name] = it }
        return placeholder.findAll(snippet.replace).mapNotNull { vars[it.groupValues[1]] }
            .filter { it.type.lowercase(Locale.ROOT) == "shell" }.distinctBy { it.name }.toList()
    }

    fun resolveSync(v: SnippetVar, nowMillis: Long, locale: Locale): String? = when (v.type.lowercase(Locale.ROOT)) {
        "date" -> {
            val offset = v.params["Offset"]?.toLongOrNull() ?: 0L
            strftime(v.params["Format"] ?: "%x", nowMillis + offset * 1000L, locale)
        }
        "echo" -> v.params["Echo"]
        "random" -> choices(v).takeIf { it.isNotEmpty() }?.random()
        else -> null // shell, clipboard (need the device), choice/form (need UI) -> resolver or placeholder
    }

    /** Params.Choices: a JSON array of strings (as exported) or one choice per line */
    fun choices(v: SnippetVar): List<String> {
        val raw = v.params["Choices"]?.trim() ?: return emptyList()
        if (raw.startsWith("[")) runCatching {
            return (Json.parseToJsonElement(raw) as JsonArray).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        }
        return raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Small strftime. Expandroid writes `%%e` where it means `%e` (day without leading zero),
     * so `%%` followed by a known directive letter is read as that directive (scope.md
     * 2026-11-21: "11/21/2026"). A lone `%%` is a literal percent sign.
     */
    fun strftime(format: String, millis: Long, locale: Locale): String {
        val c = Calendar.getInstance(locale).apply { timeInMillis = millis }
        val sb = StringBuilder()
        var i = 0
        fun two(n: Int) = if (n < 10) "0$n" else n.toString()
        while (i < format.length) {
            val ch = format[i]
            if (ch != '%' || i + 1 >= format.length) { sb.append(ch); i++; continue }
            var d = format[i + 1]
            var consumed = 2
            if (d == '%') {
                val next = format.getOrNull(i + 2)
                if (next != null && next in DIRECTIVES) { d = next; consumed = 3 } else { sb.append('%'); i += 2; continue }
            }
            val hour12 = c.get(Calendar.HOUR).let { if (it == 0) 12 else it }
            val s: String = when (d) {
                'Y' -> c.get(Calendar.YEAR).toString()
                'y' -> two(c.get(Calendar.YEAR) % 100)
                'm' -> two(c.get(Calendar.MONTH) + 1)
                'd' -> two(c.get(Calendar.DAY_OF_MONTH))
                'e' -> c.get(Calendar.DAY_OF_MONTH).toString()
                'H' -> two(c.get(Calendar.HOUR_OF_DAY))
                'k' -> c.get(Calendar.HOUR_OF_DAY).toString()
                'I' -> two(hour12)
                'l' -> hour12.toString()
                'M' -> two(c.get(Calendar.MINUTE))
                'S' -> two(c.get(Calendar.SECOND))
                'p' -> if (c.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
                'j' -> c.get(Calendar.DAY_OF_YEAR).toString().padStart(3, '0')
                'a' -> c.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, locale) ?: ""
                'A' -> c.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, locale) ?: ""
                'b', 'h' -> c.getDisplayName(Calendar.MONTH, Calendar.SHORT, locale) ?: ""
                'B' -> c.getDisplayName(Calendar.MONTH, Calendar.LONG, locale) ?: ""
                'D', 'x' -> "${two(c.get(Calendar.MONTH) + 1)}/${two(c.get(Calendar.DAY_OF_MONTH))}/${two(c.get(Calendar.YEAR) % 100)}"
                'F' -> "${c.get(Calendar.YEAR)}-${two(c.get(Calendar.MONTH) + 1)}-${two(c.get(Calendar.DAY_OF_MONTH))}"
                'R' -> "${two(c.get(Calendar.HOUR_OF_DAY))}:${two(c.get(Calendar.MINUTE))}"
                'T', 'X' -> "${two(c.get(Calendar.HOUR_OF_DAY))}:${two(c.get(Calendar.MINUTE))}:${two(c.get(Calendar.SECOND))}"
                'u' -> ((c.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1).toString()
                'w' -> (c.get(Calendar.DAY_OF_WEEK) - 1).toString()
                's' -> (millis / 1000).toString()
                'n' -> "\n"
                't' -> "\t"
                else -> "%$d"
            }
            sb.append(s)
            i += consumed
        }
        return sb.toString()
    }

    private const val DIRECTIVES = "YymdeHkIlMSpjaAbhBDxFRTXuwsnt"
}
