package com.edib.procurement.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.edib.procurement.data.LibraryConfig
import com.edib.procurement.data.ProcurementRepository
import com.edib.procurement.database.ContractorWithCount
import com.edib.procurement.database.DocumentListRow
import com.edib.procurement.database.EntryRow
import com.edib.procurement.database.FollowUpGroupEntity
import com.edib.procurement.database.FollowUpImportEntity
import com.edib.procurement.database.GroupListRow
import com.edib.procurement.database.HomeCounts
import com.edib.procurement.database.LinkedDocumentRow
import com.edib.procurement.database.ProjectWithCount
import com.edib.procurement.database.UnlinkedDocumentRow
import com.edib.procurement.matching.MatchingReport
import com.edib.procurement.database.LinkEntryRow
import com.edib.procurement.database.LinkTargetRow
import com.edib.procurement.aipack.PackDetail
import com.edib.procurement.aipack.PackInclude
import com.edib.procurement.aipack.PackPreview
import com.edib.procurement.aipack.PackProgress
import com.edib.procurement.aipack.PackResult
import com.edib.procurement.aipack.PackScope
import com.edib.procurement.aipack.PackScopeType
import com.edib.procurement.followup.ImportReport
import com.edib.procurement.sharing.ExternalDocumentOpener
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One-shot user feedback (a snackbar line). */
data class Message(val id: Long, val text: String, val isError: Boolean = false)

/** What a long-running import/scan is doing right now. */
data class BusyState(
    val running: Boolean = false,
    val label: String = "",
    val detail: String = "",
    val error: String? = null
)

private val EMPTY_CONFIG = LibraryConfig(null, null, 0, null, null, 0, 0)

class ProcurementViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ProcurementRepository(app)

    private val _config = MutableStateFlow(EMPTY_CONFIG)
    val config: StateFlow<LibraryConfig> = _config.asStateFlow()

    private val _busy = MutableStateFlow(BusyState())
    val busy: StateFlow<BusyState> = _busy.asStateFlow()

    private val _message = MutableStateFlow<Message?>(null)
    val message: StateFlow<Message?> = _message.asStateFlow()

    private val _report = MutableStateFlow<ImportReport?>(null)
    val report: StateFlow<ImportReport?> = _report.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private var scanJob: Job? = null

    val counts: StateFlow<HomeCounts?> = repo.homeCounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val projects: StateFlow<List<ProjectWithCount>> = repo.projects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val contractors: StateFlow<List<ContractorWithCount>> = repo.contractors()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val unresolvedContractors: StateFlow<List<String>> = repo.unresolvedContractors()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val imports: StateFlow<List<FollowUpImportEntity>> = repo.imports()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tags: StateFlow<List<String>> = repo.tags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Groups across all projects, filtered by the global search box. */
    val followUpGroups: StateFlow<List<GroupListRow>> =
        _query.flatMapLatest { q -> repo.groups(null, q) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val documents: StateFlow<List<DocumentListRow>> =
        _query.flatMapLatest { q -> repo.documents(q) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val unlinkedDocuments: StateFlow<List<UnlinkedDocumentRow>> = repo.unlinkedDocuments()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Count only, for screens that need the number and not the rows. */
    val unlinkedCount: StateFlow<Int> = repo.unlinkedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        refreshConfig()
    }

    val contractorAliasCount: StateFlow<Int> = repo.contractorAliasCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

val documentCount: StateFlow<Int> = repo.documentCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

val groupCount: StateFlow<Int> = repo.groupCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

fun setQuery(q: String) { _query.value = q }

    fun clearMessage() { _message.value = null }

    private fun say(text: String, isError: Boolean = false) {
        _message.value = Message(System.currentTimeMillis(), text, isError)
    }

    // ------------------------------------------------------------- actions

    fun importFollowUp(uri: Uri) {
        viewModelScope.launch {
            _busy.value = BusyState(true, "Importing Follow-up workbook", uri.lastPathSegment ?: "")
            try {
                val result = repo.importFollowUp(uri)
                _report.value = result
                _busy.value = BusyState()
                say(
                    "Imported ${result.groups} groups · ${result.entries} entries · " +
                        "${result.projects} projects"
                )
                refreshConfig()
            } catch (e: Throwable) {
                _busy.value = BusyState(error = e.message ?: "Import failed")
                say(e.message ?: "Import failed", isError = true)
            }
        }
    }

    fun scanDocumentTree(uri: Uri, displayName: String?) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _busy.value = BusyState(true, "Indexing documents", "")
            try {
                var lastEmitted = 0
                repo.setDocumentTree(uri, displayName).collect { p ->
                    if (p.done) {
                        _busy.value = BusyState()
                        repo.recordScanFinished(p.indexed)
                        refreshConfig()
                        say("Indexed ${p.indexed} document(s)")
                    } else if (p.scanned - lastEmitted >= 25) {
                        // Republish only on a visible change, otherwise a tree of
                        // thousands of files would recompose the screen per file.
                        lastEmitted = p.scanned
                        _busy.value = BusyState(
                            true, "Indexing documents",
                            "${p.indexed} found · ${p.scanned} scanned"
                        )
                    }
                }
            } catch (e: Throwable) {
                _busy.value = BusyState(error = e.message ?: "Scan failed")
                say(e.message ?: "Scan failed", isError = true)
            }
        }
    }

    /**
 * Re-indexes the folder already chosen.
 *
 * Kept separate from [scanDocumentTree] because a rescan must not send the
 * user back through the system folder picker: the persisted URI grant is still
 * held, so the stored tree can simply be read again.
 */
