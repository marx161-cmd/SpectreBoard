package com.termux.spectreboard.spectre

import android.content.Context
import android.util.Log

object PhoneticExpander {
    private const val TAG = "PhoneticExpander"
    private val hashToWords = HashMap<String, MutableList<String>>()
    @Volatile private var loaded = false

    fun load(words: Collection<String>) {
        synchronized(this) {
            if (loaded) return
            hashToWords.clear()
        for (word in words) {
            val (primary, secondary) = doubleMetaphone(word)
            if (primary.isNotEmpty()) {
                hashToWords.getOrPut(primary) { mutableListOf() }.add(word)
            }
            if (secondary.isNotEmpty() && secondary != primary) {
                hashToWords.getOrPut(secondary) { mutableListOf() }.add(word)
            }
        }
        Log.i(TAG, "PhoneticExpander loaded: ${hashToWords.values.sumOf { it.size }} words into ${hashToWords.size} buckets")
            loaded = true
        }
    }

    fun loadFromAssets(context: Context, assetPath: String) {
        try {
            val words = context.assets.open(assetPath).bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() && it.length >= 2 }
                    .map { it.trim().lowercase() }
                    .toList()
            }
            load(words)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load phonetic dictionary", e)
        }
    }

    fun expand(word: String): List<String> {
        val (primary, secondary) = doubleMetaphone(word)
        val results = mutableListOf<String>()
        if (primary.isNotEmpty()) hashToWords[primary]?.let { results.addAll(it) }
        if (secondary.isNotEmpty() && secondary != primary) hashToWords[secondary]?.let { results.addAll(it) }
        return results.distinct()
    }

    val isLoaded: Boolean get() = loaded

    fun doubleMetaphone(value: String): Pair<String, String> {
        if (value.isEmpty()) return Pair("", "")

        var primary = ""
        var secondary = ""
        var current = 0
        val length = value.length
        val last = length - 1

        val original = value.uppercase()

        // Pad original so we can index beyond the end
        val padded = original + "     "

        // Slavo-Germanic flag
        val slavoGermanic = original.contains("W") || original.contains("K") ||
            original.contains("CZ") || original.contains("WITZ")

        if (padded.startsWith("GN") || padded.startsWith("KN") || padded.startsWith("PN") ||
            padded.startsWith("WR") || padded.startsWith("PS")) {
            current++
        }

        if (original.startsWith("X")) {
            primary += "S"
            secondary += "S"
            current++
        }

        while (primary.length < 4 || secondary.length < 4) {
            if (current >= length) break

            when (original[current]) {
                'A', 'E', 'I', 'O', 'U', 'Y' -> {
                    if (current == 0) {
                        primary += "A"; secondary += "A"
                    }
                    current++
                }

                'B' -> {
                    primary += "P"; secondary += "P"
                    if (padded[current + 1] == 'B') current++
                    current++
                }

                'C' -> {
                    if ((current > 1 && !isVowel(padded[current - 2]) &&
                            padded.substring(current - 1, current + 2) == "ACH") ||
                        (padded[current + 1] == 'I' && padded[current + 2] == 'A')) {
                        primary += "X"; secondary += "X"
                        current += 2
                    } else if (padded[current + 1] == 'H') {
                        if (current == 0 && padded.startsWith("CH", current + 2)) {
                            primary += "K"; secondary += "K"
                        } else if (current == 0 && padded.startsWith("CHAR") ||
                            padded.startsWith("CHEM") || padded.startsWith("CHOL") ||
                            padded.startsWith("CHOR") || padded.startsWith("CHYM") ||
                            padded.startsWith("CHR")) {
                            primary += "K"; secondary += "K"
                        } else if (current > 0 && padded[current - 1] in "AEIOU" &&
                            padded[current + 2] in " LRNBHFVW K") {
                            primary += "K"; secondary += "X"
                        } else {
                            primary += "X"; secondary += "K"
                        }
                        current += 2
                    } else if (padded[current + 1] == 'Z' && padded[current - 1] != 'W') {
                        primary += "S"; secondary += "X"
                        current += 2
                    } else if (padded[current + 1] == 'C' && current == 0 &&
                        padded[current + 2] != 'I' && padded[current + 2] != 'E') {
                        primary += "K"; secondary += "K"
                        current += 2
                    } else if (padded[current + 1] == 'C' && current > 0 &&
                        padded[current - 1] != 'M' && padded[current - 1] != 'N') {
                        primary += "K"; secondary += "K"
                        current += 2
                    } else {
                        if (padded[current + 1] in "IEY") {
                            primary += "S"; secondary += "S"
                        } else {
                            primary += "K"; secondary += "K"
                        }
                        if (padded[current + 1] == 'C') current++
                        current++
                    }
                }

                'D' -> {
                    if (padded[current + 1] == 'G') {
                        if (padded[current + 2] in "IEY") {
                            primary += "J"; secondary += "J"
                            current += 3
                        } else {
                            primary += "TK"; secondary += "TK"
                            current += 2
                        }
                    } else if (padded[current + 1] == 'T' || padded[current + 1] == 'D') {
                        primary += "T"; secondary += "T"
                        current += 2
                    } else {
                        primary += "T"; secondary += "T"
                        current++
                    }
                }

                'F' -> { primary += "F"; secondary += "F"; if (padded[current + 1] == 'F') current++; current++ }
                'G' -> {
                    if (padded[current + 1] == 'H') {
                        if (current > 0 && !isVowel(padded[current - 1])) {
                            primary += "K"; secondary += "K"
                            current += 2
                        } else if (current == 0 && padded[current + 2] == 'I') {
                            primary += "J"; secondary += "J"
                            current += 2
                        } else if ((current > 1 && padded[current - 2] in "BHD") ||
                            (current > 2 && padded[current - 3] in "BHD") ||
                            (current > 3 && padded[current - 4] in "BH")) {
                            current += 2
                        } else {
                            if (current > 2 && padded[current - 1] == 'U' &&
                                padded[current - 3] in "CGLRT") {
                                primary += "F"; secondary += "F"
                            } else if (current > 0 && padded[current - 1] != 'I') {
                                primary += "K"; secondary += "K"
                            }
                            current += 2
                        }
                    } else if (padded[current + 1] == 'N') {
                        if (current == 1 && isVowel(padded[0]) && !slavoGermanic) {
                            primary += "KN"; secondary += "N"
                        } else if (padded[current + 2] != 'E' && padded.substring(current + 1, current + 3) != "EY" &&
                            !slavoGermanic) {
                            primary += "N"; secondary += "KN"
                        } else {
                            primary += "KN"; secondary += "KN"
                        }
                        current += 2
                    } else if (padded.substring(current + 1, current + 3) == "LI" && !slavoGermanic) {
                        primary += "KL"; secondary += "L"
                        current += 2
                    } else if (current == 0 && (padded[current + 1] == 'Y' ||
                            padded[current + 1] in "EIY")) {
                        primary += "K"; secondary += "J"
                        current += 2
                    } else {
                        if (padded[current + 1] in "EIY" || padded.substring(current + 1, current + 3) == "ES" ||
                            padded.substring(current + 1, current + 3) == "EP" ||
                            padded.substring(current + 1, current + 3) == "EB" ||
                            padded.substring(current + 1, current + 3) == "EL" ||
                            padded.substring(current + 1, current + 3) == "EY" ||
                            padded.substring(current + 1, current + 3) == "IB" ||
                            padded.substring(current + 1, current + 3) == "IL" ||
                            padded.substring(current + 1, current + 3) == "IN" ||
                            padded.substring(current + 1, current + 3) == "IE" ||
                            (padded.substring(current + 1, current + 3) == "ER" && padded[current - 1] !in "IEY")) {
                            primary += "J"; secondary += "J"
                        } else {
                            primary += "K"; secondary += "K"
                        }
                        if (padded[current + 1] == 'G') current++
                        current++
                    }
                }

                'H' -> {
                    if ((current > 0 && isVowel(padded[current - 1])) &&
                        (current == last || !isVowel(padded[current + 1]))) {
                        primary += "H"; secondary += "H"
                        current += 2
                    } else {
                        current++
                    }
                }

                'J' -> {
                    if (original.startsWith("JOSE") || original.startsWith("SAN ")) {
                        primary += "H"; secondary += "H"
                        current++
                    } else {
                        if (current == 0 && padded[current + 1] != ' ') {
                            primary += "J"; secondary += "A"
                        } else if (isVowel(padded[current - 1]) && !slavoGermanic &&
                            padded[current + 1] in "AEIOU") {
                            primary += "J"; secondary += "H"
                        } else if (current == last) {
                            primary += "J"; secondary += ""
                        } else {
                            primary += "J"; secondary += "J"
                        }
                        if (padded[current + 1] == 'J') current++
                        current++
                    }
                }

                'K' -> { primary += "K"; secondary += "K"; if (padded[current + 1] == 'K') current++; current++ }
                'L' -> {
                    if (padded[current + 1] == 'L') {
                        if ((current == (length - 3) && padded.substring(current - 1, current + 4).endsWith("ILLO")) ||
                            (padded.substring(current + 1, current + 4) == "ILL" &&
                                padded.substring(current - 1, current + 4) in listOf("ALLS", "ALLE")) ||
                            padded.substring(current - 1, current + 4) == "ASLL" ||
                            padded.substring(current - 1, current + 4) == "OSLL") {
                            primary += "L"; secondary += ""
                        } else {
                            primary += "L"; secondary += "L"
                        }
                        current += 2
                    } else {
                        primary += "L"; secondary += "L"
                        current++
                    }
                }

                'M' -> { primary += "M"; secondary += "M"; if (padded[current + 1] == 'M') current++; current++ }
                'N' -> { primary += "N"; secondary += "N"; if (padded[current + 1] == 'N') current++; current++ }

                'P' -> {
                    if (padded[current + 1] == 'H') {
                        primary += "F"; secondary += "F"; current += 2
                    } else if (padded[current + 1] == 'P' || padded[current + 1] == 'B') {
                        primary += "P"; secondary += "P"; current += 2
                    } else {
                        primary += "P"; secondary += "P"; current++
                    }
                }

                'Q' -> { primary += "K"; secondary += "K"; if (padded[current + 1] == 'Q') current++; current++ }
                'R' -> {
                    if (current == last && !slavoGermanic &&
                        padded[current - 2] in "IE" && padded[current - 1].let { it !in "AEIOU" } &&
                        current > 2) {
                        primary += ""; secondary += ""
                    } else {
                        primary += "R"; secondary += "R"
                    }
                    if (padded[current + 1] == 'R') current++
                    current++
                }

                'S' -> {
                    if (padded.substring(current + 1, current + 3) == "IO" ||
                        padded.substring(current + 1, current + 3) == "IA") {
                        if (padded[current - 1] in "AEIOU") {
                            primary += "X"; secondary += "X"
                        } else {
                            primary += "X"; secondary += "S"
                        }
                        current += 3
                    } else if (padded[current + 1] == 'H') {
                        primary += "X"; secondary += "X"; current += 2
                    } else if (padded.substring(current + 1, current + 3) == "CH" &&
                        (current > 0 || padded.substring(current + 3, current + 4) in " IEY")) {
                        primary += "X"; secondary += "K"; current += 3
                    } else if (padded.substring(current + 1, current + 3) in listOf("CZ", "CS") &&
                        padded.substring(current + 1, current + 4) != "CHR") {
                        current += 2
                    } else if (padded[current + 1] == 'S') {
                        primary += "S"; secondary += "S"; current += 2
                    } else {
                        primary += "S"; secondary += "S"; current++
                    }
                }

                'T' -> {
                    if (padded.substring(current + 1, current + 3) == "IA" ||
                        padded.substring(current + 1, current + 3) == "IO") {
                        primary += "X"; secondary += "X"; current += 3
                    } else if (padded[current + 1] == 'H') {
                        primary += "0"; secondary += "T"; current += 2
                    } else if (padded.substring(current + 1, current + 3) == "CH") {
                        current += 2
                    } else if (padded[current + 1] == 'T' || padded[current + 1] == 'D') {
                        primary += "T"; secondary += "T"; current += 2
                    } else {
                        primary += "T"; secondary += "T"; current++
                    }
                }

                'V' -> { primary += "F"; secondary += "F"; if (padded[current + 1] == 'V') current++; current++ }
                'W' -> {
                    if (padded[current + 1] == 'R') {
                        primary += "R"; secondary += "R"; current += 2
                    } else if (current == 0 && (padded[current + 1] in "AEIOUY" || padded[current + 1] == 'H')) {
                        primary += "A"; secondary += if (padded[current + 1] in "AEIOUY") "F" else "A"
                        current += 2
                    } else if ((current == last && isVowel(padded[current - 1])) ||
                        original.startsWith("EWSK") || original.startsWith("OWSK") ||
                        original.startsWith("OWSKI") || original.startsWith("SCH")) {
                        primary += ""; secondary += "F"; current++
                    } else if (original.startsWith("WICZ") || original.startsWith("WITZ")) {
                        primary += "TS"; secondary += "FX"; current += 4
                    } else {
                        current++
                    }
                }

                'X' -> {
                    if (current == 0) {
                        primary += "S"; secondary += "S"; current++
                    } else if (!(current == last && (padded[current - 2] in "IAU" || padded[current - 3] in "IAU" || padded[current - 4] in "IAU"))) {
                        primary += "KS"; secondary += "KS"; current++
                    } else {
                        current++
                    }
                }

                'Z' -> {
                    if (padded[current + 1] == 'H') {
                        primary += "J"; secondary += "J"; current += 2
                    } else if (padded.substring(current + 1, current + 3) == "ZO" ||
                        padded.substring(current + 1, current + 3) == "ZI" ||
                        padded.substring(current + 1, current + 3) == "ZA" ||
                        (slavoGermanic && current > 0 && padded[current - 1] != 'T')) {
                        primary += "S"; secondary += "TS"
                    } else {
                        primary += "S"; secondary += "S"
                    }
                    if (padded[current + 1] == 'Z') current++
                    current++
                }

                else -> current++
            }
        }

        return Pair(primary.take(4), secondary.take(4))
    }

    private fun isVowel(ch: Char): Boolean = ch in "AEIOUY"
}
