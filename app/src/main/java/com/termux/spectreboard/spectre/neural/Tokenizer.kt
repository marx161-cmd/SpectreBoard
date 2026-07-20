package com.termux.spectreboard.spectre.neural

object Tokenizer {
    private val charToIdx = mutableMapOf<Char, Int>()
    private val idxToChar = mutableMapOf<Int, Char>()

    init {
        val mapping = mapOf(
            'a' to 4, 'b' to 5, 'c' to 6, 'd' to 7, 'e' to 8,
            'f' to 9, 'g' to 10, 'h' to 11, 'i' to 12, 'j' to 13,
            'k' to 14, 'l' to 15, 'm' to 16, 'n' to 17, 'o' to 18,
            'p' to 19, 'q' to 20, 'r' to 21, 's' to 22, 't' to 23,
            'u' to 24, 'v' to 25, 'w' to 26, 'x' to 27, 'y' to 28,
            'z' to 29
        )
        for ((ch, idx) in mapping) {
            charToIdx[ch] = idx
            idxToChar[idx] = ch
        }
    }

    fun loadFromJson(json: String) {
        val c2i = org.json.JSONObject(json).getJSONObject("char_to_idx")
        charToIdx.clear()
        idxToChar.clear()
        for (key in c2i.keys()) {
            if (key.length == 1) {
                val idx = c2i.getInt(key)
                charToIdx[key[0]] = idx
                idxToChar[idx] = key[0]
            }
        }
    }

    fun tokenize(word: String): LongArray {
        return word.lowercase().map { c -> charToIdx[c]?.toLong() ?: NeuralConfig.UNK_IDX }.toLongArray()
    }

    fun detokenize(tokens: LongArray): String {
        return buildString {
            for (token in tokens) {
                val idx = token.toInt()
                if (idx in 4..29) idxToChar[idx]?.let { append(it) }
            }
        }
    }

    fun isCharValid(c: Char): Boolean = c in 'a'..'z'
    fun charToToken(c: Char): Long = charToIdx[c]?.toLong() ?: NeuralConfig.UNK_IDX
    fun tokenToChar(token: Long): Char? = idxToChar[token.toInt()]
    val vocabSize: Int get() = NeuralConfig.VOCAB_SIZE
}
