package com.edib.procurement.followup

/**
 * Text normalisation used for *searching and matching only*.
 *
 * Nothing here ever replaces the value that is displayed or stored as raw: a
 * code keeps `codeRaw` exactly as the workbook spelled it and gains
 * `codeNormalized` alongside it.
 */
object TextNormalizer {

    private val WHITESPACE = Regex("[\\s\\u00A0\\u2007\\u202F\\u200B\\u200C\\u200D\\uFEFF]+")
    private val HYPHEN_LIKE = Regex("[\\u2010\\u2011\\u2012\\u2013\\u2014\\u2015\\u2212\\uFF0D]")
    private val TRIVIAL_PUNCT = Regex("^[\\s\\.,;:\\-_/\\\\]+|[\\s\\.,;:\\-_/\\\\]+$")

    /**
     * Full Unicode folding: accent/hamza normalisation, visually equivalent
     * separators, collapsed whitespace, uppercased Latin.
     *
     * Arabic text is normalised for *comparison* only. The raw string is never
     * rewritten — Egyptian Arabic must not be turned into formal Arabic.
     */
    fun normalize(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        var s = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFKC)

        // NFKC folds some Arabic presentation forms; also fold the ones it keeps.
        s = s.replace(HYPHEN_LIKE, "-")

        // Arabic orthographic presentation forms -> base letters (comparison only).
        s = FOLDED_ARABIC.entries.fold(s) { acc, (k, v) -> acc.replace(k, v) }

        s = TRIVIAL_PUNCT.replace(s, "")
        s = WHITESPACE.replace(s, " ").trim()
        return if (s.isEmpty()) "" else s.uppercase()
    }

    /**
     * Code normalisation. Deliberately conservative: variant markers (A, B, C,
     * ADJ, Adjusted, ملحق) MUST survive, because they distinguish entries.
     *
     * "MMH2-GR-AR-14-25  B" normalises to the same value as
     * "MMH2-GR-AR-14-25 B", which is what makes a spaced code find its file.
     */
    fun normalizeCode(input: String?): String {
        if (input.isNullOrBlank()) return ""
        var s = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFKC)
        s = s.replace(HYPHEN_LIKE, "-")
        s = FOLDED_ARABIC.entries.fold(s) { acc, (k, v) -> acc.replace(k, v) }
        // Spaces around separators disappear; the separator itself is kept so
        // "14-25B" and "14 25B" still differ from "14-25 B".
        s = s.replace(Regex("\\s*-\\s*"), "-")
        s = s.replace(Regex("\\s*/\\s*"), "/")
        s = WHITESPACE.replace(s, " ").trim()
        return s.uppercase()
    }

    /**
     * Compact form for tolerant code search: "MMH2 GR AR 14 25" should reach
     * "MMH2-GR-AR-14-25A". Separators become single spaces so a query can be
     * compared token-wise. This is a search key, never a display value.
     */
    fun codeSearchKey(input: String?): String {
        val n = normalizeCode(input)
        if (n.isEmpty()) return ""
        return n.replace('-', ' ').replace('_', ' ').replace(WHITESPACE, " ").trim()
    }

    /** File name stem with the extension removed, normalised for comparison. */
    fun nameStem(displayName: String?): String {
        if (displayName.isNullOrBlank()) return ""
        val dot = displayName.lastIndexOf('.')
        val stem = if (dot > 0) displayName.substring(0, dot) else displayName
        return normalize(stem)
    }

    fun extension(displayName: String?): String? {
        if (displayName.isNullOrBlank()) return null
        val dot = displayName.lastIndexOf('.')
        if (dot <= 0 || dot == displayName.length - 1) return null
        return displayName.substring(dot + 1).lowercase()
    }

    /**
     * Filename normalisation for matching: the name and stem both folded so
     * "MMH2-GR-AR-14-25  B.PDF" and "mmh2 gr ar 14 25 b.pdf" collide.
     */
    fun normalizeFileName(displayName: String?): String = normalize(displayName)

    /**
     * A weaker key used when punctuation differs (duplicate hyphens, spaces for
     * separators). Keeps the extension out of the comparison.
     */
    fun looseFileKey(displayName: String?): String {
        val stem = nameStem(displayName)
        if (stem.isEmpty()) return ""
        return stem.replace('-', ' ').replace('_', ' ').replace(WHITESPACE, " ").trim()
    }

    private val FOLDED_ARABIC: Map<String, String> = buildMap {
        // Alef variants (أ إ آ ٱ) -> ا
        put("\u0623", "\u0627")
        put("\u0625", "\u0627")
        put("\u0622", "\u0627")
        put("\u0671", "\u0627")
        put("\u0672", "\u0627")
        put("\u0673", "\u0627")
        put("\u0675", "\u0627")
        // Ya variants (ى ي) -> ي ; Ta marbuta -> ه ; Teh marbuta -> ه
        put("\u0649", "\u064A")
        put("\u06CC", "\u064A")
        put("\u0629", "\u0647")
        put("\u0626", "\u064A")
        // Waw with hamza -> و
        put("\u0624", "\u0648")
        // Remove tatweel and harakat (presentation/orthography noise only)
        put("\u0640", "")
        for (c in listOf('\u064B', '\u064C', '\u064D', '\u064E', '\u064F',
            '\u0650', '\u0651', '\u0652', '\u0670')) {
            put(c.toString(), "")
        }
    }
}
