package com.edib.procurement.followup

import java.security.MessageDigest

/**
 * Derives the A / B / C / "L adj" / ملحق marker shown on each entry.
 *
 * This is a *display and ordering* helper. It is never used to decide grouping
 * or identity, because "MMH2-GR-AR-14-25B" and a hypothetical separate
 * "MMH2-GR-AR-14-25 B" are the same variant but the workbook may also contain
 * genuinely different roots with the same suffix.
 */
object EntryVariant {

    private val SUFFIX = Regex("^(.*?)[\\s\\-–—]*([A-Z]{1,3})\\s*$")

    /** Explicit adjusted markers, checked before the single-letter form. */
    private val ADJUSTED_MARKERS = listOf("ADJ", "ADJUSTED", "ADJ.")

    /**
     * Returns the variant marker for a code relative to its group root, or null
     * when the code has no distinguishable suffix.
     */
    fun derive(codeRaw: String?, rootCodeNormalized: String?): String? {
        val code = TextNormalizer.normalizeCode(codeRaw)
        if (code.isEmpty()) return null
        val root = TextNormalizer.normalizeCode(rootCodeNormalized)

        if (root.isNotEmpty() && code == root) return null
        if (root.isNotEmpty() && code.startsWith(root)) {
            val tail = code.removePrefix(root).trim()
            if (tail.isEmpty()) return null
            return tail.trim('-', ' ')
        }

        // The anchor code often carries its own letter ("…-25A") while the
        // continuation codes carry a spaced letter ("…-25 B", "…-25 L adj").
        // Compare against the root with its trailing letter stripped so the
        // whole marker survives: "B", "L", "L ADJ", "M".
        if (root.isNotEmpty()) {
            val bareRoot = stripTrailingLetter(root)
            if (bareRoot.isNotEmpty() && bareRoot != root && code.startsWith(bareRoot)) {
                val tail = code.removePrefix(bareRoot).trim().trim('-', ' ')
                if (tail.isNotEmpty()) return tail
            }
        }

        // No usable root: fall back to a trailing single/two/three letter token.
        val m = SUFFIX.find(code) ?: return null
        val tail = m.groupValues[2]
        val head = m.groupValues[1]
        // Only treat it as a variant when the head itself looks like a code.
        val denseHead = Regex("[^A-Z0-9]").replace(head, "")
        if (denseHead.length < 4) return null
        return tail
    }

    /** Removes a trailing single Latin letter from a code, if present. */
    private fun stripTrailingLetter(code: String): String {
        if (code.length < 2) return code
        val last = code.last()
        if (!last.isUpperCase()) return code
        return code.dropLast(1).trimEnd('-', ' ')
    }

    fun isAdjusted(variant: String?): Boolean {
        val v = TextNormalizer.normalize(variant ?: return false)
        if (v.isEmpty()) return false
        // The marker may lead ("ADJ") or follow a base letter ("L ADJ").
        if (ADJUSTED_MARKERS.any { marker -> v.split(' ', '-').contains(marker) }) return true
        // Arabic "ملحق" means "annex/addendum".
        return variant!!.contains("ملحق")
    }

    /**
     * Natural ordering key for variants so A, B, ... K, L, "L adj", M sort the
     * way a person expects instead of alphabetically.
     */
    fun sortKey(variant: String?, codeRaw: String?): String {
        val v = TextNormalizer.normalize(variant ?: return "")
        if (v.isEmpty()) {
            // No variant: sort by the code itself so it lands in a sane place.
            return "1:" + TextNormalizer.normalizeCode(codeRaw)
        }
        val base = Regex("^([A-Z]+)").find(v)?.groupValues?.get(1) ?: ""
        val isSuffix = base.length == 1 && base[0].isLetter()
        if (!isSuffix) return "0:$v"
        val letter = base[0]
        val adj = if (isAdjusted(variant)) "1" else "0"
        return "0:$letter:$adj"
    }
}

/**
 * Stable identities.
 *
 * The Excel row number is NOT an identity — rows move when the workbook is
 * replaced. Everything here is derived from the content a follow-up record
 * actually has, so a newer workbook that shifts rows still reconciles onto the
 * same group and entry.
 *
 * Where genuinely different records share every identifying field (the
 * reference workbook has a handful: blank code, blank path, blank description,
 * no contractor), no content-only key can tell them apart. Those fall back to
 * the record's own number/row so they stay distinct instead of silently
 * merging into one another.
 */
object StableKeys {

    private fun sha(vararg parts: String?): String {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update((it ?: "").toByteArray(Charsets.UTF_8)); md.update(0) }
        return md.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * Group identity.
     *
     * Signals, strongest first: the anchor's code, then the PR/Order file the
     * anchor points at, then project + area + responsible + number. Every
     * component is content-derived.
     */
    fun groupKey(
        projectRaw: String?,
        area: String?,
        relatedTo: String?,
        number: String?,
        rootCodeRaw: String?,
        anchorPrPath: String?,
        anchorOrderPath: String?,
        /**
         * Auxiliary cell text (the workbook's unnamed columns) folded in.
         *
         * A few anchor rows share a project and a truncated path but differ
         * only in such a column ("B" versus "0008 - 0012"). Without it those
         * would be indistinguishable.
         */
        auxDiscriminator: String? = null
    ): String {
        val code = TextNormalizer.normalizeCode(rootCodeRaw)
        val prFile = PathHints.fileNameStem(anchorPrPath)
        val orderFile = PathHints.fileNameStem(anchorOrderPath)
        val base = listOf(
            TextNormalizer.normalize(projectRaw),
            TextNormalizer.normalize(area),
            TextNormalizer.normalize(relatedTo),
            number?.trim(),
            code
        )
        val core = "g:" + sha(*base.toTypedArray()) + ":" + sha(prFile, orderFile)

        // Distinct auxiliary text always disambiguates: it is workbook content
        // that a future revision will preserve.
        val aux = TextNormalizer.normalize(auxDiscriminator)
        if (aux.isNotEmpty()) return "$core:a" + sha(aux)

        // A group with no code, no file and no auxiliary text is only separable
        // by its own No.
        if (code.isEmpty() && prFile.isEmpty() && orderFile.isEmpty()) {
            val extra = sha(number?.trim())
            if (extra.isNotEmpty()) return "$core:n$extra"
        }
        return core
    }

