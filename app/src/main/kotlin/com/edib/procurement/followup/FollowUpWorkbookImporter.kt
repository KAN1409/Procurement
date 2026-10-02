package com.edib.procurement.followup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.edib.procurement.database.ContractorAliasEntity
import com.edib.procurement.database.ContractorDao
import com.edib.procurement.database.ContractorEntity
import com.edib.procurement.database.FollowUpDao
import com.edib.procurement.database.FollowUpEntryEntity
import com.edib.procurement.database.FollowUpGroupEntity
import com.edib.procurement.database.FollowUpImportEntity
import com.edib.procurement.database.ProjectEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/** What an import produced, computed from the workbook — never hardcoded. */
data class ImportReport(
    val groups: Int,
    val entries: Int,
    val projects: Int,
    val contractors: Int,
    val unresolvedContractors: Int,
    val supplierAliases: Int,
    val sheetsFound: List<String>,
    val recognizedFields: List<String>,
    val unknownColumnHeaders: List<String>,
    val hyperlinkResolvedColumns: Int,
    val continuationRows: Int,
    val warnings: List<String>
)

/**
 * Reads the Follow-up workbook and writes it into Room.
 *
 * Read-only with respect to the workbook: it opens the SAF stream and never
 * writes, renames or moves anything.
 */
