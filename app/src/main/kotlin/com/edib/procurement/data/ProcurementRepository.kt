package com.edib.procurement.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import com.edib.procurement.database.AiPackDao
import com.edib.procurement.database.AiPackEntity
import com.edib.procurement.database.AiPackSourceEntity
import com.edib.procurement.database.AppSettingEntity
import com.edib.procurement.database.ContractorAliasEntity
import com.edib.procurement.database.ContractorDao
import com.edib.procurement.database.ContractorEntity
import com.edib.procurement.database.ContractorWithCount
import com.edib.procurement.database.ContractorDetail
import com.edib.procurement.database.ProjectDetail
import com.edib.procurement.database.DocumentDao
import com.edib.procurement.database.DocumentEntity
import com.edib.procurement.database.DocumentLinkEntity
import com.edib.procurement.database.DocumentListRow
import com.edib.procurement.database.EntryRow
import com.edib.procurement.database.FollowUpDao
import com.edib.procurement.database.FollowUpImportEntity
import com.edib.procurement.database.FollowUpGroupEntity
import com.edib.procurement.database.GroupListRow
import com.edib.procurement.database.HomeCounts
import com.edib.procurement.database.LinkedDocumentRow
import com.edib.procurement.database.ProjectWithCount
import com.edib.procurement.database.LinkDetailRow
import com.edib.procurement.database.LinkEntryRow
import com.edib.procurement.database.LinkTargetRow
import com.edib.procurement.database.Migrations
import com.edib.procurement.database.ProcurementDatabase
import com.edib.procurement.database.PackDao
import com.edib.procurement.database.ProjectEntity
import com.edib.procurement.database.RateCandidateRow
import com.edib.procurement.database.RateDraft
import com.edib.procurement.database.RateListRow
import com.edib.procurement.database.RateObservationEntity
import com.edib.procurement.database.RateScope
import com.edib.procurement.database.RateSourceChoice
import com.edib.procurement.database.RateStatus
import com.edib.procurement.database.normalizeRateItem
import com.edib.procurement.database.normalizeRateUnit
import com.edib.procurement.rates.RateCandidateScanProgress
import com.edib.procurement.rates.RateCandidateScanResult
import com.edib.procurement.rates.RateCandidateScanner
import com.edib.procurement.rates.RateCheckRequest
import com.edib.procurement.rates.RateCheckResult
import com.edib.procurement.rates.RateComparator
import com.edib.procurement.database.SuggestionRow
import com.edib.procurement.database.UnlinkedDocumentRow
import com.edib.procurement.database.UnresolvedContractorRow
import com.edib.procurement.database.UserTagEntity
import com.edib.procurement.documents.DocumentTreeScanner
import com.edib.procurement.documents.MasterWorkbook
import com.edib.procurement.documents.MasterWorkbookIdentity
import com.edib.procurement.documents.ScanProgress
import com.edib.procurement.followup.FollowUpWorkbookImporter
import com.edib.procurement.followup.ImportReport
import com.edib.procurement.followup.TextNormalizer
import com.edib.procurement.aipack.AiPackBuilder
import com.edib.procurement.aipack.CorpusValidator
import com.edib.procurement.aipack.ContentIndexer
import com.edib.procurement.aipack.ContentIndexingService
import com.edib.procurement.aipack.MultipartAiPackBuilder
import com.edib.procurement.aipack.MultipartResult
import com.edib.procurement.aipack.PackSizeEstimator
import com.edib.procurement.aipack.PackSplitConfig
import com.edib.procurement.aipack.StaleMultipartCleanup
import com.edib.procurement.aipack.AiPackRequest
import com.edib.procurement.aipack.ResolvedScope
import com.edib.procurement.aipack.PackNaming
import com.edib.procurement.aipack.PackDetail
import com.edib.procurement.aipack.PackInclude
import com.edib.procurement.aipack.PackPreview
import com.edib.procurement.aipack.PackProgress
import com.edib.procurement.aipack.PackResult
import com.edib.procurement.aipack.PackScope
import com.edib.procurement.aipack.PackScopeType
import com.edib.procurement.matching.MatchingEngine
import com.edib.procurement.matching.MatchingReport
import androidx.room.withTransaction
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Persisted library configuration. */
data class LibraryConfig(
    val followUpUri: String?,
    val followUpDisplayName: String?,
    val followUpImportedAt: Long,
    val treeUri: String?,
    val treeDisplayName: String?,
    val treeIndexedAt: Long,
    val documentCountAtScan: Int
)

/**
 * Single entry point for the UI. Everything disk-touching runs on IO inside
 * these methods, so no screen ever does storage work on the main thread.
 */
/**
 * Runs [block] on a scope that lives as long as the process.
 *
 * The repository has no lifecycle of its own, so a one-off maintenance task
 * such as a migration backfill is launched here rather than in a ViewModel.
 */
private fun runMaintenance(block: suspend () -> Unit) {
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { block() }
}

class ProcurementRepository(context: Context) {

    private val appContext = context.applicationContext
    private val db = ProcurementDatabase.getInstance(appContext)
    private val followUpDao = db.followUpDao()
    private val contractorDao = db.contractorDao()
    private val documentDao = db.documentDao()
    private val rateDao = db.rateDao()
    private val rateCandidateDao = db.rateCandidateDao()
    private val rateCandidateScanner = RateCandidateScanner(rateCandidateDao)

    private val importer = FollowUpWorkbookImporter(appContext, followUpDao, contractorDao)
    private val scanner = DocumentTreeScanner(appContext, documentDao)
    private val packDao: PackDao = db.packDao()
    private val aiPackDao: AiPackDao = db.aiPackDao()

    init {
        // Matching keys are derived in Kotlin, so they are backfilled once after
        // a migration rather than inside the SQL migration itself. Only rows
        // whose key is still empty are rewritten.
        runMaintenance {
        Migrations.backfillMatchKeys(followUpDao, documentDao)
        // A pack interrupted by a crash or a force-stop is discarded, so it can
        // never be presented as a finished pack.
        val cleared = cleanupIncompletePacks()
        if (cleared > 0) {
            android.util.Log.i("ProcurementPack", "cleared $cleared incomplete pack file(s)")
        }
    }
    }

