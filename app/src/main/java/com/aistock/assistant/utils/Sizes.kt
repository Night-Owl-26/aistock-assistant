package com.aistock.assistant.utils

/** Spoken sizes in the short form shops write on labels and in sheets. */
object Sizes {
    private val words = linkedMapOf(
        "triple extra large" to "XXXL", "triple xl" to "XXXL", "3xl" to "XXXL", "xxxl" to "XXXL",
        "double extra large" to "XXL", "double xl" to "XXL", "2xl" to "XXL", "xxl" to "XXL",
        "extra large" to "XL", "x large" to "XL", "xl" to "XL",
        "extra small" to "XS", "x small" to "XS", "xs" to "XS",
        "small" to "S", "medium" to "M", "med" to "M", "large" to "L",
    )

    /** "medium" -> "M", "double xl" -> "XXL", "xl" -> "XL"; numbers and anything else are kept as said. */
    fun short(size: String): String {
        val t = size.trim().lowercase().replace(Regex("\\s+"), " ").removeSuffix(" size").removePrefix("size ").trim()
        words[t]?.let { return it }
        if (t.length <= 4 && t.all { it in 'a'..'z' }) return t.uppercase()
        return size.trim()
    }
}
