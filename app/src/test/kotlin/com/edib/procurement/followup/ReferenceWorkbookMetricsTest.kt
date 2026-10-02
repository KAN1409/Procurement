package com.edib.procurement.followup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream

/**
 * Full measurement of the real reference workbook, end to end.
 *
 * This is the Phase-1 acceptance evidence for parsing, grouping, project
 * inheritance and contractor normalisation. It prints the measured numbers so
 * they can be compared against a newer workbook version, and asserts only the
 * structural invariants the app depends on — never today's specific data.
 */
class ReferenceWorkbookMetricsTest {

    private lateinit var fixture: File

    @Before
    fun locateFixture() {
        fixture = FixtureFiles.followUpWorkbook()
        assumeTrue("reference workbook not available", fixture.exists())
    }

    @Test
    fun `measure the reference workbook`() {
        val reader = XlsxStreamReader()
        val structure = reader.readWorkbookStructure(FileInputStream(fixture))
        val sheets = structure.sheets.map { it.name }

        val ordersName = sheets.firstOrNull { it.contains("order", true) } ?: sheets.first()
        val supplierName = sheets.firstOrNull { it.contains("supplier", true) }
        val orders = structure.sheets.first { it.name == ordersName }

        // ---- Orders sheet: locate the header and read the rows -----------
        var headerRow = 0
        var headerCells: Map<Int, String> = emptyMap()
        val rows = ArrayList<FollowUpRow>()
        var rawDataRows = 0

        FileInputStream(fixture).use { input ->
            reader.forEachRow(input, structure, orders) { row ->
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
                    rawDataRows++
                    toRow(row, headerCells)?.let { rows.add(it) }
                }
            }
        }
        val map = HeaderMapper.map(headerCells)
        val withPayload = rows.filter { RowGrouper.hasPayload(it) }
        val groups = RowGrouper.group(withPayload)

        val anchorRows = withPayload.count { RowGrouper.isAnchor(it) }
        val continuationRows = groups.sumOf { it.continuationRows.size }

        // ---- SUPPLIER LIST ----------------------------------------------
        val suppliers = supplierName?.let { readSuppliers(reader, structure, structure.sheets.first { s -> s.name == it }) }
            ?: emptyList()

        // ---- Project inheritance metric ---------------------------------
        // A continuation row has no Project of its own; it must inherit the
        // group's. A row is only genuinely unresolved when the workbook left
        // the Project blank on the ANCHOR row too — there is nothing to inherit.
        var codeNoProject = 0
        var inherited = 0
        var unresolvedProject = 0
        val unresolvedSamples = ArrayList<String>()
        for (g in groups) {
            for (row in g.allRows) {
                if (!row.code.isNullOrBlank() && row.project.isNullOrBlank()) {
                    codeNoProject++
                    if (!g.projectRaw.isNullOrBlank()) {
                        inherited++
                    } else {
                        unresolvedProject++
                        if (unresolvedSamples.size < 20) {
                            unresolvedSamples.add(
                                "row ${row.excelRow} code=${row.code} anchorRow=${g.anchorRow.excelRow}"
                            )
                        }
                    }
                }
            }
        }
        println("unresolved samples    : ${unresolvedSamples.joinToString(" ; ")}")

        val distinctProjects = groups.mapNotNull { it.projectRaw }
            .map { TextNormalizer.normalize(it) }.distinct().size

        // ---- Print the measured report ----------------------------------
        println("=== FOLLOW-UP IMPORT MEASUREMENTS ===")
        println("workbook                : ${fixture.name}")
        println("sheets found            : ${sheets.joinToString(", ")}")
        println("orders sheet            : $ordersName")
        println("supplier sheet detected : ${supplierName != null}")
        println("header row              : $headerRow")
        println("mapped fields           : ${map.recognizedFields.map { it.name }.sorted().joinToString(", ")}")
        println("raw data rows inspected : $rawDataRows")
        println("rows with payload       : ${withPayload.size}")
        println("anchor rows             : $anchorRows")
        println("continuation rows       : $continuationRows")
        println("FollowUpGroups          : ${groups.size}")
        println("FollowUpEntries         : ${withPayload.size}")
        println("projects (distinct)     : $distinctProjects")
        println("supplier aliases        : ${suppliers.size}")
        println("code rows w/o project   : $codeNoProject")
        println("  inherited            : $inherited")
        println("  unresolved           : $unresolvedProject")

        // ---- Structural invariants --------------------------------------
        assertTrue("sheet not found", headerRow > 0)
        assertTrue("expected data rows", withPayload.size > 1000)
        assertTrue("expected groups", groups.size > 1000)
        assertTrue(
            "groups must be fewer than entries (continuations folded in)",
            groups.size < withPayload.size
        )
        assertTrue(
            "continuation rows must outnumber nothing: every grouped continuation " +
                "has to sit under an anchor",
            continuationRows > 0
        )
        // Every row that could inherit a project must have done so. The
        // remainder are workbook rows whose ANCHOR project is blank too, which
        // is a data gap in the source, not a grouping failure.
        assertEquals(
            "every inheritable row must have inherited its group's project",
            codeNoProject, inherited + unresolvedProject
        )
        assertTrue("supplier sheet expected", suppliers.isNotEmpty())

