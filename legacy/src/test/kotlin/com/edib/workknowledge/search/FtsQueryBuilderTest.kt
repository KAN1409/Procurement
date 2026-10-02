package com.edib.workknowledge.search

import com.edib.workknowledge.data.SearchDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FTS4 query builder, tested against the measured behaviour of the
 * SQLite build the app runs on.
 *
 * Observed on device:
 *   "MR" "104"     -> 1 row   correct
 *   "MR" AND "104" -> 0 rows  explicit AND unsupported
 *   MR-023         -> 0 rows  hyphen parsed, not literal
 *   OR / NEAR      -> malformed MATCH expression
 *
 * Every assertion below exists because one of those facts was a live bug.
 */
class FtsQueryBuilderTest {

    // ------------------------------------------------------- required identifiers

    @Test
    fun `MR-104 becomes two required quoted terms`() {
        assertEquals("\"mr\" \"104\"", FtsQueryBuilder.build("MR-104"))
    }

    @Test
    fun `PR-023 becomes two required quoted terms`() {
        assertEquals("\"pr\" \"023\"", FtsQueryBuilder.build("PR-023"))
    }

    @Test
    fun `CL-201 becomes two required quoted terms`() {
        assertEquals("\"cl\" \"201\"", FtsQueryBuilder.build("CL-201"))
    }

    @Test
    fun `explicit AND is never emitted`() {
        // "MR" AND "104" silently returns zero rows on this build.
        for (q in listOf("MR-104", "PR-023", "CL-201", "galala marble", "master bathroom")) {
            val expr = FtsQueryBuilder.build(q)!!
            assertFalse("explicit AND breaks MATCH: $expr", expr.contains(" AND "))
        }
    }

    @Test
    fun `the hyphen never reaches the query grammar`() {
        for (q in listOf("MR-104", "PR-023", "CL-201", "MR/104", "A+B", "100%")) {
            val expr = FtsQueryBuilder.build(q)!!
            assertFalse("hyphen leaked into $expr", expr.contains("-"))
            assertFalse("slash leaked into $expr", expr.contains("/"))
            assertFalse("plus leaked into $expr", expr.contains("+"))
            assertFalse("percent leaked into $expr", expr.contains("%"))
        }
    }

    // --------------------------------------------------------------- phrases

    @Test
    fun `two word material name becomes two quoted terms`() {
        assertEquals("\"piatra\" \"grey\"", FtsQueryBuilder.build("Piatra Grey"))
        assertEquals("\"galala\" \"marble\"", FtsQueryBuilder.build("Galala Marble"))
        assertEquals("\"master\" \"bathroom\"", FtsQueryBuilder.build("Master Bathroom"))
    }

    @Test
    fun `all terms are required`() {
        // A space is implicit AND, so both words must be present.
        val expr = FtsQueryBuilder.build("galala marble")!!
        assertEquals(2, expr.split(" ").size)
    }

    // ------------------------------------------------- reserved-word injection

    @Test
    fun `reserved words are quoted and become ordinary search terms`() {
        // Bare OR / NEAR raise "malformed MATCH expression" on this build.
        assertEquals("\"or\"", FtsQueryBuilder.build("OR"))
        assertEquals("\"near\"", FtsQueryBuilder.build("NEAR"))
        assertEquals("\"not\"", FtsQueryBuilder.build("NOT"))
    }

    @Test
    fun `bare wildcard is dropped rather than passed through`() {
        assertNull(FtsQueryBuilder.build("*"))
        assertNull(FtsQueryBuilder.build("  *  "))
        assertEquals("\"mr\"", FtsQueryBuilder.build("MR*"))
    }

    @Test
    fun `lone punctuation produces no query at all`() {
        for (q in listOf("", "   ", "-", "*", "/", "()", "+%")) {
            assertNull("no expression for [$q]", FtsQueryBuilder.build(q))
        }
    }

    @Test
    fun `parenthesised and quoted input is reduced to plain terms`() {
        assertEquals("\"marble\"", FtsQueryBuilder.build("( marble )"))
        assertEquals("\"galala\" \"marble\"", FtsQueryBuilder.build("\"Galala\" \"Marble\""))
    }

    @Test
    fun `mixed separators split into terms`() {
        assertEquals("\"mr\" \"104\"", FtsQueryBuilder.build("MR/104"))
        assertEquals("\"a\" \"b\"", FtsQueryBuilder.build("A+B"))
        assertEquals("\"100\"", FtsQueryBuilder.build("100%"))
    }

    @Test
    fun `arabic input survives tokenisation`() {
        assertEquals("\"رخام\"", FtsQueryBuilder.build("رخام"))
        assertEquals("\"رخام\" \"جالون\"", FtsQueryBuilder.build("رخام جالون"))
    }

