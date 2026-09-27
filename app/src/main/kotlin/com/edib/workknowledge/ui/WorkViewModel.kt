package com.edib.workknowledge.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.edib.workknowledge.data.ChatMessageEntity
import com.edib.workknowledge.data.DocumentEntity
import com.edib.workknowledge.data.DocumentProcessingWorker
import com.edib.workknowledge.data.DocumentSummaryEntity
import com.edib.workknowledge.data.ProjectEntity
import com.edib.workknowledge.data.WorkRepository
import com.edib.workknowledge.ui.screens.DiagnosticsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ProjectRow(val project: ProjectEntity, val documentCount: Int, val chunkCount: Int)

data class DocumentRow(
    val document: DocumentEntity,
    val summary: DocumentSummaryEntity?,
    val chunkCount: Int
)

sealed interface UiMessage {
    data class Info(val text: String) : UiMessage
    data class Failure(val text: String) : UiMessage
}

/**
 * Single ViewModel for the whole V1 flow.
 *
 * Scope is explicit: a chat is always bound to one project (or a fixed set of
 * documents inside it), and that scope is surfaced in the UI so the user can
 * never unknowingly query an unrelated project.
 */
class WorkViewModel(app: Application) : AndroidViewModel(app) {

    private val container = AppContainer(app)
    private val repository: WorkRepository = container.repository
    private val workManager = WorkManager.getInstance(app)

    val embeddingConfig get() = container.embeddingFactory.getConfig()
    val chatConfig get() = container.chatFactory.getConfig()

    private val loadedDocuments = MutableStateFlow<Map<Long, DocumentEntity>>(emptyMap())
    private val _diagnostics = MutableStateFlow(DiagnosticsState())
    val diagnostics: StateFlow<DiagnosticsState> = _diagnostics.asStateFlow()

    val projects: StateFlow<List<ProjectEntity>> = repository.observeActiveProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val selectedProjectId = MutableStateFlow(0L)
    val currentProjectId: StateFlow<Long> = selectedProjectId.asStateFlow()


    private val _messages = MutableStateFlow<UiMessage?>(null)
    val messages: StateFlow<UiMessage?> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _asking = MutableStateFlow(false)
    val asking: StateFlow<Boolean> = _asking.asStateFlow()

