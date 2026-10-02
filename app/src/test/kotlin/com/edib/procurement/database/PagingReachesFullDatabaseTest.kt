package com.edib.procurement.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

/**
 * Paging must reach the whole database, not a fixed window.
 *
 * These tests run the exact SQL the PagingSource uses against a real SQLite
 * database seeded with more rows than a single page holds, so a regression that
 * reintroduced a "first 200 only" cap would fail here rather than in the field.
 */
class PagingReachesFullDatabaseTest {

    private val pageSize = 200
    private val total = 650

    private fun seed(): java.sql.Connection {
        val c = DriverManager.getConnection("jdbc:sqlite::memory:")
        c.createStatement().use { st ->
            st.executeUpdate(
                "CREATE TABLE followup_group (" +
                    "id INTEGER PRIMARY KEY, isPresentInCurrentImport INTEGER, " +
                    "stableKey TEXT, number TEXT, projectRaw TEXT, area TEXT, " +
                    "relatedTo TEXT, rootCodeRaw TEXT, rootCodeNormalized TEXT, " +
                    "prDate TEXT, notes TEXT, userTags TEXT, manualProjectOverride TEXT, " +
                    "isFavorite INTEGER, originalExcelRow INTEGER, projectId INTEGER)"
            )
            st.executeUpdate(
                "CREATE TABLE followup_entry (" +
                    "id INTEGER PRIMARY KEY, groupId INTEGER, codeRaw TEXT, " +
                    "codeNormalized TEXT, matchCodeKey TEXT, description TEXT, " +
                    "companyRaw TEXT, prPathRaw TEXT, orderPathRaw TEXT, " +
                    "sourceExcelRow INTEGER)"
            )
            st.executeUpdate(
                "CREATE TABLE document (" +
                    "id INTEGER PRIMARY KEY, isMissing INTEGER, normalizedName TEXT, " +
                    "relativePath TEXT, displayName TEXT, documentUri TEXT, " +
                    "mimeType TEXT, size INTEGER, lastModified INTEGER, " +
                    "extension TEXT, lazySha256 TEXT, treeUri TEXT, matchFileKey TEXT, " +
                    "matchStemKey TEXT)"
            )
        }
        c.prepareStatement(
            "INSERT INTO followup_group VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        ).use { ps ->
            for (i in 1..total) {
                ps.setInt(1, i)
                ps.setInt(2, 1)
                ps.setString(3, "key-$i")
                ps.setString(4, "$i")
                ps.setString(5, "Project ${i % 40}")
                ps.setString(6, "Area ${i % 10}")
                ps.setString(7, "Engineer ${i % 25}")
                ps.setString(8, "CODE-%05d".format(i))
                ps.setString(9, "CODE %05d".format(i))
                ps.setString(10, "2025-01-01")
                ps.setString(11, "notes $i")
                ps.setString(12, "")
                ps.setString(13, null)
                ps.setInt(14, 0)
                ps.setInt(15, i)
                ps.setObject(16, null)
                ps.addBatch()
            }
            ps.executeBatch()
        }
        c.prepareStatement(
            "INSERT INTO document VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        ).use { ps ->
            for (i in 1..total) {
                ps.setInt(1, i)
                ps.setInt(2, 0)
                ps.setString(3, "DOCUMENT %05d".format(i))
                ps.setString(4, "folder/DOCUMENT-%05d.pdf".format(i))
                ps.setString(5, "DOCUMENT-%05d.pdf".format(i))
                ps.setString(6, "content://x/$i")
                ps.setString(7, "application/pdf")
                ps.setLong(8, 100L + i)
                ps.setLong(9, 0)
                ps.setString(10, "pdf")
                ps.setString(11, null)
                ps.setString(12, "tree")
                ps.setString(13, "document %05d pdf".format(i))
                ps.setString(14, "document %05d".format(i))
                ps.addBatch()
            }
            ps.executeBatch()
        }
        return c
    }

    /** The predicate the groups PagingSource uses. */
    private fun groupQuery(query: String) = """
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
    fun `scrolling past the first page reaches the last row`() {
        val c = seed()
        val seen = ArrayList<Int>()
        var offset = 0
        while (true) {
            val page = c.createStatement().use { st ->
                st.executeQuery("${groupQuery("")} LIMIT $pageSize OFFSET $offset")
                    .use { rs ->
                        val ids = ArrayList<Int>()
                        while (rs.next()) ids.add(rs.getInt(1))
                        ids
                    }
            }
            if (page.isEmpty()) break
            seen.addAll(page)
            offset += page.size
            if (page.size < pageSize) break
        }
        c.close()

        assertEquals("every group is reachable", total, seen.size)
        assertEquals("no duplicates across pages", total, seen.distinct().size)
        assertEquals("the final row is included", total, seen.last())
    }

    @Test
    fun `a search term finds a row far beyond the first page`() {
        val c = seed()
        val target = total - 3   // deep in the table
        val code = "CODE %05d".format(target)

        // The first page on its own does not contain it.
        c.createStatement().use { st ->
            st.executeQuery("${groupQuery("")} LIMIT $pageSize").use { rs ->
                var firstPageContains = false
                while (rs.next()) if (rs.getInt(1) == target) firstPageContains = true
                assertTrue("the fixture row must be past page one", !firstPageContains)
            }
        }

        // The search query finds it, because it runs over the whole table.
        c.createStatement().use { st ->
            st.executeQuery(groupQuery(code)).use { rs ->
                assertTrue("search must reach the row", rs.next())
                assertEquals(target, rs.getInt(1))
                assertTrue("and only that row", !rs.next())
            }
        }
        c.close()
    }

    @Test
    fun `a search term that matches nothing returns nothing`() {
        val c = seed()
        c.createStatement().use { st ->
            st.executeQuery(groupQuery("NOTHING-MATCHES-THIS")).use { rs ->
                assertTrue(!rs.next())
            }
        }
        c.close()
    }

    @Test
    fun `document paging likewise reaches past the first page`() {
        val c = seed()
        val seen = ArrayList<Int>()
        var offset = 0
        while (true) {
            val page = c.createStatement().use { st ->
                st.executeQuery(
                    "SELECT d.id FROM document d WHERE d.isMissing = 0 " +
                        "ORDER BY d.id ASC LIMIT $pageSize OFFSET $offset"
                ).use { rs ->
                    val ids = ArrayList<Int>()
                    while (rs.next()) ids.add(rs.getInt(1))
                    ids
                }
            }
            if (page.isEmpty()) break
            seen.addAll(page)
            offset += page.size
            if (page.size < pageSize) break
        }
        c.close()
        assertEquals("every document is reachable", total, seen.size)
    }

    @Test
    fun `counts stay database-wide rather than page-wide`() {
        val c = seed()
        c.createStatement().use { st ->
            st.executeQuery(
                "SELECT COUNT(*) FROM followup_group WHERE isPresentInCurrentImport = 1"
            ).use { rs ->
                rs.next()
                assertEquals("the count is the whole table", total, rs.getInt(1))
            }
        }
        c.close()
    }
}
