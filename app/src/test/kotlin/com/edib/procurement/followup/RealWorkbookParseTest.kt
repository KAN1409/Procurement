package com.edib.procurement.followup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream

/**
 * End-to-end parse of the real reference workbook.
 *
 * The fixture is the attached "Following up Orders Eng. Ragaey 6 MAY 2026.xlsx".
 * It is a *behaviour* reference: the assertions below check structure the app
 * depends on, not today's specific data, so a newer workbook will not break them.
 */
class RealWorkbookParseTest {

    private lateinit var fixture: File

    @Before
    fun locateFixture() {
        fixture = FixtureFiles.followUpWorkbook()
        assumeTrue(
            "reference workbook not available in this environment",
            fixture.exists()
        )
    }

    @Test
    fun `both sheets are found by name`() {
        val structure = XlsxStreamReader().readWorkbookStructure(FileInputStream(fixture))
        val names = structure.sheets.map { it.name }
        assertTrue("Orders sheet missing: $names", names.any { it.contains("order", true) })
        assertTrue("SUPPLIER LIST sheet missing: $names", names.any { it.contains("supplier", true) })
    }

    @Test
    fun `the orders sheet header maps to the recognised fields`() {
        val structure = XlsxStreamReader().readWorkbookStructure(FileInputStream(fixture))
        val orders = structure.sheets.first { it.name.contains("order", true) }

        var headerCells: Map<Int, String> = emptyMap()
        var headerRow = 0
        FileInputStream(fixture).use { input ->
            XlsxStreamReader().forEachRow(input, structure, orders) { row ->
                if (headerRow == 0) {
                    val recognised = row.cells.count {
                        HeaderMapper.resolveField(TextNormalizer.normalize(it.value)) !=
                            FollowUpField.UNKNOWN
                    }
                    if (recognised >= 3) {
                        headerRow = row.rowNumber
                        headerCells = row.cells
                    }
                }
            }
        }

        assertTrue("no header row detected", headerRow > 0)
        val map = HeaderMapper.map(headerCells)
        assertTrue("CODE not mapped", map.column(FollowUpField.CODE) != null)
        assertTrue("PROJECT not mapped", map.column(FollowUpField.PROJECT) != null)
        assertTrue("ORDER_PATH not mapped", map.column(FollowUpField.ORDER_PATH) != null)
        assertTrue("COMPANY not mapped", map.column(FollowUpField.COMPANY) != null)
    }

    @Test
    fun `parsing the orders sheet yields groups and entries`() {
        val structure = XlsxStreamReader().readWorkbookStructure(FileInputStream(fixture))
        val orders = structure.sheets.first { it.name.contains("order", true) }

        var headerCells: Map<Int, String> = emptyMap()
        var headerRow = 0
        val dataRows = ArrayList<XlsxStreamReader.SheetRow>()
        FileInputStream(fixture).use { input ->
            XlsxStreamReader().forEachRow(input, structure, orders) { row ->
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
                    dataRows.add(row)
                }
            }
        }

        val map = HeaderMapper.map(headerCells)
        val rows = dataRows.mapNotNull { toRow(it, map) }.filter { RowGrouper.hasPayload(it) }
        val groups = RowGrouper.group(rows)

        assertTrue("expected a populated workbook", rows.size > 1000)
        assertTrue("expected thousands of groups, got ${groups.size}", groups.size > 1000)
        assertTrue("expected continuation rows", groups.any { it.continuationRows.isNotEmpty() })