fun rescanDocumentTree() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _busy.value = BusyState(true, "Indexing documents", "")
            try {
                var lastEmitted = 0
                repo.rescanDocumentTree().collect { p ->
                    if (p.done) {
                        _busy.value = BusyState()
                        repo.recordScanFinished(p.indexed)
                        refreshConfig()
                        say("Indexed ${p.indexed} document(s)")
                    } else if (p.scanned - lastEmitted >= 25) {
                        lastEmitted = p.scanned
                        _busy.value = BusyState(
                            true, "Indexing documents",
                            "${p.indexed} found · ${p.scanned} scanned"
                        )
                    }
                }
            } catch (e: Throwable) {
                _busy.value = BusyState(error = e.message ?: "Scan failed")
                say(e.message ?: "Scan failed", isError = true)
            }
        }
    }

    // ------------------------------------------------------- matching

    private val _matchReport = MutableStateFlow<MatchingReport?>(null)
    val matchReport: StateFlow<MatchingReport?> = _matchReport.asStateFlow()

    val suggestions = repo.suggestions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val unlinkedOnly = repo.unlinkedOnly()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val unresolvedByUsage = repo.unresolvedByUsage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Runs the matcher.
     *
     * Triggered after a scan, after an import and from SETTINGS. It never
     * touches a manual link, so it is always safe to re-run.
     */
    /** Maps a raw contractor string to a canonical contractor by name. */
    fun mapContractorRaw(raw: String, canonical: String) {
        viewModelScope.launch {
            repo.mapContractorRaw(raw, canonical)
            say("Mapped \"$raw\" to \"$canonical\"")
        }
    }

    fun runMatching() {
        viewModelScope.launch {
            _busy.value = BusyState(true, "Matching documents", "")
            try {
                val report = repo.runMatching()
                _matchReport.value = report
                _busy.value = BusyState()
                say(
                    "Matched: ${report.autoLinked} auto · ${report.suggested} suggested · " +
                        "${report.conflicts} conflicts"
                )
            } catch (e: Throwable) {
                _busy.value = BusyState(error = e.message ?: "Matching failed")
                say(e.message ?: "Matching failed", isError = true)
            }
        }
    }

    fun acceptSuggestion(linkId: Long) {
        viewModelScope.launch {
            repo.acceptSuggestion(linkId)
            say("Link accepted and locked")
        }
    }

    fun removeLink(linkId: Long) {
        viewModelScope.launch {
            repo.removeLink(linkId)
            say("Link removed")
        }
    }

    fun linkManually(documentId: Long, groupId: Long?, entryId: Long?, note: String?) {
        viewModelScope.launch {
            repo.linkManually(documentId, groupId, entryId, note)
            say("Linked by hand. This link is locked and survives rescan and re-import.")
        }
    }

    fun linksWithDetail(documentId: Long) = repo.linksWithDetail(documentId)

    fun searchLinkTargets(query: String) = repo.searchLinkTargets(query)

    fun searchLinkEntries(query: String) = repo.searchLinkEntries(query)

    /** Marks a raw contractor string as deliberately not a contractor. */
    fun ignoreContractorValue(raw: String) {
        viewModelScope.launch {
            repo.ignoreContractorValue(raw)
            say("Ignored \"$raw\"")
        }
    }

    fun refreshConfig() {
        viewModelScope.launch { _config.value = repo.currentConfig() }
    }

    fun openDocument(documentUri: String, extension: String?, mime: String?) {
        val result = ExternalDocumentOpener.open(getApplication(), documentUri, extension, mime)
        if (result != ExternalDocumentOpener.OpenResult.OPENED) {
            say(
                when (result) {
                    ExternalDocumentOpener.OpenResult.NO_APP -> "No installed app can open this file type"
                    ExternalDocumentOpener.OpenResult.NO_FILE -> "This file is not accessible any more"
                    else -> "Could not open this file"
                },
                isError = true
            )
        }
    }

    fun toggleFavorite(groupId: Long, current: Boolean) {
        viewModelScope.launch {
            repo.setFavorite(groupId, !current)
            say(if (current) "Removed from favourites" else "Marked as favourite")
        }
    }

    fun saveTags(groupId: Long, tags: List<String>) {
        viewModelScope.launch {
            repo.setTags(groupId, tags)
            say("Tags saved")
        }
    }

    fun mapContractor(entryId: Long, rawCompany: String?, contractorId: Long?) {
        viewModelScope.launch {
            repo.mapContractor(entryId, rawCompany.orEmpty(), contractorId)
            say("Contractor mapping remembered for future imports")
        }
    }

    fun createAndMapContractor(entryId: Long, rawCompany: String?, name: String) {
        viewModelScope.launch {
            val id = repo.createContractor(name)
            if (id > 0) {
                repo.mapContractor(entryId, rawCompany.orEmpty(), id)
                say("Created contractor '$name'")
            }
        }
    }

    fun linkDocument(
        documentId: Long,
        groupId: Long?,
        entryId: Long?,
        evidence: String? = null
    ) {
        viewModelScope.launch {
            repo.linkDocument(documentId, groupId, entryId, "MANUAL", evidence)
            say("Linked. This link is locked and survives re-import.")
        }
    }

    fun unlink(linkId: Long) {
        viewModelScope.launch {
            repo.unlink(linkId)
            say("Link removed")
        }
    }

    // ------------------------------------------------------- database paging

