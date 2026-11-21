// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.spectre.snippets

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File

/**
 * The imported snippets (scope.md 2026-11-21). App-private storage only:
 * files/snippets/<source>.json is a verbatim copy of the last good import of each file NAME.
 * Re-importing a file with the same name replaces that file's set (so the Expandroid export
 * "ttconfig.json" and e.g. "spectre-commands.json" coexist); importing a same-named file with
 * no matches removes that source. All sources merge; on a duplicate trigger the source that
 * sorts last wins. Snippet contents are never logged (only counts).
 */
object SnippetStore {
    private const val TAG = "SpectreSnippets"
    private const val DIR = "snippets"
    private const val MAX_IMPORT_BYTES = 4 * 1024 * 1024

    class ImportResult(val source: String, val count: Int, val total: Int)

    @Volatile private var set: SnippetSet? = null
    @Volatile private var appContext: Context? = null
    private val listeners = mutableListOf<() -> Unit>()

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /** current merged set; loads from disk on first use (cheap: a few KB of JSON) */
    fun current(): SnippetSet {
        set?.let { return it }
        val ctx = appContext ?: return SnippetSet.EMPTY
        synchronized(this) {
            set?.let { return it }
            val loaded = loadAll(ctx)
            set = loaded
            Log.i(TAG, "loaded ${loaded.snippets.size} snippets")
            return loaded
        }
    }

    /** Import the file at [uri] as source "its file name". Throws if unreadable/invalid (old set kept). */
    fun import(context: Context, uri: Uri): ImportResult {
        init(context)
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = input.readBytes()
            if (buf.size > MAX_IMPORT_BYTES) throw IllegalArgumentException("file too large")
            buf
        } ?: throw IllegalArgumentException("cannot open file")
        val text = bytes.toString(Charsets.UTF_8)
        val parsed = SnippetSet.parse(text)
        val source = sourceName(context, uri)
        val dir = dir(context).apply { mkdirs() }
        val f = File(dir, "$source.json")
        if (parsed.isEmpty) {
            f.delete()
        } else {
            val tmp = File(dir, "$source.json.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
        }
        val merged = synchronized(this) { loadAll(context).also { set = it } }
        Log.i(TAG, "imported ${parsed.snippets.size} snippets, total ${merged.snippets.size}")
        notifyListeners()
        return ImportResult(source, parsed.snippets.size, merged.snippets.size)
    }

    fun addListener(l: () -> Unit) = synchronized(listeners) { if (l !in listeners) listeners.add(l) }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners.remove(l) }
    private fun notifyListeners() = synchronized(listeners) { listeners.toList() }.forEach { it() }

    private fun loadAll(ctx: Context): SnippetSet {
        val files = dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.name } ?: return SnippetSet.EMPTY
        val byTrigger = LinkedHashMap<String, Snippet>()
        val globals = ArrayList<SnippetVar>()
        files.forEach { f ->
            try {
                val s = SnippetSet.parse(f.readText())
                s.snippets.forEach { byTrigger.remove(it.trigger); byTrigger[it.trigger] = it }
                globals.addAll(s.globalVars)
            } catch (e: Exception) {
                Log.w(TAG, "stored snippet file unreadable: ${e.javaClass.simpleName}")
            }
        }
        return if (byTrigger.isEmpty()) SnippetSet.EMPTY else SnippetSet(byTrigger.values.toList(), globals)
    }

    private fun sourceName(context: Context, uri: Uri): String {
        val display = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) { null }
        val base = (display ?: uri.lastPathSegment ?: "import").substringAfterLast('/').removeSuffix(".json")
        return base.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_').take(64).ifEmpty { "import" }
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, DIR)
}
