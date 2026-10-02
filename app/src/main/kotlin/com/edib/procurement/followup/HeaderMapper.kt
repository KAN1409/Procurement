package com.edib.procurement.followup

/**
 * Logical fields the importer understands. The workbook's physical column
 * letters are deliberately absent: nothing here may assume B == No.
 */
enum class FollowUpField {
    NUMBER,
    PROJECT,
    AREA,
    RELATED_TO,
    CODE,
    PR_PATH,
    ORDER_PATH,
    COMPANY,
    DESCRIPTION,
    PR_DATE,
    ORDER_DATE,
    PR_WITH_ENGINEER,
    NOTES,
    FINANCIAL_RECEIVED_DATE,
    FINAL_APPROVAL_DATE,
    CHECK,
    OLD_COMPANY,
    /** Nothing recognised; kept so a future column is not silently dropped. */
    UNKNOWN
}

/**
 * A column position in a specific sheet, plus whether it held a header label.
 *
 * [headerLabel] is the raw header text exactly as written in the workbook,
 * because the import report needs to quote what it actually saw.
 */
data class ColumnRef(
    val column: Int,
    val headerLabel: String,
    val normalizedHeader: String,
    val field: FollowUpField
)

/**
 * Header row -> logical field mapping.
 *
 * Matching is by normalised header text with a small alias table, so
 * "Project " / "PROJECT" / "Project" all resolve to PROJECT. Columns whose
 * header is not recognised are retained as UNKNOWN rather than dropped, which
 * keeps a newer workbook's extra columns available for future features.
 *
 * When two columns claim the same field the first one (left to right) wins and
 * the loser is retained as UNKNOWN, so no data is lost.
 */
object HeaderMapper {

    /** Longest-alias-wins matching, so "order description" beats "description". */
    private val ALIASES: Map<FollowUpField, List<String>> = mapOf(
        FollowUpField.NUMBER to listOf("no", "no.", "num", "number", "sr", "s/n", "seq", "index"),
        FollowUpField.PROJECT to listOf("project", "projects", "project name", "the project"),
        FollowUpField.AREA to listOf("area", "areas", "region", "location", "site"),
        FollowUpField.RELATED_TO to listOf(
            "related to", "related to ..", "related to...", "relatedto",
            "responsible", "responsible engineer", "engineer", "follow up by"
        ),
        FollowUpField.CODE to listOf("code", "pr code", "order code", "pr/order code", "item code"),
        FollowUpField.PR_PATH to listOf(
            "hyper link for pr", "hyperlink for pr", "hyperlink for pr ",
            "pr", "pr link", "pr file", "pr path", "purchase requisition", "link for pr"
        ),
        FollowUpField.ORDER_PATH to listOf(
            "hyper link for order", "hyperlink for order", "hyper link for order",
            "order", "po", "po link", "order link", "order file", "order path",
            "purchase order", "link for order"
        ),
        FollowUpField.COMPANY to listOf(
            "new company", "company", "contractor", "supplier", "vendor",
            "new company name", "current company"
        ),
        FollowUpField.DESCRIPTION to listOf(
            "order description", "description", "item description", "desc",
            "work description", "order description "
        ),
        FollowUpField.PR_DATE to listOf("pr date", "prdate", "date of pr", "requisition date"),
        FollowUpField.ORDER_DATE to listOf("order date", "orderdate", "po date", "date of order"),
        FollowUpField.PR_WITH_ENGINEER to listOf(
            "pr with eng", "pr with eng.", "pr with engineer", "pr with eng "
        ),
        FollowUpField.NOTES to listOf("notes", "note", "remarks", "comment", "comments"),
        FollowUpField.FINANCIAL_RECEIVED_DATE to listOf(
            "financial received date", "financial received", "finance received date"
        ),
        FollowUpField.FINAL_APPROVAL_DATE to listOf(
            "final approval date", "final approval", "approval date"
        ),
        FollowUpField.CHECK to listOf("check", "check ", "status", "check status"),
        FollowUpField.OLD_COMPANY to listOf(
            "old company till (14/may/2023)", "old company till", "old company",
            "old company ", "previous company"
        )
    )

