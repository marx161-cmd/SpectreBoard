package com.termux.spectreboard.spectre

object FuzzyExpander {
    @Volatile private var vocabTrie: com.termux.spectreboard.spectre.neural.VocabularyTrie? = null
    private const val MAX_DISTANCE = 2
    private const val MAX_RESULTS = 25

    fun setVocabulary(trie: com.termux.spectreboard.spectre.neural.VocabularyTrie) {
        vocabTrie = trie
    }

    fun expand(word: String): List<String> {
        val trie = vocabTrie ?: return emptyList()
        val query = word.lowercase()
        val results = mutableListOf<String>()
        val initialRow = IntArray(query.length + 1) { it } // dp[0..n] = 0,1,2,...,n
        searchTrie(trie, query, StringBuilder(), initialRow, results)
        return results
    }

    private fun searchTrie(
        trie: com.termux.spectreboard.spectre.neural.VocabularyTrie,
        query: String,
        prefix: StringBuilder,
        prevRow: IntArray,
        results: MutableList<String>
    ) {
        if (results.size >= MAX_RESULTS) return
        val maxDist = MAX_DISTANCE
        val cols = query.length + 1

        val nextChars = trie.getAllowedNextChars(prefix.toString())
        for (c in nextChars) {

            val currRow = IntArray(cols)
            currRow[0] = prevRow[0] + 1 // insert

            var minDist = currRow[0]
            for (j in 1 until cols) {
                val cost = if (query[j - 1] == c) 0 else 1
                currRow[j] = minOf(
                    currRow[j - 1] + 1,   // insert
                    prevRow[j] + 1,       // delete
                    prevRow[j - 1] + cost // substitute/replace
                )
                if (currRow[j] < minDist) minDist = currRow[j]
            }

            if (minDist > maxDist) continue

            prefix.append(c)
            val word = prefix.toString()

            if (currRow[cols - 1] <= maxDist && trie.isWord(word) && word != query) {
                results.add(word)
                if (results.size >= MAX_RESULTS) {
                    prefix.deleteCharAt(prefix.length - 1)
                    return
                }
            }

            searchTrie(trie, query, prefix, currRow, results)
            prefix.deleteCharAt(prefix.length - 1)
        }
    }
}
