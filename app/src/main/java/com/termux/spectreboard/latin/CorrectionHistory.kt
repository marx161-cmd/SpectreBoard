package com.termux.spectreboard.latin

import android.text.TextUtils
import com.termux.spectreboard.event.Event
import com.termux.spectreboard.latin.common.InputPointers

class CorrectionHistory(private val capacity: Int = 8) {

    private val entries = ArrayList<LastComposedWord>(capacity)

    val size: Int get() = entries.size

    fun push(entry: LastComposedWord) {
        if (entries.size >= capacity) {
            entries.removeAt(0)
        }
        entries.add(entry)
    }

    fun active(): LastComposedWord? {
        for (i in entries.indices.reversed()) {
            val e = entries[i]
            if (e.canRevertCommit()) return e
        }
        return null
    }

    fun deactivateAll() {
        for (e in entries) e.deactivate()
    }

    fun clear() {
        entries.clear()
    }

    fun isEmpty(): Boolean = entries.isEmpty()

    fun entryAt(index: Int): LastComposedWord? =
        if (index in 0 until entries.size) entries[index] else null
}
