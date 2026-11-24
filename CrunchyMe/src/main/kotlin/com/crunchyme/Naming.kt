package com.crunchyme

/**
 * Audio-language and quality detection. Only declared evidence is used: names, native
 * names, ISO codes and BCP-47 tags. Unknown labels stay empty instead of being guessed.
 */
object Naming {

    private data class Lang(val canonical: String, val codes: List<String>, val aliases: List<String>)

    private fun lang(canonical: String, codes: String, aliases: String): Lang = Lang(
        canonical = canonical,
        codes = codes.split(' ').filter { it.isNotBlank() },
        aliases = aliases.split('|').map { it.trim().lowercase() }.filter { it.isNotBlank() },
    )

    private val LANGS: List<Lang> = listOf(
        lang("Hindi", "hi hin", "hindi|hindustani|हिंदी|हिन्दी"),
        lang("Bengali", "bn ben bang", "bengali|bangla|বাংলা"),
        lang("Tamil", "ta tam", "tamil|தமிழ்"),
        lang("Telugu", "te tel", "telugu|తెలుగు"),
        lang("Malayalam", "ml mal", "malayalam|malyalam|മലയാളം"),
        lang("Kannada", "kn kan", "kannada|kannad|ಕನ್ನಡ"),
        lang("Marathi", "mr mar", "marathi|मराठी"),
        lang("Punjabi", "pa pan", "punjabi|panjabi|ਪੰਜਾਬੀ"),
        lang("Gujarati", "gu guj", "gujarati|ગુજરાતી"),
        lang("Urdu", "ur urd", "urdu|اردو"),
        lang("Nepali", "ne nep", "nepali|नेपाली"),
        lang("Sinhala", "si sin", "sinhala|සිංහල"),
        lang("Assamese", "as asm", "assamese|অসমীয়া"),
        lang("Odia", "or ori", "odia|oriya|ଓଡ଼ିଆ"),
        lang("Bhojpuri", "bho", "bhojpuri|भोजपुरी"),
        lang("English", "en eng", "english|eng"),
        lang("Arabic", "ar ara", "arabic|العربية|عربي"),
        lang("Russian", "ru rus", "russian|русский"),
        lang("Chinese", "zh chi zho cmn", "chinese|mandarin|cantonese|中文|普通话"),
        lang("Japanese", "ja jpn", "japanese|日本語"),
        lang("Korean", "ko kor", "korean|한국어"),
        lang("French", "fr fre fra", "french|français|francais"),
        lang("Spanish", "es spa", "spanish|español|espanol|latino"),
        lang("Portuguese", "pt por", "portuguese|português|portugues"),
        lang("German", "de ger deu", "german|deutsch"),
        lang("Italian", "it ita", "italian|italiano"),
        lang("Dutch", "nl dut", "dutch|nederlands"),
        lang("Polish", "pl pol", "polish|polski"),
        lang("Turkish", "tr tur", "turkish|türkçe|turkce"),
        lang("Thai", "th tha", "thai|ไทย"),
        lang("Vietnamese", "vi vie", "vietnamese|tiếng việt"),
        lang("Indonesian", "id ind", "indonesian|bahasa indonesia"),
        lang("Malay", "ms may msa", "malay|bahasa melayu|melayu"),
        lang("Filipino", "fil tl tgl", "filipino|tagalog"),
        lang("Swedish", "sv swe", "swedish|svenska"),
        lang("Norwegian", "no nor nob", "norwegian|norsk"),
        lang("Danish", "da dan", "danish|dansk"),
        lang("Finnish", "fi fin", "finnish|suomi"),
        lang("Czech", "cs cze", "czech|čeština"),
        lang("Slovak", "sk slo", "slovak|slovenčina"),
        lang("Hungarian", "hu hun", "hungarian|magyar"),
        lang("Romanian", "ro rum", "romanian|română"),
        lang("Bulgarian", "bg bul", "bulgarian|български"),
        lang("Greek", "el gre", "greek|ελληνικά"),
        lang("Hebrew", "he heb", "hebrew|עברית"),
        lang("Persian", "fa per fas", "persian|farsi|فارسی"),
        lang("Ukrainian", "uk ukr", "ukrainian|українська"),
        lang("Serbian", "sr srp", "serbian|српски"),
        lang("Croatian", "hr hrv", "croatian|hrvatski"),
        lang("Albanian", "sq alb", "albanian|shqip"),
        lang("Georgian", "ka geo", "georgian|ქართული"),
        lang("Armenian", "hy arm", "armenian|հայերեն"),
        lang("Kazakh", "kk kaz", "kazakh|қазақ"),
        lang("Swahili", "sw swa", "swahili|kiswahili"),
        lang("Afrikaans", "af afr", "afrikaans"),
        lang("Amharic", "am amh", "amharic|አማርኛ"),
        lang("Pashto", "ps pus", "pashto|پښتو"),
        lang("Kurdish", "ku kur", "kurdish|kurdî"),
        lang("Latin", "la lat", "latin"),
        lang("Catalan", "ca cat", "catalan|català"),
        lang("Basque", "eu baq", "basque|euskara"),
        lang("Galician", "gl glg", "galician|galego"),
        lang("Belarusian", "be bel", "belarusian|беларуская"),
        lang("Macedonian", "mk mac", "macedonian|македонски"),
    )

