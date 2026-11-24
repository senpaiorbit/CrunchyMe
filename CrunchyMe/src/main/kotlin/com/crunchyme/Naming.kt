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

    fun bytesLabel(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> String.format(java.util.Locale.US, "%.0f MB", bytes / 1_000_000.0)
        else -> ""
    }

    fun linkLabel(height: Int, audio: String, size: String): String = listOf(
        qualityName(height),
        audio.trim(),
        size.trim(),
    ).filter { it.isNotBlank() }.joinToString(" · ")

    private val COUNTRY: Map<String, String> = mapOf(
        "Hindi" to "IN", "Bengali" to "BD", "Tamil" to "IN", "Telugu" to "IN",
        "Malayalam" to "IN", "Kannada" to "IN", "Marathi" to "IN", "Punjabi" to "IN",
        "Gujarati" to "IN", "Urdu" to "PK", "Nepali" to "NP", "Sinhala" to "LK",
        "Assamese" to "IN", "Odia" to "IN", "Bhojpuri" to "IN", "English" to "GB",
        "Arabic" to "SA", "Russian" to "RU", "Chinese" to "CN", "Japanese" to "JP",
        "Korean" to "KR", "French" to "FR", "Spanish" to "ES", "Portuguese" to "PT",
        "German" to "DE", "Italian" to "IT", "Dutch" to "NL", "Polish" to "PL",
        "Turkish" to "TR", "Thai" to "TH", "Vietnamese" to "VN", "Indonesian" to "ID",
        "Malay" to "MY", "Filipino" to "PH", "Swedish" to "SE", "Norwegian" to "NO",
        "Danish" to "DK", "Finnish" to "FI", "Czech" to "CZ", "Slovak" to "SK",
        "Hungarian" to "HU", "Romanian" to "RO", "Bulgarian" to "BG", "Greek" to "GR",
        "Hebrew" to "IL", "Persian" to "IR", "Ukrainian" to "UA", "Serbian" to "RS",
        "Croatian" to "HR", "Albanian" to "AL", "Georgian" to "GE", "Armenian" to "AM",
        "Kazakh" to "KZ", "Swahili" to "KE", "Afrikaans" to "ZA", "Amharic" to "ET",
        "Pashto" to "AF", "Kurdish" to "IQ", "Latin" to "VA", "Catalan" to "AD",
        "Basque" to "ES", "Galician" to "ES", "Belarusian" to "BY", "Macedonian" to "MK",
    )

    private fun flag(country: String): String =
        country.uppercase().map { Character.toChars(0x1F1E6 + (it - 'A')).concatToString() }.joinToString("")

    fun languageFlag(language: String?): String {
        val name = language?.trim().orEmpty()
        val canonical = if (name in COUNTRY) name else labelOf(name)
        return COUNTRY[canonical]?.let(::flag).orEmpty()
    }

    private val RESOLUTION = Regex("""\b\d{3,4}\s*p(?:\d{2,3})?\b|\b[24]\s*k\b|\b(?:FHD|UHD|QHD)\b|\bHD\b""", RegexOption.IGNORE_CASE)
    private val SIZE_WORD = Regex("""\b\d+(?:\.\d+)?\s?(?:GB|MB)\b""", RegexOption.IGNORE_CASE)
    private val MULTI = Regex("""(?i)\b(?:multi|dual|both)(?:\s*-?\s*audio)?\b""")

    private fun stripQuality(text: String): String =
        RESOLUTION.replace(text, " ").replace(Regex("""\s+"""), " ").trim()

    private fun stripTrailingLanguage(text: String): String {
        var out = text.trim()
        val names = languages(out)
        for (name in names) {
            out = out.replace(Regex("""(?i)(?:\s*[(\[]?\s*${Regex.escape(name)}\s*[)\]]?\s*)$"""), " ").trim()
        }
        out = MULTI.replace(out, " ").trim()
        return out.replace(Regex("""\s+"""), " ").trim()
    }

    private fun baseName(serverName: String): String {
        var name = stripQuality(serverName)
        name = stripTrailingLanguage(name)
        return stripQuality(name).ifBlank { "Server" }
    }

    private fun displayLanguageNames(audioLabel: String?, mediaLabel: String?): List<String> {
        val audio = languages(audioLabel)
        val media = languages(mediaLabel)
        return when {
            audio.size >= 2 && audio.size >= media.size -> audio
            media.size > audio.size -> media
            audio.isNotEmpty() -> audio
            else -> media
        }
    }

    private fun ordered(names: List<String>, originalLanguage: String?): List<String> {
        val anchor = labelOf(originalLanguage).takeIf { it.isNotBlank() }
        val priority = listOfNotNull(anchor, "Hindi", "English")
        val out = LinkedHashSet<String>()
        priority.filter { it in names }.forEach { out += it }
        names.forEach { out += it }
        return out.toList()
    }

    private fun languageTagPart(
        audioLabel: String?,
        mediaLabel: String?,
        originalLanguage: String?,
    ): String {
        val names = ordered(displayLanguageNames(audioLabel, mediaLabel), originalLanguage)
        if (names.size > 3) return "(Multi)"
        if (names.isEmpty()) {
            val single = displayLabel(audioLabel, originalLanguage)
                .ifBlank { displayLabel(mediaLabel, originalLanguage) }
            return if (single.isBlank() || single == "Unknown") "(Unknown)" else "($single)"
        }
        val rendered = names.map { name ->
            languageFlag(name).takeIf { it.isNotBlank() }?.let { "$it $name" } ?: name
        }
        return "(" + rendered.joinToString(", ") + ")"
    }

    private val TOKEN_PATTERNS: List<Pair<Regex, String>> = listOf(
        Regex("""\bWEB[ \-]?DL\b""", RegexOption.IGNORE_CASE) to "WEB-DL",
        Regex("""\bWEB[ \-]?RIP\b""", RegexOption.IGNORE_CASE) to "WEBRip",
        Regex("""\bBLU[ \-]?RAY\b""", RegexOption.IGNORE_CASE) to "BluRay",
        Regex("""\bBR[ \-]?RIP\b""", RegexOption.IGNORE_CASE) to "BRRip",
        Regex("""\bHD[ \-]?RIP\b""", RegexOption.IGNORE_CASE) to "HDRip",
        Regex("""\bDVD[ \-]?RIP\b""", RegexOption.IGNORE_CASE) to "DVDRip",
        Regex("""\bHEVC\b|\bH\.?[ \-]?265\b|\bX\.?265\b""", RegexOption.IGNORE_CASE) to "HEVC",
        Regex("""\bH\.?[ \-]?264\b|\bX\.?264\b|\bAVC\b""", RegexOption.IGNORE_CASE) to "H.264",
        Regex("""\bAV1\b""", RegexOption.IGNORE_CASE) to "AV1",
        Regex("""\bDOLBY[ \-]?VISION\b|\bDV\b""", RegexOption.IGNORE_CASE) to "Dolby Vision",
        Regex("""\bHDR10\+(?!\d)""", RegexOption.IGNORE_CASE) to "HDR10+",
        Regex("""\bHDR10\b""", RegexOption.IGNORE_CASE) to "HDR10",
        Regex("""\bHDR\b""", RegexOption.IGNORE_CASE) to "HDR",
        Regex("""\b10[ \-]?BIT\b""", RegexOption.IGNORE_CASE) to "10bit",
        Regex("""\bDOLBY[ \-]?ATMOS\b|\bATMOS\b""", RegexOption.IGNORE_CASE) to "Dolby Atmos",
        Regex("""\bTRUEHD\b""", RegexOption.IGNORE_CASE) to "TrueHD",
        Regex("""\bDTS[ \-]?H[DI]\b""", RegexOption.IGNORE_CASE) to "DTS-HD",
        Regex("""\bDTS\b""", RegexOption.IGNORE_CASE) to "DTS",
        Regex("""\bAAC\b""", RegexOption.IGNORE_CASE) to "AAC",
    )

    fun renditionTokens(text: String?): List<String> {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return emptyList()
        val found = LinkedHashMap<String, String>()
        for ((regex, label) in TOKEN_PATTERNS) {
            if (regex.containsMatchIn(value)) found.putIfAbsent(label.lowercase(), label)
        }
        if (found.keys.any { it == "dolby vision" || it == "hdr10+" || it == "hdr10" }) {
            found.remove("hdr")
        }
        return found.values.toList()
    }

    /** CloudStream renders the quality itself; keep the name free of a duplicate quality token. */
    fun displayName(
        serverName: String,
        audioLabel: String = "",
        sizeLabel: String = "",
        mediaLabel: String = "",
        originalLanguage: String? = null,
    ): String {
        val base = baseName(serverName)
        val tokens = renditionTokens(listOf(mediaLabel, serverName).joinToString(" "))
            .filterNot { token -> base.contains(token, ignoreCase = true) }
            .joinToString(" ")
        return listOf(base, tokens, languageTagPart(audioLabel, mediaLabel, originalLanguage), sizeLabel.trim())
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    fun priorityName(
        serverName: String,
        audioLabel: String = "",
        mediaLabel: String = "",
        originalLanguage: String? = null,
    ): String = listOf(
        baseName(serverName),
        languageTagPart(audioLabel, mediaLabel, originalLanguage),
    ).filter { it.isNotBlank() }.joinToString(" ")

    fun richerLanguageLabel(primary: String?, fallback: String?): String {
        val first = languages(primary).size
        val second = languages(fallback).size
        return if (second > first) fallback.orEmpty().ifBlank { primary.orEmpty() }
        else primary.orEmpty().ifBlank { fallback.orEmpty() }
    }

    fun canonicalSubtitleName(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return "Subtitle"
        return languages(value).firstOrNull() ?: value
    }
}