/**
 * Paged groups for a project.
 *
 * [key] makes the flatMapKeys emit whenever the query changes, so a new search
 * term produces a new Pager. Without it, Paging can keep serving the page it
 * already loaded, which looks exactly like "the search found nothing".
 */
/**
 * Paged projects, keyed on the search box.
 *
 * These are `stateIn`-cached on purpose. Handing `collectAsLazyPagingItems` a
 * flow that is rebuilt on every recomposition makes it restart the pager each
 * time, which shows up as a list that never resolves. Caching keeps one
 * Pager alive per query and lets a new term cancel the previous one.
 */
val projectPages: StateFlow<PagingData<ProjectWithCount>> =
    _query.flatMapLatest { q -> repo.projectsPager(q) }
        .cachedIn(viewModelScope)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PagingData.empty())

val groupPages: StateFlow<PagingData<GroupListRow>> =
    _query.flatMapLatest { q -> repo.groupsPager(null, q) }
        .cachedIn(viewModelScope)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PagingData.empty())

val groupPagesForProject: (Long) -> Flow<PagingData<GroupListRow>> = { projectId ->
    repo.groupsPager(projectId, "").cachedIn(viewModelScope)
}

val documentPages: StateFlow<PagingData<DocumentListRow>> =
    _query.flatMapLatest { q -> repo.documentsPager(q) }
        .cachedIn(viewModelScope)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PagingData.empty())

