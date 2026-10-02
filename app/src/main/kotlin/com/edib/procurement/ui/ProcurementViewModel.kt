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
import com.edib.procurement.database.ProjectDetail
import com.edib.procurement.database.ContractorDetail
import com.edib.procurement.database.UnlinkedDocumentRow
import com.edib.procurement.matching.MatchingReport
import com.edib.procurement.database.LinkEntryRow
import com.edib.procurement.database.LinkTargetRow
import com.edib.procurement.aipack.PackDetail
import com.edib.procurement.aipack.AiPackOrigin
import com.edib.procurement.aipack.AiPackRequest
import com.edib.procurement.aipack.MultipartResult
import com.edib.procurement.aipack.CorpusValidator
import com.edib.procurement.aipack.PackEstimate
import com.edib.procurement.aipack.PackOutputMode
import com.edib.procurement.aipack.PackSplitConfig
import com.edib.procurement.aipack.formatBytes
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
        cleanStaleMultipartSessions()
    }

    /**
     * Clears debris from a killed multipart export.
     *
     * Runs once at startup. A completed export has a history row and is never
     * touched; only part files with no history row, and any temporary ".part"
     * file, are removed.
     */
    private fun cleanStaleMultipartSessions() {
        viewModelScope.launch {
            runCatching {
                val dir = java.io.File(getApplication<Application>().filesDir, "aipacks")
                val recorded = repo.allPackRecords().mapNotNull { it.absolutePath }
                    .map { java.io.File(it).name }
                    .toMutableSet()
                val r = com.edib.procurement.aipack.StaleMultipartCleanup(dir, recorded).run()
                if (r.removedAny) {
                    android.util.Log.i(
                        "ProcurementPack",
                        "removed ${r.removedTemp.size} temp, ${r.removedOrphanParts.size} part, " +
                            "${r.removedOrphanIndex.size} index file(s) from an unfinished export"
                    )
                }
            }
        }
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

    // ------------------------------------------------------------------
    // Multi-select surfaces (Follow-up groups, Documents)
    // ------------------------------------------------------------------

    private val _selectState = MutableStateFlow(MultiSelectState())
    val selectState: StateFlow<MultiSelectState> = _selectState.asStateFlow()

    /** True while a multi-select surface is in selection mode. */
    val selectionActive: Boolean get() = _selectState.value.inMode

    /**
     * Enters selection mode on a surface.
     *
     * Mode is tracked separately from "something is selected", because the
     * screen has to show the SELECT affordance's result before anything is
     * picked. Arming is idempotent: pressing SELECT twice must not clear the
     * rows already chosen.
     */
    fun beginSelection(scope: SelectScope) {
        _selectState.value = _selectState.value.copy(scope = scope, mode = true)
    }

    /** Leaves selection mode and forgets the selection. */
    fun endSelection() {
        _selectState.value = MultiSelectState()
    }

    fun toggleSelection(id: Long) {
        _selectState.value = _selectState.value.toggle(id)
    }

    fun clearSelection() {
        // CLEAR empties the selection but leaves the surface in selection mode,
        // so the reader can immediately pick a different set.
        _selectState.value = _selectState.value.copy(
            mode = true, ids = emptySet(), selectAllIds = emptySet(), allMode = false
        )
    }

    fun syncSelectionQuery() {
        _selectState.value = _selectState.value.withQuery(_query.value)
    }

    /**
     * SELECT ALL RESULTS against the whole current query.
     *
     * Reads ids straight from the database rather than from loaded pages, so
     * it really is every result. The call then turns the selection into a pack
     * request over the same scope.
     */
    fun selectAllResults(onReady: (AiPackRequest) -> Unit) {
        val st = _selectState.value
        viewModelScope.launch {
            val captured: List<Long> = when (st.scope) {
                SelectScope.GROUPS -> repo.allGroupIdsForQuery(_query.value)
                SelectScope.DOCUMENTS -> repo.allDocumentIdsForQuery(_query.value)
                SelectScope.ENTRIES -> emptyList()
            }
            _selectState.value = MultiSelectState.selectAll(st, captured)
            val next = _selectState.value
            onReady(requestForSelection(next))
        }
    }

    /** Builds the pack request matching the current selection. */
    fun requestForSelection(st: MultiSelectState = _selectState.value): AiPackRequest {
        val label = when (st.scope) {
            SelectScope.GROUPS -> selectionLabel("Follow-up groups", st.count)
            SelectScope.ENTRIES -> selectionLabel("Follow-up entries", st.count)
            SelectScope.DOCUMENTS -> selectionLabel("Documents", st.count)
        }
        return when (st.scope) {
            SelectScope.GROUPS -> AiPackRequest.groups(st.effectiveIds.toList(), label)
            SelectScope.ENTRIES -> AiPackRequest.entries(st.effectiveIds.toList(), label)
            // Unlinked documents are allowed here and only here: a user picking
            // files by hand is stating intent, not having context invented.
            SelectScope.DOCUMENTS -> AiPackRequest.documents(
                st.effectiveIds.toList(), label, unlinked = true
            )
        }.copy(origin = selectionOrigin(st.scope))
    }

    private fun selectionOrigin(scope: SelectScope) = when (scope) {
        SelectScope.GROUPS -> AiPackOrigin.FOLLOW_UP
        SelectScope.ENTRIES -> AiPackOrigin.FOLLOW_UP
        SelectScope.DOCUMENTS -> AiPackOrigin.DOCUMENTS
    }

    private fun selectionLabel(what: String, n: Int): String =
        if (_query.value.isBlank()) "$what ($n)" else "$what matching \"${_query.value}\" ($n)"

    // ------------------------------------------------------------------
    // Project / Contractor detail
    // ------------------------------------------------------------------

    private val _projectDetail = MutableStateFlow<ProjectDetail?>(null)
    val projectDetail: StateFlow<ProjectDetail?> = _projectDetail.asStateFlow()

    private val _contractorDetail = MutableStateFlow<ContractorDetail?>(null)
    val contractorDetail: StateFlow<ContractorDetail?> = _contractorDetail.asStateFlow()

    private val _projectQuery = MutableStateFlow("")
    val projectQuery: StateFlow<String> = _projectQuery.asStateFlow()

    fun setProjectQuery(q: String) { _projectQuery.value = q }

    fun loadProject(id: Long) {
        viewModelScope.launch { _projectDetail.value = repo.projectDetail(id) }
    }

    fun loadContractor(id: Long) {
        viewModelScope.launch { _contractorDetail.value = repo.contractorDetail(id) }
    }

    /**
     * Pack request for a contractor, resolved to the groups they actually work
     * in. Built here rather than in the screen so the id plumbing lives in one
     * place and the scope cannot silently come out empty.
     */
    fun contractorPackRequest(id: Long, name: String): AiPackRequest? {
        val d = _contractorDetail.value ?: return null
        if (d.id != id || d.entryCount == 0) return null
        return AiPackRequest.contractor(name, emptyList())
            .copy(id = id, origin = AiPackOrigin.CONTRACTOR_DETAIL)
    }

    fun projectPackRequest(id: Long, name: String): AiPackRequest =
        AiPackRequest.project(id, name)
            .copy(origin = AiPackOrigin.PROJECT_DETAIL)

    fun groupsOfProjectPager(projectId: Long): Flow<PagingData<GroupListRow>> =
        repo.groupsOfProjectPager(projectId, _projectQuery.value)
            .cachedIn(viewModelScope)

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

    private val _packRequest = MutableStateFlow<AiPackRequest?>(null)
    val packRequest: StateFlow<AiPackRequest?> = _packRequest.asStateFlow()

    /** Opens the pack flow for one Follow-up group. */
    fun buildGroupPackScope(groupId: Long) {
        viewModelScope.launch {
            startPack(repo.scopeForGroup(groupId))
        }
    }

    /**
     * The single entry point every screen uses to open the pack flow.
     *
     * A request is resolved into a concrete scope here, so the configuration
     * screen and the generator never care which screen the user came from.
     */
    fun openPackRequest(request: AiPackRequest) {
        if (!request.isValid) {
            say("Nothing selected to export", isError = true)
            return
        }
        // Stop any pack still running: its progress belongs to the old scope and
        // would otherwise be displayed against this new one.
        packJob?.cancel()
        packJob = null
        _packProgress.value = null
        _packResult.value = null
        _packRequest.value = request
        viewModelScope.launch {
            val scope = repo.resolveRequest(request)
            if (scope.groupIds.isEmpty() && scope.entryIds.isEmpty() &&
                scope.documentIds.isEmpty()
            ) {
                say("Nothing to export for ${request.displayName}", isError = true)
                return@launch
            }
            startPack(scope)
        }
    }

    /** Drops the pending request once the pack flow is dismissed. */
    fun clearPackRequest() {
        _packRequest.value = null
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

    private val _multipartResult = MutableStateFlow<MultipartResult?>(null)
    val multipartResult: StateFlow<MultipartResult?> = _multipartResult.asStateFlow()

    /**
     * Pre-generation size estimate.
     *
     * Uses only database sizes, so it is cheap enough to recompute whenever the
     * target changes while the configuration sheet is open.
     */
    fun estimateSplit(scope: PackScope, targetPartBytes: Long, onReady: (PackEstimate) -> Unit) {
        viewModelScope.launch {
            val e = repo.estimatePack(scope, targetPartBytes)
            onReady(e)
        }
    }

    /**
     * Builds the scope as one index plus N parts.
     *
     * Shares the cancellation handle with a single pack, so pressing Cancel
     * stops whichever run is in flight and the builder removes every file it had
     * already created.
     */
    fun buildMultipartPack(config: PackSplitConfig, include: PackInclude, detail: PackDetail) {
        val scope = _packScope.value ?: return
        packJob?.cancel()
        packJob = viewModelScope.launch {
            _packProgress.value = PackProgress("Preparing", 0, 0)
            _multipartResult.value = null
            try {
                val r = repo.buildMultipart(scope, config, include, detail) { p ->
                    _packProgress.value = p
                }
                _multipartResult.value = r
                if (!r.integrity.ok) {
                    say("Export failed its integrity check and was discarded", isError = true)
                } else {
                    say(
                        "AI Pack export created: ${r.partFileNames.size} part(s), " +
                            formatBytes(r.totalPackBytes)
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _packProgress.value = null
                _multipartResult.value = null
                say("AI Pack export cancelled")
            } catch (e: Throwable) {
                _packProgress.value = null
                _multipartResult.value = null
                say(e.message ?: "AI Pack export failed", isError = true)
            }
        }
    }

    /** Share one generated part. */
    fun sharePartFile(path: String) {
        val f = java.io.File(path)
        if (!f.exists()) {
            say("That part is no longer available", isError = true)
            return
        }
        val uri = repo.shareUriFor(f) ?: run {
            say("That part is no longer available", isError = true)
            return
        }
        ExternalDocumentOpener.shareFile(getApplication(), uri, "application/zip", "Share AI Pack")
    }

    private val _corpusReport = MutableStateFlow<CorpusValidator.Report?>(null)
    val corpusReport: StateFlow<CorpusValidator.Report?> = _corpusReport.asStateFlow()

    private val _corpusBusy = MutableStateFlow(false)
    val corpusBusy: StateFlow<Boolean> = _corpusBusy.asStateFlow()

    /**
     * Builds the corpus validation report for the currently selected folder.
     *
     * This is the smoke-test aid for a future REAL business folder. It reports
     * what extraction would find; it does not run OCR and it does not claim a
     * real corpus has been validated.
     */
    fun buildCorpusReport() {
        viewModelScope.launch {
            _corpusBusy.value = true
            _corpusReport.value = null
            try {
                val docs = repo.corpusDocuments()
                val report = repo.validateCorpus(docs) { d ->
                    when {
                        d.extension.equals("pdf", true) ->
                            repo.probePdf(d.documentUri)
                        d.extension.equals("docx", true) ->
                            repo.probeDocx(d.documentUri)
                        d.extension.equals("xlsx", true) ||
                            d.extension.equals("xls", true) ->
                            repo.probeWorkbook(d.documentUri)
                        else -> CorpusValidator.Outcome.TEXT
                    }
                }
                _corpusReport.value = report
            } catch (e: Throwable) {
                say("Could not read the document folder", isError = true)
            } finally {
                _corpusBusy.value = false
            }
        }
    }

    /** Opens a generated index or part with an external viewer. */
    fun openGeneratedFile(path: String) {
        val f = java.io.File(path)
        if (!f.exists()) {
            say("That file is no longer available", isError = true)
            return
        }
        openDocument(
            f.toURI().toString(),
            if (f.name.endsWith(".zip")) "zip" else "md",
            null
        )
    }

    /** Share the index. */
    fun shareIndexFile(path: String) {
        val f = java.io.File(path)
        if (!f.exists()) {
            say("The index is no longer available", isError = true)
            return
        }
        val uri = repo.shareUriFor(f) ?: run {
            say("The index is no longer available", isError = true)
            return
        }
        ExternalDocumentOpener.shareFile(getApplication(), uri, "text/markdown", "Share AI Pack Index")
    }

    /**
     * Share the index and every part in one ACTION_SEND_MULTIPLE.
     *
     * Not every target accepts a multi-file share; individual part sharing
     * remains available for that case.
     */
    fun shareAll(r: MultipartResult) {
        val files = (listOf(r.indexPath) + r.partPaths)
            .map { java.io.File(it) }
            .filter { it.exists() }
        if (files.isEmpty()) {
            say("Those files are no longer available", isError = true)
            return
        }
        val uris = files.mapNotNull { repo.shareUriFor(it) }
        if (uris.isEmpty()) {
            say("Those files are no longer available", isError = true)
            return
        }
        ExternalDocumentOpener.shareFiles(getApplication(), uris)
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
        // A pack still running must be stopped before the state is wiped.
        // Otherwise its progress keeps publishing into the fresh screen and its
        // old scope is what the next CREATE actually builds, which silently
        // exports the wrong selection.
        packJob?.cancel()
        packJob = null
        _packRequest.value = null
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
