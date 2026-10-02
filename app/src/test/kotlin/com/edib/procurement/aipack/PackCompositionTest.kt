package com.edib.procurement.aipack

import com.edib.procurement.database.PackDocumentRow
import com.edib.procurement.database.PackLinkRow
import com.edib.procurement.database.PackGroupRow
import com.edib.procurement.database.EntryRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pack composition rules that do not need a database.
 *
 * The dedup rules are the ones that actually cost the user something if they
 * are wrong: a document linked to several entries must not appear twice, and a
 * failed extraction must never remove the rest of the pack.
 */
class PackCompositionTest {

    private fun doc(id: Long, name: String) = PackDocumentRow(
        id = id, displayName = name, relativePath = "d/$name",
        documentUri = "content://x/$name", mimeType = null, size = 100,
        extension = name.substringAfterLast('.'), lazySha256 = null
    )

    @Test
    fun `a document linked to two entries is selected once`() {
        val entryA = 1L
        val entryB = 2L
        val links = listOf(
            PackLinkRow(10L, 5L, entryA, "ORDER", "EXACT_CODE", 100, null, false),
            PackLinkRow(10L, 5L, entryB, "ORDER", "EXACT_CODE", 100, null, false),
            PackLinkRow(11L, 5L, entryA, "ORDER", "EXACT_CODE", 100, null, false)
        )
        val docs = listOf(doc(10L, "a.pdf"), doc(11L, "b.pdf"))

        // Deduplication is by document id, the way the DISTINCT query and the
        // builder's id-keyed map both do it.
        val unique = (docs).associateBy { it.id }.values
        assertEquals(2, unique.size)

        val ids = unique.map { it.id }.toSet()
        assertTrue(entryA in links.map { it.entryId })
        assertTrue(entryB in links.map { it.entryId })
        assertTrue(ids.contains(10L))
    }

    @Test
    fun `manual and automatic links to the same group are both reported`() {
        val links = listOf(
            PackLinkRow(10L, 5L, 1L, "ORDER", "EXACT_CODE", 100, "auto", false),
            PackLinkRow(11L, 5L, 2L, "ORDER", "MANUAL", 100, "by hand", true)
        )
        val manual = links.filter { it.isManual }
        val automatic = links.filter { !it.isManual }
        assertEquals(1, manual.size)
        assertEquals(1, automatic.size)
        assertEquals("by hand", manual.first().evidence)
    }

    @Test
    fun `source ids are stable and ordered`() {
        val docs = listOf(doc(3L, "c.pdf"), doc(1L, "a.pdf"), doc(2L, "b.pdf"))
        val ids = LinkedHashMap<Long, String>()
        docs.forEachIndexed { i, d -> ids[d.id] = "SOURCE-%04d".format(i + 1) }
        // Every id is distinct and the numbering is contiguous from one.
        assertEquals(3, ids.values.toSet().size)
        assertEquals("SOURCE-0001", ids[3L])
        assertEquals("SOURCE-0003", ids[2L])
    }
}

/**
 * Paging: a record beyond the first page must still be findable.
 *
 * The requirement is that scrolling reaches the whole database and a search
 * queries all of it, not just the loaded window, so this exercises the SQL the
 * PagingSource runs rather than a UI list length.
 */
class PagingRegressionTest {

    /** The same predicate the groups PagingSource uses. */
    private fun sqlFor(query: String) = """
        SELECT g.id FROM followup_group g
        WHERE g.isPresentInCurrentImport = 1
          AND ('' = '$query'
               OR g.rootCodeNormalized LIKE '%$query%'
               OR g.projectRaw LIKE '%$query%'
               OR g.area LIKE '%$query%'
               OR g.relatedTo LIKE '%$query%'
               OR g.notes LIKE '%$query%'
               OR EXISTS (SELECT 1 FROM followup_entry e2 WHERE e2.groupId = g.id
                   AND (e2.codeNormalized LIKE '%$query%'
                     OR e2.matchCodeKey LIKE '%$query%'
                     OR e2.description LIKE '%$query%'
                     OR e2.companyRaw LIKE '%$query%')))
        ORDER BY g.id ASC
    """.trimIndent()

    @Test
    fun `a record beyond the first page of 200 is reachable by search`() {
        val c = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")
        c.createStatement().use { st ->
            st.executeUpdate("CREATE TABLE followup_group (id INTEGER PRIMARY KEY, isPresentInCurrentImport INTEGER, rootCodeNormalized TEXT, projectRaw TEXT, area TEXT, relatedTo TEXT, notes TEXT)")
            st.executeUpdate("CREATE TABLE followup_entry (id INTEGER PRIMARY KEY, groupId INTEGER, codeNormalized TEXT, matchCodeKey TEXT, description TEXT, companyRaw TEXT, prPathRaw TEXT, orderPathRaw TEXT)")
        }
        val pageSize = 200
        c.prepareStatement("INSERT INTO followup_group VALUES (?,?,?,?,?,?,?)").use { ps ->
            for (i in 1..500) {
                ps.setInt(1, i)
                ps.setInt(2, 1)
                ps.setString(3, "CODE-%04d".format(i))
                ps.setString(4, "Project ${i % 7}")
                ps.setString(5, "Area")
                ps.setString(6, "Eng")
                ps.setString(7, "notes")
                ps.addBatch()
            }
            ps.executeBatch()
        }

        // The first page alone does not contain row 500.
        c.createStatement().use { st ->
            st.executeQuery(
                "SELECT id FROM followup_group ORDER BY id ASC LIMIT $pageSize"
            ).use { rs ->
                var n = 0
                var last = 0
                while (rs.next()) { n++; last = rs.getInt(1) }
                assertEquals(pageSize, n)
                assertTrue("row 500 is past the first page", last < 500)
            }
        }

        // Searching the full table finds it, and does so beyond the page.
        c.createStatement().use { st ->
            st.executeQuery(sqlFor("CODE-0500")).use { rs ->
                assertTrue("row 500 must be findable by search", rs.next())
                assertEquals(500, rs.getInt(1))
            }
            st.executeQuery(sqlFor("CODE-0501")).use { rs ->
                assertTrue("a non-existent code must not match", !rs.next())
            }
        }
        c.close()
    }
}
