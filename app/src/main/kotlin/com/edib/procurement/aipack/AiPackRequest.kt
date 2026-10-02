package com.edib.procurement.aipack

/**
 * One request to open the AI Pack flow, independent of which screen asked.
 *
 * Every entry point in the app (a group's AI PACK button, a project, a
 * contractor, a multi-select on Follow-up or Documents) builds one of these and
 * hands it to the same configuration screen. That keeps a single generator
 * behind a single configuration surface, instead of a near-duplicate pack UI
 * per screen.
 *
 * The ids are the caller's intent, not the builder's: the builder resolves them
 * into groups, entries and documents. A request that carries no ids at all is
 * a configuration error and is refused in [isValid].
 */
data class AiPackRequest(
    val scopeType: PackScopeType,
    val displayName: String,
    /** One id for [scopeType] == PROJECT, otherwise empty. */
    val projectIds: List<Long> = emptyList(),
    /**
     * The canonical contractor id for CONTRACTOR scope. Kept separate from the
     * group list because the repository expands a contractor into its groups.
     */
    val id: Long? = null,
    /** Groups selected directly, or the whole project/contractor expansion. */
    val groupIds: List<Long> = emptyList(),
    /** Individual entries, used for ENTRIES scope. */
    val entryIds: List<Long> = emptyList(),
    /** Explicitly chosen documents, used for DOCUMENTS scope. */
    val documentIds: List<Long> = emptyList(),
    /**
     * True when the caller wants documents that have no Follow-up link at all.
     * Only ever true for an explicit DOCUMENTS selection, never for a
     * project/contractor/group scope, which must not invent context.
     */
    val allowUnlinked: Boolean = false,
    /** Where the user came from, used to return there on Back. */
    val origin: AiPackOrigin = AiPackOrigin.GROUP_DETAIL
) {

    /** The scope this request resolves to, for the generator. */
    fun toScope(resolved: ResolvedScope = ResolvedScope()): PackScope = PackScope(
        type = scopeType,
        name = displayName,
        groupIds = resolved.groupIds.ifEmpty { groupIds },
        entryIds = resolved.entryIds.ifEmpty { entryIds },
        documentIds = documentIds
    )

    /** A request must name a real thing to export. */
    val isValid: Boolean
        get() = when (scopeType) {
            PackScopeType.PROJECT -> projectIds.isNotEmpty()
            PackScopeType.GROUP -> groupIds.isNotEmpty()
            PackScopeType.ENTRIES -> entryIds.isNotEmpty() || groupIds.isNotEmpty()
            PackScopeType.DOCUMENTS -> documentIds.isNotEmpty()
            PackScopeType.CONTRACTOR -> id != null || groupIds.isNotEmpty() || entryIds.isNotEmpty()
        }

    /** Ids the database must expand before building. */
    val needsGroupResolution: Boolean
        get() = scopeType == PackScopeType.PROJECT || scopeType == PackScopeType.CONTRACTOR

    companion object {
        fun group(groupId: Long, name: String) = AiPackRequest(
            scopeType = PackScopeType.GROUP,
            displayName = name,
            groupIds = listOf(groupId)
        )

        fun project(projectId: Long, name: String) = AiPackRequest(
            scopeType = PackScopeType.PROJECT,
            displayName = name,
            projectIds = listOf(projectId)
        )

        fun contractor(name: String, groupIds: List<Long>) = AiPackRequest(
            scopeType = PackScopeType.CONTRACTOR,
            displayName = name,
            groupIds = groupIds
        )

        fun groups(groupIds: List<Long>, name: String) = AiPackRequest(
            scopeType = PackScopeType.GROUP,
            displayName = name,
            groupIds = groupIds
        )

        fun entries(entryIds: List<Long>, name: String) = AiPackRequest(
            scopeType = PackScopeType.ENTRIES,
            displayName = name,
            entryIds = entryIds
        )

        fun documents(documentIds: List<Long>, name: String, unlinked: Boolean) =
            AiPackRequest(
                scopeType = PackScopeType.DOCUMENTS,
                displayName = name,
                documentIds = documentIds,
                allowUnlinked = unlinked
            )
    }
}

/** Groups/entries the repository resolved a request down to. */
data class ResolvedScope(
    val groupIds: List<Long> = emptyList(),
    val entryIds: List<Long> = emptyList()
)

/** Which screen opened the pack, so Back returns somewhere sensible. */
enum class AiPackOrigin { GROUP_DETAIL, PROJECT_DETAIL, CONTRACTOR_DETAIL, FOLLOW_UP, DOCUMENTS }
