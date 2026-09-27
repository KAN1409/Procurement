package com.edib.workknowledge.search

import com.edib.workknowledge.data.SearchDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ranking and query-safety policy for exact retrieval.
 *
 * These are the rules that make `MR-104` find the MR-104 row rather than a
 * loosely related passage, so they are tested directly rather than only
 * through a live database.
 */
class DocumentSearchRankingTest {

    private val search = DocumentSearch(FakeSearchDao)

    private fun candidate(
        chunkId: Long,
        snippet: String,
        filename: String = "WK_BOQ_Runtime_Test.xlsx",
        sheet: String? = "Master Bathroom",
        rows: Pair<Int, Int>? = 2 to 3,
        page: Int? = null,
        bm25: Double = -5.0
    ) = DocumentSearch.Candidate(
        chunkId = chunkId,
        projectId = 1,
        documentId = 1,
        filename = filename,
        projectName = "WK Runtime Verification",
        pageNumber = page,
        sheetName = sheet,
        rowStart = rows?.first,
        rowEnd = rows?.second,
        cellRange = null,
        snippet = snippet,
        bm25Rank = bm25
    )

    // ------------------------------------------------------------- query safety

    @Test
    fun `identifier with a hyphen is tokenised safely, not treated as an operator`() {
        val match = search.toMatchExpression("PR-023")
        assertNotNull(match)
        // FTS would treat a bare PR-023 as "PR NOT 023" and would throw on
        // an unquoted term, so the hyphen must never reach the query raw.
        assertFalse("the raw hyphen must not survive into the MATCH expression", match!!.contains("PR-023"))
        assertTrue(match.contains("\"PR\""))
        assertTrue(match.contains("\"023\""))
    }

    @Test
    fun `last token gets a prefix wildcard for as-you-type search`() {
        val match = search.toMatchExpression("Piatra Gre")!!
        assertTrue(match.endsWith("\"Gre\"*"))
    }

    @Test
    fun `all tokens are required rather than any-token matching`() {
        assertTrue(search.toMatchExpression("master bathroom")!!.contains(" AND "))
    }

    @Test
    fun `double quotes in user input cannot break out of the expression`() {
        // The tokeniser keeps only letters, digits, underscore and the Arabic
        // block, so a raw quote is discarded before the expression is built.
        // No user-supplied quote can reach the MATCH string.
        assertEquals("\"a\" AND \"b\"*", search.toMatchExpression("a\"b"))
    }

    @Test
    fun `an injection attempt is reduced to quoted plain tokens`() {
        // FTS operators are neutralised by quoting: "OR" becomes the literal
        // token "OR" that must also appear in the text, not a boolean operator.
        val match = search.toMatchExpression("MR-104\" OR \"zzz")!!
        assertEquals("\"MR\" AND \"104\" AND \"OR\" AND \"zzz\"*", match)
    }

    @Test
    fun `empty or symbol-only queries produce no expression`() {
        assertNull(search.toMatchExpression(""))
        assertNull(search.toMatchExpression("   "))
        assertNull(search.toMatchExpression("---"))
    }

    @Test
    fun `arabic text is tokenised rather than discarded`() {
        val match = search.toMatchExpression("رخام")!!
        assertTrue("Arabic must survive tokenisation", match.contains("رخام"))
    }

    // ----------------------------------------------------------------- ranking

    @Test
    fun `exact identifier hit outranks a semantically closer but non-literal hit`() {
        val exact = candidate(
            chunkId = 2,
            snippet = "HEADERS: Item Code | Description\nRow 2: Item Code: MR-104; " +
                "Description: Galala Marble Wall Cladding; Quantity: 37.5",
            bm25 = -1.0
        )
        val related = candidate(
            chunkId = 1,
            snippet = "Marble selection notes for the upstairs areas",
            bm25 = -15.0
        )

        val ranked = search.rank("MR-104", listOf(related, exact))

        assertEquals("the literal identifier must come first", 2L, ranked.first().chunkId)
        assertTrue(ranked.first().exactMatch)
        assertFalse(ranked.last().exactMatch)
    }

