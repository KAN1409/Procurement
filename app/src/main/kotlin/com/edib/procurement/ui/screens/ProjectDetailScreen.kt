package com.edib.procurement.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.edib.procurement.database.ContractorDetail
import com.edib.procurement.database.ProjectDetail
import com.edib.procurement.ui.ProcurementViewModel
import com.edib.procurement.ui.components.Chip
import com.edib.procurement.ui.components.EmptyState
import com.edib.procurement.ui.theme.ProcurementColors

/**
 * Project Detail.
 *
 * Reached by tapping a project row. Shows the project's real totals and, as the
 * primary action, opens the shared AI Pack flow with [AiPackScopeType.PROJECT],
 * so the pack covers every group in this project and nothing from another.
 */
@Composable
fun ProjectDetailScreen(
    vm: ProcurementViewModel,
    projectId: Long,
    onBack: () -> Unit,
    onOpenGroup: (Long) -> Unit,
    onOpenPack: () -> Unit
) {
    val detail by vm.projectDetail.collectAsStateWithLifecycle()
    val query by vm.projectQuery.collectAsStateWithLifecycle()
    val groups = vm.groupsOfProjectPager(projectId).collectAsLazyPagingItems()

    LaunchedEffect(projectId) { vm.loadProject(projectId) }

    Column(
        Modifier
            .fillMaxSize()
            .background(ProcurementColors.Graphite900)
    ) {
        ScreenHeader(detail?.canonicalName ?: "Project")
        if (detail != null) {
            val d = detail!!
            ProjectSummaryCard(d) {
                vm.openPackRequest(vm.projectPackRequest(d.id, d.canonicalName))
                onOpenPack()
            }
        }

        SearchField(
            value = query,
            onValueChange = vm::setProjectQuery,
            placeholder = "Search this project's groups\u2026",
            modifier = Modifier.padding(horizontal = 14.dp)
        )
        BoxSpacer(8)

        if (groups.itemCount == 0 &&
            groups.loadState.refresh is androidx.paging.LoadState.NotLoading
        ) {
            EmptyState("No groups in this project match.")
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp)
        ) {
            items(groups.itemCount, key = { i -> groups.peek(i)?.id ?: i.toLong() }) { i ->
                val g = groups[i] ?: return@items
                GroupRow(g) { onOpenGroup(g.id) }
                Spacer(Modifier.height(4.dp))
            }
            if (groups.loadState.append is androidx.paging.LoadState.Loading) {
                item { PagingSpinner() }
            }
        }
    }
}

/**
 * Contractor Detail.
 *
 * The pack action here is [AiPackScopeType.CONTRACTOR]: every entry whose
 * canonical (or manual) contractor is this one, keeping its project and group
 * context. Documents reached from several of those entries are emitted once by
 * the generator, which keys on document id.
 */
@Composable
fun ContractorDetailScreen(
    vm: ProcurementViewModel,
    contractorId: Long,
    onBack: () -> Unit,
    onOpenPack: () -> Unit
) {
    val detail by vm.contractorDetail.collectAsStateWithLifecycle()

    LaunchedEffect(contractorId) { vm.loadContractor(contractorId) }

    Column(
        Modifier
            .fillMaxSize()
            .background(ProcurementColors.Graphite900)
    ) {
        ScreenHeader(detail?.canonicalName ?: "Contractor")
        val d = detail
        if (d == null) {
            EmptyState("Contractor not found.")
            return@Column
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp)
                .background(ProcurementColors.Graphite850, RoundedCornerShape(3.dp))
                .padding(14.dp)
        ) {
            Text(
                d.canonicalName,
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                color = ProcurementColors.TextPrimary
            )
            BoxSpacer(8)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("${d.entryCount} entries")
                Chip("${d.groupCount} groups")
                Chip("${d.projectCount} projects")
            }
            BoxSpacer(6)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("${d.documentCount} docs", ProcurementColors.StatusInfo)
                if (d.aliasCount > 0) Chip("${d.aliasCount} aliases")
                if (d.isUserCreated) Chip("USER CREATED", ProcurementColors.AccentRedBright)
            }
            BoxSpacer(14)
            Button(
                onClick = {
                    val request = vm.contractorPackRequest(d.id, d.canonicalName)
                    if (request == null) {
                        // No entries means nothing to export; say so rather
                        // than opening a pack screen with an empty scope.
                        return@Button
                    }
                    vm.openPackRequest(request)
                    onOpenPack()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = ProcurementColors.AccentRed
                ),
                shape = RoundedCornerShape(2.dp),
                modifier = Modifier.fillMaxWidth()
            ) { Text("CREATE AI PACK") }
            BoxSpacer(6)
            Text(
                "Includes every entry mapped to this contractor, with its project " +
                    "and group context.",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = ProcurementColors.TextTertiary
            )
        }
    }
}

/** The counts block at the top of Project Detail, with the pack action. */
@Composable
private fun ProjectSummaryCard(d: ProjectDetail, onCreatePack: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .background(ProcurementColors.Graphite850, RoundedCornerShape(3.dp))
            .padding(14.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("${d.groupCount} groups")
            Chip("${d.entryCount} entries")
        }
        BoxSpacer(6)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("${d.documentCount} docs", ProcurementColors.StatusInfo)
            Chip("${d.contractorCount} contractors")
        }
        BoxSpacer(14)
        Button(
            onClick = onCreatePack,
            colors = ButtonDefaults.buttonColors(containerColor = ProcurementColors.AccentRed),
            shape = RoundedCornerShape(2.dp),
            modifier = Modifier.fillMaxWidth()
        ) { Text("CREATE AI PACK") }
        BoxSpacer(6)
        Text(
            "Whole project: every Follow-up group it contains and their documents.",
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = ProcurementColors.TextTertiary
        )
    }
    BoxSpacer(10)
}