class FollowUpWorkbookImporter(
    private val context: Context,
    private val followUpDao: FollowUpDao,
    private val contractorDao: ContractorDao
) {

    companion object {
        const val SETTING_IMPORT_URI = "followup.importUri"
        const val SETTING_IMPORT_DATE = "followup.importDate"

        /**
         * Sheet-name hints, already in normalised (uppercase) form.
         *
         * They are stored normalised on purpose: matching a normalised sheet
         * name against raw lowercase hints silently fails to match, which once
         * left the SUPPLIER LIST sheet unread and produced zero contractors.
         */
        internal val ORDERS_SHEET_HINTS =
            listOf("orders", "order", "follow up", "followup").map { TextNormalizer.normalize(it) }
        internal val SUPPLIER_SHEET_HINTS =
            listOf("supplier", "vendor", "company list").map { TextNormalizer.normalize(it) }

        /** "correct name" as written in the reference SUPPLIER LIST sheet. */
        private val CANONICAL_HEADERS = setOf("CORRECT NAME", "CORRECTNAME", "CORRECT COMPANY")
        private val NOTE_HEADERS = setOf("ADJUSTED", "NOTE", "NOTES", "ADJUSTMENT")
    }

    /**
     * Caches canonical name -> id so a workbook with thousands of rows does not
     * repeat the same lookup. Declared suspend because the DAO is.
     */
    private class ContractorCache(private val contractorDao: ContractorDao) {
        private val ids = HashMap<String, Long>()

        suspend fun idFor(canonicalRaw: String): Long {
            val normalized = TextNormalizer.normalize(canonicalRaw)
            ids[normalized]?.let { return it }
            val existing = contractorDao.idByNormalized(normalized)
            if (existing != null && existing > 0) {
                ids[normalized] = existing
                return existing
            }
            val created = contractorDao.insertContractor(
                ContractorEntity(canonicalName = canonicalRaw, normalizedName = normalized)
            )
            val id = if (created > 0) created else {
                contractorDao.idByNormalized(normalized) ?: 0L
            }
            ids[normalized] = id
            return id
        }

        fun size(): Int = ids.size
    }

    suspend fun import(uri: Uri): ImportReport = withContext(Dispatchers.IO) {
        takeReadPermission(uri)
        val reader = XlsxStreamReader()
        val structure = openStream(uri).use { input ->
            reader.readWorkbookStructure(input)
        }

        val ordersSheet = pickSheet(structure.sheets, ORDERS_SHEET_HINTS)
            ?: structure.sheets.first()
        val supplierSheet = pickSheet(structure.sheets, SUPPLIER_SHEET_HINTS)

        // Parsing is plain blocking work (already on the IO dispatcher); the
        // only suspend points are the database writes inside persist().
        val supplierAliases = supplierSheet?.let { readSupplierList(uri, structure, it) }
            ?: emptyList()

        val parsed = readOrders(uri, structure, ordersSheet)

        persist(uri, ordersSheet.name, parsed, supplierAliases, structure.sheets.map { it.name })
    }

    /**
     * Keeps read access to the chosen workbook across reboots, so reopening the
     * app never asks for it again.
     */
    private fun takeReadPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    /**
     * Opens the workbook read-only. [Uri] streams are nullable, and a null here
     * means the permission was revoked, which is worth naming explicitly.
     */
    private fun openStream(uri: Uri): InputStream =
        resolver().openInputStream(uri)
            ?: throw IllegalStateException(
                "Cannot open the workbook. Pick it again to grant access."
            )

    private fun resolver() =
        context.contentResolver
            ?: throw IllegalStateException("no content resolver")

    // ------------------------------------------------------------- Orders

    private data class ParsedOrders(
        val map: HeaderMapper.HeaderMap,
        val rows: List<FollowUpRow>,
        val headerRow: Int,
        val hyperlinkCells: Int
    )

    // Parses one sheet. Plain blocking work, run on the IO dispatcher by the
    // caller; no coroutine suspension happens here.
    private fun readOrders(
        uri: Uri,
        structure: XlsxStreamReader.WorkbookStructure,
        sheet: XlsxStreamReader.SheetInfo
    ): ParsedOrders {
        val reader = XlsxStreamReader()

        // Two passes over the same part: one to find the header row, one to
        // read the data. The sheet part is streamed both times, so peak memory
        // stays at shared-strings size rather than sheet size.
        var headerRowNumber = 0
        var headerCells: Map<Int, String> = emptyMap()
        var dataRows = ArrayList<XlsxStreamReader.SheetRow>()

        openStream(uri).use { input ->
            reader.forEachRow(input, structure, sheet) { r ->
                val recognised = r.cells.count {
                    HeaderMapper.resolveField(TextNormalizer.normalize(it.value)) != FollowUpField.UNKNOWN
                }
                // The header row is the first row with at least three recognised
                // labels. Scored on how many columns resolve, so a workbook with
                // extra or renamed columns still finds its header, and a banner
                // or title row above it does not get mistaken for it.
                if (headerRowNumber == 0 && recognised >= 3) {
                    headerRowNumber = r.rowNumber
                    headerCells = r.cells
                } else if (headerRowNumber != 0 && r.rowNumber > headerRowNumber) {
                    dataRows.add(r)
                }
            }
        }
        if (headerRowNumber == 0) {
            throw XlsxStreamReader.WorkbookFormatException(
                "no recognisable header row found in sheet '${sheet.name}'"
            )
        }

        val map = HeaderMapper.map(headerCells)
        val rows = ArrayList<FollowUpRow>()
        var hyperlinkCells = 0

        // The rows were captured during the single pass above; no second pass is
        // needed and the sheet part is opened only once.
        for (r in dataRows) {
            hyperlinkCells += r.hyperlinks.size
            val row = toFollowUpRow(r, map) ?: continue
            rows.add(row)
        }

        return ParsedOrders(map, rows, headerRowNumber, hyperlinkCells)
    }

    /** Builds a [FollowUpRow], or null when the sheet row is entirely blank. */
    private fun toFollowUpRow(
        r: XlsxStreamReader.SheetRow,
        map: HeaderMapper.HeaderMap
    ): FollowUpRow? {
        if (r.cells.isEmpty()) return null

        fun t(field: FollowUpField): String? = map.column(field)?.let { r.text(it) }
        fun p(field: FollowUpField): String? = map.column(field)?.let { r.pathText(it) }

        // Recognised fields, trimmed but otherwise untouched.
        val number = t(FollowUpField.NUMBER)
        val project = t(FollowUpField.PROJECT)
        val area = t(FollowUpField.AREA)
        val relatedTo = t(FollowUpField.RELATED_TO)
        val code = t(FollowUpField.CODE)
        val company = t(FollowUpField.COMPANY)
        val description = t(FollowUpField.DESCRIPTION)
        val prDate = t(FollowUpField.PR_DATE)
        val orderDate = t(FollowUpField.ORDER_DATE)
        val prWithEng = t(FollowUpField.PR_WITH_ENGINEER)
        val notes = t(FollowUpField.NOTES)
        val finDate = t(FollowUpField.FINANCIAL_RECEIVED_DATE)
        val finalDate = t(FollowUpField.FINAL_APPROVAL_DATE)
        val check = t(FollowUpField.CHECK)
        val oldCompany = t(FollowUpField.OLD_COMPANY)
        val prPath = p(FollowUpField.PR_PATH)
        val orderPath = p(FollowUpField.ORDER_PATH)

        val recognisedColumns = map.recognizedFields.mapNotNull { map.column(it) }.toSet()
        val extra = HashMap<Int, String>()
        for ((idx, value) in r.cells) {
            if (idx in recognisedColumns) continue
            if (value.isBlank()) continue
            extra[idx] = value
        }

        val candidate = FollowUpRow(
            excelRow = r.rowNumber,
            number = number, project = project, area = area, relatedTo = relatedTo,
            code = code, prPath = prPath, orderPath = orderPath, company = company,
            description = description, prDate = prDate, orderDate = orderDate,
            prWithEngineer = prWithEng, notes = notes,
            financialReceivedDate = finDate, finalApprovalDate = finalDate,
            checkValue = check, oldCompany = oldCompany,
            extraCells = extra
        )

        // A row with no payload at all is not worth storing.
        return if (RowGrouper.hasPayload(candidate)) candidate else null
    }

    // --------------------------------------------------------- SUPPLIER LIST

    private fun readSupplierList(
        uri: Uri,
        structure: XlsxStreamReader.WorkbookStructure,
        sheet: XlsxStreamReader.SheetInfo
    ): List<SupplierAlias> {
        val reader = XlsxStreamReader()
        var aliasCol = -1
        var canonicalCol = -1
        var noteCol = -1
        val out = ArrayList<SupplierAlias>()

        openStream(uri).use { input ->
            reader.forEachRow(input, structure, sheet) { r ->
                if (aliasCol < 0 || canonicalCol < 0) {
                    // Detect by header text, tolerating "Company " / "correct name"
                    // with any whitespace, casing or punctuation.
                    val normalized = r.cells.mapValues { TextNormalizer.normalize(it.value) }
                    normalized.forEach { (idx, v) ->
                        if (canonicalCol < 0 && v == "COMPANY") aliasCol = idx
                        if (canonicalCol < 0 && v in CANONICAL_HEADERS) canonicalCol = idx
                        if (noteCol < 0 && v in NOTE_HEADERS) noteCol = idx
                    }
                    return@forEachRow
                }
                val alias = r.text(aliasCol) ?: return@forEachRow
                val canonical = r.text(canonicalCol) ?: return@forEachRow
                out.add(
                    SupplierAlias(
                        aliasRaw = alias,
                        canonicalRaw = canonical,
                        note = if (noteCol >= 0) r.text(noteCol) else null
                    )
                )
            }
        }
        return out
    }

    // ------------------------------------------------------------ persist

    private suspend fun persist(
        uri: Uri,
        ordersSheetName: String,
        parsed: ParsedOrders,
        supplierAliases: List<SupplierAlias>,
        sheetNames: List<String>
    ): ImportReport = withContext(Dispatchers.IO) { persistOnIo(uri, ordersSheetName, parsed, supplierAliases, sheetNames) }

    private suspend fun persistOnIo(
        uri: Uri,
        ordersSheetName: String,
        parsed: ParsedOrders,
        supplierAliases: List<SupplierAlias>,
        sheetNames: List<String>
    ): ImportReport {
        val now = System.currentTimeMillis()
        val warnings = ArrayList<String>()

        // Manual aliases the user created always win over the workbook's list.
        val manualAliases = contractorDao.manualAliases()
        val manualPairs = manualAliases.mapNotNull { a ->
            val canonical = contractorDao.contractorName(a.contractorId)
            if (canonical.isNullOrBlank()) null else SupplierAlias(a.aliasRaw, canonical, null)
        }
        val normalizer = SupplierNormalizer(manualPairs + supplierAliases)

        val groups = RowGrouper.group(parsed.rows)
        val schemaFingerprint =
            StableKeys.schemaFingerprint(parsed.map, sheetNames)
        val workbookFingerprint =
            StableKeys.workbookFingerprint(groups)

        val previous = followUpDao.currentImport()

        val importId = followUpDao.insertImport(
            FollowUpImportEntity(
                sourceUri = uri.toString(),
                displayName = displayName(uri),
                importDate = now,
                fileModifiedDate = lastModified(uri),
                schemaFingerprint = schemaFingerprint,
                workbookFingerprint = workbookFingerprint,
                isCurrent = true,
                groupCount = groups.size,
                entryCount = groups.sumOf { it.allRows.size }
            )
        )
        if (previous != null) followUpDao.clearCurrentImport()

        // Everything from the previous import is no longer part of the current
        // workbook until the fresh rows below re-assert it. Marking first means
        // a group that genuinely disappeared is archived rather than silently
        // left behind, and a group that still exists simply gets its flag set
        // again when its row is inserted.
        followUpDao.markAllAbsent(importId)

        // ---- contractors -------------------------------------------------
        val contractorIds = ContractorCache(contractorDao)
        val unresolved = HashSet<String>()

        // Persist the alias table so future imports and manual mapping reuse it.
        for (alias in supplierAliases) {
            val canon = alias.canonicalRaw.trim()
            if (canon.isEmpty()) continue
            val aliasNorm = TextNormalizer.normalize(alias.aliasRaw)
            if (aliasNorm.isEmpty()) continue
            val id = contractorIds.idFor(canon)
            if (contractorDao.aliasByNormalized(aliasNorm) == null) {
                contractorDao.insertAlias(
                    ContractorAliasEntity(
                        contractorId = id,
                        aliasRaw = alias.aliasRaw,
                        aliasNormalized = aliasNorm,
                        origin = "SUPPLIER_LIST"
                    )
                )
            }
        }

        // ---- groups and entries -----------------------------------------
        var projectCount = 0
        var entryCount = 0
        val projectIds = HashMap<String, Long>()

        for (g in groups) {
            val anchor = g.anchorRow
            val projectRaw = anchor.project

            var projectId: Long? = null
            if (!projectRaw.isNullOrBlank()) {
                val norm = TextNormalizer.normalize(projectRaw)
                projectId = projectIds.getOrPut(norm) {
                    followUpDao.projectIdByNormalized(norm) ?: run {
                        val newId = followUpDao.insertProject(
                            ProjectEntity(canonicalName = projectRaw.trim(), normalizedName = norm)
                        )
                        projectCount++
                        if (newId <= 0) {
                            followUpDao.projectByNormalized(norm)?.id ?: 0L
                        } else newId
                    }
                }
            }

            val rootCodeNorm = TextNormalizer.normalizeCode(anchor.code)
            // Unnamed columns still carry identity in this workbook: two anchor
            // rows can share a project and a truncated path and differ only in
            // such a column. Folding them in keeps those groups distinct.
            val auxText = anchor.extraCells.values
                .sorted()
                .joinToString("|")
                .takeIf { it.isNotBlank() }
            val stableKey = StableKeys.groupKey(
                projectRaw = projectRaw,
                area = anchor.area,
                relatedTo = anchor.relatedTo,
                number = anchor.number,
                rootCodeRaw = anchor.code,
                anchorPrPath = anchor.prPath,
                anchorOrderPath = anchor.orderPath,
                auxDiscriminator = auxText
            )

            // Look the group up by its stable key first. `stableKey` is not a
            // unique index (two different groups may legitimately collide on
            // content), so an IGNORE insert would always create a duplicate
            // row and orphan its entries.
            val existingGroup = followUpDao.groupByStableKey(stableKey)
            val reusedExisting = existingGroup != null
            val gid = existingGroup?.id ?: run {
                val inserted = followUpDao.insertGroup(
                    FollowUpGroupEntity(
                        stableKey = stableKey,
                        sourceImportId = importId,
                        originalExcelRow = anchor.excelRow,
                        number = anchor.number,
                        projectId = projectId,
                        projectRaw = projectRaw,
                        area = anchor.area,
                        relatedTo = anchor.relatedTo,
                        rootCodeRaw = anchor.code,
                        rootCodeNormalized = rootCodeNorm,
                        prDate = anchor.prDate,
                        notes = anchor.notes,
                        createdAt = now,
                        updatedAt = now
                    )
                )
                if (inserted > 0) inserted else followUpDao.groupByStableKey(stableKey)?.id
            }
            if (gid == null) continue

            if (reusedExisting) {
                // Existing group: re-assert presence and refresh only the
                // workbook-derived fields. `userTags`, `isFavorite` and
                // `manualProjectOverride` are the user's own data and are
                // deliberately not touched.
                followUpDao.reassertGroup(
                    id = gid,                    sourceImportId = importId,
                    originalExcelRow = anchor.excelRow,
                    number = anchor.number,
                    projectId = projectId,
                    projectRaw = projectRaw,
                    area = anchor.area,
                    relatedTo = anchor.relatedTo,
                    rootCodeRaw = anchor.code,
                    rootCodeNormalized = rootCodeNorm,
                    prDate = anchor.prDate,
                    notes = anchor.notes,
                    updatedAt = now
                )
            }

            // Every row of the group — anchor first — is an entry. This is what
            // turns the A/B/C rows into entries of ONE group.
            val signatures = ArrayList<String>(g.allRows.size)
            for ((ordinal, row) in g.allRows.withIndex()) {
                val contractorId = when (val res = normalizer.resolve(row.company)) {
                    is ContractorResolution.Resolved -> contractorIds.idFor(res.contractorRaw)
                    is ContractorResolution.Unresolved -> {
                        unresolved.add(res.contractorRaw); null
                    }
                    ContractorResolution.Blank -> null
                }

                val entrySig = StableKeys.entryKey(
                    groupStableKey = stableKey,
                    codeRaw = row.code,
                    prPath = row.prPath,
                    orderPath = row.orderPath,
                    companyRaw = row.company,
                    description = row.description,
                    prDate = row.prDate,
                    orderDate = row.orderDate,
                    ordinal = ordinal
                )
                signatures.add(entrySig)

                val inserted = followUpDao.insertEntry(
                    FollowUpEntryEntity(
                        groupId = gid,
                        sourceExcelRow = row.excelRow,
                        codeRaw = row.code,
                        codeNormalized = TextNormalizer.normalizeCode(row.code),
                        matchCodeKey = com.edib.procurement.matching.MatchNormalizer.codeKey(row.code),
                        prPathRaw = row.prPath,
                        orderPathRaw = row.orderPath,
                        companyRaw = row.company,
                        canonicalContractorId = contractorId,
                        description = row.description,
                        prDate = row.prDate,
                        orderDate = row.orderDate,
                        prWithEngineer = row.prWithEngineer,
                        notes = row.notes,
                        financialReceivedDate = row.financialReceivedDate,
                        finalApprovalDate = row.finalApprovalDate,
                        checkValue = row.checkValue,
                        oldCompanyRaw = row.oldCompany,
                        entryVariant = EntryVariant.derive(row.code, rootCodeNorm),
                        sourceSignature = entrySig
                    )
                )
                if (inserted > 0) entryCount++
            }

            if (reusedExisting) {
                // Entries whose signature the new import did not re-assert are
                // leftovers from an earlier version of the identity rules. They
                // are removed so a replacement cannot accumulate duplicates.
                // Manual links point at entry ids, so only rows that carry no
                // link are removed here.
                followUpDao.dropUnlinkedEntriesNotIn(gid, signatures)
            }
        }

        // Manual contractor corrections from a previous import are re-applied by
        // source signature, so replacing the workbook never loses a correction.
        val reapply = followUpDao.entriesWithManualContractor()
        for (e in reapply) {
            val aliasNorm = TextNormalizer.normalize(e.companyRaw ?: "")
            val contractorId = contractorDao.aliasByNormalized(aliasNorm)?.contractorId
            if (contractorId != null) {
                followUpDao.setManualContractor(
                    e.id,
                    contractorId
                )
            }
        }

        if (parsed.map.usedFallback) {
            warnings.add(
                "No recognised headers in '${ordersSheetName}'; used canonical field order. " +
                    "Check the column names before trusting this import."
            )
        }
        if (unresolved.isNotEmpty()) {
            warnings.add(
                "${unresolved.size} contractor cell(s) matched no supplier alias. " +
                    "Map them in CONTRACTORS to resolve them in future imports."
            )
        }
        if (supplierAliases.isEmpty()) {
            warnings.add("No SUPPLIER LIST aliases were read; contractors stay raw.")
        }

        // persist() is a suspend function with a block body, so an explicit return
        // is legal here.
        return ImportReport(
            groups = groups.size,
            entries = entryCount,
            projects = projectCount,
            contractors = contractorIds.size(),
            unresolvedContractors = unresolved.size,
            supplierAliases = normalizer.aliasCount,
            sheetsFound = sheetNames,
            recognizedFields = parsed.map.recognizedFields.map { it.name }.sorted(),
            unknownColumnHeaders = parsed.map.unknownColumns
                .map { it.headerLabel.trim() }.filter { it.isNotEmpty() }.distinct(),
            hyperlinkResolvedColumns = parsed.hyperlinkCells,
            continuationRows = groups.sumOf { it.continuationRows.size },
            warnings = warnings
        )
    }

    // ------------------------------------------------------------- helpers

/**
 * Chooses a sheet by its NAME, never by index: sheet order changes between
 * workbook versions.
 *
 * Both the name and the hints go through [TextNormalizer.normalize], which
 * uppercases. Comparing a normalised name against raw lowercase hints silently
 * fails to match, which previously left the SUPPLIER LIST sheet unread.
 */
private fun pickSheet(
        sheets: List<XlsxStreamReader.SheetInfo>,
        hints: List<String>
    ): XlsxStreamReader.SheetInfo? {
        val normalizedHints = hints.map { TextNormalizer.normalize(it) }
        sheets.forEach { s ->
            val n = TextNormalizer.normalize(s.name)
            if (normalizedHints.contains(n)) return s
        }
        sheets.forEach { s ->
            val n = TextNormalizer.normalize(s.name)
            if (normalizedHints.any { n.contains(it) }) return s
        }
        return null
    }

    fun displayName(uri: Uri): String =
        resolver().query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "workbook.xlsx"

    /**
     * Workbook modification time, best effort.
     *
     * Not every provider exposes DATE_MODIFIED through OpenableColumns, so this
     * falls back to the DocumentsContract column and finally to 0. A newer
     * workbook is detected by its content fingerprint regardless.
     */
    fun lastModified(uri: Uri): Long {
        // OpenableColumns.DATE_MODIFIED is only available from API 29 on some
        // providers; the literal column name is the portable query.
        queryLong(uri, "last_modified").takeIf { it > 0 }?.let { return it }
        return runCatching {
            val docId = DocumentsContract.getDocumentId(uri)
            queryLong(
                DocumentsContract.buildDocumentUri(uri.authority.orEmpty(), docId),
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )
        }.getOrDefault(0L)
    }

    private fun queryLong(uri: Uri, column: String): Long =
        resolver().query(uri, arrayOf(column), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        } ?: 0L
}