    @Test
    fun `identifier match is recognised despite tokenizer splitting on the hyphen`() {
        // The FTS tokenizer turns MR-104 into "mr" and "104", so a literal
        // substring test on the raw query would miss. The boost must still fire.
        val hit = search.rank(
            "MR-104",
            listOf(candidate(chunkId = 1, snippet = "Row 2: Item Code: MR-104; Quantity: 37.5"))
        ).single()
        assertTrue("MR-104 must be treated as an exact hit", hit.exactMatch)
    }

    @Test
    fun `phrase query matches the material name`() {
        val hits = search.rank(
            "Piatra Grey",
            listOf(
                candidate(
                    chunkId = 3,
                    filename = "WK_BOQ_Runtime_Test.xlsx",
                    snippet = "Row 3: Item Code: MR-105; Description: Piatra Grey Feature Band"
                )
            )
        )
        assertTrue(hits.single().exactMatch)
        assertEquals("Master Bathroom", hits.single().sheetName)
    }

    @Test
    fun `ranking is stable for equal scores`() {
        val a = candidate(chunkId = 5, snippet = "same text here", bm25 = -5.0)
        val b = candidate(chunkId = 2, snippet = "same text here", bm25 = -5.0)
        val ranked = search.rank("nothing", listOf(a, b))
        // Deterministic tie-break by chunk id, so repeated runs agree.
        assertEquals(listOf(2L, 5L), ranked.map { it.chunkId })
    }

    @Test
    fun `provenance is carried through to the hit`() {
        val pdf = candidate(
            chunkId = 9,
            filename = "WK_Runtime_Test.pdf",
            sheet = null,
            rows = null,
            page = 3,
            snippet = "Purchase Request: PR-023 Status: Approved"
        )
        val hit = search.rank("PR-023", listOf(pdf)).single()
        assertEquals("WK_Runtime_Test.pdf", hit.filename)
        assertEquals(3, hit.pageNumber)
        assertEquals("Page 3", hit.location)
        assertNull(hit.sheetName)
    }

    @Test
    fun `excel provenance names the sheet and row range`() {
        val hit = search.rank(
            "MR-104",
            listOf(candidate(chunkId = 2, snippet = "MR-104 row", rows = 2 to 3))
        ).single()
        assertEquals("Master Bathroom · Rows 2–3", hit.location)
    }

    @Test
    fun `exact boost is large enough to dominate relevance`() {
        val weakExact = search.scoreOf(exact = true, bm25Rank = 0.0)
        val strongFuzzy = search.scoreOf(exact = false, bm25Rank = -20.0)
        assertTrue(
            "a weak exact hit (score=$weakExact) must beat a strong fuzzy one (score=$strongFuzzy)",
            weakExact > strongFuzzy
        )
    }

    /** Not used for ranking; present so the class can be constructed. */
    private object FakeSearchDao : SearchDao {
        override suspend fun insertAll(rows: List<com.edib.workknowledge.data.ChunkFtsEntity>) = Unit
        override suspend fun deleteByIds(ids: List<Long>) = Unit
        override suspend fun deleteById(chunkId: Long) = Unit
        override suspend fun deleteByDocument(documentId: Long) = Unit
        override suspend fun clearIndex() = Unit
        override suspend fun insertIndex(rows: List<com.edib.workknowledge.data.ChunkFtsEntity>) = Unit
        override suspend fun readAllForIndex(): List<com.edib.workknowledge.data.ChunkFtsEntity> = emptyList()
        override suspend fun count(): Int = 0
        override suspend fun match(query: String, limit: Int): List<SearchDao.FtsHit> = emptyList()
        override suspend fun matchBodies(
            query: String, limit: Int, projectId: Long?, documentIds: List<Long>?
        ): List<SearchDao.BodyHit> = emptyList()
    }
}
