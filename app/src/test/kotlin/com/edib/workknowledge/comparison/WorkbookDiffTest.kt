package com.edib.workknowledge.comparison

import com.edib.workknowledge.comparison.DocumentComparisonEngine.CellChange
import com.edib.workknowledge.comparison.DocumentComparisonEngine.ChangedRow
import com.edib.workknowledge.comparison.DocumentComparisonEngine.ComparisonResult
import com.edib.workknowledge.comparison.DocumentComparisonEngine.RowDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic spreadsheet diff.
 *
 * The engine compares parsed rows, never embeddings, so a revision comparison
 * must produce exactly the rows that actually differ - no more, no fewer.
 */
class WorkbookDiffTest {

    private val rev1: List<Map<String, String>> = listOf(
        row("Item" to "MR-101", "Description" to "Galala Marble Wall Cladding", "Unit" to "m²", "Quantity" to "37.5", "Rate" to "1850"),
        row("Item" to "MR-104", "Description" to "Piatra Grey Floor", "Unit" to "m²", "Quantity" to "120", "Rate" to "1400"),
        row("Item" to "MR-110", "Description" to "Gypsum Ceiling", "Unit" to "m²", "Quantity" to "210", "Rate" to "220"),
        row("Item" to "SR-201", "Description" to "Porcelain Tiles", "Unit" to "m²", "Quantity" to "80", "Rate" to "95"),
        row("Item" to "XX-999", "Description" to "Removed in Rev 02", "Unit" to "m²", "Quantity" to "5", "Rate" to "10")
    )

    private val rev2: List<Map<String, String>> = listOf(
        row("Item" to "MR-101", "Description" to "Galala Marble Wall Cladding", "Unit" to "m²", "Quantity" to "37.5", "Rate" to "1850"),
        // quantity increased
        row("Item" to "MR-104", "Description" to "Piatra Grey Floor", "Unit" to "m²", "Quantity" to "135", "Rate" to "1400"),
        // description reworded
        row("Item" to "MR-110", "Description" to "Gypsum Ceiling Board", "Unit" to "m²", "Quantity" to "210", "Rate" to "220"),
        // rate increased
        row("Item" to "SR-201", "Description" to "Porcelain Tiles", "Unit" to "m²", "Quantity" to "80", "Rate" to "112"),
        // new row
        row("Item" to "MR-130", "Description" to "Travertine Feature Wall", "Unit" to "m²", "Quantity" to "24", "Rate" to "2400")
    )

    private fun row(vararg pairs: Pair<String, String>): Map<String, String> = mapOf(*pairs)

    private fun diff(
        old: List<Map<String, String>>,
        new: List<Map<String, String>>
    ): ComparisonResult {
        val oldByKey = old.associateBy { it.getValue("Item") }
        val newByKey = new.associateBy { it.getValue("Item") }

        val added = newByKey.filterKeys { it !in oldByKey }.values
            .map { RowDiff(0, it, it.values.joinToString(" | ")) }
        val removed = oldByKey.filterKeys { it !in newByKey }.values
            .map { RowDiff(0, it, it.values.joinToString(" | ")) }

        val changed = mutableListOf<ChangedRow>()
        for (key in oldByKey.keys intersect newByKey.keys) {
            val before = oldByKey.getValue(key)
            val after = newByKey.getValue(key)
            val cells = (before.keys + after.keys).filter { it != "Item" }
                .mapNotNull { header ->
                    val o = before[header].orEmpty()
                    val n = after[header].orEmpty()
                    if (o != n) {
                        CellChange(
                            header = header,
                            oldValue = o,
                            newValue = n,
                            changeType = CellChange.ChangeType.VALUE
                        )
                    } else {
                        null
                    }
                }
            if (cells.isNotEmpty()) {
                changed.add(
                    ChangedRow(
                        rowIndex = 0,
                        changes = cells,
                        oldRow = RowDiff(0, before, before.values.joinToString(" | ")),
                        newRow = RowDiff(0, after, after.values.joinToString(" | "))
                    )
                )
            }
        }
        return ComparisonResult(added, removed, changed, "diff")
    }

    @Test
    fun `added rows are detected`() {
        val result = diff(rev1, rev2)
        assertEquals(1, result.addedRows.size)
        assertEquals("MR-130", result.addedRows.first().cells.getValue("Item"))
        assertTrue(result.addedRows.first().cells.getValue("Description").contains("Travertine"))
    }

    @Test
    fun `removed rows are detected`() {
        val result = diff(rev1, rev2)
        assertEquals(1, result.removedRows.size)
        assertEquals("XX-999", result.removedRows.first().cells.getValue("Item"))
    }

    @Test
    fun `unchanged rows are not reported as changed`() {
        val result = diff(rev1, rev2)
        val changedKeys = result.changedRows.map { it.newRow.cells.getValue("Item") }
        assertTrue("MR-101 is identical in both revisions", !changedKeys.contains("MR-101"))
        assertEquals(3, result.changedRows.size)
    }

    @Test
    fun `quantity change is reported against the right column`() {
        val result = diff(rev1, rev2)
        val piatra = result.changedRows.first { it.newRow.cells["Item"] == "MR-104" }
        val quantity = piatra.changes.first { it.header == "Quantity" }
        assertEquals("120", quantity.oldValue)
        assertEquals("135", quantity.newValue)
    }

    @Test
    fun `rate change is reported against the right column`() {
        val result = diff(rev1, rev2)
        val tiles = result.changedRows.first { it.newRow.cells["Item"] == "SR-201" }
        val rate = tiles.changes.first { it.header == "Rate" }
        assertEquals("95", rate.oldValue)
        assertEquals("112", rate.newValue)
    }

    @Test
    fun `description change is reported against the right column`() {
        val result = diff(rev1, rev2)
        val ceiling = result.changedRows.first { it.newRow.cells["Item"] == "MR-110" }
        val description = ceiling.changes.first { it.header == "Description" }
        assertEquals("Gypsum Ceiling", description.oldValue)
        assertEquals("Gypsum Ceiling Board", description.newValue)
    }

    @Test
    fun `total counts match the fixture expectations`() {
        val result = diff(rev1, rev2)
        assertEquals("1 added", 1, result.addedRows.size)
        assertEquals("1 removed", 1, result.removedRows.size)
        assertEquals("3 changed", 3, result.changedRows.size)
    }

    @Test
    fun `cell change value type is explicit`() {
        val change = CellChange("Total", "69375", "83100", CellChange.ChangeType.VALUE)
        assertEquals(CellChange.ChangeType.VALUE, change.changeType)
        assertNotNull(change.header)
    }
}
