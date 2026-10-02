package com.edib.procurement.followup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anchor / continuation grouping.
 *
 * The workbook interleaves anchor rows with continuation rows that only carry a
 * Code and operational data. These tests pin that behaviour so a future change
 * cannot silently turn a 14-entry group back into 14 unrelated projects.
 */
class RowGrouperTest {

    @Test
    fun `anchor with no and project, then two continuations, then a new anchor`() {
        val rows = listOf(
            row(excelRow = 2, number = "2441", project = "Jaz Negma", code = "MMH2-GR-AR-14-25A"),
            row(excelRow = 3, code = "MMH2-GR-AR-14-25  B"),
            row(excelRow = 4, code = "MMH2-GR-AR-14-25  C"),
            row(excelRow = 5, number = "2442", project = "Jaz Negma", code = "MMH2-GR-AR-15-25A")
        )

        val groups = RowGrouper.group(rows)

        assertEquals(2, groups.size)
        assertEquals(listOf(2, 3, 4), groups[0].allRows.map { it.excelRow })
        assertEquals(listOf(5), groups[1].allRows.map { it.excelRow })
        assertEquals("MMH2-GR-AR-14-25A", groups[0].rootCodeRaw)
    }

    @Test
    fun `continuation rows inherit group context rather than becoming unknown projects`() {
        val rows = listOf(
            row(excelRow = 2, number = "2441", project = "Jaz Negma", code = "MMH2-GR-AR-14-25A"),
            row(excelRow = 3, code = "MMH2-GR-AR-14-25B"),
            row(excelRow = 4, code = "MMH2-GR-AR-14-25C")
        )

        val groups = RowGrouper.group(rows)

        assertEquals(1, groups.size)
        // Every entry belongs to the one group, so the group knows the project.
        assertEquals("Jaz Negma", groups[0].projectRaw)
        assertEquals(3, groups[0].allRows.size)
        groups[0].continuationRows.forEach { continuation ->
            assertNull("continuation must not invent a project", continuation.project)
        }
    }

    @Test
    fun `a continuation carrying only a contractor still attaches to the previous anchor`() {
        val rows = listOf(
            row(excelRow = 2, number = "10", project = "Giza Palace", code = "GP-AR-001-25A"),
            row(excelRow = 3, company = "Alpha Construction"),
            row(excelRow = 4, company = "Crystal Blue", orderDate = "12 Mar 2025")
        )

        val groups = RowGrouper.group(rows)

        assertEquals(1, groups.size)
        assertEquals(3, groups[0].allRows.size)
    }

    @Test
    fun `a row with a project but no number is still an anchor`() {
        // This workbook has 15 such rows; they must not be absorbed by the
        // previous group.
        val rows = listOf(
            row(excelRow = 2, number = "1", project = "MAKADI", code = "MK-A-1"),
            row(excelRow = 3, project = "Makadi Saraya", code = "SGR-PA-AR-0017-21"),
            row(excelRow = 4, project = "Makadi Saraya", code = "SGR-PA-AR-0017-21")
        )

        val groups = RowGrouper.group(rows)

        assertEquals(3, groups.size)
        assertEquals("Makadi Saraya", groups[1].projectRaw)
    }

    @Test
    fun `a row with a number but no project is still an anchor`() {
        val rows = listOf(
            row(excelRow = 2, number = "1", project = "Costa Mars", code = "JTB-1"),
            row(excelRow = 3, number = "2", code = "JTB-2")
        )

        val groups = RowGrouper.group(rows)

        assertEquals(2, groups.size)
    }

    @Test
    fun `a non-numeric placeholder number does not start a group`() {
        val rows = listOf(
            row(excelRow = 2, number = "1", project = "CAVO", code = "CV-1"),
            row(excelRow = 3, number = "-", code = "CV-2")
        )

        val groups = RowGrouper.group(rows)

        assertEquals(1, groups.size)
        assertEquals(2, groups[0].allRows.size)
    }

