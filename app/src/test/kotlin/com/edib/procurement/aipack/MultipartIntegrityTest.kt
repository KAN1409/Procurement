package com.edib.procurement.aipack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The union of every part's sources must equal the export's source set.
 *
 * Splitting is the one operation that can silently drop or repeat data, so the
 * export is only shown as finished when this passes. These tests pin both the
 * happy path and the specific ways a split goes wrong.
 */
class MultipartIntegrityTest {

    @Test
    fun `a well-formed split passes`() {
        val report = MultipartIntegrity.verify(
            expectedSourceIds = listOf("SOURCE-0001", "SOURCE-0002", "SOURCE-0003"),
            actualSourceIdsByPart = listOf(
                listOf("SOURCE-0001"),
                listOf("SOURCE-0002", "SOURCE-0003")
            ),
            documentIdBySource = mapOf(
                "SOURCE-0001" to 10L, "SOURCE-0002" to 11L, "SOURCE-0003" to 12L
            ),
            partCount = 2
        )
        assertTrue(report.problems.toString(), report.ok)
        assertEquals("COMPLETE", report.status)
        assertEquals(3, report.expectedSources)
        assertEquals(3, report.actualSources)
    }

    @Test
    fun `a missing source fails the check`() {
        val report = MultipartIntegrity.verify(
            expectedSourceIds = listOf("SOURCE-0001", "SOURCE-0002", "SOURCE-0003"),
            actualSourceIdsByPart = listOf(listOf("SOURCE-0001", "SOURCE-0002")),
            documentIdBySource = mapOf("SOURCE-0001" to 1L, "SOURCE-0002" to 2L, "SOURCE-0003" to 3L),
            partCount = 1
        )
        assertFalse(report.ok)
        assertEquals("ERROR", report.status)
        assertEquals(listOf("SOURCE-0003"), report.missingSourceIds)
    }

    @Test
    fun `a repeated source fails the check`() {
        val report = MultipartIntegrity.verify(
            expectedSourceIds = listOf("SOURCE-0001"),
            actualSourceIdsByPart = listOf(listOf("SOURCE-0001"), listOf("SOURCE-0001")),
            documentIdBySource = mapOf("SOURCE-0001" to 1L),
            partCount = 2
        )
        assertFalse(report.ok)
        assertEquals(listOf("SOURCE-0001"), report.duplicateSourceIds)
    }

    @Test
    fun `every source appears exactly once across many parts`() {
        val expected = (1..50).map { "SOURCE-%04d".format(it) }
        val perPart = listOf(
            expected.take(20), expected.drop(20).take(20), expected.drop(40)
        )
        val report = MultipartIntegrity.verify(
            expectedSourceIds = expected,
            actualSourceIdsByPart = perPart,
            documentIdBySource = expected.mapIndexed { i, s -> s to (i + 1).toLong() }.toMap(),
            partCount = 3
        )
        assertTrue(report.problems.toString(), report.ok)
        assertEquals(50, report.actualSources)
    }

    @Test
    fun `an empty part is reported`() {
        val report = MultipartIntegrity.verify(
            expectedSourceIds = listOf("SOURCE-0001"),
            actualSourceIdsByPart = listOf(listOf("SOURCE-0001"), emptyList()),
            documentIdBySource = mapOf("SOURCE-0001" to 1L),
            partCount = 2
        )
        assertFalse("a part with no sources is a bug, not a valid split", report.ok)
        assertTrue(report.problems.any { it.contains("no sources") })
    }

    @Test
    fun `a documents-only export with no groups still verifies`() {
        val report = MultipartIntegrity.verify(
            expectedSourceIds = listOf("SOURCE-0001", "SOURCE-0002"),
            actualSourceIdsByPart = listOf(listOf("SOURCE-0001", "SOURCE-0002")),
            documentIdBySource = mapOf("SOURCE-0001" to 7L, "SOURCE-0002" to 8L),
            partCount = 1
        )
        assertTrue(report.problems.toString(), report.ok)
    }

    @Test
    fun `declared part count must match what was written`() {
        val report = MultipartIntegrity.verify(
            expectedSourceIds = listOf("SOURCE-0001"),
            actualSourceIdsByPart = listOf(listOf("SOURCE-0001")),
            documentIdBySource = mapOf("SOURCE-0001" to 1L),
            partCount = 5   // lied about the count
        )
        assertFalse(report.ok)
        assertTrue(report.problems.any { it.contains("declared 5") })
    }
}