    /**
     * Drops rows left behind by superseded imports.
     *
     * Re-importing the same workbook archives the previous groups, and the
     * archived rows are exact duplicates of the live ones. They are removed
     * only when a provenance check proves nothing of the user's is attached.
     */
    suspend fun purgeSupersededImports(): Migrations.PurgeReport = withContext(Dispatchers.IO) {
        val current = followUpDao.currentImport()?.id
            ?: return@withContext Migrations.PurgeReport(0, 0, 0)
        Migrations.purgeSupersededImportData(followUpDao, current)
    }

    companion object {
        private const val KEY_FOLLOWUP_URI = "library.followupUri"
        private const val KEY_FOLLOWUP_NAME = "library.followupName"
        private const val KEY_FOLLOWUP_DATE = "library.followupDate"
        private const val KEY_TREE_URI = "library.treeUri"
        private const val KEY_TREE_NAME = "library.treeName"
        private const val KEY_TREE_DATE = "library.treeDate"
        private const val KEY_TREE_COUNT = "library.treeCount"

        const val PAGE_SIZE = 200

        /** Normalized name of the placeholder used for ignored raw values. */
        const val IGNORED_CONTRACTOR_KEY = "(NOT A CONTRACTOR)"
    }

    /** No workbook selected yet: nothing to exclude. */
    private object MasterIdentity {
        val NONE = MasterWorkbookIdentity(null, null, null, 0)
    }

    // --------------------------------------------------------- persistence

    suspend fun takePersistableReadPermission(uri: Uri) {
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    suspend fun currentConfig(): LibraryConfig = withContext(Dispatchers.IO) {
        LibraryConfig(
            followUpUri = documentDao.setting(KEY_FOLLOWUP_URI)?.value,
            followUpDisplayName = documentDao.setting(KEY_FOLLOWUP_NAME)?.value,
            followUpImportedAt =
                documentDao.setting(KEY_FOLLOWUP_DATE)?.value?.toLongOrNull() ?: 0L,
            treeUri = documentDao.setting(KEY_TREE_URI)?.value,
            treeDisplayName = documentDao.setting(KEY_TREE_NAME)?.value,
            treeIndexedAt = documentDao.setting(KEY_TREE_DATE)?.value?.toLongOrNull() ?: 0L,
            documentCountAtScan =
                documentDao.setting(KEY_TREE_COUNT)?.value?.toIntOrNull() ?: 0
        )
    }

    private suspend fun putSetting(key: String, value: String) =
        documentDao.putSetting(AppSettingEntity(key, value))

    // -------------------------------------------------------------- import

    suspend fun importFollowUp(uri: Uri): ImportReport = withContext(Dispatchers.IO) {
        takePersistableReadPermission(uri)
        val report = importer.import(uri)
        // The import leaves the previous generation archived; drop it now that
        // the fresh one is in place, so repeated imports do not accumulate.
        purgeSupersededImports()
        putSetting(KEY_FOLLOWUP_URI, uri.toString())
        putSetting(KEY_FOLLOWUP_NAME, importer.displayName(uri))
        putSetting(KEY_FOLLOWUP_DATE, System.currentTimeMillis().toString())
        report
    }

    suspend fun currentImport(): FollowUpImportEntity? =
        withContext(Dispatchers.IO) { followUpDao.currentImport() }

    // ------------------------------------------------------------ scanning

    /**
 * Identity of the currently selected master workbook, used to keep it out of
 * the document index.
 *
 * The size is read from the SAF provider so the fallback comparisons (document
 * id, then content) have something concrete to work with.
 */
private suspend fun masterIdentity(): MasterWorkbookIdentity {
        val uri = documentDao.setting(KEY_FOLLOWUP_URI)?.value ?: return MasterIdentity.NONE
        val storedName = documentDao.setting(KEY_FOLLOWUP_NAME)?.value
        var resolvedName: String? = null

        // Ask the provider for the size rather than opening a descriptor: a
        // column query is cheaper and works for providers that refuse
        // openFileDescriptor without a persistable grant.
        var size = 0L
        runCatching {
            appContext.contentResolver.query(
                Uri.parse(uri),
                arrayOf(
                    android.provider.OpenableColumns.DISPLAY_NAME,
                    android.provider.OpenableColumns.SIZE
                ),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx =
                        cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                        resolvedName = cursor.getString(nameIdx)
                    }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                }
            }
        }

        if (size <= 0L) {
            runCatching {
                appContext.contentResolver.openFileDescriptor(Uri.parse(uri), "r")
                    ?.use { descriptor -> descriptor.statSize }
                    ?: 0L
            }.getOrNull()?.let { size = it }
        }

        return MasterWorkbook.from(uri, resolvedName ?: storedName, size)
    }

    suspend fun setDocumentTree(
        uri: Uri,
        displayName: String?
    ): Flow<ScanProgress> = withContext(Dispatchers.IO) {
        takePersistableReadPermission(uri)
        putSetting(KEY_TREE_URI, uri.toString())
        putSetting(KEY_TREE_NAME, displayName?.takeIf { it.isNotBlank() } ?: friendlyNameFromId(uri))
        scanner.scan(uri, master = masterIdentity())
    }

    suspend fun recordScanFinished(indexed: Int) = withContext(Dispatchers.IO) {
        putSetting(KEY_TREE_DATE, System.currentTimeMillis().toString())
        putSetting(KEY_TREE_COUNT, indexed.toString())
    }

/**
 * Re-indexes the already-selected folder.
 *
 * Rescanning must not require the user to pick the folder again: the
 * persisted URI permission is still held, so the stored tree is reused and
 * only re-read. If the grant has been revoked, the scan reports the failure
 * instead of silently indexing nothing.
 *
 * Returns the scan flow. The work runs on IO when the flow is collected.
 */
    suspend fun rescanDocumentTree(): Flow<ScanProgress> {
        val stored = documentDao.setting(KEY_TREE_URI)?.value
        if (stored.isNullOrBlank()) {
            throw IllegalStateException("No document folder has been selected yet.")
        }
        return scanner.scan(
            Uri.parse(stored),
            master = masterIdentity()
        )
    }

    /**
     * Last-resort display name for a picked folder.
     *
     * Never returns the raw SAF identifier: the trailing path segment is used
     * so the user sees "ProcurementDocs" rather than
     * "primary:Download/ProcurementDocs".
     */
    private fun friendlyNameFromId(uri: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return "Documents"
        return id.substringAfterLast(':').substringAfterLast('/').ifBlank { "Documents" }
    }

    suspend fun forgetDocumentTree() = withContext(Dispatchers.IO) {
        documentDao.setting(KEY_TREE_URI)?.let { documentDao.deleteByTree(it.value) }
        putSetting(KEY_TREE_URI, "")
        putSetting(KEY_TREE_NAME, "")
        putSetting(KEY_TREE_COUNT, "0")
    }

    // ---------------------------------------------------------------- read

    /** Partially assembled counters, combined pairwise as queries arrive. */
