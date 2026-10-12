package com.aistock.assistant.voice

/**
 * Finds "Hey Stocky" in what the speech recogniser heard. Recognisers spell an unknown name many ways
 * ("hey stocky", "hey stockie", "a stocky", "ஹே ஸ்டாக்கி"), so all the common spellings count.
 */
object WakePhrase {
    private val names = listOf("stocky", "stockie", "stocki", "stockey", "stokey", "stoky", "stalky", "stocks", "stock e", "ஸ்டாக்கி", "ஸ்டாக்கீ", "ஸ்டோக்கி", "ஸ்டாகி")
    private val greetings = listOf("hey", "hi", "hai", "ok", "okay", "a", "he", "hay", "ஹே", "ஏய்", "ஹாய்", "ஓகே")

    /**
     * @return null when the wake phrase was not said; otherwise the words after it (possibly empty),
     * e.g. "hey stocky add 10 metres fabric" -> "add 10 metres fabric".
     */
    fun find(heard: String): String? {
        val text = heard.lowercase().replace(Regex("[,.!?।]"), " ").replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return null
        for (g in greetings) for (n in names) {
            val phrase = "$g $n"
            val i = text.indexOf(phrase)
            if (i >= 0 && (i == 0 || text[i - 1] == ' ')) {
                val end = i + phrase.length
                if (end < text.length && text[end] != ' ') continue
                return text.substring(end).trim()
            }
        }
        // "Stocky, how much fabric?" said at the very start also counts.
        for (n in names) if (text == n || text.startsWith("$n ")) return text.removePrefix(n).trim()
        return null
    }

    private val stopWords = setOf("stop", "stop listening", "bye", "bye stocky", "goodbye", "thats all", "that's all", "thank you bye", "podhum", "pothum", "poi varen", "போதும்", "நிறுத்து", "சரி போதும்")

    /** The owner ended the spoken conversation. */
    fun isStop(heard: String): Boolean = heard.lowercase().trim().trim('.', '!', ' ') in stopWords
}