    @Test
    fun `leading continuation rows are kept instead of discarded`() {
        val rows = listOf(
            row(excelRow = 2, code = "ORPHAN-CODE-1"),
            row(excelRow = 3, number = "1", project = "CAVO", code = "CV-1")
        )

        val groups = RowGrouper.group(rows)

        assertEquals(2, groups.size)
        assertEquals("ORPHAN-CODE-1", groups[0].rootCodeRaw)
    }

    @Test
    fun `a completely blank row is ignored`() {
        val rows = listOf(
            row(excelRow = 2, number = "1", project = "CAVO", code = "CV-1"),
            FollowUpRow(excelRow = 3),
            row(excelRow = 4, code = "CV-2")
        )

        val groups = RowGrouper.group(rows)

        assertEquals(1, groups.size)
        assertEquals(2, groups[0].allRows.size)
    }

    @Test
    fun `an unknown column alone is enough payload to keep a continuation row`() {
        // Column I holds things like "B", "0008 - 0012" or "تركيب B". Such a row
        // must not be thrown away.
        val rows = listOf(
            row(excelRow = 2, number = "1", project = "CAVO", code = "CV-1"),
            FollowUpRow(excelRow = 3, extraCells = mapOf(8 to "0008 - 0012"))
        )

        val groups = RowGrouper.group(rows)

        assertEquals(1, groups.size)
        assertEquals(2, groups[0].allRows.size)
    }

    @Test
    fun `grouping reproduces the reference workbook structure`() {
        // The real sheet: an anchor, then a run of continuation rows A..M plus
        // "L adj", then a fresh anchor.
        val suffixes = listOf("B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "L adj", "M")
        val rows = listOf(
            row(
                excelRow = 3843, number = "2441", project = "Jaz Negma",
                area = "Marsa Allam", relatedTo = "ELham",
                code = "MMH2-GR-AR-14-25A"
            )
        ) + suffixes.mapIndexed { i, s ->
            row(excelRow = 3844 + i, code = "MMH2-GR-AR-14-25 $s")
        } + row(
            excelRow = 3857, number = "2442", project = "Jaz Negma",
            code = "MMH2-GR-AR-15-25A"
        )

        val groups = RowGrouper.group(rows)

        assertEquals(2, groups.size)
        // The anchor plus 13 continuation rows.
        assertEquals(14, groups[0].allRows.size)
        assertEquals("Jaz Negma", groups[0].projectRaw)
        assertEquals(1, groups[1].allRows.size)

        // Variants are display helpers derived from the group root.
        val root = TextNormalizer.normalizeCode("MMH2-GR-AR-14-25A")
        assertEquals("B", EntryVariant.derive("MMH2-GR-AR-14-25 B", root))
        assertEquals("L", EntryVariant.derive("MMH2-GR-AR-14-25 L", root))
        assertEquals("L ADJ", EntryVariant.derive("MMH2-GR-AR-14-25 L adj", root))
        assertEquals("M", EntryVariant.derive("MMH2-GR-AR-14-25 M", root))
    }

    private fun row(
        excelRow: Int,
        number: String? = null,
        project: String? = null,
        area: String? = null,
        relatedTo: String? = null,
        code: String? = null,
        company: String? = null,
        orderDate: String? = null
    ) = FollowUpRow(
        excelRow = excelRow,
        number = number,
        project = project,
        area = area,
        relatedTo = relatedTo,
        code = code,
        company = company,
        orderDate = orderDate
    )
}

class EntryVariantTest {

    @Test
    fun `variant is derived from the group root, never by stripping suffixes`() {
        val root = "MMH2-GR-AR-14-25A"
        assertEquals("B", EntryVariant.derive("MMH2-GR-AR-14-25  B", root))
        assertEquals("L", EntryVariant.derive("MMH2-GR-AR-14-25  L", root))
        assertEquals("L ADJ", EntryVariant.derive("MMH2-GR-AR-14-25 L adj", root))
        assertEquals("M", EntryVariant.derive("MMH2-GR-AR-14-25  M", root))
    }

