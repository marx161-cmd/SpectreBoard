// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.spectre.snippets

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * The imported snippet set (scope.md 2026-11-21). Lives only in app-private storage:
 * filesDir/snippets/snippets.json is a verbatim copy of the last successfully parsed import.
 * Re-import replaces the whole set. Snippet contents are never logged.
 */
object SnippetStore {
    private const val TAG = "SpectreSnippets"
    private const val DIR = "snippets"
    private const val FILE = "snippets.json"
    private const val MAX_IMPORT_BYTES = 4 * 1024 * 1024

    @Volatile private var set: SnippetSet? = null
    @Volatile private var appContext: Context? = null
    private val listeners = mutableListOf<() -> Unit>()

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /** current set; loads from disk on first use (cheap: a few KB of JSON) */
    fun current(): SnippetSet {
        set?.let { return it }
        val ctx = appContext ?: return SnippetSet.EMPTY
        synchronized(this) {
            set?.let { return it }
            val loaded = try {
                val f = file(ctx)
                if (f.isFile) SnippetSet.parse(f.readText()) else SnippetSet.EMPTY
            } catch (e: Exception) {
                Log.w(TAG, "stored snippets unreadable: ${e.javaClass.simpleName}")
                SnippetSet.EMPTY
            }
            set = loaded
            Log.i(TAG, "loaded ${loaded.snippets.size} snippets")
            return loaded
        }
    }

    /** Replace the set with the file at [uri]. Returns the snippet count; throws if unreadable/invalid (old set kept). */
    fun import(context: Context, uri: Uri): Int {
        init(context)
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = input.readBytes()
            if (buf.size > MAX_IMPORT_BYTES) throw IllegalArgumentException("file too large")
            buf
        } ?: throw IllegalArgumentException("cannot open file")
        val text = bytes.toString(Charsets.UTF_8)
        val parsed = SnippetSet.parse(text)
        if (parsed.isEmpty) throw IllegalArgumentException("no snippets in file")
        val f = file(context)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
        set = parsed
        Log.i(TAG, "imported ${parsed.snippets.size} snippets")
        notifyListeners()
        return parsed.snippets.size
    }

    fun addListener(l: () -> Unit) = synchronized(listeners) { if (l !in listeners) listeners.add(l) }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners.remove(l) }
    private fun notifyListeners() = synchronized(listeners) { listeners.toList() }.forEach { it() }

    private fun file(ctx: Context) = File(File(ctx.filesDir, DIR), FILE)
}