        // Groups must be fewer than entries, i.e. continuation rows really were
        // folded into their parent group.
        assertTrue(groups.size < rows.size)
    }

    @Test
    fun `Jaz Negma appears as a real project with a multi entry group`() {
        val groups = parseGroups()

        val jazGroups = groups.filter { it.projectRaw?.contains("Jaz Negma", true) == true }
        assertTrue("Jaz Negma not found", jazGroups.isNotEmpty())

        // The reference workbook's Jaz Negma group has an anchor plus A..M
        // continuation rows, all inheriting the group's project.
        val big = jazGroups.maxByOrNull { it.allRows.size }
        assertTrue("expected a multi-entry Jaz Negma group", (big?.allRows?.size ?: 0) > 3)
        big!!.continuationRows.forEach { continuation ->
            assertEquals(
                "continuation rows must inherit project context, not invent one",
                null, continuation.project
            )
        }
    }

    @Test
    fun `hyperlink targets supply the full historical paths`() {
        val structure = XlsxStreamReader().readWorkbookStructure(FileInputStream(fixture))
        val orders = structure.sheets.first { it.name.contains("order", true) }

        var headerRow = 0
        var headerCells: Map<Int, String> = emptyMap()
        val pending = ArrayList<XlsxStreamReader.SheetRow>()
        FileInputStream(fixture).use { input ->
            XlsxStreamReader().forEachRow(input, structure, orders) { row ->
                if (headerRow == 0) {
                    val recognised = row.cells.count {
                        HeaderMapper.resolveField(TextNormalizer.normalize(it.value)) !=
                            FollowUpField.UNKNOWN
                    }
                    if (recognised >= 3) {
                        headerRow = row.rowNumber
                        headerCells = row.cells
                    }
                } else pending.add(row)
            }
        }

        val map = HeaderMapper.map(headerCells)
        val orderPathCol = map.column(FollowUpField.ORDER_PATH)
        assumeTrue("no order path column", orderPathCol != null)

        // At least one hyperlink must resolve to a full, untruncated path.
        val fullPaths = pending.mapNotNull { it.hyperlinks[orderPathCol] }
            .filter { it.contains('/') && it.length > 50 }
        assertTrue("no full historical paths resolved", fullPaths.isNotEmpty())
    }

    private fun parseGroups(): List<FollowUpGroup> {
        val structure = XlsxStreamReader().readWorkbookStructure(FileInputStream(fixture))
        val orders = structure.sheets.first { it.name.contains("order", true) }

        var headerRow = 0
        var headerCells: Map<Int, String> = emptyMap()
        val dataRows = ArrayList<XlsxStreamReader.SheetRow>()
        FileInputStream(fixture).use { input ->
            XlsxStreamReader().forEachRow(input, structure, orders) { row ->
                if (headerRow == 0) {
                    val recognised = row.cells.count {
                        HeaderMapper.resolveField(TextNormalizer.normalize(it.value)) !=
                            FollowUpField.UNKNOWN
                    }
                    if (recognised >= 3) {
                        headerRow = row.rowNumber
                        headerCells = row.cells
                    }
                } else dataRows.add(row)
            }
        }
        val map = HeaderMapper.map(headerCells)
        val rows = dataRows.mapNotNull { toRow(it, map) }.filter { RowGrouper.hasPayload(it) }
        return RowGrouper.group(rows)
    }

    private fun toRow(r: XlsxStreamReader.SheetRow, map: HeaderMapper.HeaderMap): FollowUpRow? {
        fun t(f: FollowUpField) = map.column(f)?.let { r.text(it) }
        fun p(f: FollowUpField) = map.column(f)?.let { r.pathText(it) }

        val recognised = map.recognizedFields.mapNotNull { map.column(it) }.toSet()
        val extra = HashMap<Int, String>()
        for ((idx, value) in r.cells) {
            if (idx !in recognised && value.isNotBlank()) extra[idx] = value
        }

        return FollowUpRow(
            excelRow = r.rowNumber,
            number = t(FollowUpField.NUMBER),
            project = t(FollowUpField.PROJECT),
            area = t(FollowUpField.AREA),
            relatedTo = t(FollowUpField.RELATED_TO),
            code = t(FollowUpField.CODE),
            prPath = p(FollowUpField.PR_PATH),
            orderPath = p(FollowUpField.ORDER_PATH),
            company = t(FollowUpField.COMPANY),
            description = t(FollowUpField.DESCRIPTION),
            prDate = t(FollowUpField.PR_DATE),
            orderDate = t(FollowUpField.ORDER_DATE),
            prWithEngineer = t(FollowUpField.PR_WITH_ENGINEER),
            notes = t(FollowUpField.NOTES),
            financialReceivedDate = t(FollowUpField.FINANCIAL_RECEIVED_DATE),
            finalApprovalDate = t(FollowUpField.FINAL_APPROVAL_DATE),
            checkValue = t(FollowUpField.CHECK),
            oldCompany = t(FollowUpField.OLD_COMPANY),
            extraCells = extra
        )
    }
}

/**
 * Locates the reference workbook.
 *
 * It is shipped as a test resource, so the first attempt is the classpath copy
 * that Gradle places on the test runtime classpath. The filesystem fallbacks
 * let the same test run against a workbook sitting in the workspace.
 */
object FixtureFiles {
    fun followUpWorkbook(): File {
        val classpath = javaClass.classLoader
            ?.getResource("fixtures/follow-up-reference.xlsx")
        if (classpath != null && classpath.protocol != "file") {
            javaClass.classLoader.getResourceAsStream("fixtures/follow-up-reference.xlsx")
                ?.use { input ->
                    val tmp = File.createTempFile("follow-up-reference", ".xlsx")
                    tmp.deleteOnExit()
                    input.copyTo(tmp.outputStream())
                    return tmp
                }
        }
        val candidates = listOf(
            System.getProperty("procurement.workbook") ?: "",
            "src/test/resources/fixtures/follow-up-reference.xlsx",
            "../app/src/test/resources/fixtures/follow-up-reference.xlsx",
            "/storage/emulated/0/Android/data/com.androidharness.app/files/workspace/fixtures/follow-up-reference.xlsx"
        )
        for (c in candidates) {
            if (c.isNotBlank()) {
                val f = File(c)
                if (f.exists()) return f
            }
        }
        for (d in listOf(".", "..", "../..")) {
            File(d).listFiles()?.forEach { f ->
                if (f.name.endsWith(".xlsx", true) && f.name.contains("ollow", true)) return f
            }
        }
        return File("__missing__")
    }
}
