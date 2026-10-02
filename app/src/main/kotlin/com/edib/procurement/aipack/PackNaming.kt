package com.edib.procurement.aipack

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Filesystem-safe names for generated packs.
 *
 * A scope name is whatever the user calls a project, and that can be Arabic, a
 * long contractor name, or a path-like string. The file name is therefore
 * derived rather than taken literally: separators, reserved characters and
 * anything outside a conservative ASCII set are folded to underscores, and the
 * length is capped well below the limit most filesystems impose.
 *
 * The untouched human-readable scope always stays in the manifest and in the
 * history row, so nothing is lost by sanitising the file name.
 */
object PackNaming {

    private const val MAX_STEM = 80

    /** Reserved on Windows and awkward everywhere; never emitted. */
    private val ILLEGAL = Regex("[^A-Za-z0-9._-]+")
    private val RESERVED = setOf(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"
    )

    /**
     * File stem for a pack, without the extension.
     *
     * `<scope>_AI_PACK_<yyyy-MM-dd>` for dated runs, with a time suffix appended
     * when a second pack is produced in the same day.
     */
    fun fileStem(scopeName: String, type: PackScopeType, stampDate: Date = Date()): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(stampDate)
        val time = SimpleDateFormat("HHmmss", Locale.US).format(stampDate)
        val cleaned = sanitise(scopeName)
        val kind = when (type) {
            PackScopeType.GROUP -> "GROUP"
            PackScopeType.ENTRIES -> "ENTRIES"
            PackScopeType.DOCUMENTS -> "DOCUMENTS"
            PackScopeType.PROJECT -> "PROJECT"
            PackScopeType.CONTRACTOR -> "CONTRACTOR"
        }
        // The stem stays short enough for any filesystem once the suffix is added.
        val room = (MAX_STEM - "_AI_PACK_$date-$kind".length).coerceAtLeast(8)
        val head = cleaned.take(room).trim('_', '.', '-').ifBlank { "SCOPE" }
        val safeKind = if (kind == "GROUP" && cleaned.isBlank()) "SCOPE" else kind
        return "${head}_AI_PACK_$date-$safeKind-$time".take(MAX_STEM)
    }

    /** Filesystem-safe form of [name], with no extension and no separators. */
    fun sanitise(name: String): String {
        if (name.isBlank()) return ""
        var out = ILLEGAL.replace(name.trim(), "_")
        // Collapse runs of underscores and trim the edges.
        out = out.replace(Regex("_+"), "_").trim('_', '.', '-')
        if (out.isEmpty()) return ""
        // A name that is only dots is not a usable file name.
        if (out.all { it == '.' }) return ""
        if (out.lowercase() in RESERVED) return "${out}_"
        return out.take(MAX_STEM)
    }

    /** Every generated pack's temporary name starts with a dot and ends here. */
    const val TEMP_SUFFIX = ".part"
}
