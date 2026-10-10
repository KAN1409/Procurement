package com.edib.procurement.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.edib.procurement.ui.screens.AiPackHistoryScreen
import com.edib.procurement.ui.screens.AiPackScreen
import com.edib.procurement.ui.screens.ContractorDetailScreen
import com.edib.procurement.ui.screens.ContractorsScreen
import com.edib.procurement.ui.screens.DocumentsScreen
import com.edib.procurement.ui.screens.FollowUpScreen
import com.edib.procurement.ui.screens.ProjectDetailScreen
import com.edib.procurement.ui.screens.RatesScreen
import com.edib.procurement.ui.screens.RateCheckScreen
import com.edib.procurement.ui.screens.AddRateScreen
import com.edib.procurement.ui.screens.GroupDetailScreen
import com.edib.procurement.ui.screens.HomeScreen
import com.edib.procurement.ui.screens.NeedsReviewScreen
import com.edib.procurement.ui.screens.ProjectsScreen
import com.edib.procurement.ui.screens.SettingsScreen
import com.edib.procurement.ui.theme.ProcurementColors

/**
 * The five bottom-bar destinations.
 *
 * Seven sections do not fit a phone-width bar without truncating labels, so the
 * three least-used ones live behind MORE. Labels stay short and are paired with
 * an icon so nothing depends on text fitting.
 */
private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "HOME", Icons.Filled.Home),
    PROJECTS("projects", "PROJECTS", Icons.Filled.Home),
    FOLLOW_UP("followup", "FOLLOW-UP", Icons.Filled.List),
    FILES("documents", "FILES", Icons.Filled.Check),
    MORE("more", "MORE", Icons.Filled.MoreVert);

    companion object {
        const val AI_PACK_ROUTE = "aipack"
    }
}

/** Secondary destinations reached from the MORE sheet. */
private enum class MoreTab(
    val route: String,
    val label: String,
    val hint: String,
    val icon: ImageVector
) {
    RATES("rates", "Rates", "Trusted commercial rates with source evidence", Icons.Filled.Search),
    CONTRACTORS("contractors", "Contractors", "Resolved and unresolved contractors", Icons.Filled.Person),
    NEEDS_REVIEW("needs-review", "Needs Review", "Suggestions, unlinked files, contractors", Icons.Filled.Info),
    AI_PACKS("ai-packs", "AI Packs", "Generated packs, history and storage", Icons.Filled.List),
    SETTINGS("settings", "Settings", "Workbook, folder, import history", Icons.Filled.Settings)
}

