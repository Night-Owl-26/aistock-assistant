package com.aistock.assistant.voice

/**
 * Gemini's prebuilt voices: the owner picks one in Settings and Stocky always speaks in it (spoken replies through the
 * server and live voice) until the owner picks another. The server checks the name against the same list.
 */
object StockyVoices {
    data class V(val name: String, val woman: Boolean, val styleEn: String, val styleTa: String)

    const val DEFAULT = "Kore"

    val ALL: List<V> = listOf(
        V("Kore", true, "firm", "உறுதியான"),
        V("Aoede", true, "breezy", "இயல்பான"),
        V("Leda", true, "youthful", "இளமையான"),
        V("Zephyr", true, "bright", "பிரகாசமான"),
        V("Autonoe", true, "bright", "பிரகாசமான"),
        V("Callirrhoe", true, "easy-going", "சாதாரணமான"),
        V("Despina", true, "smooth", "மென்மையான"),
        V("Erinome", true, "clear", "தெளிவான"),
        V("Laomedeia", true, "upbeat", "உற்சாகமான"),
        V("Achernar", true, "soft", "மென்மையான"),
        V("Gacrux", true, "mature", "முதிர்ந்த"),
        V("Pulcherrima", true, "forward", "நேரடியான"),
        V("Vindemiatrix", true, "gentle", "அன்பான"),
        V("Sulafat", true, "warm", "இதமான"),
        V("Puck", false, "upbeat", "உற்சாகமான"),
        V("Charon", false, "informative", "விளக்கமான"),
        V("Fenrir", false, "excitable", "துடிப்பான"),
        V("Orus", false, "firm", "உறுதியான"),
        V("Enceladus", false, "breathy", "மெல்லிய"),
        V("Iapetus", false, "clear", "தெளிவான"),
        V("Umbriel", false, "easy-going", "சாதாரணமான"),
        V("Algieba", false, "smooth", "மென்மையான"),
        V("Algenib", false, "gravelly", "கரகரப்பான"),
        V("Rasalgethi", false, "informative", "விளக்கமான"),
        V("Alnilam", false, "firm", "உறுதியான"),
        V("Schedar", false, "even", "சீரான"),
        V("Achird", false, "friendly", "நட்பான"),
        V("Zubenelgenubi", false, "casual", "சாதாரணமான"),
        V("Sadachbia", false, "lively", "கலகலப்பான"),
        V("Sadaltager", false, "knowledgeable", "அறிவார்ந்த"),
    )

    fun valid(name: String?): String = ALL.firstOrNull { it.name == name }?.name ?: DEFAULT

    fun label(v: V, tamil: Boolean): String =
        if (tamil) "${v.name} · ${if (v.woman) "பெண்" else "ஆண்"} · ${v.styleTa}" else "${v.name} · ${if (v.woman) "woman" else "man"} · ${v.styleEn}"
}
