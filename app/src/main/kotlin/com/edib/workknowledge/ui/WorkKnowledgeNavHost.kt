package com.edib.workknowledge.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.edib.workknowledge.data.ChatMessageEntity
import com.edib.workknowledge.data.DocumentEntity
import com.edib.workknowledge.ui.screens.ChatScreen
import com.edib.workknowledge.ui.screens.DiagnosticsScreen
import com.edib.workknowledge.ui.screens.DiagnosticsState
import com.edib.workknowledge.ui.screens.ExcelViewerScreen
import com.edib.workknowledge.ui.screens.HomeScreen
import com.edib.workknowledge.ui.screens.PdfViewerScreen
import com.edib.workknowledge.ui.screens.ProjectScreen
import com.edib.workknowledge.ui.screens.SearchScreen
import com.edib.workknowledge.ui.screens.SettingsScreen

object Routes {
    const val HOME = "home"
    const val PROJECT = "project/{projectId}"
    const val CHAT = "chat/{projectId}"
    const val PDF = "pdf/{documentId}?page={page}"
    const val EXCEL = "excel/{documentId}?sheet={sheet}&from={from}&to={to}"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"
    const val SEARCH = "search"
    const val COMPARE = "compare/{projectId}"
}

/** Where a citation should take the user. */
data class CitationTarget(
    val documentId: Long,
    val filename: String,
    val uri: String,
    val mimeType: String,
    val page: Int? = null,
    val sheet: String? = null,
    val rowStart: Int? = null,
    val rowEnd: Int? = null
)

