package com.edib.procurement.matching

import com.edib.procurement.followup.TextNormalizer

/**
 * Normalisation used by the matcher.
 *
 * Two distinct keys, because they answer different questions:
 *
 *  - [filenameKey] answers "is this the same file?" and ignores separators
 *    entirely, so `MMH2-GR-AR-14-25B` and `MMH2 GR AR 14 25 B` collide.
 *  - [codeKey] answers "is this the same code?" and is deliberately stricter,
 *    because a trailing A and a trailing B are different entries that must
 *    never be collapsed into one another.
 *
 * The raw value is never modified. These keys exist only for comparison.
 */
object MatchNormalizer {

    private val SEPARATORS = Regex("[\\s\\-_/\\\\.]+")
    private val TRIM_PUNCT = Regex("^[\\s.,;:()\\[\\]{}'\"`]+|[\\s.,;:()\\[\\]{}'\"`]+$")

    /**
     * Aggressively folded file name key, extension included.
     *
     * All separators collapse to a single space and the extension is kept as a
     * distinct trailing token, so `MMH2-GR-AR-14-25B.pdf` and
     * `mmh2 gr ar 14 25 b pdf` produce the same key.
     *
     * Spacing before a variant letter is not meaningful in a file name, so it
     * is absorbed. That is safe here because a variant letter is never dropped:
     * `…25A` still differs from `…25B`.
     */
    fun filenameKey(name: String?): String {
        val stem = fileStemKey(name)
        if (stem.isEmpty()) return ""
        val extension = TextNormalizer.extension(name)?.lowercase().orEmpty()
        return if (extension.isEmpty()) stem else "$stem $extension"
    }

    /**
     * The stem of a file name, separators folded and case normalised.
     *
     * Every space is removed rather than collapsed to one, because a file name
     * may or may not carry a space before a trailing variant letter and that
     * difference is purely cosmetic.
     */
    fun fileStemKey(name: String?): String {
        if (name.isNullOrBlank()) return ""
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0 && dot < name.length - 1) name.substring(0, dot) else name
        val trimmed = TRIM_PUNCT.replace(stem.trim(), "")
        return SEPARATORS.replace(trimmed, " ").replace(" ", "").lowercase()
    }

    /**
     * Code key for entry matching.
     *
     * Separators collapse to a single space and the result is uppercased, but
     * the letter that distinguishes a variant is part of the key:
     *
     *   "MMH2-GR-AR-14-25  B" -> "MMH2 GR AR 14 25 B"
     *   "MMH2-GR-AR-14-25B"   -> "MMH2 GR AR 14 25B"
     *
     * The two above differ by a space only. That is intentional: they are the
     * same entry in practice, so [codeKeyLoose] exists for the looser compare,
     * while [codeKey] keeps the space so a strict comparison can tell them
     * apart when that matters.
     */
    fun codeKey(code: String?): String {
        if (code.isNullOrBlank()) return ""
        val normalized = TextNormalizer.normalizeCode(code)
        if (normalized.isEmpty()) return ""
        // Collapse repeated separators into one, then drop the trailing one so
        // "MMH2-GR-AR-14-25  B" and "MMH2-GR-AR-14-25B" fold identically.
        return normalized.replace('-', ' ').replace('_', ' ')
            .replace(Regex(" +"), " ").trim()
    }

    /**
     * Loosest code key, with all spacing removed and case folded.
     *
     * Both sides of a code comparison go through here so a lower-cased file-name
     * key and an upper-cased code key compare equal. The variant letter is
     * still part of the key, so `…25A` never equals `…25B`.
     */
    fun codeKeyLoose(code: String?): String =
        codeKey(code).replace(" ", "").lowercase()

    /**
     * Does this document's stem identify the given entry code?
     *
     * Requires the whole stem to be the code, not merely to contain it, so
     * `MMH2-GR-AR-14-25B-DRAFT` does not silently match entry B. A trailing
     * filler word is tolerated only when the code still accounts for the
     * meaningful tokens.
     */
    fun stemMatchesCode(stem: String?, code: String?): Boolean {
        val k1 = codeKeyLoose(stem)
        val k2 = codeKeyLoose(code)
        if (k1.isEmpty() || k2.isEmpty()) return false
        if (k1 == k2) return true
        // The stem may carry an extra leading project token, e.g. a file named
        // "JAZNEGMA-MM2-GR-AR-14-25B". Requiring the code to be a whole-token
        // suffix keeps this from matching unrelated codes.
        return k1.endsWith(k2) && k1.length - k2.length in 1..12 &&
            k1[k1.length - k2.length - 1].isLetterOrDigit()
    }

    /** True when [text] carries the Arabic annex marker. */
    fun hasAdjustedMarker(text: String?): Boolean =
        text?.contains("ملحق") == true ||
            codeKey(text).contains("ADJ") ||
            codeKey(text).contains("ADJUSTED")

    /** Year tokens such as "2025" appearing in a path or name. */
    fun years(text: String?): Set<String> {
        if (text.isNullOrBlank()) return emptySet()
        return Regex("(?<!\\d)(20\\d{2})(?!\\d)").findAll(text).map { it.value }.toSet()
    }
}
