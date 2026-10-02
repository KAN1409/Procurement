package com.edib.procurement.followup

/**
 * One spreadsheet row after header mapping, reduced to logical fields plus the
 * raw cell text of any column we did not recognise.
 */
data class FollowUpRow(
    val excelRow: Int,
    val number: String? = null,
    val project: String? = null,
    val area: String? = null,
    val relatedTo: String? = null,
    val code: String? = null,
    val prPath: String? = null,
    val orderPath: String? = null,
    val company: String? = null,
    val description: String? = null,
    val prDate: String? = null,
    val orderDate: String? = null,
    val prWithEngineer: String? = null,
    val notes: String? = null,
    val financialReceivedDate: String? = null,
    val finalApprovalDate: String? = null,
    val checkValue: String? = null,
    val oldCompany: String? = null,
    /** Retained verbatim for columns the header mapper did not recognise. */
    val extraCells: Map<Int, String> = emptyMap()
)

/**
 * A group built by [RowGrouper]: the anchor row plus the continuation rows that
 * belong to it.
 */
data class FollowUpGroup(
    val anchorRow: FollowUpRow,
    val continuationRows: List<FollowUpRow>
) {
    val allRows: List<FollowUpRow> = listOf(anchorRow) + continuationRows

    /** The workbook row every entry in this group came from, in order. */
    val firstExcelRow: Int get() = anchorRow.excelRow

    val projectRaw: String? get() = anchorRow.project
    val area: String? get() = anchorRow.area
    val relatedTo: String? get() = anchorRow.relatedTo
    val number: String? get() = anchorRow.number
    val rootCodeRaw: String? get() = anchorRow.code
}

/**
 * Structural row grouping.
 *
 * The workbook interleaves anchor rows (a real `No.` and/or Project) with
 * continuation rows that only carry a Code, paths, contractor, dates or notes
 * and inherit the previous row's project context. Stripping A/B/C suffixes with
 * a regex would invent relationships that the workbook does not state, so
 * grouping is decided purely by which context columns are blank.
 *
 * A row is an ANCHOR when it carries identifying context:
 *   - a meaningful `No.`, or
 *   - an explicit Project.
 *
 * A row is a CONTINUATION when every identifying context column (No., Project,
 * Area, Related to) is blank but the row still carries payload (Code, PR path,
 * Order path, contractor, description, any date, notes, check).
 *
 * A continuation row before any anchor is emitted as its own single-row group
 * rather than discarded, so no workbook content is ever lost.
 */
object RowGrouper {

    private fun s(v: String?): String = v?.trim().orEmpty()

    private fun isBlank(v: String?): Boolean = s(v).isEmpty()

    /**
     * Excel numeric cells render as "2441", "2441.0" or "2,441". A number is
     * meaningful; a placeholder such as "-" or "N/A" is not.
     */
    private fun isMeaningfulNumber(v: String?): Boolean {
        val t = s(v)
        if (t.isEmpty()) return false
        if (t.any { it.isDigit() }) return true
        // Non-numeric placeholder.
        return false
    }

    /** Does this row carry any payload worth importing? */
    fun hasPayload(row: FollowUpRow): Boolean {
        val fields = listOf(
            row.code, row.prPath, row.orderPath, row.company, row.description,
            row.prDate, row.orderDate, row.prWithEngineer, row.notes,
            row.financialReceivedDate, row.finalApprovalDate, row.checkValue,
            row.oldCompany
        )
        if (fields.any { !isBlank(it) }) return true
        return row.extraCells.values.any { !isBlank(it) }
    }

    fun isAnchor(row: FollowUpRow): Boolean {
        if (isMeaningfulNumber(row.number)) return true
        if (!isBlank(row.project)) return true
        return false
    }

    fun isContinuation(row: FollowUpRow): Boolean =
        !isAnchor(row) && hasPayload(row)

    /**
     * @param rows data rows in workbook order, already header-mapped and with
     *   fully blank rows removed.
     */
    fun group(rows: List<FollowUpRow>): List<FollowUpGroup> {
        val groups = ArrayList<FollowUpGroup>()
        var currentAnchor: FollowUpRow? = null
        var currentContinuation = ArrayList<FollowUpRow>()

        fun flush() {
            val anchor = currentAnchor
            if (anchor != null) {
                groups.add(FollowUpGroup(anchor, currentContinuation.toList()))
            }
            currentAnchor = null
            currentContinuation = ArrayList()
        }

        for (row in rows) {
            when {
                isAnchor(row) -> {
                    flush()
                    currentAnchor = row
                }
                // A continuation with no anchor yet still becomes a group of its
                // own; it is kept rather than dropped.
                currentAnchor == null -> {
                    groups.add(FollowUpGroup(row, emptyList()))
                }
                hasPayload(row) -> currentContinuation.add(row)
                // Completely blank row: nothing to attach, ignore it.
                else -> Unit
            }
        }
        flush()
        return groups
    }
}
