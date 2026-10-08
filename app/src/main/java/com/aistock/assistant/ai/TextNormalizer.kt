package com.aistock.assistant.ai

/** Turns spoken or typed English, Tamil and mixed "Tanglish" into plain lower-case English keywords. */
object TextNormalizer {
    // Tamil-script keywords, longest first so that longer forms win.
    private val tamil: List<Pair<String, String>> = listOf(
        "குறைவாக உள்ள" to "low stock", "குறைவான" to "low stock", "குறைவாக" to "low stock", "குறைவு" to "low stock",
        "விற்பனை செய்தேன்" to "sold", "விற்றேன்" to "sold", "விற்றோம்" to "sold", "விற்பனை" to "sales",
        "வாங்கினேன்" to "bought", "வாங்கினோம்" to "bought", "கொள்முதல்" to "purchases", "வாங்கியது" to "purchases",
        "வந்துள்ளது" to "received", "வந்தது" to "received", "பெற்றேன்" to "received",
        "சேர்க்கவும்" to "add", "சேர்த்து" to "add", "சேர்க்க" to "add", "சேர்" to "add",
        "கழிக்கவும்" to "remove", "நீக்கவும்" to "remove", "நீக்கு" to "remove", "கழி" to "remove",
        "சேதமடைந்தது" to "damaged", "சேதம்" to "damaged", "பழுது" to "damaged",
        "எவ்வளவு" to "how many", "எத்தனை" to "how many",
        "இருக்கிறது" to "have", "இருக்கு" to "have", "உள்ளது" to "have",
        "இருப்பு" to "stock", "ஸ்டாக்கில்" to "to stock", "ஸ்டாக்ல" to "to stock", "ஸ்டாக்" to "stock",
        "செலவுகள்" to "expenses", "செலவு" to "expense",
        "இந்த மாதம்" to "this month", "இந்த வாரம்" to "this week", "கடந்த மாதம்" to "last month", "கடந்த வாரம்" to "last week",
        "இன்றைய" to "today", "இன்னைக்கு" to "today", "இன்று" to "today", "நேற்றைய" to "yesterday", "நேற்று" to "yesterday",
        "ரூபாய்க்கு" to "rupees", "ரூபாய்" to "rupees", "ரூபா" to "rupees",
        "காட்டவும்" to "show", "காட்டு" to "show", "சொல்லு" to "show",
        "வாடிக்கையாளராக" to "as customer", "வாடிக்கையாளர்" to "customer",
        "சப்ளையராக" to "as supplier", "சப்ளையர்" to "supplier", "விநியோகஸ்தர்" to "supplier",
        "பொருட்கள்" to "products", "பொருள்" to "product",
        "செலுத்தினேன்" to "paid", "கொடுத்தேன்" to "paid",
        "தர வேண்டும்" to "owes me", "உதவி" to "help", "அறிக்கை" to "report",
    ).sortedByDescending { it.first.length }

    // Romanised Tamil ("Tanglish") words commonly mixed into business talk.
    private val tanglish: List<Pair<Regex, String>> = listOf(
        "stock la|stockla|stock il|stockil|stock ku|stockku" to "to stock",
        "pannunga|pannungal|panunga|pannidunga|pannirunga|pannidu|pannanum|pannu|podunga|podu|seiyunga" to "",
        "irukkudhu|irukkuthu|irukudhu|irukku|iruku|irukka|iruka" to "have",
        "evvalavu|evalavu|evlavu|evlo|ethanai|ethana|yethana" to "how many",
        "vithaachu|vithachu|vittachu|vithen|vithom|vitten|vittom" to "sold",
        "vaanginen|vaanginom|vanginen|vanginom|vaangiten|vangiten|vaangi|vangi" to "bought",
        "vandhirukku|vanthirukku|vandhuchu|vanthuchu|vandhadhu|vandhachu" to "received",
        "selavugal|selavu" to "expense",
        "kammiya|kammi|kuraivu" to "low",
        "kaatunga|kaattu|kaatu|kaami|sollunga|sollu" to "show",
        "innikku|innaiku|inniku|indru|inru" to "today",
        "neththu|nettru|nethu|netru" to "yesterday",
        "roobai|rooba|rubai|ruba" to "rupees",
        "serthu|sethu|serkka|serka|koottu|kootu" to "add",
        "kazhichu|korachu|kurachu|kazhi" to "remove",
        "kuduthen|koduthen|kudutha|kattinen" to "paid",
        "aagiduchu|aayiduchu|aachu|aagi" to "",
        "venum|vendum|konjam" to "",
    ).map { Regex("\\b(?:${it.first})\\b") to it.second }