val contractorPages: StateFlow<PagingData<ContractorWithCount>> =
    _query.flatMapLatest { q -> repo.contractorsPager(q) }
        .cachedIn(viewModelScope)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PagingData.empty())

/**
 * Separate query state for the "Link to…" picker.
 *
 * It must NOT share the global [_query]: that one belongs to the screen-level
 * search boxes, and writing the picker's term into it would filter whatever
 * list the user returns to. It is a distinct StateFlow so the picker filters
 * on exactly what was typed.
 */
private val _linkQuery = MutableStateFlow("")
val linkQuery: StateFlow<String> = _linkQuery.asStateFlow()

fun setLinkQuery(q: String) { _linkQuery.value = q }

val linkEntryPages: StateFlow<PagingData<LinkEntryRow>> =
    _linkQuery.flatMapLatest { q -> repo.linkEntriesPager(q) }
        .cachedIn(viewModelScope)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PagingData.empty())

val linkGroupPages: StateFlow<PagingData<LinkTargetRow>> =
    _linkQuery.flatMapLatest { q -> repo.linkGroupsPager(q) }
        .cachedIn(viewModelScope)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PagingData.empty())


    fun entryRows(groupId: Long, query: String): Flow<PagingData<EntryRow>> =
        repo.entriesPager(groupId, query).cachedIn(viewModelScope)

    // ------------------------------------------------------------- AI Pack

    private val _packScope = MutableStateFlow<PackScope?>(null)
    val packScope: StateFlow<PackScope?> = _packScope.asStateFlow()

    private val _packPreview = MutableStateFlow<PackPreview?>(null)
    val packPreview: StateFlow<PackPreview?> = _packPreview.asStateFlow()

    private val _packProgress = MutableStateFlow<PackProgress?>(null)
    val packProgress: StateFlow<PackProgress?> = _packProgress.asStateFlow()

    private val _packResult = MutableStateFlow<PackResult?>(null)
    val packResult: StateFlow<PackResult?> = _packResult.asStateFlow()

    private var packJob: Job? = null

    /** Opens the pack flow for one Follow-up group. */
    fun buildGroupPackScope(groupId: Long) {
        viewModelScope.launch {
            startPack(repo.scopeForGroup(groupId))
        }
    }

    fun startPack(scope: PackScope) {
        packJob?.cancel()
        _packScope.value = scope
        _packResult.value = null
        viewModelScope.launch {
            _packPreview.value = repo.packPreview(scope)
        }
    }

    fun togglePackDocument(documentId: Long) {
        val scope = _packScope.value ?: return
        val selected = scope.documentIds.toMutableSet()
        if (!selected.add(documentId)) selected.remove(documentId)
        _packScope.value = scope.copy(documentIds = selected.toList())
        viewModelScope.launch { _packPreview.value = repo.packPreview(_packScope.value!!) }
    }

    fun clearPackDocuments() {
        val scope = _packScope.value ?: return
        _packScope.value = scope.copy(documentIds = emptyList())
        viewModelScope.launch { _packPreview.value = repo.packPreview(_packScope.value!!) }
    }

    fun buildPack(include: PackInclude, detail: PackDetail, zip: Boolean) {
        val scope = _packScope.value ?: return
        packJob?.cancel()
        packJob = viewModelScope.launch {
            _packProgress.value = PackProgress("Preparing", 0, 0)
            try {
                val result = repo.buildPack(scope, include, detail, zip) { p ->
                    _packProgress.value = p
                }
                _packResult.value = result
                say("AI Pack created: ${result.fileName}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // A cancelled run leaves no file behind, by design.
                _packProgress.value = null
                say("AI Pack generation cancelled")
            } catch (e: Throwable) {
                _packProgress.value = null
                say(e.message ?: "AI Pack failed", isError = true)
            }
        }
    }

    fun cancelPack() {
        packJob?.cancel()
        packJob = null
        _packProgress.value = null
    }

    fun sharePack(result: PackResult) {
        val file = java.io.File(result.absolutePath)
        val uri = repo.shareUriFor(file)
        if (uri == null) {
            say("The pack file is no longer available", isError = true)
            return
        }
        val mime = if (result.fileName.endsWith(".zip")) "application/zip" else "text/markdown"
        ExternalDocumentOpener.shareFile(getApplication(), uri, mime, "Share AI Pack")
    }

    fun openPack(result: PackResult) {
        val file = java.io.File(result.absolutePath)
        if (!file.exists()) {
            say("The pack file is no longer available", isError = true)
            return
        }
        openDocument(
            file.toURI().toString(),
            if (result.fileName.endsWith(".zip")) "zip" else "md",
            null
        )
    }

    fun clearPack() {
        _packScope.value = null
        _packPreview.value = null
        _packResult.value = null
        _packProgress.value = null
    }

    // --------------------------------------------------- pack history

    val packHistory = repo.packHistory.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )

    val packStorageBytes = repo.packStorageBytes.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L
    )

    fun packSources(packId: Long) = repo.packSources(packId)

    fun deletePack(id: Long) {
        viewModelScope.launch {
            repo.deletePack(id)
            say("AI Pack deleted. No original document was affected.")
        }
    }

    fun deleteAllPacks() {
        viewModelScope.launch {
            repo.deleteAllPacks()
            say("All generated packs deleted. Original documents untouched.")
        }
    }

    fun openPackFile(pack: com.edib.procurement.database.AiPackEntity) {
        val file = java.io.File(pack.absolutePath)
        if (!file.exists()) {
            say("The generated file is missing (FILE MISSING)", isError = true)
            return
        }
        // Shared through the app's FileProvider: never a filesystem path, which
        // other apps cannot read.
        val uri = repo.shareUriFor(file)
        if (uri == null) {
            say("This file cannot be opened from the app", isError = true)
            return
        }
        ExternalDocumentOpener.openUri(
            getApplication(), uri, if (pack.fileName.endsWith(".zip")) "zip" else "md"
        )
    }

    fun sharePackFile(pack: com.edib.procurement.database.AiPackEntity) {
        val file = java.io.File(pack.absolutePath)
        if (!file.exists()) {
            say("The generated file is missing (FILE MISSING)", isError = true)
            return
        }
        val uri = repo.shareUriFor(file)
        if (uri == null) {
            say("This file cannot be shared from the app", isError = true)
            return
        }
        val mime = if (pack.fileName.endsWith(".zip")) "application/zip" else "text/markdown"
        ExternalDocumentOpener.shareFile(getApplication(), uri, mime, pack.scopeName)
    }

    // ------------------------------------------------------- detail flows

    /** Group header. Collected with an initial value so the UI never flashes. */
    fun groupDetail(id: Long): Flow<FollowUpGroupEntity?> = repo.group(id)

    fun entryList(id: Long): Flow<List<EntryRow>> = repo.entries(id)

    fun documentList(id: Long): Flow<List<LinkedDocumentRow>> = repo.linkedDocuments(id)

    fun projectGroups(projectId: Long): StateFlow<List<GroupListRow>> =
        repo.groups(projectId, _query.value)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