private data class Counts1(
    val projects: Int = 0,
    val groups: Int = 0,
    val entries: Int = 0,
    val documents: Int = 0
)

private data class Counts2(
    val base: Counts1,
    val contractors: Int,
    val unresolvedContractors: Int,
    val linkedDocuments: Int,
    val unlinkedDocuments: Int
)

/**
 * Live counters for HOME.
 *
 * Combined pairwise because `combine` only offers typed overloads up to five
 * flows. Each step names its fields, which keeps the chain readable and lets
 * the compiler infer every type.
 */
fun homeCounts(): Flow<HomeCounts> {
    var f: Flow<Counts1> = documentDao.projectCountFlow().map { Counts1(projects = it) }
    f = f.combine(documentDao.groupCountFlow()) { a, v -> a.copy(groups = v) }
    f = f.combine(documentDao.entryCountFlow()) { a, v -> a.copy(entries = v) }
    f = f.combine(documentDao.documentCountFlow()) { a, v -> a.copy(documents = v) }

    var g: Flow<Counts2> = f.map { Counts2(base = it, contractors = 0, unresolvedContractors = 0, linkedDocuments = 0, unlinkedDocuments = 0) }
    g = g.combine(documentDao.contractorCountFlow()) { a, v -> a.copy(contractors = v) }
    g = g.combine(documentDao.unresolvedContractorCountFlow()) { a, v -> a.copy(unresolvedContractors = v) }
    g = g.combine(documentDao.linkedDocumentCountFlow()) { a, v -> a.copy(linkedDocuments = v) }
    g = g.combine(documentDao.unlinkedCountFlow()) { a, v -> a.copy(unlinkedDocuments = v) }

    return g.combine(documentDao.suggestedCountFlow()) { a, suggested ->
        HomeCounts(
            projects = a.base.projects,
            groups = a.base.groups,
            entries = a.base.entries,
            documents = a.base.documents,
            linkedDocuments = a.linkedDocuments,
            unlinkedDocuments = a.unlinkedDocuments,
            suggestedLinks = suggested,
            unresolvedContractors = a.unresolvedContractors,
            contractors = a.contractors
        )
    }
}

    fun projects(): Flow<List<ProjectWithCount>> = documentDao.projectsFlow()

    fun contractorAliasCount(): Flow<Int> = documentDao.contractorAliasCountFlow()

    fun documentCount(): Flow<Int> = documentDao.documentCountFlow()

    fun groupCount(): Flow<Int> = documentDao.groupCountFlow()

    fun groups(projectId: Long?, query: String): Flow<List<GroupListRow>> =
        followUpDao.groupsFlow(projectId, query.trim())

    fun group(id: Long): Flow<FollowUpGroupEntity?> = followUpDao.groupById(id)

    fun entries(groupId: Long): Flow<List<EntryRow>> = followUpDao.entriesFlow(groupId)

    fun linkedDocuments(groupId: Long): Flow<List<LinkedDocumentRow>> =
        documentDao.linkedDocumentsFlow(groupId)

    fun documents(query: String, offset: Int = 0): Flow<List<DocumentListRow>> =
        documentDao.documentsFlow(query.trim(), PAGE_SIZE, offset)

    fun unlinkedDocuments(): Flow<List<UnlinkedDocumentRow>> =
        documentDao.unlinkedFlow(PAGE_SIZE)

    fun unlinkedCount(): Flow<Int> = documentDao.unlinkedCountFlow()

    fun contractors(): Flow<List<ContractorWithCount>> = contractorDao.contractorsFlow()

    fun unresolvedContractors(): Flow<List<String>> = contractorDao.unresolvedRawNamesFlow()

    fun imports(): Flow<List<FollowUpImportEntity>> = followUpDao.importsFlow()

    fun tags(): Flow<List<String>> = documentDao.tagsFlow()

    fun searchDocuments(query: String): Flow<List<DocumentListRow>> =
        documentDao.documentsFlow(query.trim(), PAGE_SIZE, 0)

    // ------------------------------------------------------------ trusted rates

    fun rates(query: String): Flow<List<RateListRow>> =
        rateDao.ratesFlow(query.trim())

    fun rateCount(): Flow<Int> = rateDao.countFlow()

    fun rateSourceDocuments(query: String): Flow<List<RateSourceChoice>> =
        rateDao.sourceDocuments(query.trim())

    fun rateCheckChoices(query: String): Flow<List<RateListRow>> =
        rateDao.awardedChoices(query.trim())

    fun rateCandidates(query: String, scope: String = "", minConfidence: Int = 0, sort: String = "CONFIDENCE"): Flow<List<RateCandidateRow>> =
        rateCandidateDao.pendingFlow(query.trim(), scope, minConfidence, sort)

    fun rateCandidateCount(): Flow<Int> = rateCandidateDao.pendingCountFlow()

    suspend fun scanRateCandidates(
        onProgress: (RateCandidateScanProgress) -> Unit = {}
    ): RateCandidateScanResult = withContext(Dispatchers.IO) {
        rateCandidateScanner.scan(onProgress)
    }

    suspend fun dismissRateCandidate(id: Long) = withContext(Dispatchers.IO) {
        rateCandidateDao.dismiss(id, System.currentTimeMillis())
    }

    /**
     * Persists one commercial observation without inventing any context.
     *
     * Quoted, approved and awarded values require a source document. OBSERVED is
     * the only status allowed without a source, and stays visibly distinct in
     * the ledger. Source filename/path/URI are snapshotted at save time so the
     * evidence trail remains intelligible if the file later moves or disappears.
     */
    suspend fun addRate(draft: RateDraft): Long = withContext(Dispatchers.IO) {
        val item = draft.itemRaw.trim()
        val unit = draft.unit.trim()
        require(item.isNotEmpty()) { "Item description is required" }
        require(unit.isNotEmpty()) { "Unit is required" }
        require(draft.rate.isFinite() && draft.rate > 0.0) { "Rate must be greater than zero" }
        require(draft.quantity == null || (draft.quantity.isFinite() && draft.quantity >= 0.0)) {
            "Quantity must be zero or greater"
        }
        require(draft.observedDate > 0L) { "Rate date is required" }
        require(draft.status in RateStatus.all) { "Unknown commercial status" }
        require(draft.scope in RateScope.all) { "Unknown commercial scope" }
        if (draft.status in RateStatus.sourceRequired) {
            require(draft.sourceDocumentId != null) {
                "${draft.status.lowercase().replaceFirstChar { it.titlecase() }} rates need a source document"
            }
        }

        val source = draft.sourceDocumentId?.let { documentDao.byId(it) }
        if (draft.sourceDocumentId != null && source == null && draft.status in RateStatus.sourceRequired) {
            throw IllegalArgumentException("Selected source document is no longer available")
        }

        val currency = draft.currency.trim().uppercase()
        require(currency.isNotEmpty()) { "Currency is required" }

        val now = System.currentTimeMillis()
        db.withTransaction {
            val savedRateId = rateDao.insert(
                RateObservationEntity(
                    itemRaw = item,
                    itemNormalized = normalizeRateItem(item),
                    material = draft.material?.trim()?.takeIf { it.isNotEmpty() },
                    specification = draft.specification?.trim()?.takeIf { it.isNotEmpty() },
                    dimensions = draft.dimensions?.trim()?.takeIf { it.isNotEmpty() },
                    unit = unit,
                    scope = draft.scope,
                    contractorId = draft.contractorId,
                    contractorRaw = draft.contractorRaw?.trim()?.takeIf { it.isNotEmpty() },
                    projectId = draft.projectId,
                    projectRaw = draft.projectRaw?.trim()?.takeIf { it.isNotEmpty() },
                    location = draft.location?.trim()?.takeIf { it.isNotEmpty() },
                    quantity = draft.quantity,
                    rate = draft.rate,
                    currency = currency,
                    status = draft.status,
                    observedDate = draft.observedDate,
                    sourceDocumentId = source?.id,
                    sourceEntryId = draft.sourceEntryId,
                    sourceFileNameSnapshot = source?.displayName,
                    sourceRelativePathSnapshot = source?.relativePath,
                    sourceUriSnapshot = source?.documentUri,
                    evidenceLocator = draft.evidenceLocator?.trim()?.takeIf { it.isNotEmpty() },
                    evidenceText = draft.evidenceText?.trim()?.takeIf { it.isNotEmpty() },
                    notes = draft.notes?.trim()?.takeIf { it.isNotEmpty() },
                    createdAt = now,
                    updatedAt = now
                )
            )
            draft.candidateId?.let { candidateId ->
                rateCandidateDao.confirm(candidateId, savedRateId, now)
            }
            savedRateId
        }
    }

    suspend fun latestComparableAwarded(
        item: String,
        unit: String,
        scope: String,
        contractorId: Long? = null
    ): RateListRow? = withContext(Dispatchers.IO) {
        rateDao.latestComparableAwarded(
            itemNormalized = normalizeRateItem(item),
            unitNormalized = normalizeRateUnit(unit),
            scope = scope,
            contractorId = contractorId
        )
    }

    /**
     * Checks a proposed rate against trusted awarded history.
     *
     * The DAO prefilters exact item/unit/scope/currency. RateComparator then
     * applies the stricter material/specification/dimensions gate and chooses
     * contractor history before context-ranked comparables.
     */
    suspend fun checkRate(request: RateCheckRequest): RateCheckResult =
        withContext(Dispatchers.IO) {
            val candidates = rateDao.awardedCandidates(
                itemNormalized = normalizeRateItem(request.itemRaw),
                scope = request.scope,
                currency = request.currency.trim().uppercase()
            )
            RateComparator.compare(request, candidates)
        }

    suspend fun deleteRate(id: Long) = withContext(Dispatchers.IO) {
        rateDao.delete(id)
    }

    // ------------------------------------------------------------ mutation

    suspend fun setFavorite(groupId: Long, favorite: Boolean) =
        withContext(Dispatchers.IO) { followUpDao.setFavorite(groupId, favorite) }

    suspend fun setTags(groupId: Long, tags: List<String>) =
        withContext(Dispatchers.IO) {
            val ids = tags.map { name ->
                documentDao.tagId(name) ?: documentDao.insertTag(UserTagEntity(name = name.trim())).let { if (it > 0) it else documentDao.tagId(name) ?: 0L }
            }
            followUpDao.setGroupTags(groupId, ids.joinToString(","), System.currentTimeMillis())
        }

    /**
     * Remembers a manual contractor mapping, both on the entry and as a global
     * alias so later imports of the same raw text resolve automatically.
     */
    suspend fun mapContractor(
        entryId: Long,
        rawCompany: String,
        contractorId: Long?
    ) = withContext(Dispatchers.IO) {
        followUpDao.setManualContractor(entryId, contractorId)
        if (contractorId != null && !rawCompany.isNullOrBlank()) {
            val norm = TextNormalizer.normalize(rawCompany)
            if (norm.isNotEmpty() && contractorDao.aliasByNormalized(norm) == null) {
                contractorDao.insertAlias(
                    ContractorAliasEntity(
                        contractorId = contractorId,
                        aliasRaw = rawCompany.trim(),
                        aliasNormalized = norm,
                        origin = "MANUAL"
                    )
                )
            }
        }
    }

    /**
     * Marks a raw contractor string as "not a contractor".
     *
     * Stored as an alias pointing at the special IGNORED contractor so the value
     * stops appearing in the review queue without deleting the workbook text.
     */
    suspend fun ignoreContractorValue(raw: String) = withContext(Dispatchers.IO) {
        val normalized = TextNormalizer.normalize(raw)
        if (normalized.isEmpty()) return@withContext
        val ignoredId = contractorDao.idByNormalized(IGNORED_CONTRACTOR_KEY)
            ?: contractorDao.insertContractor(
                ContractorEntity(
                    canonicalName = "(not a contractor)",
                    normalizedName = IGNORED_CONTRACTOR_KEY,
                    isUserCreated = true
                )
            ).let { if (it > 0) it else contractorDao.idByNormalized(IGNORED_CONTRACTOR_KEY) ?: 0L }
        if (ignoredId > 0) {
            val existing = followUpDao.entriesWithRawCompany(raw)
            existing.forEach { followUpDao.setManualContractor(it.id, ignoredId) }
        }
    }

    suspend fun createContractor(name: String): Long = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        val norm = TextNormalizer.normalize(trimmed)
        contractorDao.idByNormalized(norm) ?: contractorDao.insertContractor(
            ContractorEntity(canonicalName = trimmed, normalizedName = norm, isUserCreated = true)
        ).let { if (it > 0) it else contractorDao.idByNormalized(norm) ?: 0L }
    }

    suspend fun allContractors(): List<ContractorEntity> =
        withContext(Dispatchers.IO) { contractorDao.allContractors() }

    /**
     * Creates a manual link. Manual links are locked, so a rescan or a re-import
     * can never remove or downgrade them.
     */
    suspend fun linkDocument(
        documentId: Long,
        groupId: Long?,
        entryId: Long?,
        linkType: String,
        evidence: String?
    ) = withContext(Dispatchers.IO) {
        documentDao.insertLink(
            DocumentLinkEntity(
                documentId = documentId,
                groupId = groupId,
                entryId = entryId,
                linkType = linkType,
                matchMethod = "MANUAL",
                confidence = 100,
                evidence = evidence,
                isManual = true,
                isLocked = true,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * Removes one link. Manual links are the user's own decision, so this is an
     * explicit action; the rescan path uses deleteAutomaticLinksFor, which
     * cannot touch a manual or locked row.
     */
    suspend fun unlink(documentLinkId: Long) = withContext(Dispatchers.IO) {
        documentDao.deleteLink(documentLinkId)
    }

    // ------------------------------------------------------ database paging

    /**
     * Database-backed pagers.
     *
     * Every long list is a [Pager] over a Room query, so the UI never holds the
     * whole table in memory and a search or filter still runs against every row
     * rather than whatever page happens to be loaded. A new search term yields
     * a new Flow, which Paging 3 uses to cancel the stale in-flight queries.
     */
    fun projectsPager(query: String): Flow<PagingData<ProjectWithCount>> =
        pager { followUpDao.projectsPagingSource(query.trim()) }

    fun groupsPager(projectId: Long?, query: String): Flow<PagingData<GroupListRow>> =
        pager { followUpDao.groupsPagingSource(projectId, query.trim()) }

    fun documentsPager(query: String): Flow<PagingData<DocumentListRow>> =
        pager { followUpDao.documentsPagingSource(query.trim()) }

    fun contractorsPager(query: String): Flow<PagingData<ContractorWithCount>> =
        pager { followUpDao.contractorsPagingSource(query.trim()) }

    fun linkEntriesPager(query: String): Flow<PagingData<LinkEntryRow>> =
        pager { followUpDao.linkEntriesPagingSource(query.trim()) }

    fun linkGroupsPager(query: String): Flow<PagingData<LinkTargetRow>> =
        pager { followUpDao.linkGroupsPagingSource(query.trim()) }

    fun entriesPager(groupId: Long, query: String): Flow<PagingData<EntryRow>> =
        pager { followUpDao.entriesPagingSource(groupId, query.trim()) }

    private fun <T : Any> pager(factory: () -> PagingSource<Int, T>): Flow<PagingData<T>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = PAGE_SIZE,
                prefetchDistance = PAGE_SIZE / 2,
                enablePlaceholders = false
            ),
            pagingSourceFactory = factory
        ).flow

    // ------------------------------------------------------------ AI Pack

    // ------------------------------------------------------------ AI Pack

    /**
     * What a pack would contain, for the configuration sheet.
     *
     * Counts come from the database, never from whatever the UI happens to have
     * paged in, so the estimate matches what will actually be built.
     */
    /**
 * The indexed corpus, reduced to what a validation report needs.
     *
     * Counts come from the database, so the report reflects what the app has
     * actually indexed rather than what happens to be on disk.
     */
    suspend fun corpusDocuments(): List<CorpusValidator.CorpusDocument> =
        withContext(Dispatchers.IO) {
            documentDao.allForValidation().map {
                CorpusValidator.CorpusDocument(
                    id = it.id,
                    displayName = it.displayName,
                    extension = it.extension ?: "",
                    size = it.size,
                    linkCount = it.linkCount,
                    documentUri = it.documentUri
                )
            }
        }

    /** Opens a source read-only and reports how PDF extraction went. */
    suspend fun probePdf(uriString: String): CorpusValidator.Outcome =
        withContext(Dispatchers.IO) {
            val input = openRead(uriString) ?: return@withContext CorpusValidator.Outcome.FAILED
            input.use { stream ->
                when (val r = com.edib.procurement.aipack.PdfTextExtractor(appContext).extract(stream)) {
                    is com.edib.procurement.aipack.PdfTextExtractor.Result.Text ->
                        if (r.pages.any { page -> page.isNotBlank() })
                            CorpusValidator.Outcome.TEXT
                        else CorpusValidator.Outcome.NO_TEXT
                    is com.edib.procurement.aipack.PdfTextExtractor.Result.NeedsOcr ->
                        CorpusValidator.Outcome.NO_TEXT
                    is com.edib.procurement.aipack.PdfTextExtractor.Result.Failed ->
                        CorpusValidator.Outcome.FAILED
                }
            }
        }

    suspend fun probeDocx(uriString: String): CorpusValidator.Outcome =
        withContext(Dispatchers.IO) {
            val input = openRead(uriString) ?: return@withContext CorpusValidator.Outcome.FAILED
            input.use {
                val doc = runCatching {
                    com.edib.procurement.aipack.DocxTextReader().read(it)
                }.getOrNull()
                when {
                    doc == null -> CorpusValidator.Outcome.FAILED
                    doc.isEmpty -> CorpusValidator.Outcome.NO_TEXT
                    else -> CorpusValidator.Outcome.TEXT
                }
            }
        }

    suspend fun probeWorkbook(uriString: String): CorpusValidator.Outcome =
        withContext(Dispatchers.IO) {
            val input = openRead(uriString) ?: return@withContext CorpusValidator.Outcome.FAILED
            input.use {
                var sawData = false
                // A counting sink: the report only needs to know whether the
                // workbook opens and yields any cells, not what they contain.
                val sink = object : com.edib.procurement.aipack.OpenXmlWorkbookReader.SheetSink {
                    override fun startSheet(name: String, maxColumn: Int, mergedRanges: List<String>) {}
                    override fun row(cells: List<com.edib.procurement.aipack.OpenXmlWorkbookReader.Cell>) {
                        if (cells.any { c -> c.value.isNotBlank() }) sawData = true
                    }
                    override fun endSheet() {}
                }
                val ok = runCatching {
                    com.edib.procurement.aipack.OpenXmlWorkbookReader().read(it, sink, true)
                    true
                }.getOrDefault(false)
                when {
                    !ok -> CorpusValidator.Outcome.FAILED
                    sawData -> CorpusValidator.Outcome.TEXT
                    else -> CorpusValidator.Outcome.NO_TEXT
                }
            }
        }

    private fun openRead(uriString: String) = runCatching {
        appContext.contentResolver.openInputStream(android.net.Uri.parse(uriString))
    }.getOrNull()

    /**
     * Builds the corpus validation report.
     *
     * The probes reuse the SAME extractors the pack builder uses, so a document
     * counted as text-readable here is text-readable in a real pack. Nothing is
     * OCR'd: an image-only PDF is reported as OCR_REQUIRED, which is the
     * evidence needed to justify a later OCR decision rather than a claim that
     * one happened.
     */
    suspend fun validateCorpus(
        documents: List<CorpusValidator.CorpusDocument>,
        probe: suspend (CorpusValidator.CorpusDocument) -> CorpusValidator.Outcome
    ): CorpusValidator.Report = withContext(Dispatchers.IO) {
        val tree = currentConfig().treeDisplayName
        com.edib.procurement.aipack.CorpusValidator(File(appContext.filesDir, "corpus"))
            .report(documents, { d -> runBlocking { probe(d) } }, tree)
    }

    /**
     * Builds the scope as a multipart export: one index plus N parts.
     *
     * The generator is shared with the single-pack path, so a split export and a
     * single export of the same scope extract identically.
     */
    suspend fun buildMultipart(
        scope: PackScope,
        config: PackSplitConfig,
        include: PackInclude,
        detail: PackDetail,
        onProgress: (PackProgress) -> Unit,
        /** True when the plan resolved to a single part. */
        onCollapsedToSingle: suspend () -> Unit
    ): MultipartResult = withContext(Dispatchers.IO) {
        val single = AiPackBuilder(appContext, packDao, aiPackDao, db.documentDao(), appContext.filesDir)
        val builder = MultipartAiPackBuilder(appContext, packDao, aiPackDao, db.documentDao(), appContext.filesDir, single)
        val result = builder.build(scope, config, include, detail, onProgress)
        // "Export: MULTIPART / PART 01 of 1" is meaningless. When the planner
        // produced exactly one part there is nothing to split, so the export is
        // emitted as an ordinary single pack instead of a one-part multipart
        // lifecycle with its own INDEX.
        if (result.partFileNames.size <= 1) {
            onCollapsedToSingle()
        }
        result
    }

    /**
     * Predicts the final AI Pack size before generating it.
     *
     * The prediction is in the units the user uploads: normalized text is
     * produced once, measured, DEFLATE-measured and then discarded. That costs
     * one extraction pass to make the number trustworthy, and the generation
     * afterwards still extracts exactly once itself - this is an advisory
     * preview, never a substitute for the real build.
     */
    suspend fun estimatePack(
        scope: PackScope,
        targetPartBytes: Long,
        include: PackInclude = PackInclude(),
        detail: PackDetail = PackDetail.COMPACT
    ): PackSizeEstimator.Prediction = withContext(Dispatchers.IO) {
        val entries = packDao.entriesOfGroups(scope.groupIds)
        val entryIds = entries.map { it.id }
        val linked = packDao.linkedDocuments(scope.groupIds, entryIds)
        val explicit =
            if (scope.documentIds.isEmpty()) emptyList()
            else packDao.documentsByIds(scope.documentIds)
        val docs = (linked + explicit).associateBy { it.id }.values.toList()
        val links = packDao.linksForDocuments(docs.map { it.id }).groupBy { it.documentId }

        val spool = com.edib.procurement.aipack.NormalizedSpool(
            File(appContext.filesDir, "spool-estimate")
        )
        try {
            val single = AiPackBuilder(appContext, packDao, aiPackDao, db.documentDao(), appContext.filesDir)
            docs.forEachIndexed { i, d ->
                val f = spool.create(d.id, "EST")
                val sink = com.edib.procurement.aipack.PackTextWriter(
                    f.outputStream().buffered(64 * 1024)
                )
                single.writeSourceBlock(
                    sink, d, links[d.id].orEmpty(), "EST", include, detail,
                    onSource = {}
                )
                sink.flush()
                sink.close()
                spool.register(d.id, "EST", f)
            }
            val meta = com.edib.procurement.aipack.normalizedTextOverheadEstimate(
                packDao.groupsByIds(scope.groupIds).size, entries.size
            )
            val metaBytes = (meta.groupHeader + meta.entry) *
                (packDao.groupsByIds(scope.groupIds).size + entries.size)
            PackSizeEstimator.predict(
                originalBytes = docs.sumOf { it.size },
                normalizedBytes = spool.totalNormalizedBytes + metaBytes * 3,
                predictedCompressedBytes = spool.totalPredictedZipBytes + metaBytes,
                targetPartBytes = targetPartBytes
            )
        } finally {
            // The spool is scratch space; a preview must leave nothing behind.
            spool.clear()
        }
    }

    suspend fun packPreview(scope: PackScope): PackPreview = withContext(Dispatchers.IO) {
        val groups = packDao.groupsByIds(scope.groupIds)
        val allEntries =
            if (scope.groupIds.isEmpty()) emptyList()
            else packDao.entriesOfGroups(scope.groupIds)
        val entries =
            if (scope.entryIds.isEmpty()) allEntries else allEntries.filter { it.id in scope.entryIds }

        val linked = packDao.linkedDocuments(scope.groupIds, entries.map { it.id })
        val explicit =
            if (scope.documentIds.isEmpty()) emptyList()
            else packDao.documentsByIds(scope.documentIds)
        val docs = (linked + explicit).associateBy { it.id }.values.toList()

        PackPreview(
            groups = groups.size,
            entries = entries.size,
            linkedDocuments = linked.size,
            selectedDocuments = docs.size,
            originalBytes = docs.sumOf { it.size }
        )
    }

/** Builds a pack. Sources are only ever read. */
    suspend fun buildPack(
        scope: PackScope,
        include: PackInclude,
        detail: PackDetail,
        zip: Boolean,
        onProgress: (PackProgress) -> Unit = {}
    ): PackResult = withContext(Dispatchers.IO) {
        AiPackBuilder(appContext, packDao, db.aiPackDao(), db.documentDao(), appContext.filesDir)
            .build(scope, include, detail, zip, onProgress)
    }

    // ------------------------------------------------------- pack history

    val packHistory: Flow<List<AiPackEntity>> = aiPackDao.packsFlow()

    /** One-shot list of recorded packs, used by the stale-export cleanup. */
    suspend fun allPackRecords(): List<AiPackEntity> = withContext(Dispatchers.IO) {
        aiPackDao.withPaths()
    }

    val packStorageBytes: Flow<Long> = aiPackDao.totalBytesFlow()

    fun packSources(packId: Long): Flow<List<AiPackSourceEntity>> =
        aiPackDao.sourcesFlow(packId)

    /**
     * Deletes one generated export and its history row.
     *
     * A MULTIPART row records only the index path, so deleting just that would
     * orphan every part file beside it. The whole export set is removed here:
     * the index, every `_<nn>.zip` sharing its stem, and the history row.
     *
     * Only files this app produced are touched. No source document is deleted:
     * packs live in the app's own storage and are separate files.
     */
    suspend fun deletePack(id: Long) = withContext(Dispatchers.IO) {
        val pack = aiPackDao.pack(id) ?: return@withContext
        val dir = File(appContext.filesDir, "aipacks")

        runCatching { java.io.File(pack.absolutePath).delete() }

        if (pack.scopeType == "MULTIPART") {
            val index = java.io.File(pack.absolutePath)
            val stem = index.name.removeSuffix("_INDEX.md")
            // A part of THIS export is exactly "<stem>_<nnn>.zip". Matching the
            // whole name is what stops a longer sibling export, whose stem
            // merely begins with this one, from being deleted with it.
            val partSuffix = Regex("_\\d{2,3}\\.(zip|md)$")
            runCatching {
                dir.listFiles()?.forEach { f ->
                    if (f.name == index.name) return@forEach
                    val suffix = partSuffix.find(f.name)?.value ?: return@forEach
                    if (f.name == stem + suffix) f.delete()
                }
            }
        }

        aiPackDao.deleteSources(id)
        aiPackDao.delete(id)
    }

    suspend fun deleteAllPacks() = withContext(Dispatchers.IO) {
        val dir = File(appContext.filesDir, "aipacks")
        runCatching { dir.listFiles()?.forEach { it.delete() } }
        aiPackDao.deleteAllSources()
        aiPackDao.deleteAll()
    }

    /**
     * Removes half-written packs left behind by a crash or a force-stop.
     *
     * Generation writes to a ".part" file and only renames it when complete, so
     * anything still carrying the marker never became a real pack. Running this
     * at startup means an interrupted run can never be mistaken for a finished
     * one.
     *
     * @return how many incomplete files were cleared
     */
    suspend fun cleanupIncompletePacks(): Int = withContext(Dispatchers.IO) {
        val dir = File(appContext.filesDir, "aipacks")
        if (!dir.isDirectory) return@withContext 0
        var cleared = 0
        dir.listFiles()?.forEach { f ->
            if (f.name.endsWith(PackNaming.TEMP_SUFFIX) ||
                (f.name.startsWith(".") && f.name.contains(PackNaming.TEMP_SUFFIX))
            ) {
                if (f.delete()) cleared++
            }
        }
        // A history row whose file has vanished is left alone; the UI reports it
        // as FILE MISSING rather than deleting the user's record silently.
        cleared
    }

    /** Content URI for sharing a generated pack, or null when it is gone. */
    fun shareUriFor(file: File): Uri? = runCatching {
        androidx.core.content.FileProvider.getUriForFile(
            appContext, "${appContext.packageName}.fileprovider", file
        )
    }.getOrNull()

    suspend fun scopeForProject(projectId: Long): PackScope = withContext(Dispatchers.IO) {
        PackScope(
            type = PackScopeType.PROJECT,
            name = packDao.projectName(projectId) ?: "Project",
            groupIds = packDao.groupIdsOfProject(projectId)
        )
    }

    suspend fun scopeForContractor(contractorId: Long): PackScope =
        withContext(Dispatchers.IO) {
            PackScope(
                type = PackScopeType.CONTRACTOR,
                name = packDao.contractorName(contractorId) ?: "Contractor",
                groupIds = packDao.groupIdsForContractor(contractorId)
            )
        }

    suspend fun scopeForGroup(groupId: Long): PackScope = withContext(Dispatchers.IO) {
        val g = packDao.groupsByIds(listOf(groupId)).firstOrNull()
        PackScope(
            type = PackScopeType.GROUP,
            name = g?.rootCodeRaw ?: "Group",
            groupIds = listOf(groupId)
        )
    }

    /**
     * Turns a caller's [AiPackRequest] into a concrete [PackScope].
     *
     * A PROJECT or CONTRACTOR request names one id and is expanded here into
     * the groups it owns, so the generator itself only ever deals with explicit
     * group/entry/document lists.
     */
    suspend fun resolveRequest(request: AiPackRequest): PackScope = withContext(Dispatchers.IO) {
        // A contractor is pinned to its own entries, not to whole groups: the
        // groups are kept so context survives, but only that contractor's
        // entries are exported.
        val contractorEntries =
            if (request.scopeType == PackScopeType.CONTRACTOR && request.id != null) {
                packDao.entryIdsForContractor(request.id)
            } else {
                emptyList()
            }
        val groups = when {
            request.scopeType == PackScopeType.PROJECT ->
                request.projectIds.flatMap { packDao.groupIdsOfProject(it) }
            request.scopeType == PackScopeType.CONTRACTOR ->
                request.id?.let { packDao.groupIdsForContractor(it) } ?: request.groupIds
            else -> request.groupIds
        }.distinct()
        request.toScope(
            ResolvedScope(
                groupIds = groups,
                entryIds = contractorEntries.ifEmpty { request.entryIds }
            )
        )
    }

    /** Detail rows for the Project / Contractor screens. */
    suspend fun projectDetail(id: Long): ProjectDetail? = withContext(Dispatchers.IO) {
        followUpDao.projectDetail(id)
    }

    suspend fun contractorDetail(id: Long): ContractorDetail? = withContext(Dispatchers.IO) {
        followUpDao.contractorDetail(id)
    }

    fun groupsOfProjectPager(projectId: Long, query: String): Flow<PagingData<GroupListRow>> =
        pager { followUpDao.groupsOfProjectPagingSource(projectId, query.trim()) }

    /**
     * Ids of every group the current query matches.
     *
     * "Select all results" has to mean the whole database result, not the rows
     * Paging has happened to load, so this runs the same filter as the list and
     * returns ids only. Reading a projection of the primary key keeps a
     * multi-thousand-row selection cheap; the full entities are never loaded
     * just to count what the user picked.
     */
    suspend fun allGroupIdsForQuery(query: String): List<Long> = withContext(Dispatchers.IO) {
        followUpDao.allGroupIds(query.trim())
    }

    suspend fun allDocumentIdsForQuery(query: String): List<Long> =
        withContext(Dispatchers.IO) {
            followUpDao.allDocumentIds(query.trim())
        }

    // ------------------------------------------------------------ content indexing

    /** Gets the content indexer instance. */
    val contentIndexer: ContentIndexer = ContentIndexer(appContext, documentDao)

    /** Gets the content indexing service instance. */
    val contentIndexingService: ContentIndexingService = ContentIndexingService(appContext, documentDao, contentIndexer)

    /** Starts content indexing for all NOT_INDEXED and STALE documents. */
    fun startContentIndexing() = contentIndexingService.start()

    /** Cancels the current content indexing operation. */
    fun cancelContentIndexing() = contentIndexingService.cancel()

    /** Runs OCR fallback for documents that need it. */
    fun runOcrFallback() = contentIndexingService.runOcrFallback()

    /** Current progress of content indexing. */
    val contentIndexingProgress = contentIndexingService.progress

    /**
     * Groups of a project, walked a page at a time.
     *
     * Used when scoping a pack: the preview has to know the real totals, and it
     * must do so without materialising the whole library.
     */
    suspend fun groupsOfProject(projectId: Long): List<GroupListRow> = withContext(Dispatchers.IO) {
        val out = ArrayList<GroupListRow>()
        var offset = 0
        while (true) {
            val page = followUpDao.groupsPageOnce(projectId, PAGE_SIZE, offset)
            if (page.isEmpty()) break
            out.addAll(page)
            offset += page.size
            if (page.size < PAGE_SIZE) break
        }
        out
    }

    suspend fun entriesOfGroup(groupId: Long): List<EntryRow> = withContext(Dispatchers.IO) {
        packDao.entriesOfGroup(groupId)
    }

    /** Groups holding at least one entry attributed to this contractor. */
    suspend fun groupsForContractor(contractorId: Long): List<GroupListRow> =
        withContext(Dispatchers.IO) {
            val ids = packDao.groupIdsForContractor(contractorId)
            if (ids.isEmpty()) emptyList()
            else followUpDao.groupListByIds(ids)
        }

    /**
     * Runs deterministic matching over the indexed documents.
     *
     * Manual links are preserved by the engine itself, so this is safe to call
     * after every scan, every import and every manual contractor mapping.
     */
    suspend fun runMatching(): MatchingReport = withContext(Dispatchers.IO) {
        MatchingEngine(followUpDao, documentDao).run()
    }

    fun suggestions(): Flow<List<SuggestionRow>> = documentDao.suggestionsFlow()

    fun unlinkedOnly(): Flow<List<SuggestionRow>> = documentDao.unlinkedOnlyFlow()

    fun unresolvedByUsage(): Flow<List<UnresolvedContractorRow>> =
        followUpDao.unresolvedContractorsByUsageFlow(60)

    fun searchLinkTargets(query: String): Flow<List<LinkTargetRow>> =
        documentDao.searchGroupsForLinking(query.trim())

    fun searchLinkEntries(query: String): Flow<List<LinkEntryRow>> =
        documentDao.searchEntriesForLinking(query.trim())

    fun linksWithDetail(documentId: Long): Flow<List<LinkDetailRow>> =
        documentDao.linksWithDetail(documentId)

    /**
     * Records a manual link, replacing whatever the matcher had produced.
     *
     * The manual row is locked, so neither a rescan nor a re-import nor a
     * later matching run can remove or downgrade it.
     */
    suspend fun linkManually(
        documentId: Long,
        groupId: Long?,
        entryId: Long?,
        note: String?
    ) = withContext(Dispatchers.IO) {
        // Drop the automatic rows for this document, then write the manual one.
        documentDao.deleteAutomaticLinksFor(documentId)
        documentDao.insertLink(
            DocumentLinkEntity(
                documentId = documentId,
                groupId = groupId,
                entryId = entryId,
                linkType = if (entryId != null) "ORDER" else "RELATED",
                matchMethod = "MANUAL",
                confidence = 100,
                evidence = note?.takeIf { it.isNotBlank() }
                    ?: "linked by hand${entryId?.let { " to entry $it" } ?: ""}",
                isManual = true,
                isLocked = true,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    /** Accepts a suggestion, turning it into a locked manual link. */
    suspend fun acceptSuggestion(linkId: Long) = withContext(Dispatchers.IO) {
        val link = documentDao.linkById(linkId) ?: return@withContext
        documentDao.updateLink(
            link.copy(isManual = true, isLocked = true, matchMethod = "MANUAL_ACCEPTED")
        )
        // Only this document's other automatic rows go; the accepted row stays.
        documentDao.deleteOtherAutomaticLinksFor(link.documentId, linkId)
    }

    suspend fun removeLink(linkId: Long) = withContext(Dispatchers.IO) {
        documentDao.deleteLinkById(linkId)
    }

    /**
     * Maps a raw contractor string to a canonical contractor, creating the
     * contractor if it does not exist.
     *
     * Both the existing entries and a persistent [ContractorAlias] are updated,
     * so a later import of the same raw text resolves automatically.
     */
    suspend fun mapContractorRaw(raw: String, canonical: String) =
        withContext(Dispatchers.IO) {
            val trimmed = canonical.trim()
            if (trimmed.isEmpty()) return@withContext

            val id = createContractor(trimmed)
            if (id <= 0) return@withContext

            followUpDao.entriesWithRawCompany(raw).forEach {
                followUpDao.setManualContractor(it.id, id)
            }

            // Persist the alias so future imports reuse the decision.
            val norm = TextNormalizer.normalize(raw)
            if (norm.isNotEmpty() && contractorDao.aliasByNormalized(norm) == null) {
                contractorDao.insertAlias(
                    ContractorAliasEntity(
                        contractorId = id,
                        aliasRaw = raw.trim(),
                        aliasNormalized = norm,
                        origin = "MANUAL"
                    )
                )
            }
        }

    suspend fun documentById(id: Long): DocumentEntity? =
        withContext(Dispatchers.IO) { documentDao.byId(id) }
}