    val documents: StateFlow<List<DocumentRow>> =
        selectedProjectId
            .flatMapLatest { projectId ->
                if (projectId <= 0L) {
                    flowOf(emptyList())
                } else {
                    repository.observeDocuments(projectId).map { docs ->
                        docs.map { doc ->
                            DocumentRow(
                                document = doc,
                                summary = null,
                                chunkCount = 0
                            )
                        }
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            projects.collect { list ->
                if (selectedProjectId.value == 0L && list.isNotEmpty()) {
                    selectedProjectId.value = list.first().id
                }
            }
        }
    }

    fun selectProject(id: Long) {
        selectedProjectId.value = id
    }

    fun consumeMessage() {
        _messages.value = null
    }

    // -------------------------------------------------------------- projects

    fun createProject(name: String, description: String, onCreated: (Long) -> Unit = {}) {
        viewModelScope.launch {
            _busy.value = true
            when (val r = repository.createProject(name, description)) {
                is WorkRepository.OpResult.Success -> {
                    selectedProjectId.value = r.value
                    onCreated(r.value)
                }
                is WorkRepository.OpResult.Failure -> _messages.value = UiMessage.Failure(r.message)
            }
            _busy.value = false
        }
    }

    fun renameProject(project: ProjectEntity, name: String, description: String) {
        viewModelScope.launch {
            when (val r = repository.renameProject(project, name, description)) {
                is WorkRepository.OpResult.Success ->
                    _messages.value = UiMessage.Info("Project updated.")
                is WorkRepository.OpResult.Failure -> _messages.value = UiMessage.Failure(r.message)
            }
        }
    }

    fun setArchived(project: ProjectEntity, archived: Boolean) {
        viewModelScope.launch {
            repository.setProjectArchived(project, archived)
            if (archived && selectedProjectId.value == project.id) {
                selectedProjectId.value = 0L
            }
        }
    }

    fun deleteProject(project: ProjectEntity) {
        viewModelScope.launch {
            when (val r = repository.deleteProject(project.id)) {
                is WorkRepository.OpResult.Success ->
                    _messages.value = UiMessage.Info("Deleted \"${project.name}\" and all its documents.")
                is WorkRepository.OpResult.Failure -> _messages.value = UiMessage.Failure(r.message)
            }
            if (selectedProjectId.value == project.id) selectedProjectId.value = 0L
        }
    }

    // -------------------------------------------------------------- documents

    /** Imports the picked URIs, then queues the real ingestion pipeline. */
    fun importUris(uris: List<Uri>) {
        val projectId = selectedProjectId.value
        if (projectId <= 0L) {
            _messages.value = UiMessage.Failure("Select a project before importing files.")
            return
        }
        if (uris.isEmpty()) return

        viewModelScope.launch {
            _busy.value = true
            var queued = 0
            var duplicates = 0
            var failed = 0

            for (uri in uris) {
                when (val r = repository.importDocument(projectId, uri)) {
                    is WorkRepository.OpResult.Success -> when (val outcome = r.value) {
                        is WorkRepository.ImportOutcome.Queued -> {
                            queued++
                            enqueueIngestion(outcome.documentId)
                        }
                        is WorkRepository.ImportOutcome.Duplicate -> duplicates++
                    }
                    is WorkRepository.OpResult.Failure -> {
                        failed++
                        _messages.value = UiMessage.Failure(r.message)
                    }
                }
            }

            _messages.value = UiMessage.Info(
                buildString {
                    if (queued > 0) append("Indexing $queued file${if (queued == 1) "" else "s"}. ")
                    if (duplicates > 0) {
                        append("$duplicates already indexed and skipped. ")
                    }
                    if (failed > 0) append("$failed could not be imported.")
                }.trim()
            )
            _busy.value = false
        }
    }

    private fun enqueueIngestion(documentId: Long) {
        val input: Data = Data.Builder()
            .putLong(DocumentProcessingWorker.KEY_DOCUMENT_ID, documentId)
            .build()
        workManager.enqueueUniqueWork(
            "ingest-$documentId",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DocumentProcessingWorker>()
                .setInputData(input)
                .build()
        )
    }

    /** Re-index: clears derived data and runs the pipeline again. */
    fun reindex(document: DocumentEntity) {
        viewModelScope.launch {
            repository.clearDerivedData(document.id)
            repository.setDocumentStatus(document.id, DocumentEntity.ProcessingStatus.QUEUED)
            enqueueIngestion(document.id)
            _messages.value = UiMessage.Info("Re-indexing ${document.filename}.")
        }
    }

    fun deleteDocument(document: DocumentEntity) {
        viewModelScope.launch {
            workManager.cancelUniqueWork("ingest-${document.id}")
            when (val r = repository.deleteDocument(document.id)) {
                is WorkRepository.OpResult.Success ->
                    _messages.value =
                        UiMessage.Info("Deleted ${document.filename} and its indexed content.")
                is WorkRepository.OpResult.Failure -> _messages.value = UiMessage.Failure(r.message)
            }
        }
    }


    // ------------------------------------------------------------------- chat

    private val _chatMessages = MutableStateFlow<List<ChatMessageEntity>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessageEntity>> = _chatMessages.asStateFlow()

    private var chatSessionId: Long = 0L

    fun startChat(projectId: Long) {
        viewModelScope.launch {
            _chatMessages.value = emptyList()
            when (val r = repository.createSession(projectId, "New chat", com.edib.workknowledge.data.ChatSessionEntity.ScopeType.PROJECT, emptyList())) {
                is WorkRepository.OpResult.Success -> chatSessionId = r.value
                is WorkRepository.OpResult.Failure -> _messages.value = UiMessage.Failure(r.message)
            }
        }
    }

    /**
     * Answers a question against the current project only.
     *
     * Retrieval runs first; the model may only see chunks that were actually
     * retrieved, and the citations shown are exactly those chunks.
     */
    fun ask(question: String) {
        val projectId = selectedProjectId.value
        if (projectId <= 0L) {
            _messages.value = UiMessage.Failure("Select a project first.")
            return
        }
        if (question.isBlank() || _asking.value) return

        viewModelScope.launch {
            _asking.value = true
            repository.addMessage(chatSessionId, ChatMessageEntity.Role.USER, question.trim())
            _chatMessages.value = _chatMessages.value + ChatMessageEntity(
                sessionId = chatSessionId,
                role = ChatMessageEntity.Role.USER,
                content = question.trim()
            )

            val history = repositoryChatHistory()
            val result = container.ragEngine.query(
                question = question.trim(),
                scope = com.edib.workknowledge.rag.RagEngine.QueryScope(
                    type = com.edib.workknowledge.rag.RagEngine.QueryScope.ScopeType.PROJECT,
                    projectId = projectId
                ),
                conversationHistory = history
            )

            when (result) {
                is com.edib.workknowledge.rag.RagEngine.OpResult.Success -> {
                    val value = result.value
                    val citations = value.citations.map { c ->
                        ChatMessageEntity.Citation(
                            chunkId = c.chunkId,
                            documentId = c.documentId,
                            documentName = c.documentName,
                            pageNumber = c.pageNumber,
                            sheetName = c.sheetName,
                            rowStart = c.rowStart,
                            rowEnd = c.rowEnd,
                            cellRange = c.cellRange,
                            snippet = c.snippet
                        )
                    }
                    val reply = ChatMessageEntity(
                        sessionId = chatSessionId,
                        role = ChatMessageEntity.Role.ASSISTANT,
                        content = value.answer
                    ).withCitations(citations)
                    repository.addMessage(
                        chatSessionId,
                        ChatMessageEntity.Role.ASSISTANT,
                        value.answer,
                        citations
                    )
                    _chatMessages.value = _chatMessages.value + reply
                }
                is com.edib.workknowledge.rag.RagEngine.OpResult.Failure ->
                    _messages.value = UiMessage.Failure(result.error)
            }
            _asking.value = false
        }
    }

    private suspend fun repositoryChatHistory(): List<com.edib.workknowledge.llm.ChatModelProvider.ChatMessage> {
        if (chatSessionId == 0L) return emptyList()
        val recent = withContext(Dispatchers.IO) {
            (repository.recentMessages(chatSessionId, 6))
        }
        return recent.map {
            com.edib.workknowledge.llm.ChatModelProvider.ChatMessage(
                role = if (it.role == ChatMessageEntity.Role.USER) {
                    com.edib.workknowledge.llm.ChatModelProvider.ChatMessage.Role.USER
                } else {
                    com.edib.workknowledge.llm.ChatModelProvider.ChatMessage.Role.ASSISTANT
                },
                content = it.content
            )
        }
    }

    // ------------------------------------------------------------- documents

    fun loadDocument(document: DocumentEntity) {
        loadedDocuments.value = loadedDocuments.value + (document.id to document)
    }

    fun documentById(id: Long): DocumentEntity? = loadedDocuments.value[id]

    /**
     * Resolves a citation to a concrete, openable target.
     *
     * Returns null when the document behind a citation is gone, rather than
     * navigating the user somewhere that does not show what was cited.
     */
    fun citationTarget(citation: ChatMessageEntity.Citation): CitationTarget? {
        val doc = loadedDocuments.value[citation.documentId] ?: return null
        return CitationTarget(
            documentId = doc.id,
            filename = doc.filename,
            uri = doc.originalUri,
            mimeType = doc.mimeType,
            page = citation.pageNumber,
            sheet = citation.sheetName,
            rowStart = citation.rowStart,
            rowEnd = citation.rowEnd
        )
    }

    // -------------------------------------------------------------- settings

    fun saveEmbeddingConfig(config: com.edib.workknowledge.embeddings.EmbeddingConfig) {
        container.embeddingFactory.saveConfig(config)
        _messages.value = UiMessage.Info("Embedding settings saved.")
    }

    fun saveChatConfig(config: com.edib.workknowledge.llm.ModelConfig) {
        container.chatFactory.saveConfig(config)
        _messages.value = UiMessage.Info("Chat settings saved.")
    }

    // ------------------------------------------------------------ diagnostics

    fun refreshDiagnostics() {
        viewModelScope.launch {
            val embedding = container.embeddingFactory.getConfig()
            val chat = container.chatFactory.getConfig()
            val dims = repository.embeddedVectorSize()
            _diagnostics.value = DiagnosticsState(
                documentCount = repository.totalDocumentCount(),
                chunkCount = repository.totalChunkCount(),
                embeddingProvider = embedding.providerType.name,
                embeddingModel = embedding.modelName,
                storedDimensions = dims,
                // Reported honestly: whether an index exists, and whether its
                // vectors match the model that is configured right now.
                indexStatus = when {
                    dims == 0 -> "Empty (no vectors stored)"
                    dims != _modelDimensions.value ->
                        "Needs rebuild: stored $dims dims, model returns ${_modelDimensions.value}"
                    else -> "Ready ($dims dims)"
                },
                chatProvider = chat.providerType.name,
                chatModel = chat.modelName,
                hasApiKey = !chat.apiKey.isNullOrBlank(),
                ollamaUrl = chat.ollamaBaseUrl,
                ollamaStatus = _ollamaStatus.value,
                recentErrors = _recentErrors.toList()
            )
        }
    }

    private val _ollamaStatus = MutableStateFlow("Not tested")
    private val _recentErrors = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, Long>>()

    /**
     * Observed from a real embedding call, never assumed. nomic-embed-text is
     * usually 768, but the model is user-configurable, so the number is measured.
     */
    private val _modelDimensions = MutableStateFlow(0)

    private suspend fun report(message: String) {
        _recentErrors.add(message to System.currentTimeMillis())
        while (_recentErrors.size > 20) _recentErrors.poll()
    }

    fun testOllama() {
        viewModelScope.launch {
            val url = chatConfig.ollamaBaseUrl.trimEnd('/')
            val reachable = runCatching {
                withContext(Dispatchers.IO) {
                    val conn = java.net.URL("$url/api/tags").openConnection()
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4000
                    conn.connect()
                    (conn.getInputStream() as? java.io.InputStream)?.bufferedReader()?.use { it.readText() }
                    true
                }
            }.getOrNull() == true

            _ollamaStatus.value = if (reachable) "Connected" else "Unreachable"
            _messages.value = if (reachable) {
                UiMessage.Info("Ollama responded at $url.")
            } else {
                UiMessage.Failure(
                    "Could not reach Ollama at $url. On a physical device this must be the " +
                        "host's LAN address (for example http://192.168.1.10:11434), not 10.0.2.2."
                )
            }
            refreshDiagnostics()
        }
    }

    fun testEmbedding() {
        viewModelScope.launch {
            _busy.value = true
            val provider = container.embeddingFactory.createProvider()
            val message = when (val r = provider.embed("connection test")) {
                is com.edib.workknowledge.embeddings.EmbeddingProvider.OpResult.Success -> {
                    _modelDimensions.value = r.value.size
                    "Embedding OK: ${r.value.size} dimensions from ${provider.modelName}."
                }
                is com.edib.workknowledge.embeddings.EmbeddingProvider.OpResult.Failure ->
                    "Embedding failed: ${r.error}"
            }
            if (message.startsWith("Embedding failed")) report(message)
            _messages.value = if (message.startsWith("Embedding OK")) {
                UiMessage.Info(message)
            } else {
                UiMessage.Failure(message)
            }
            _busy.value = false
            refreshDiagnostics()
        }
    }

    fun testChat() {
        viewModelScope.launch {
            _busy.value = true
            val provider = container.chatFactory.createProvider()
            val message = when (
                val r = provider.generateResponse(
                    systemPrompt = "Reply with the single word: ready",
                    userMessage = "ping",
                    contextChunks = emptyList(),
                    conversationHistory = emptyList()
                )
            ) {
                is com.edib.workknowledge.llm.ChatModelProvider.OpResult.Success ->
                    "Chat OK: ${provider.modelName} responded."
                is com.edib.workknowledge.llm.ChatModelProvider.OpResult.Failure ->
                    "Chat failed: ${r.error}"
            }
            if (message.startsWith("Chat failed")) report(message)
            _messages.value = if (message.startsWith("Chat OK")) {
                UiMessage.Info(message)
            } else {
                UiMessage.Failure(message)
            }
            _busy.value = false
        }
    }
}
