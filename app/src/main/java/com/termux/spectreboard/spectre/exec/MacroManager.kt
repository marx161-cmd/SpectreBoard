// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.spectre.exec

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

object MacroManager {
    private const val MACRO_DIR = "/data/data/com.termux/files/home/.termux/spectreboard"

    const val MACRO_CODE_BASE = 11000
    const val MACRO_CODE_MIN = -(MACRO_CODE_BASE + 9 * 100 + 99)
    const val MACRO_CODE_MAX = -MACRO_CODE_BASE

    private val macroCache = HashMap<Int, ArrayList<MacroDefinition>>()
    private var lastStamp = -1L

    @Synchronized
    fun ensureLoaded() {
        // Reload whenever the macro tree changes, so folders/scripts added or
        // edited in Termux appear without needing to restart the keyboard
        // process (the old one-shot latch never re-scanned).
        val stamp = dirStamp()
        if (stamp == lastStamp) return
        lastStamp = stamp
        loadCustomMacrosFromTermux()
    }

    // Cheap fingerprint of the macro tree (folder + script mtimes/names) so
    // add/remove/edit is noticed, without a full reload on every keyboard build.
    private fun dirStamp(): Long {
        val base = File(MACRO_DIR)
        if (!base.isDirectory) return 0L
        var s = base.lastModified()
        base.listFiles()?.forEach { sub ->
            s = 31 * s + sub.lastModified() + sub.name.hashCode()
            if (sub.isDirectory) sub.listFiles()?.forEach { f ->
                s = 31 * s + f.lastModified() + f.name.hashCode()
            }
        }
        return s
    }

    private fun loadCustomMacrosFromTermux() {
        macroCache.clear()
        val baseDir = File(MACRO_DIR)
        Log.i("MacroManagerDbg", "load dir=$MACRO_DIR exists=${baseDir.exists()} isDir=${baseDir.isDirectory}")
        if (!baseDir.exists() || !baseDir.isDirectory) return

        for (folderNum in 0..9) {
            // Match a folder whose leading number == folderNum, allowing
            // zero-padding (e.g. "01_shortcuts" -> key 1, "0_x"/"00_x" -> key 0).
            val matchingDirs = baseDir.listFiles { dir ->
                dir.isDirectory && dir.name.substringBefore('_').toIntOrNull() == folderNum
            }
            if (matchingDirs.isNullOrEmpty()) continue
            val targetFolder = matchingDirs.first()

            val scripts = targetFolder.listFiles { _, name -> name.endsWith(".sh") }
                ?.sortedBy { it.name }
                ?: continue

            Log.i("MacroManagerDbg", "folder $folderNum -> ${targetFolder.name} scripts=${scripts.size}")
            val folderMacros = ArrayList<MacroDefinition>(scripts.size)
            for (script in scripts) {
                val label = parseHeaderLabel(script)
                folderMacros.add(MacroDefinition(label, script.absolutePath))
            }
            macroCache[folderNum] = folderMacros
        }
    }

    private fun parseHeaderLabel(file: File): String {
        try {
            BufferedReader(FileReader(file)).use { br ->
                var line = br.readLine()
                while (line != null) {
                    if (line.startsWith("# Label:")) {
                        return line.removePrefix("# Label:").trim()
                    }
                    line = br.readLine()
                }
            }
        } catch (_: Exception) {}
        return file.name.removeSuffix(".sh")
    }

    fun getFolderMacros(folderNum: Int): ArrayList<MacroDefinition>? {
        ensureLoaded()
        return macroCache[folderNum]
    }

    fun codeToMacro(primaryCode: Int): MacroDefinition? {
        if (primaryCode !in MACRO_CODE_MIN..MACRO_CODE_MAX) return null
        val absoluteCode = -primaryCode
        val folderNum = (absoluteCode - MACRO_CODE_BASE) / 100
        val scriptIndex = (absoluteCode - MACRO_CODE_BASE) % 100
        return getFolderMacros(folderNum)?.getOrNull(scriptIndex)
    }
}
