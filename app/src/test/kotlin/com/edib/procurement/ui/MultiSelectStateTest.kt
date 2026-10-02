package com.edib.procurement.ui

import com.edib.procurement.aipack.PackScopeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Selection tracking for the multi-select surfaces.
 *
 * The important behaviours are:
 *  - only ids are held, so a selection over thousands of rows never pulls
 *    thousands of Room entities into memory;
 *  - SELECT ALL RESULTS really means the whole query, which is modelled here
 *    with more ids than any single page would hold;
 *  - a row the user turns off after a bulk select stays off.
 */
class MultiSelectStateTest {

    private val bigIds = (1L..650L).toList()   // more than the 200 paging window

    @Test
    fun `toggling adds then removes an id`() {
        val s = MultiSelectState(scope = SelectScope.GROUPS)
        val on = s.toggle(5L)
        assertEquals(setOf(5L), on.effectiveIds)
        val off = on.toggle(5L)
        assertTrue(off.effectiveIds.isEmpty())
    }

    @Test
    fun `count reflects the selection`() {
        var s = MultiSelectState(scope = SelectScope.DOCUMENTS)
        s = s.toggle(1L).toggle(2L).toggle(3L)
        assertEquals(3, s.count)
        assertFalse(s.isEmpty)
    }

    @Test
    fun `select all covers every matched row beyond one page`() {
        val base = MultiSelectState(scope = SelectScope.GROUPS)
        val all = MultiSelectState.selectAll(base, bigIds)

        assertTrue("must exceed a single paging window", all.count > 200)
        assertEquals(650, all.count)
        assertTrue(all.allMode)
    }

    @Test
    fun `select all defines the selection and discards earlier picks`() {
        // The user picked row 900, which is outside the current result set, then
        // pressed SELECT ALL RESULTS. "All results" means the query's result, so
        // the stale manual pick is replaced rather than merged.
        val before = MultiSelectState(scope = SelectScope.GROUPS).toggle(900L)
        val after = MultiSelectState.selectAll(before, bigIds)

        assertFalse(after.effectiveIds.contains(900L))
        assertEquals(bigIds.size, after.count)
    }

    @Test
    fun `toggling after select all leaves bulk mode so the pick is explicit`() {
        val all = MultiSelectState.selectAll(MultiSelectState(), bigIds)
        // Turning a row OFF after a bulk select keeps the rest selected.
        val off = all.toggle(3L)
        assertEquals(649, off.count)
        assertFalse(off.effectiveIds.contains(3L))

        // Turning a row back ON restores it.
        assertEquals(650, off.toggle(3L).count)
    }

    @Test
    fun `changing the query drops the stale bulk selection`() {
        val all = MultiSelectState(scope = SelectScope.GROUPS, query = "almaza")
            .let { MultiSelectState.selectAll(it, bigIds) }
        val requeried = all.withQuery("giza")

        assertFalse("a new search must not reuse the previous bulk set", requeried.allMode)
        assertTrue(requeried.effectiveIds.isEmpty())
    }

    @Test
    fun `scope maps to the right pack scope type`() {
        assertEquals(PackScopeType.GROUP, MultiSelectState(SelectScope.GROUPS).packScopeType())
        assertEquals(PackScopeType.ENTRIES, MultiSelectState(SelectScope.ENTRIES).packScopeType())
        assertEquals(PackScopeType.DOCUMENTS, MultiSelectState(SelectScope.DOCUMENTS).packScopeType())
    }

    @Test
    fun `mode can be armed before anything is selected`() {
        val s = MultiSelectState(scope = SelectScope.DOCUMENTS, mode = true)
        assertTrue("action bar must show as soon as selection mode is entered", s.inMode)
        assertTrue(s.isEmpty)
    }

    @Test
    fun `having selections implies selection mode`() {
        assertTrue(MultiSelectState(scope = SelectScope.GROUPS).toggle(1L).inMode)
    }

    @Test
    fun `clearing empties the selection`() {
        val s = MultiSelectState(scope = SelectScope.GROUPS).toggle(1L).toggle(2L).clear()
        assertTrue(s.effectiveIds.isEmpty())
        assertEquals(0, s.count)
    }
}