    @Test
    fun `is adjusted detects an adjusted marker in the variant`() {
        assertTrue(EntryVariant.isAdjusted("L ADJ"))
        assertTrue(EntryVariant.isAdjusted("ملحق"))
        assertFalse(EntryVariant.isAdjusted("L"))
        assertFalse(EntryVariant.isAdjusted(null))
    }

    @Test
    fun `the root itself has no variant`() {
        assertNull(EntryVariant.derive("MMH2-GR-AR-14-25A", "MMH2-GR-AR-14-25A"))
    }

    @Test
    fun `a plain trailing letter becomes the variant when no root exists`() {
        assertEquals("B", EntryVariant.derive("MMH2-GR-AR-14-25B", null))
        assertNotNull(EntryVariant.derive("MMH2-GR-AR-14-25B", null))
    }
}

class StableKeysTest {

    @Test
    fun `group key is content derived and ignores the excel row number`() {
        val a = StableKeys.groupKey(
            "Jaz Negma", "Marsa Allam", "ELham", "2441",
            "MMH2-GR-AR-14-25A", null, "Area\\Marsa Alam\\H2\\MMH2-GR-AR-14-25A.pdf"
        )
        val b = StableKeys.groupKey(
            "Jaz Negma", "Marsa Allam", "ELham", "2441",
            "MMH2-GR-AR-14-25A", null, "Area\\Marsa Alam\\H2\\MMH2-GR-AR-14-25A.pdf"
        )
        assertEquals(a, b)
        assertTrue(a.startsWith("g:"))
    }

    @Test
    fun `group key changes when the historical order file changes`() {
        val a = StableKeys.groupKey(
            "Jaz Negma", "M", "ELham", "2441", "MMH2-GR-AR-14-25A",
            null, "Area\\M\\MMH2-GR-AR-14-25A.pdf"
        )
        val b = StableKeys.groupKey(
            "Jaz Negma", "M", "ELham", "2441", "MMH2-GR-AR-14-25A",
            null, "Area\\M\\MMH2-GR-AR-14-25B.pdf"
        )
        assertTrue("same code, different historical file must differ", a != b)
    }

    @Test
    fun `entry key distinguishes the same code with different files`() {
        val g = "g:test"
        val a = StableKeys.entryKey(g, "MMH2-GR-AR-14-25", null, "Area\\x\\MMH2-AR-1.pdf", null, null, null, null)
        val b = StableKeys.entryKey(g, "MMH2-GR-AR-14-25", null, "Area\\y\\MMH2-AR-2.pdf", null, null, null, null)
        assertTrue(a != b)
    }
}

class PathHintsTest {

    @Test
    fun `basename is extracted from a windows path`() {
        assertEquals(
            "MMH2-GR-AR-14-25A.pdf",
            PathHints.fileName("Area\\Marsa Alam\\H2\\MMH2-GR-AR-14-25A.pdf")
        )
    }

    @Test
    fun `stem and extension are separated`() {
        val p = "Area\\Marsa Alam\\H2\\MMH2-GR-AR-14-25A.pdf"
        assertEquals("MMH2-GR-AR-14-25A", PathHints.fileNameStem(p))
        assertEquals("pdf", PathHints.extension(p))
    }

    @Test
    fun `directory segments are normalised and exclude the file name`() {
        val segs = PathHints.directorySegments("Area\\Marsa Alam\\H2\\file.pdf")
        assertEquals(listOf("AREA", "MARSA ALAM", "H2"), segs)
    }

    @Test
    fun `year hints are found in a folder path`() {
        assertEquals(setOf("2025"), PathHints.yearHints("طلبات الشراء Scan\\2025\\MARSA ALLAM"))
    }

    @Test
    fun `prose in a path column is not treated as a path`() {
        assertFalse(PathHints.looksLikePath("تم ارجاعه و الغاؤه- بناء على طلب المهندسة"))
        assertFalse(PathHints.looksLikePath("مش تبعنا"))
        assertFalse(PathHints.looksLikePath(null))
        assertTrue(PathHints.looksLikePath("Area\\x\\y.pdf"))
        assertTrue(PathHints.looksLikePath("N:\\Ragaey\\Area\\file.docx"))
    }
}