    fun normalize(raw: String): String {
        var s = raw.trim().lowercase()
        s = s.replace("₹", " rupees ")
        s = s.replace(Regex("(?<=\\d),(?=\\d)"), "")
        s = s.replace(Regex("[’'`]s\\b"), "")
        s = s.replace(Regex("[’'`]"), "")
        s = s.replace("/-", " ")
        s = s.replace(Regex("(?<!\\d)\\.|\\.(?!\\d)"), " ")
        s = s.replace(Regex("[?!,;:\"“”()\\[\\]{}*_|~-]"), " ")
        for ((k, v) in tamil) if (s.contains(k)) s = s.replace(k, " $v ")
        s = s.replace(Regex("\\s+"), " ").trim()
        s = s.replace(Regex("\\b(?:rs|inr)\\b"), "rupees").replace(Regex("\\brupee\\b"), "rupees")
        for ((re, v) in tanglish) s = re.replace(s, v)
        s = s.replace(Regex("\\s+"), " ").trim()
        // "ravi kitta irundhu ..." -> "from ravi ...",  "kumar traders ku ..." -> "to kumar traders ..."
        s = Regex("^(.+?) kitta(?: irundhu| irunthu| irundu)?\\b").replace(s) { "from ${it.groupValues[1]}" }
        s = Regex("^(\\D+?) (?:ku|kku)\\b(?= .)").replace(s) { "to ${it.groupValues[1]}" }
        s = NumberWords.replace(s)
        return s.replace(Regex("\\s+"), " ").trim()
    }
}

/** Replaces spelled-out numbers ("five hundred", "two thousand five hundred") with digits. */
object NumberWords {
    private val small = mapOf(
        "zero" to 0L, "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L, "five" to 5L, "six" to 6L, "seven" to 7L,
        "eight" to 8L, "nine" to 9L, "ten" to 10L, "eleven" to 11L, "twelve" to 12L, "thirteen" to 13L, "fourteen" to 14L,
        "fifteen" to 15L, "sixteen" to 16L, "seventeen" to 17L, "eighteen" to 18L, "nineteen" to 19L, "twenty" to 20L,
        "thirty" to 30L, "forty" to 40L, "fourty" to 40L, "fifty" to 50L, "sixty" to 60L, "seventy" to 70L,
        "eighty" to 80L, "ninety" to 90L,
        // common Tamil number words in Latin script
        "onnu" to 1L, "rendu" to 2L, "moonu" to 3L, "naalu" to 4L, "anju" to 5L, "aaru" to 6L, "ezhu" to 7L,
        "ettu" to 8L, "onbadhu" to 9L, "pathu" to 10L, "irubadhu" to 20L, "muppadhu" to 30L, "ambadhu" to 50L,
    )
    private val big = mapOf(
        "thousand" to 1_000L, "lakh" to 100_000L, "lakhs" to 100_000L, "lac" to 100_000L,
        "crore" to 10_000_000L, "crores" to 10_000_000L, "aayiram" to 1_000L, "latcham" to 100_000L,
    )
    private val hundred = setOf("hundred", "nooru")

    private fun isNum(w: String) = w in small || w in big || w in hundred

    fun replace(s: String): String {
        val toks = s.split(" ").filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var i = 0
        while (i < toks.size) {
            if (!isNum(toks[i])) { out.add(toks[i]); i++; continue }
            var total = 0L
            var current = 0L
            var j = i
            while (j < toks.size) {
                val w = toks[j]
                when {
                    w in small -> current += small.getValue(w)
                    w in hundred -> current = (if (current == 0L) 1L else current) * 100L
                    w in big -> { total += (if (current == 0L) 1L else current) * big.getValue(w); current = 0L }
                    w == "and" && j > i && j + 1 < toks.size && isNum(toks[j + 1]) -> {}
                    else -> break
                }
                j++
            }
            out.add((total + current).toString())
            i = j
        }
        return out.joinToString(" ")
    }
}