    /** Alias -> field, plus the resolved alias length so longer aliases win. */
    private val LOOKUP: Map<String, FollowUpField> = buildMap {
        for ((field, aliases) in ALIASES) {
            for (alias in aliases) put(TextNormalizer.normalize(alias), field)
        }
    }

    /**
     * Builds the mapping for one sheet.
     *
     * @param headerRowCells column index (0-based) -> raw header text
     */
    fun map(headerRowCells: Map<Int, String>): HeaderMap {
        val byColumn = headerRowCells.entries
            .sortedBy { it.key }
            .associate { (idx, raw) ->
                val normalized = TextNormalizer.normalize(raw)
                val field = resolveField(normalized)
                Triple(idx, raw, normalized) to field
            }

        // First column (left to right) that claims a field keeps it.
        val taken = HashSet<FollowUpField>()
        val columns = byColumn.map { (triple, field) ->
            val (idx, raw, normalized) = triple
            val effective = if (field == FollowUpField.UNKNOWN || !taken.add(field)) {
                FollowUpField.UNKNOWN
            } else {
                field
            }
            ColumnRef(idx, raw, normalized, effective)
        }

        // A sheet with no recognisable header at all is still imported rather than
        // rejected; the caller is warned through [HeaderMap.usedFallback].
        return HeaderMap(
            columns = columns,
            usedFallback = columns.none { it.field != FollowUpField.UNKNOWN }
        )
    }

    /**
     * Resolves one header. Exact alias match first; then a containment test so a
     * workbook that adds a qualifier ("NEW Company (2026)") still maps. Contains
     * "pr" is deliberately excluded as a token because it would match "pr date",
     * "pr with eng." and every hyper link column.
     */
    fun resolveField(normalizedHeader: String): FollowUpField {
        if (normalizedHeader.isEmpty()) return FollowUpField.UNKNOWN
        LOOKUP[normalizedHeader]?.let { return it }

        // Longest alias that appears as a whole-token prefix of the header.
        val tokens = tokenize(normalizedHeader)
        if (tokens.isEmpty()) return FollowUpField.UNKNOWN
        var best: FollowUpField? = null
        var bestLen = 0
        for ((field, aliases) in ALIASES) {
            for (alias in aliases) {
                val aliasTokens = tokenize(alias)
                if (aliasTokens.isEmpty() || aliasTokens.size > tokens.size) continue
                if (aliasTokens.indices.all { aliasTokens[it] == tokens[it] }) {
                    if (aliasTokens.size > bestLen) {
                        best = field
                        bestLen = aliasTokens.size
                    }
                }
            }
        }
        return best ?: FollowUpField.UNKNOWN
    }

    private fun tokenize(s: String): List<String> =
        s.split(' ', '-', '_', '(', ')', '/', '.', ':', ';', '\t')
            .filter { it.isNotEmpty() }

    /**
     * The mapping plus a flag recording whether any header was recognised.
     */
    data class HeaderMap(
        val columns: List<ColumnRef>,
        val usedFallback: Boolean
    ) {
        private val byField: Map<FollowUpField, ColumnRef> =
            columns.filter { it.field != FollowUpField.UNKNOWN }.associateBy { it.field }

        private val byIndex: Map<Int, ColumnRef> = columns.associateBy { it.column }

        fun column(field: FollowUpField): Int? = byField[field]?.column

        fun ref(field: FollowUpField): ColumnRef? = byField[field]

        fun refAt(index: Int): ColumnRef? = byIndex[index]

        val unknownColumns: List<ColumnRef>
            get() = columns.filter { it.field == FollowUpField.UNKNOWN }

        val recognizedFields: Set<FollowUpField>
            get() = byField.keys
    }
}
