package com.edib.procurement.database

import com.edib.procurement.aipack.AiPackRequest
import com.edib.procurement.aipack.ResolvedScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How each scope type resolves into the concrete group/entry/document ids the
 * generator is given.
 *
 * The generator only ever receives explicit id lists, so every scope's
 * correctness depends on this translation: a PROJECT must expand to all of its
 * groups, a CONTRACTOR to its own entries, and a user selection to exactly what
 * was picked.
 */
class PackScopeResolutionTest {

    @Test
    fun `project request expands to the project id`() {
        val r = AiPackRequest.project(42L, "Jaz Negma")
        val scope = r.toScope(ResolvedScope(groupIds = listOf(1L, 2L, 3L)))

        assertEquals(42L, r.projectIds.single())
        assertEquals(listOf(1L, 2L, 3L), scope.groupIds)
        assertTrue(r.needsGroupResolution)
    }

    @Test
    fun `contractor request carries the contractor id for expansion`() {
        val r = AiPackRequest.contractor("Al Alpha", emptyList()).copy(id = 9L)

        assertEquals(9L, r.id)
        assertTrue("contractor must be resolved to its groups", r.needsGroupResolution)
        assertTrue(r.isValid)
    }

    @Test
    fun `contractor pack keeps groups for context but pins the entries`() {
        val r = AiPackRequest.contractor("Al Alpha", emptyList()).copy(id = 9L)
        val scope = r.toScope(
            ResolvedScope(groupIds = listOf(10L, 11L), entryIds = listOf(100L, 101L))
        )

        // Groups survive so each entry keeps its project/group context...
        assertEquals(listOf(10L, 11L), scope.groupIds)
        // ...but only the contractor's own entries are exported.
        assertEquals(listOf(100L, 101L), scope.entryIds)
    }

    @Test
    fun `group selection keeps the selected groups only`() {
        val ids = listOf(5L, 6L, 7L)
        val r = AiPackRequest.groups(ids, "Follow-up groups (3)")
        val scope = r.toScope(ResolvedScope(groupIds = ids))

        assertEquals(ids, scope.groupIds)
        assertTrue(scope.entryIds.isEmpty())
    }

    @Test
    fun `documents scope carries the selected document ids and allows unlinked`() {
        val r = AiPackRequest.documents(listOf(1L, 2L, 3L), "Documents (3)", unlinked = true)
        val scope = r.toScope()

        assertEquals(listOf(1L, 2L, 3L), scope.documentIds)
        assertTrue("an explicit document selection may include unlinked files", r.allowUnlinked)
    }

    @Test
    fun `project and group scopes never allow unlinked documents`() {
        assertFalse(AiPackRequest.project(1L, "P").allowUnlinked)
        assertFalse(AiPackRequest.group(1L, "G").allowUnlinked)
    }

    @Test
    fun `a request with nothing to export is rejected`() {
        assertFalse(AiPackRequest.project(1L, "P").copy(projectIds = emptyList()).isValid)
        assertFalse(AiPackRequest.group(1L, "G").copy(groupIds = emptyList()).isValid)
        assertFalse(AiPackRequest.entries(emptyList(), "none").isValid)
        assertFalse(AiPackRequest.documents(emptyList(), "none", unlinked = true).isValid)
    }

    @Test
    fun `entries scope accepts entries or the groups holding them`() {
        assertTrue(AiPackRequest.entries(listOf(1L), "e").isValid)
        assertTrue(AiPackRequest.groups(listOf(1L), "g").isValid)
    }

    @Test
    fun `multi-group selection survives as one request`() {
        val selected = listOf(11L, 12L, 13L, 14L)
        val r = AiPackRequest.groups(selected, "Follow-up groups (4)")
        assertEquals(4, r.groupIds.size)
        assertEquals(selected, r.toScope(ResolvedScope(groupIds = selected)).groupIds)
    }
}