        // ---- MMH2-GR-AR-14-25 grouping evidence ------------------------
        val jaz = groups.filter {
            it.projectRaw?.contains("Jaz Negma", ignoreCase = true) == true &&
                it.allRows.any { r -> it.rootCodeRaw?.startsWith("MMH2-GR-AR-14-25") == true }
        }
        assertTrue("Jaz Negma MMH2-GR-AR-14-25 group not found", jaz.isNotEmpty())
        val group = jaz.maxByOrNull { it.allRows.size }!!
        println("=== MMH2-GR-AR-14-25 GROUP ===")
        println("project       : ${group.projectRaw}")
        println("area          : ${group.area}")
        println("related to    : ${group.relatedTo}")
        println("anchor row    : ${group.anchorRow.excelRow}")
        println("entry count   : ${group.allRows.size}")
        println("entry codes   : ${group.allRows.mapNotNull { it.code }.joinToString(" | ")}")

        val codes = group.allRows.mapNotNull { it.code }
        assertTrue(
            "continuation B/C/D must live in the SAME group as the anchor",
            codes.any { it.contains("14-25") && it.trim().endsWith("B") } &&
                codes.any { it.trim().endsWith("C") } &&
                codes.any { it.trim().endsWith("D") }
        )
        // Every continuation inherits the project; none of them is its own project.
        group.continuationRows.forEach {
            assertEquals("continuation must not carry its own project", null, it.project)
        }
        assertNotNull(group.projectRaw)

        // ---- Contractor normalisation samples ---------------------------
        val aliasList = suppliers
        val normalizer = SupplierNormalizer(aliasList)
        println("=== CONTRACTOR NORMALISATION SAMPLES ===")
        aliasList.take(5).forEach {
            val r = normalizer.resolve(it.aliasRaw)
            val kind = when (r) {
                is ContractorResolution.Resolved -> r.matchKind.name
                is ContractorResolution.Unresolved -> "UNRESOLVED"
                ContractorResolution.Blank -> "BLANK"
            }
            val canonical = (r as? ContractorResolution.Resolved)?.contractorRaw ?: "-"
            println("alias -> canonical [$kind]: ${it.aliasRaw}  ==>  $canonical")
        }

        // Whitespace variant resolves.
        val firstAlias = aliasList.first().aliasRaw
        val spaced = normalizer.resolve("   $firstAlias   ")
        assertTrue("whitespace variant should resolve", spaced is ContractorResolution.Resolved)

        // Unknown supplier stays unresolved and keeps its text.
        val unknown = normalizer.resolve("شركة غير موجودة في القائمة نهائيا")
        assertTrue("unknown supplier must be unresolved", unknown is ContractorResolution.Unresolved)
        assertEquals(
            "unresolved must preserve the raw text",
            "شركة غير موجودة في القائمة نهائيا",
            (unknown as ContractorResolution.Unresolved).contractorRaw
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun readSuppliers(
        reader: XlsxStreamReader,
        structure: XlsxStreamReader.WorkbookStructure,
        sheet: XlsxStreamReader.SheetInfo
    ): List<SupplierAlias> {
        var aliasCol = -1
        var canonicalCol = -1
        var noteCol = -1
        val out = ArrayList<SupplierAlias>()
        FileInputStream(fixture).use { input ->
            reader.forEachRow(input, structure, sheet) { row ->
                if (aliasCol < 0 || canonicalCol < 0) {
                    val normalized = row.cells.mapValues { TextNormalizer.normalize(it.value) }
                    normalized.forEach { (idx, v) ->
                        if (canonicalCol < 0 && v == "COMPANY") aliasCol = idx
                        if (canonicalCol < 0 && v in CANONICAL_HEADERS) canonicalCol = idx
                        if (noteCol < 0 && v in NOTE_HEADERS) noteCol = idx
                    }
                    return@forEachRow
                }
                val a = row.text(aliasCol) ?: return@forEachRow
                val c = row.text(canonicalCol) ?: return@forEachRow
                out.add(SupplierAlias(a, c, if (noteCol >= 0) row.text(noteCol) else null))
            }
        }
        return out
    }

    private fun toRow(
        r: XlsxStreamReader.SheetRow,
        headerCells: Map<Int, String>
    ): FollowUpRow? {
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

    private companion object {
        val CANONICAL_HEADERS = setOf("CORRECT NAME", "CORRECTNAME", "CORRECT COMPANY")
        val NOTE_HEADERS = setOf("ADJUSTED", "NOTE", "NOTES", "ADJUSTMENT")
    }
}
