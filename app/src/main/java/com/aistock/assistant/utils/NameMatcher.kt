package com.aistock.assistant.utils

sealed class Match<out T> {
    data class Exact<T>(val item: T) : Match<T>()
    /** One close but not identical candidate: ask "did you mean". */
    data class Suggest<T>(val item: T) : Match<T>()
    data class Multiple<T>(val items: List<T>) : Match<T>()
    object None : Match<Nothing>()
}

/** Case-insensitive, plural-insensitive and typo-tolerant name matching. Never guesses between several candidates. */
object NameMatcher {
    fun tokens(name: String): List<String> =
        name.lowercase().replace(Regex("[^\\p{L}\\p{N}\\p{M}]+"), " ").trim().split(" ")
            .filter { it.isNotEmpty() }.map { singular(it) }

    fun key(name: String): String = tokens(name).joinToString(" ")

    fun singular(w: String): String = when {
        w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
        w.length > 4 && (w.endsWith("ses") || w.endsWith("xes") || w.endsWith("ches") || w.endsWith("shes")) -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") -> w.dropLast(1)
        else -> w
    }

    fun <T> match(query: String, items: List<T>, nameOf: (T) -> String): Match<T> {
        val q = tokens(query)
        if (q.isEmpty()) return Match.None
        val qKey = q.joinToString(" ")
        val exact = items.filter { key(nameOf(it)) == qKey }
        if (exact.size == 1) return Match.Exact(exact[0])
        if (exact.size > 1) return Match.Multiple(exact)

        val qSet = q.toSet()
        val candidates = items.filter { item ->
            val t = tokens(nameOf(item))
            val tSet = t.toSet()
            tSet.containsAll(qSet) || (tSet.size >= 2 && qSet.containsAll(tSet)) ||
                similarity(qKey, t.joinToString(" ")) >= 0.8
        }
        return when (candidates.size) {
            0 -> Match.None
            1 -> Match.Suggest(candidates[0])
            else -> Match.Multiple(candidates.take(8))
        }
    }

    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val max = maxOf(a.length, b.length)
        return 1.0 - levenshtein(a, b).toDouble() / max
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** "blue shirt" -> "Blue Shirt"; keeps existing capitals such as "ABC" and "A4". */
    fun titleCase(s: String): String = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { w ->
        if (w.any { it.isUpperCase() }) w
        else if (w.any { it.isDigit() }) w.uppercase()
        else w.replaceFirstChar { it.titlecase() }
    }
}