@Composable
fun WorkKnowledgeNavHost(viewModel: WorkViewModel) {
    val navController = rememberNavController()
    val context = LocalContext.current
    // Edge-to-edge is on, so the system bars must be respected explicitly or
    // the toolbar renders underneath the status bar.
    val insets = WindowInsets.safeDrawing

    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val documents by viewModel.documents.collectAsStateWithLifecycle()
    val messages by viewModel.chatMessages.collectAsStateWithLifecycle()
    val asking by viewModel.asking.collectAsStateWithLifecycle()
    val projectId by viewModel.currentProjectId.collectAsStateWithLifecycle()
    val uiMessage by viewModel.messages.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val searchIndexSize by viewModel.searchIndexSize.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Import outcomes (including "already indexed") and provider failures are
    // only useful if they are actually shown.
    LaunchedEffect(uiMessage) {
        val message = uiMessage ?: return@LaunchedEffect
        val text = when (message) {
            is com.edib.workknowledge.ui.UiMessage.Info -> message.text
            is com.edib.workknowledge.ui.UiMessage.Failure -> message.text
        }
        snackbarHostState.showSnackbar(text)
        viewModel.consumeMessage()
    }

    var creating by remember { mutableStateOf(false) }

    val project = projects.firstOrNull { it.id == projectId }
    val projectName = project?.name ?: "this project"

    if (creating) {
        NewProjectDialog(
            onDismiss = { creating = false },
            onCreate = { name, description ->
                creating = false
                viewModel.createProject(name, description) { id ->
                    navController.navigate("project/$id")
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(insets)
    ) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME
    ) {

        composable(Routes.HOME) {
            HomeScreen(
                projects = projects,
                onProjectClick = { selected ->
                    viewModel.selectProject(selected.id)
                    navController.navigate("project/${selected.id}")
                },
                onNewProject = { creating = true },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                onOpenSearch = { navController.navigate(Routes.SEARCH) }
            )
        }

        composable(
            route = Routes.PROJECT,
            arguments = listOf(navArgument("projectId") { type = NavType.LongType })
        ) { entry ->
            val id = entry.arguments?.getLong("projectId") ?: 0L
            LaunchedEffect(id) { viewModel.selectProject(id) }
            val current = projects.firstOrNull { it.id == id } ?: project

            if (current == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                ProjectScreen(
                    project = current,
                    documents = documents,
                    onBack = { navController.popBackStack() },
                    onImport = viewModel::importUris,
                    onOpenDocument = { doc ->
                        viewModel.loadDocument(doc)
                        navController.navigate(documentDetailRoute(doc))
                    },
                    onDeleteDocument = viewModel::deleteDocument,
                    onRenameProject = { name, description ->
                        viewModel.renameProject(current, name, description)
                    },
                    onDeleteProject = {
                        viewModel.deleteProject(current)
                        navController.popBackStack()
                    },
                    onArchiveProject = {
                        viewModel.setArchived(current, true)
                        navController.popBackStack()
                    }
                )
            }
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument("projectId") { type = NavType.LongType })
        ) { entry ->
            val id = entry.arguments?.getLong("projectId") ?: 0L
            LaunchedEffect(id) {
                viewModel.selectProject(id)
                viewModel.startChat(id)
            }
            ChatScreen(
                projectName = projects.firstOrNull { it.id == id }?.name ?: "this project",
                messages = messages,
                asking = asking,
                onBack = { navController.popBackStack() },
                onAsk = viewModel::ask,
                onOpenCitation = { citation ->
                    viewModel.citationTarget(citation)?.let { target ->
                        navController.navigate(documentDetailRoute(target))
                    }
                }
            )
        }

        composable(
            route = Routes.PDF,
            arguments = listOf(
                navArgument("documentId") { type = NavType.LongType },
                navArgument("page") { type = NavType.StringType; defaultValue = "1" }
            )
        ) { entry ->
            val id = entry.arguments?.getLong("documentId") ?: 0L
            val page = entry.arguments?.getString("page")?.toIntOrNull() ?: 1
            val doc = viewModel.documentById(id)
            if (doc == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                PdfViewerScreen(
                    filename = doc.filename,
                    uri = android.net.Uri.parse(doc.originalUri),
                    initialPage = page,
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable(
            route = Routes.EXCEL,
            arguments = listOf(
                navArgument("documentId") { type = NavType.LongType },
                navArgument("sheet") { type = NavType.StringType; defaultValue = "" },
                navArgument("from") { type = NavType.StringType; defaultValue = "0" },
                navArgument("to") { type = NavType.StringType; defaultValue = "0" }
            )
        ) { entry ->
            val id = entry.arguments?.getLong("documentId") ?: 0L
            val sheet = entry.arguments?.getString("sheet")?.takeIf { it.isNotBlank() }
            val from = entry.arguments?.getString("from")?.toIntOrNull() ?: 0
            val to = entry.arguments?.getString("to")?.toIntOrNull() ?: 0
            val doc = viewModel.documentById(id)
            if (doc == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                ExcelViewerScreen(
                    filename = doc.filename,
                    uri = android.net.Uri.parse(doc.originalUri),
                    initialSheet = sheet,
                    initialRowStart = from.takeIf { it > 0 },
                    initialRowEnd = to.takeIf { it > 0 },
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable(Routes.SEARCH) {
            LaunchedEffect(Unit) { viewModel.refreshSearchIndexSize() }
            SearchScreen(
                results = searchResults,
                searching = searching,
                indexSize = searchIndexSize,
                onQueryChange = viewModel::search,
                onOpenHit = { hit ->
                    viewModel.openSearchHit(hit) { target ->
                        navController.navigate(documentDetailRoute(target))
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                embeddingConfig = viewModel.embeddingConfig,
                chatConfig = viewModel.chatConfig,
                onSaveEmbedding = viewModel::saveEmbeddingConfig,
                onSaveChat = viewModel::saveChatConfig,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.DIAGNOSTICS) {
            LaunchedEffect(Unit) { viewModel.refreshDiagnostics() }
            DiagnosticsScreen(
                state = viewModel.diagnostics.value,
                onBack = { navController.popBackStack() },
                onTestOllama = viewModel::testOllama,
                onTestEmbedding = viewModel::testEmbedding,
                onTestChat = viewModel::testChat
            )
        }
    }
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(16.dp)
    )
    }
}

private fun documentDetailRoute(doc: DocumentEntity): String =
    if (doc.mimeType == "application/pdf") {
        "pdf/${doc.id}?page=1"
    } else {
        "excel/${doc.id}?sheet=&from=0&to=0"
    }

private fun documentDetailRoute(target: CitationTarget): String = when {
    target.mimeType == "application/pdf" ->
        "pdf/${target.documentId}?page=${target.page ?: 1}"
    else -> {
        val sheet = target.sheet.orEmpty()
        val from = target.rowStart ?: 0
        val to = target.rowEnd ?: 0
        "excel/${target.documentId}?sheet=$sheet&from=$from&to=$to"
    }
}

@Composable
private fun NewProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, description) },
                enabled = name.isNotBlank()
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