    private val CODE_TO_CANONICAL: Map<String, String> = buildMap {
        for (language in LANGS) {
            put(language.canonical.lowercase(), language.canonical)
            for (code in language.codes) put(code, language.canonical)
            for (alias in language.aliases) put(alias, language.canonical)
        }
    }

    /** Multi-letter aliases only: bare two-letter codes are too easy to hit inside titles. */
    private val TOKEN_ALIASES: List<Pair<String, String>> = LANGS
        .flatMap { language ->
            (language.aliases + language.canonical.lowercase() + language.codes.filter { it.length >= 3 })
                .map { it to language.canonical }
        }
        .sortedByDescending { it.first.length }

    private val SEPARATORS = Regex("""[^\p{L}\p{N}\p{M}']+""")
    private val BCP47 = Regex("""(?i)(?:^|[^a-z0-9])([a-z]{2,3})[-_]([a-z]{2}|[0-9]{3})(?:[^a-z0-9]|$)""")
    private val BRACKET_CODE = Regex("""(?i)[\[(]([a-z]{2,3})[\])]""")
    private val DECLARED = Regex("""(?i)(?:lang|language|audio)\s*[:=]\s*["']?([a-z]{2,3})""")
    private val MULTI_MARKER = Regex("""(?i)\b(?:multi|dual|dubbed)\b""")
    private val HEIGHT = Regex("""(?i)\b(\d{3,4})\s*p(?:[0-9]{2})?\b""")
    private val SIZE = Regex("""(?i)\b(\d+(?:\.\d+)?)\s*(gib|gb|mib|mb)\b""")

    fun languages(text: String?): List<String> {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        val lowered = raw.lowercase()
        val padded = " " + SEPARATORS.replace(lowered, " ") + " "

        val hits = LinkedHashMap<String, Int>()
        fun add(canonical: String?, at: Int) {
            if (canonical == null || at < 0) return
            val previous = hits[canonical]
            if (previous == null || at < previous) hits[canonical] = at
        }

        for ((alias, canonical) in TOKEN_ALIASES) {
            add(canonical, padded.indexOf(" $alias "))
        }
        for (match in BCP47.findAll(lowered)) {
            add(CODE_TO_CANONICAL[match.groupValues[1].lowercase()], match.range.first)
        }
        for (match in BRACKET_CODE.findAll(lowered)) {
            add(CODE_TO_CANONICAL[match.groupValues[1].lowercase()], match.range.first)
        }
        for (match in DECLARED.findAll(lowered)) {
            add(CODE_TO_CANONICAL[match.groupValues[1].lowercase()], match.range.first)
        }
        return hits.entries.sortedBy { it.value }.map { it.key }
    }

    fun labelOf(text: String?): String {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return ""
        return fromCode(value).ifBlank { languages(value).firstOrNull().orEmpty() }
    }

    /** For fields that are known to hold a language code rather than free text. */
    fun fromCode(code: String?): String = CODE_TO_CANONICAL[code?.trim()?.lowercase()].orEmpty()

    /**
     * Up to three declared languages are listed; more than three collapse to "Multi".
     * Returns "" when nothing can be proven.
     */
    fun displayLabel(raw: String?, originalLanguage: String? = null): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return labelOf(originalLanguage)
        if (text.equals("original", true) || text.equals("orig", true)) {
            return labelOf(originalLanguage).ifBlank { "Original" }
        }
        val names = languages(text)
        return when {
            names.size == 1 -> names[0]
            names.size in 2..3 -> names.joinToString(", ")
            names.size > 3 -> "Multi"
            MULTI_MARKER.containsMatchIn(text) -> "Multi"
            else -> ""
        }
    }

    fun qualityHeight(text: String?): Int {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return 0
        HEIGHT.find(value)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        if (Regex("""(?i)\b(?:4k|uhd)\b""").containsMatchIn(value)) return 2160
        if (Regex("""(?i)\b(?:2k|qhd|1440p)\b""").containsMatchIn(value)) return 1440
        return 0
    }

    fun qualityName(height: Int): String = when {
        height >= 2160 -> "4K"
        height >= 1440 -> "2K"
        height >= 1080 -> "1080p"
        height >= 720 -> "720p"
        height >= 576 -> "576p"
        height >= 480 -> "480p"
        height > 0 -> "${height}p"
        else -> ""
    }

    fun sizeLabel(text: String?): String {
        val match = SIZE.find(text ?: return "") ?: return ""
        val unit = match.groupValues[2].uppercase().replace("IB", "B")
        return "${match.groupValues[1]} $unit"
    }

    fun linkLabel(height: Int, audio: String, size: String): String = listOf(
        qualityName(height),
        audio.trim(),
        size.trim(),
    ).filter { it.isNotBlank() }.joinToString(" · ")
}
