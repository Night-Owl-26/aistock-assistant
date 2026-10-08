package com.aistock.assistant.utils

object Units {
    val ALL = listOf("PCS", "BOX", "KG", "GRAM", "LITRE", "ML", "METER", "FEET", "PACK", "BAG", "BOTTLE", "SET", "OTHER")

    private val words: Map<String, String> = buildMap {
        fun add(code: String, vararg w: String) { for (x in w) this[x] = code }
        add("PCS", "pcs", "pc", "piece", "pieces", "nos", "numbers", "unit", "units", "பீஸ்", "எண்ணிக்கை")
        add("BOX", "box", "boxes", "பெட்டி", "பாக்ஸ்")
        add("KG", "kg", "kgs", "kilo", "kilos", "kilogram", "kilograms", "கிலோ")
        add("GRAM", "g", "gm", "gms", "gram", "grams", "கிராம்")
        add("LITRE", "l", "ltr", "ltrs", "litre", "litres", "liter", "liters", "லிட்டர்")
        add("ML", "ml")
        add("METER", "m", "mtr", "mtrs", "meter", "meters", "metre", "metres", "மீட்டர்")
        add("FEET", "ft", "foot", "feet", "அடி")
        add("PACK", "pack", "packs", "packet", "packets", "பாக்கெட்")
        add("BAG", "bag", "bags", "பை", "மூட்டை")
        add("BOTTLE", "bottle", "bottles", "பாட்டில்")
        add("SET", "set", "sets", "செட்")
    }

    /** Regex alternation of every spoken unit word, longest first. */
    val WORD_PATTERN: String = words.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

    /** Maps a spoken or typed word to a unit code; unknown text is kept as a custom unit in upper case. */
    fun normalize(word: String?): String? {
        val w = word?.trim()?.lowercase() ?: return null
        if (w.isEmpty()) return null
        words[w]?.let { return it }
        val upper = w.uppercase()
        return if (upper in ALL) upper else null
    }

    fun custom(word: String): String = normalize(word) ?: word.trim().uppercase().take(12)

    fun label(unit: String, qtyMilli: Long, tamil: Boolean = false): String {
        val one = qtyMilli == 1000L
        if (tamil) {
            return when (unit) {
                "PCS" -> "பீஸ்"; "BOX" -> "பெட்டி"; "KG" -> "கிலோ"; "GRAM" -> "கிராம்"; "LITRE" -> "லிட்டர்"
                "ML" -> "மி.லி"; "METER" -> "மீட்டர்"; "FEET" -> "அடி"; "PACK" -> "பாக்கெட்"; "BAG" -> "பை"
                "BOTTLE" -> "பாட்டில்"; "SET" -> "செட்"; "OTHER" -> "யூனிட்"
                else -> unit.lowercase()
            }
        }
        return when (unit) {
            "PCS" -> if (one) "piece" else "pieces"
            "BOX" -> if (one) "box" else "boxes"
            "KG" -> "kg"
            "GRAM" -> if (one) "gram" else "grams"
            "LITRE" -> if (one) "litre" else "litres"
            "ML" -> "ml"
            "METER" -> if (one) "meter" else "meters"
            "FEET" -> "feet"
            "PACK" -> if (one) "pack" else "packs"
            "BAG" -> if (one) "bag" else "bags"
            "BOTTLE" -> if (one) "bottle" else "bottles"
            "SET" -> if (one) "set" else "sets"
            "OTHER" -> if (one) "unit" else "units"
            else -> unit.lowercase()
        }
    }

    /** Converts between compatible units. Returns null when the units cannot be converted. */
    fun convert(qtyMilli: Long, from: String, to: String): Long? {
        if (from == to) return qtyMilli
        return when {
            from == "KG" && to == "GRAM" -> qtyMilli * 1000
            from == "LITRE" && to == "ML" -> qtyMilli * 1000
            from == "GRAM" && to == "KG" -> if (qtyMilli % 1000 == 0L) qtyMilli / 1000 else null
            from == "ML" && to == "LITRE" -> if (qtyMilli % 1000 == 0L) qtyMilli / 1000 else null
            else -> null
        }
    }
}
