package com.termux.spectreboard.spectre.neural

import android.content.Context

class VocabularyTrie {
    private class Node {
        val children = mutableMapOf<Char, Node>()
        var isEndOfWord = false
    }

    private val root = Node()
    var size: Int = 0
        private set

    fun loadFromAssets(context: Context, assetPath: String) {
        try {
            context.assets.open(assetPath).bufferedReader().useLines { lines ->
                for (line in lines) {
                    val word = line.trim().lowercase()
                    if (word.length >= 2 && word.all { it in 'a'..'z' }) {
                        insert(word)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun insert(word: String) {
        var node = root
        for (c in word) {
            if (c !in 'a'..'z') continue
            node = node.children.getOrPut(c) { Node() }
        }
        if (!node.isEndOfWord) {
            node.isEndOfWord = true
            size++
        }
    }

    fun hasPrefix(prefix: String): Boolean {
        var node = root
        for (c in prefix) {
            if (c !in 'a'..'z') continue
            node = node.children[c] ?: return false
        }
        return true
    }

    fun isWord(word: String): Boolean {
        var node = root
        for (c in word) {
            if (c !in 'a'..'z') continue
            node = node.children[c] ?: return false
        }
        return node.isEndOfWord
    }

    fun getAllowedNextChars(prefix: String): Set<Char> {
        var node = root
        for (c in prefix) {
            if (c !in 'a'..'z') continue
            node = node.children[c] ?: return emptySet()
        }
        return node.children.keys
    }
}
