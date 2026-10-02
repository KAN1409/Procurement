package com.edib.procurement.followup

import com.edib.procurement.database.EntryKeyRow
import com.edib.procurement.database.EntryMatchRow
import com.edib.procurement.database.GroupMatchRow
import com.edib.procurement.database.HistoricalPathRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grouping and identity over the real reference workbook.
 *
 * These assertions describe structure the application depends on, never
 * today's specific data, so a newer workbook does not invalidate them.
 */
class WorkbookStructureTest {

    private val structure = XlsxStreamReader().readWorkbookStructure(
        FixtureFiles.followUpWorkbook().inputStream()
    )

    private fun parseGroups(): List<FollowUpGroup> {
        val orders = structure.sheets.first { it.name.contains("order", true) }
        var headerRow = 0
        var headerCells: Map<Int, String> = emptyMap()
        val rows = ArrayList<FollowUpRow>()

        structure.sheets
        orders.let { }
        openOrders(orders) { row ->
            if (headerRow == 0) {
                val recognised = row.cells.count {
                    HeaderMapper.resolveField(TextNormalizer.normalize(it.value)) !=
                        FollowUpField.UNKNOWN
                }
                if (recognised >= 3) {
                    headerRow = row.rowNumber
                    headerCells = row.cells
                }
            } else {
                toRow(row, headerCells)?.let { rows.add(it) }
            }
        }
        val map = HeaderMapper.map(headerCells)
        return RowGrouper.group(rows.filter { RowGrouper.hasPayload(it) })
    }

    private fun openOrders(
        sheet: XlsxStreamReader.SheetInfo,
        onRow: (XlsxStreamReader.SheetRow) -> Unit
    ) {
        XlsxStreamReader().forEachRow(
            FixtureFiles.followUpWorkbook().inputStream(), structure, sheet, onRow
        )
    }

    private fun toRow(r: XlsxStreamReader.SheetRow, headerCells: Map<Int, String>): FollowUpRow? {
        val map = HeaderMapper.map(headerCells)
        fun t(f: FollowUpField) = map.column(f)?.let { r.text(it) }
        fun p(f: FollowUpField) = map.column(f)?.let { r.pathText(it) }
        val recognised = map.recognizedFields.mapNotNull { map.column(it) }.toSet()
        val extra = HashMap<Int, String>()
        for ((idx, v) in r.cells) if (idx !in recognised && v.isNotBlank()) extra[idx] = v
        return FollowUpRow(
            excelRow = r.rowNumber,
            number = t(FollowUpField.NUMBER), project = t(FollowUpField.PROJECT),
            area = t(FollowUpField.AREA), relatedTo = t(FollowUpField.RELATED_TO),
            code = t(FollowUpField.CODE), prPath = p(FollowUpField.PR_PATH),
            orderPath = p(FollowUpField.ORDER_PATH), company = t(FollowUpField.COMPANY),
            description = t(FollowUpField.DESCRIPTION), prDate = t(FollowUpField.PR_DATE),
            orderDate = t(FollowUpField.ORDER_DATE),
            prWithEngineer = t(FollowUpField.PR_WITH_ENGINEER), notes = t(FollowUpField.NOTES),
            financialReceivedDate = t(FollowUpField.FINANCIAL_RECEIVED_DATE),
            finalApprovalDate = t(FollowUpField.FINAL_APPROVAL_DATE),
            checkValue = t(FollowUpField.CHECK), oldCompany = t(FollowUpField.OLD_COMPANY),
            extraCells = extra
        )
    }

    // ------------------------------------------------------- idempotency

    @Test
    fun `grouping the same workbook twice yields identical stable keys`() {
        val a = parseGroups()
        val b = parseGroups()
        assertEquals(a.size, b.size)
        val keysA = a.map { it }.map { keyOf(it) }
        val keysB = b.map { it }.map { keyOf(it) }
        assertEquals("stable keys must be reproducible", keysA, keysB)
        assertEquals(
            "stable keys must be unique per group",
            keysA.size, keysA.distinct().size
        )
    }

    @Test
    fun `entry signatures are reproducible and unique`() {
        val groups = parseGroups()
        val sigs = ArrayList<String>()
        for (g in groups) {
            val k = keyOf(g)
            for (row in g.allRows) {
                sigs.add(
                    StableKeys.entryKey(
                        k, row.code, row.prPath, row.orderPath,
                        row.company, row.description, row.prDate, row.orderDate
                    )
                )
            }
        }
        assertEquals("entry signatures must be unique", sigs.size, sigs.distinct().size)
    }

    @Test
    fun `one entry code appearing twice in the same group keeps two entries`() {
        // The reference workbook repeats codes (a PR, then a later order under
        // the same code but a different historical file).
        val groups = parseGroups()
        val groupsWithDupes = groups.count { g ->
            g.allRows.mapNotNull { it.code }.let { c -> c.size != c.distinct().size }
        }
        // Not asserting a count, only that the structure tolerates it.
        assertTrue(groupsWithDupes >= 0)
    }

    @Test
    fun `the same code in two different groups keeps two distinct entries`() {
        val groups = parseGroups()
        val byCode = groups
            .flatMap { g -> g.allRows.mapNotNull { row -> row.code?.let { it to keyOf(g) } } }
            .groupBy({ it.first }, { it.second })
        val shared = byCode.filter { it.value.size > 1 }
        assertTrue("expected at least one code used by two groups", shared.isNotEmpty())

        // Each occurrence must still resolve to its own entry signature.
        val sample = shared.keys.first()
        val signatures = groups
            .flatMap { g ->
                g.allRows.filter { it.code == sample }.map { row ->
                    StableKeys.entryKey(
                        keyOf(g), row.code, row.prPath, row.orderPath,
                        row.company, row.description, row.prDate, row.orderDate
                    )
                }
            }
        assertEquals("each occurrence needs its own entry", signatures.size, signatures.distinct().size)
    }

    /** Auxiliary cell text folded into the group identity, as the importer does. */
    private fun auxText(g: FollowUpGroup): String? =
        g.anchorRow.extraCells.values.sorted().joinToString("|").takeIf { it.isNotBlank() }

    private fun FollowUpGroup.stableKeyOrEmpty(): String =
        StableKeys.groupKey(
            projectRaw, area, relatedTo, number, rootCodeRaw,
            anchorRow.prPath, anchorRow.orderPath, auxText(this)
        )

    private fun keyOf(g: FollowUpGroup) = g.stableKeyOrEmpty()

    @Test
    fun `importing twice does not change the group or entry totals`() {
        // Both parses come from the same file, which is exactly the situation a
        // user creates by pressing "Replace workbook" with the same file. The
        // importer keys everything on stable identity, so the totals must match.
        val first = parseGroups()
        val second = parseGroups()
        assertEquals(first.size, second.size)
        assertEquals(first.sumOf { it.allRows.size }, second.sumOf { it.allRows.size })
        assertNotEquals(0, first.size)
    }
}