@Composable
fun ProcurementApp() {
    val vm: ProcurementViewModel = viewModel()
    val nav = rememberNavController()
    var currentTab by rememberSaveable { mutableStateOf(Tab.HOME.route) }
    var showMore by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(message?.id) {
        message?.let {
            snackbar.showSnackbar(it.text)
            vm.clearMessage()
        }
    }

    // Captured before the launchers so the picker callback can resolve the
    // picked folder's display name.
    val context = LocalContext.current

    // SAF only: ACTION_OPEN_DOCUMENT for the workbook, ACTION_OPEN_DOCUMENT_TREE
    // for the repository. Neither needs a broad storage permission.
    val workbookPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) vm.importFollowUp(uri)
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val name = queryTreeDisplayName(context, uri)
            vm.scanDocumentTree(uri, name)
        }
    }

    val openWorkbook = { workbookPicker.launch(WORKBOOK_MIME_TYPES) }
    val openFolder = { folderPicker.launch(null) }

    Scaffold(
        containerColor = ProcurementColors.Graphite900,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(
                containerColor = ProcurementColors.Graphite850,
                contentColor = ProcurementColors.TextSecondary,
                tonalElevation = 0.dp
            ) {
                Tab.entries.forEach { tab ->
                    val selected = if (tab == Tab.MORE) {
                        MoreTab.entries.any { it.route == currentTab }
                    } else {
                        currentTab == tab.route
                    }
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (tab == Tab.MORE) {
                                showMore = true
                            } else {
                                currentTab = tab.route
                                nav.navigate(tab.route) {
                                    popUpTo(Tab.HOME.route) { inclusive = tab == Tab.HOME }
                                    launchSingleTop = true
                                }
                            }
                        },
                        icon = {
                            Icon(
                                tab.icon,
                                contentDescription = tab.label,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        label = {
                            Text(
                                tab.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Clip
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = ProcurementColors.AccentRedBright,
                            selectedTextColor = ProcurementColors.AccentRedBright,
                            unselectedIconColor = ProcurementColors.TextTertiary,
                            unselectedTextColor = ProcurementColors.TextTertiary,
                            indicatorColor = Color.Transparent
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(ProcurementColors.Graphite900)
        ) {
            NavHost(navController = nav, startDestination = Tab.HOME.route) {
                composable(Tab.HOME.route) {
                    HomeScreen(
                        vm = vm,
                        onPickWorkbook = openWorkbook,
                        onPickFolder = openFolder,
                        onRescanFolder = { vm.rescanDocumentTree() },
                        onRunMatching = { vm.runMatching() },
                        onOpenProjects = { nav.toTab(Tab.PROJECTS.route) },
                        onOpenFollowUp = { nav.toTab(Tab.FOLLOW_UP.route) },
                        onOpenDocuments = { nav.toTab(Tab.FILES.route) },
                        onOpenContractors = { nav.toTab(MoreTab.CONTRACTORS.route) },
                        onOpenSettings = { nav.toTab(MoreTab.SETTINGS.route) }
                    )
                }
                composable(Tab.PROJECTS.route) {
                    ProjectsScreen(
                        vm = vm,
                        onOpenGroup = { nav.toGroup(it) },
                        onOpenProject = { nav.navigate("project/$it") }
                    )
                }
                composable(Tab.FOLLOW_UP.route) {
                    FollowUpScreen(
                        vm = vm,
                        onOpenGroup = { nav.toGroup(it) },
                        onOpenPack = { nav.openPack() }
                    )
                }
                composable(Tab.FILES.route) {
                    DocumentsScreen(
                        vm = vm,
                        onPickFolder = openFolder,
                        onRescanFolder = { vm.rescanDocumentTree() },
                        onOpenPack = { nav.openPack() }
                    )
                }
                composable(MoreTab.RATES.route) {
                    RatesScreen(
                        vm = vm,
                        onAddRate = { nav.navigate("rate-add") },
                        onCheckRate = { nav.navigate("rate-check") }
                    )
                }
                composable("rate-check") {
                    RateCheckScreen(
                        vm = vm,
                        onBack = { nav.popBackStack() }
                    )
                }
                composable("rate-add") {
                    AddRateScreen(
                        vm = vm,
                        onBack = { nav.popBackStack() }
                    )
                }
                composable(MoreTab.CONTRACTORS.route) {
                    ContractorsScreen(
                        vm = vm,
                        onOpenContractor = { nav.navigate("contractor/$it") }
                    )
                }
                composable(MoreTab.NEEDS_REVIEW.route) { NeedsReviewScreen(vm = vm) }
                composable(MoreTab.AI_PACKS.route) { AiPackHistoryScreen(vm = vm) }
                composable(MoreTab.SETTINGS.route) {
SettingsScreen(
        vm = vm,
        onPickWorkbook = openWorkbook,
        onPickFolder = openFolder,
        onRescanFolder = { vm.rescanDocumentTree() },
        onRunMatching = { vm.runMatching() }
    )
                }
                composable(
                    route = "project/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.LongType })
                ) { entry ->
                    val id = entry.arguments?.getLong("id") ?: return@composable
                    ProjectDetailScreen(
                        vm = vm,
                        projectId = id,
                        onBack = { nav.popBackStack() },
                        onOpenGroup = { nav.toGroup(it) },
                        onOpenPack = { nav.openPack() }
                    )
                }
                composable(
                    route = "contractor/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.LongType })
                ) { entry ->
                    val id = entry.arguments?.getLong("id") ?: return@composable
                    ContractorDetailScreen(
                        vm = vm,
                        contractorId = id,
                        onBack = { nav.popBackStack() },
                        onOpenPack = { nav.openPack() }
                    )
                }
                composable(
                    route = "group/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.LongType })
                ) { entry ->
                    val id = entry.arguments?.getLong("id") ?: return@composable
                    GroupDetailScreen(
                        vm = vm,
                        groupId = id,
                        onBack = { nav.popBackStack() },
                        onOpenPack = { nav.navigate("aipack") }
                    )
                }
                composable(Tab.AI_PACK_ROUTE) {
                    AiPackScreen(vm = vm, onClose = { nav.popBackStack() })
                }
            }
        }
    }

    if (showMore) {
        MoreSheet(
            currentRoute = currentTab,
            onDismiss = { showMore = false },
            onPick = { route ->
                showMore = false
                currentTab = route
                nav.navigate(route) { launchSingleTop = true }
            }
        )
    }
}

/** Secondary destinations, presented as a sheet rather than squeezed into the bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreSheet(
    currentRoute: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ProcurementColors.Graphite850,
        dragHandle = null
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            MoreTab.entries.forEach { tab ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(tab.route) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        tab.icon,
                        contentDescription = null,
                        tint = if (tab.route == currentRoute)
                            ProcurementColors.AccentRedBright else ProcurementColors.TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (tab.route == currentRoute)
                                ProcurementColors.AccentRedBright else ProcurementColors.TextPrimary
                        )
                        Text(
                            tab.hint,
                            style = MaterialTheme.typography.labelSmall,
                            color = ProcurementColors.TextTertiary
                        )
                    }
                }
                HorizontalDivider(color = ProcurementColors.Graphite700, thickness = 1.dp)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun androidx.navigation.NavHostController.toTab(route: String) {
    navigate(route) { launchSingleTop = true }
}

private fun androidx.navigation.NavHostController.toGroup(id: Long) {
    navigate("group/$id")
}

/**
 * Opens the shared AI Pack configuration screen.
 *
 * The config screen is popped first so re-opening it from another screen always
 * rebuilds it. [launchSingleTop] on its own is wrong here: if a previous pack
 * entry is still on the stack, navigating again would be swallowed and the new
 * scope would never be shown. Popping first also keeps the back stack shallow,
 * so Back returns to the screen the user actually came from, with no duplicate
 * config screens stacked up behind it.
 */
private fun androidx.navigation.NavHostController.openPack() {
    popBackStack(Tab.AI_PACK_ROUTE, inclusive = true)
    navigate(Tab.AI_PACK_ROUTE)
}

/**
 * Best-effort human name for the picked folder.
 *
 * A tree URI must be converted with `buildTreeDocumentUri`, not
 * `buildDocumentUri`: the latter does not resolve for a tree and falls back to
 * the raw document id, which surfaced to the user as
 * "primary:Download/ProcurementDocs".
 */
private fun queryTreeDisplayName(context: Context, uri: Uri): String? = try {
    val authority = uri.authority ?: return null
    val isTree = DocumentsContract.isTreeUri(uri)
    val docId = if (isTree) {
        DocumentsContract.getTreeDocumentId(uri)
    } else {
        DocumentsContract.getDocumentId(uri)
    }
    val documentUri = if (isTree) {
        DocumentsContract.buildTreeDocumentUri(authority, docId)
    } else {
        DocumentsContract.buildDocumentUri(authority, docId)
    }
    context.contentResolver.query(
        documentUri,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null, null, null
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }?.takeIf { it.isNotBlank() }
        ?: docId.substringAfterLast(':').substringAfterLast('/').ifEmpty { null }
} catch (_: Exception) {
    // Last resort: the trailing segment of the raw id, never the whole id.
    runCatching {
        DocumentsContract.getTreeDocumentId(uri)
            .substringAfterLast(':').substringAfterLast('/')
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

private val WORKBOOK_MIME_TYPES = arrayOf(
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-excel",
    "application/octet-stream"
)