    /**
     * Entry identity.
     *
     * Uses the code plus the entry's own historical files, which is what
     * distinguishes "same code, different historical file" (a real case in this
     * workbook). Falls back to group + row signature when the code is blank so
     * the entry is still reconcilable.
     */
    fun entryKey(
        groupStableKey: String,
        codeRaw: String?,
        prPath: String?,
        orderPath: String?,
        companyRaw: String?,
        description: String?,
        prDate: String?,
        orderDate: String?,
        /** Position within the group, used only to separate identical rows. */
        ordinal: Int = 0
    ): String {
        val code = TextNormalizer.normalizeCode(codeRaw)

        // The WHOLE historical path is part of the identity, not just the file
        // stem: this workbook's path cells are truncated for display, so two
        // different orders can share a stem and differ only in the tail
        // (".../GP-BR-AR-0006-22 PR.pdf" vs ".../GP-BR-AR-0006-22 PR...pd").
        val prFull = TextNormalizer.normalize(prPath)
        val orderFull = TextNormalizer.normalize(orderPath)

        if (code.isNotEmpty() || prFull.isNotEmpty() || orderFull.isNotEmpty()) {
            return "e:" + sha(
                groupStableKey, code, prFull, orderFull
            )
        }

        // Nothing but free text: identify by content.
        val content = "e:" + sha(
            groupStableKey,
            TextNormalizer.normalize(companyRaw),
            TextNormalizer.normalize(description),
            prDate?.trim(), orderDate?.trim()
        )
        // A few consecutive rows in the reference workbook are identical in
        // every field. They are genuinely separate follow-ups, so the position
        // within the group is the only thing that can separate them; without it
        // they would collapse into one entry.
        return if (content.isEmpty()) content else "$content#$ordinal"
    }

    /** Fingerprint of the recognised header layout, to spot a schema change. */
    fun schemaFingerprint(map: HeaderMapper.HeaderMap, sheetNames: List<String>): String {
        val cols = map.columns
            .filter { it.field != FollowUpField.UNKNOWN }
            .sortedBy { it.field.ordinal }
            .joinToString(",") { "${it.field.name}:${it.normalizedHeader}" }
        return sha(cols, sheetNames.sorted().joinToString("|"))
    }

    /** Fingerprint of the whole imported content, to spot a data revision. */
    fun workbookFingerprint(groups: List<FollowUpGroup>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0
        for (g in groups) {
            for (r in g.allRows) {
                count++
                digest.update(
                    listOf(
                        r.number, r.code, r.company, r.description, r.orderDate
                    ).joinToString("\u0001").toByteArray(Charsets.UTF_8)
                )
            }
        }
        return count.toString() + "-" + digest.digest().take(10).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Extracts the matchable parts of a historical Windows path.
 *
 * These paths come from an engineer's PC and are never valid on Android. Only
 * the basename, extension and trailing segments are meaningful for finding the
 * equivalent real file.
 */
object PathHints {

    /** Last path segment with the extension removed, normalised. */
    fun fileNameStem(path: String?): String {
        val base = fileName(path)
        if (base.isEmpty()) return ""
        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0) base.substring(0, dot) else base
        return TextNormalizer.normalize(stem)
    }

    fun fileName(path: String?): String {
        val p = path?.trim() ?: return ""
        if (p.isEmpty()) return ""
        val last = p.replace('\\', '/').substringAfterLast('/')
        return last.trim()
    }

    fun fileNameNormalized(path: String?): String = TextNormalizer.normalize(fileName(path))

    fun extension(path: String?): String? = TextNormalizer.extension(fileName(path))

    /** All path segments, normalised, excluding the file name itself. */
    fun directorySegments(path: String?): List<String> {
        val p = path?.trim() ?: return emptyList()
        if (p.isEmpty()) return emptyList()
        val parts = p.replace('\\', '/').split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.size <= 1) return emptyList()
        return parts.dropLast(1).map { TextNormalizer.normalize(it) }.filter { it.isNotEmpty() }
    }

    /** Last N directory segments — the most specific part of a path. */
    fun lastSegments(path: String?, n: Int): List<String> {
        val segs = directorySegments(path)
        return if (segs.size <= n) segs else segs.takeLast(n)
    }

    /** Year hints such as "2025" in a folder or file name. */
    fun yearHints(path: String?): Set<String> {
        val p = path ?: return emptySet()
        return Regex("(?<!\\d)(20\\d{2})(?!\\d)").findAll(p).map { it.groupValues[1] }.toSet()
    }

    /**
     * Is this cell a path at all? The workbook sometimes puts prose in the
     * hyperlink columns ("تم ارجاعه و الغاؤه...") and sometimes the literal text
     * "مش تبعنا" ("not with us"). Those are notes, not paths.
     */
    fun looksLikePath(value: String?): Boolean {
        val v = value?.trim() ?: return false
        if (v.isEmpty()) return false
        if (v.contains('\\') || v.contains('/')) return true
        if (v.contains(':') && Regex("[A-Za-z]:").containsMatchIn(v)) return true
        if (Regex("(?i)\\.(pdf|docx?|xlsx?|csv|txt)$").containsMatchIn(v)) return true
        return false
    }
}