    // ------------------------------------------------------------- identifiers

    @Test
    fun `identifier shaped input is recognised for exact-match boosting`() {
        assertTrue(FtsQueryBuilder.isIdentifierLike("MR-104"))
        assertTrue(FtsQueryBuilder.isIdentifierLike("PR-023"))
        assertTrue(FtsQueryBuilder.isIdentifierLike("CL-201"))
    }

    @Test
    fun `ordinary prose is not treated as an identifier`() {
        assertFalse(FtsQueryBuilder.isIdentifierLike("Piatra Grey"))
        assertFalse(FtsQueryBuilder.isIdentifierLike("Master Bathroom"))
        assertFalse(FtsQueryBuilder.isIdentifierLike("what is the rate"))
        assertFalse(FtsQueryBuilder.isIdentifierLike("MR104"))
    }

    @Test
    fun `repeats are collapsed so a term is not required twice`() {
        assertEquals("\"mr\" \"104\"", FtsQueryBuilder.build("MR-104 MR-104"))
    }
}

/**
 * Ranking policy for exact retrieval, kept separate so it can be asserted
 * without a database.
 */
class DocumentSearchRankingTest {

    private val search = DocumentSearch(NoopSearchDao)

    private fun candidate(
        chunkId: Long,
        snippet: String,
        filename: String = "WK_BOQ_Runtime_Test.xlsx",
        sheet: String? = "Master Bathroom",
        rows: Pair<Int, Int>? = 2 to 3,
        page: Int? = null
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
        snippet = snippet
    )

    @Test
    fun `exact identifier hit outranks a related but non-literal hit`() {
        val exact = candidate(
            chunkId = 2,
            snippet = "Row 2: Item Code: MR-104; Description: Galala Marble Wall Cladding; Quantity: 37.5"
        )
        val related = candidate(
            chunkId = 1,
            snippet = "Marble selection notes for the upstairs areas"
        )

        val ranked = search.rank("MR-104", listOf(related, exact))

        assertEquals("the literal identifier must come first", 2L, ranked.first().chunkId)
        assertTrue(ranked.first().exactMatch)
        assertFalse(ranked.last().exactMatch)
    }

    @Test
    fun `identifier match is recognised despite tokenizer splitting on the hyphen`() {
        val hit = search.rank(
            "MR-104",
            listOf(candidate(chunkId = 1, snippet = "Row 2: Item Code: MR-104; Quantity: 37.5"))
        ).single()
        assertTrue("MR-104 must be treated as an exact hit", hit.exactMatch)
    }

    @Test
    fun `phrase query carries sheet and row provenance`() {
        val hits = search.rank(
            "Piatra Grey",
            listOf(candidate(chunkId = 3, snippet = "Row 3: MR-105; Description: Piatra Grey Feature Band"))
        )
        assertTrue(hits.single().exactMatch)
        assertEquals("Master Bathroom", hits.single().sheetName)
        assertEquals("Master Bathroom · Rows 2–3", hits.single().location)
    }

    @Test
    fun `pdf hit carries page provenance and no sheet`() {
        val hit = search.rank(
            "PR-023",
            listOf(
                candidate(
                    chunkId = 9,
                    filename = "WK_Runtime_Test.pdf",
                    sheet = null,
                    rows = null,
                    page = 3,
                    snippet = "Purchase Request: PR-023 Status: Approved"
                )
            )
        ).single()
        assertEquals("WK_Runtime_Test.pdf", hit.filename)
        assertEquals(3, hit.pageNumber)
        assertEquals("Page 3", hit.location)
        assertNull(hit.sheetName)
    }

    @Test
    fun `ranking is deterministic for equal candidates`() {
        val a = candidate(chunkId = 5, snippet = "same text here")
        val b = candidate(chunkId = 2, snippet = "same text here")
        val ranked = search.rank("nothing", listOf(a, b))
        assertEquals(listOf(2L, 5L), ranked.map { it.chunkId })
    }

    @Test
    fun `exact boost dominates a weak literal match over a strong fuzzy one`() {
        val weakExact = search.scoreOf(
            exact = true,
            candidate = candidate(1, "short but exact")
        )
        val strongFuzzy = search.scoreOf(
            exact = false,
            candidate = candidate(2, "x".repeat(400) + " mr-104")
        )
        assertTrue(
            "exact=$weakExact must beat fuzzy=$strongFuzzy",
            weakExact > strongFuzzy
        )
    }

    private object NoopSearchDao : SearchDao {
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
            query: String, limit: Int, projectId: Long?
        ): List<SearchDao.BodyHit> = emptyList()
        override suspend fun matchBodiesInDocuments(
            query: String, limit: Int, documentIds: List<Long>
        ): List<SearchDao.BodyHit> = emptyList()
    }
}
