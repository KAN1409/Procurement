package com.edib.procurement.aipack

/**
 * How one logical export is divided into parts.
 *
 * Splitting is a PLANNING problem: the generator never cuts bytes mid-stream.
 * Every document SOURCE block, and every Follow-up entry, is an indivisible unit
 * that lands in exactly one part. This file only decides which part each unit
 * belongs to; the builder then writes each part on its own.
 *
 * Determinism is the point. The same scope and the same threshold must always
 * produce the same part count and the same assignment, so an export can be
 * reproduced and compared.
 */
object PackSplitter {

    /** Below this many parts, two digits are enough; the width grows past 99. */
    fun numberWidth(partCount: Int): Int =
        if (partCount > 99) 3 else 2

    fun partLabel(partNumber: Int, width: Int): String =
        "PART %0${width}d".format(partNumber)

    fun partFileStem(baseStem: String, partNumber: Int, width: Int): String =
        "${baseStem}_%0${width}d".format(partNumber)

    /**
     * A unit that must not be split across parts.
     *
     * [bytes] is the unit's estimated normalized size. For a document that is
     * the extracted text; for entry/group metadata it is a small fixed estimate.
     */
    data class Unit(
        val key: String,
        val kind: Kind,
        val bytes: Long,
        /** Group this unit belongs to, for continuity headers and the index. */
        val groupKey: String? = null,
        val groupLabel: String? = null,
        /** Entry this unit belongs to, when the unit is entry metadata. */
        val entryCode: String? = null,
        /** SOURCE id, when the unit is a document. Assigned globally, once. */
        val sourceId: String? = null,
        val documentId: Long? = null,
        val filename: String? = null
    ) {
        enum class Kind { GROUP_HEADER, ENTRY, SOURCE }
    }

    /** One planned part. */
    data class Part(
        val number: Int,
        val units: List<Unit>,
        val estimatedBytes: Long,
        /** True when this part exists only because one unit exceeds the target. */
        val isOversized: Boolean = false
    ) {
        val groups: List<String> get() = units.mapNotNull { it.groupLabel }.distinct()
        val sources: List<Unit> get() = units.filter { it.kind == Unit.Kind.SOURCE }
        val entries: List<Unit> get() = units.filter { it.kind == Unit.Kind.ENTRY }
        val oversizedReason: String?
            get() = if (isOversized) SINGLE_SOURCE_EXCEEDS_TARGET else null
    }

    const val SINGLE_SOURCE_EXCEEDS_TARGET = "SINGLE_SOURCE_EXCEEDS_TARGET"

    /**
     * Greedy bin packing over logical units, preserving the input order.
     *
     * Order is the original Follow-up order (group header, its entries, then its
     * document sources). A unit is appended to the current part while it fits;
     * the first unit that would overflow starts the next part. A single unit
     * larger than the target is never truncated: it becomes an oversized part of
     * its own and is flagged, so the UI can warn instead of silently losing data.
     *
     * A group is kept together when practical: once a part is started for a new
     * group, the remaining units of the previous group are flushed first. If one
     * group on its own is bigger than the target, its units simply continue into
     * following parts, which the continuity headers make explicit.
     */
    fun plan(units: List<Unit>, targetPartBytes: Long): List<Part> {
        require(targetPartBytes > 0) { "target part size must be positive" }
        if (units.isEmpty()) return emptyList()

        val parts = ArrayList<Part>()
        var current = ArrayList<Unit>()
        var currentBytes = 0L
        var currentGroup: String? = null

        fun flush() {
            if (current.isNotEmpty()) {
                parts.add(Part(parts.size + 1, current.toList(), currentBytes))
                current = ArrayList()
                currentBytes = 0L
            }
        }

        for (u in units) {
            val startsNewGroup = u.groupKey != null && u.groupKey != currentGroup

            // Keep a group whole when practical, but only if closing the current
            // part would actually not fit anyway. Flushing purely because the
            // group id changed would split a scope that comfortably fits in one
            // part, which is worse than a little interleaving.
            val wouldOverflow = current.isNotEmpty() && currentBytes + u.bytes > targetPartBytes
            if (startsNewGroup && wouldOverflow) flush()

            // A unit bigger than the whole target gets its own part rather than
            // being truncated or split.
            if (u.bytes > targetPartBytes && u.kind == Unit.Kind.SOURCE) {
                flush()
                current.add(u)
                currentBytes = u.bytes
                parts.add(Part(parts.size + 1, current.toList(), currentBytes, isOversized = true))
                current = ArrayList()
                currentBytes = 0L
                currentGroup = u.groupKey
                continue
            }

            if (current.isNotEmpty() && currentBytes + u.bytes > targetPartBytes) flush()

            current.add(u)
            currentBytes += u.bytes
            currentGroup = u.groupKey ?: currentGroup
        }
        flush()
        return parts
    }

    /**
     * Assigns global SOURCE ids across the whole export.
     *
     * Numbering runs once over every document in export order, so a document
     * keeps the same SOURCE id in the index and in whichever part holds it. It is
     * deliberately NOT restarted per part.
     */
    fun assignSourceIds(documentsInOrder: List<Long>): Map<Long, String> {
        val out = LinkedHashMap<Long, String>(documentsInOrder.size)
        documentsInOrder.forEachIndexed { i, id -> out[id] = "SOURCE-%04d".format(i + 1) }
        return out
    }
}
