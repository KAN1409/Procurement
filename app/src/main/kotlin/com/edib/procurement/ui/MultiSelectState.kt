package com.edib.procurement.ui

import com.edib.procurement.aipack.PackScopeType

/**
 * Selection state for the multi-select surfaces (Follow-up, Documents).
 *
 * Only ids are held. Materialising whole Room entities for a selection that can
 * cover thousands of rows would defeat the point of paging, so the set stores
 * primary keys and the pack builder resolves them when it runs.
 *
 * [selectAllIds] is separate from the visible list on purpose: "select all
 * results" must mean every row the current query matches, not only the pages
 * Paging has loaded so far.
 */
data class MultiSelectState(
    val scope: SelectScope = SelectScope.GROUPS,
    /** True once the user has entered selection mode on this surface. */
    val mode: Boolean = false,
    val ids: Set<Long> = emptySet(),
    /**
     * Ids captured by SELECT ALL RESULTS. In bulk mode [ids] holds the rows
     * the user switched back off, which [effectiveIds] subtracts.
     */
    val selectAllIds: Set<Long> = emptySet(),
    val allMode: Boolean = false,
    val query: String = ""
) {

/**
 * The selection the pack will be built from.
 *
 * In bulk mode [ids] holds rows the user turned OFF after SELECT ALL RESULTS,
 * so they are subtracted. In manual mode it holds the picks themselves.
 */
val effectiveIds: Set<Long>
    get() = if (allMode) selectAllIds - ids else ids

    val count: Int get() = effectiveIds.size

    val isEmpty: Boolean get() = effectiveIds.isEmpty()

    /** Whether the surface is in selection mode, which drives the action bar. */
    val inMode: Boolean get() = mode || !isEmpty

    fun packScopeType(): PackScopeType = when (scope) {
        SelectScope.GROUPS -> PackScopeType.GROUP
        SelectScope.ENTRIES -> PackScopeType.ENTRIES
        SelectScope.DOCUMENTS -> PackScopeType.DOCUMENTS
    }

    /**
 * Adds or removes one row.
 *
 * In bulk mode the row is removed from (or added back to) the captured set, so
 * turning one row off after SELECT ALL leaves the other 649 selected instead of
 * collapsing the whole selection to that single row.
 */
fun toggle(id: Long): MultiSelectState =
    if (allMode) {
        // `ids` holds the rows switched OFF since the bulk capture. Removing a
        // row drops it from the captured set and records it as an exclusion;
        // adding it back clears the exclusion and restores the capture entry.
        if (ids.contains(id)) {
            copy(ids = ids - id, selectAllIds = selectAllIds + id)
        } else {
            copy(selectAllIds = selectAllIds - id, ids = ids + id)
        }
    } else {
        copy(ids = if (ids.contains(id)) ids - id else ids + id)
    }

fun clear(): MultiSelectState =
    MultiSelectState(scope = scope, query = query, mode = mode)

    /** Selection follows the search box, so a stale bulk set is never reused. */
    fun withQuery(q: String): MultiSelectState =
        if (q == query) this else copy(query = q, allMode = false, selectAllIds = emptySet())

    companion object {
        /**
         * Merges the user's manual picks with a bulk "select all" capture.
         *
         * Rows deselected after a SELECT ALL must stay deselected, so manual
         * ids win over the captured set in both directions.
         */
        /**
         * SELECT ALL RESULTS over the whole query.
         *
         * The bulk capture replaces whatever was picked manually, because
         * "all results" is defined by the query, not by earlier taps. [ids]
         * becomes the exclusion set, so it starts empty; a row switched off
         * afterwards is added there.
         */
        fun selectAll(previous: MultiSelectState, captured: List<Long>): MultiSelectState =
            previous.copy(
                allMode = true,
                selectAllIds = captured.toSet(),
                ids = emptySet(),
                mode = true
            )
    }
}

/** What a multi-select surface is selecting rows of. */
enum class SelectScope { GROUPS, ENTRIES, DOCUMENTS }
