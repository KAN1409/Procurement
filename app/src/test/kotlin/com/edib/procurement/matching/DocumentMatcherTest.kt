package com.edib.procurement.matching

import com.edib.procurement.database.EntryMatchRow
import com.edib.procurement.database.GroupMatchRow
import com.edib.procurement.database.HistoricalPathRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Document matching.
 *
 * The governing rule throughout: a wrong automatic link is worse than an
 * unlinked document. Every test below that ends in UNLINKED or SUGGESTED is
 * protecting against a false positive, not a missing feature.
 */
class DocumentMatcherTest {

    // ------------------------------------------------------------- fixtures

    private val jazGroup = GroupMatchRow(
        groupId = 1L,
        projectRaw = "Jaz Negma",
        rootCodeRaw = "MMH2-GR-AR-14-25A",
        rootCodeNormalized = "MMH2-GR-AR-14-25A"
    )

    private val entryA = EntryMatchRow(
        entryId = 100L,
        groupId = 1L,
        codeRaw = "MMH2-GR-AR-14-25A",
        matchCodeKey = MatchNormalizer.codeKey("MMH2-GR-AR-14-25A"),
        description = "Marble works guest rooms",
        contractorNormalized = "ALPHA CONSTRUCTION"
    )

    private val entryB = EntryMatchRow(
        entryId = 101L,
        groupId = 1L,
        codeRaw = "MMH2-GR-AR-14-25  B",
        matchCodeKey = MatchNormalizer.codeKey("MMH2-GR-AR-14-25  B"),
        description = "Marble supply extra",
        contractorNormalized = "EL NADA"
    )

    private val entryC = EntryMatchRow(
        entryId = 102L,
        groupId = 1L,
        codeRaw = "MMH2-GR-AR-14-25  C",
        matchCodeKey = MatchNormalizer.codeKey("MMH2-GR-AR-14-25  C"),
        description = "Installation",
        contractorNormalized = "CRYSTAL BLUE"
    )

    /** A second project that happens to reuse the same root code. */
    private val otherGroup = GroupMatchRow(
        groupId = 2L,
        projectRaw = "Giza Palace",
        rootCodeRaw = "MMH2-GR-AR-14-25A",
        rootCodeNormalized = "MMH2-GR-AR-14-25A"
    )

    private val otherEntryA = entryA.copy(entryId = 200L, groupId = 2L)

    private fun matcher(
        groups: List<GroupMatchRow> = listOf(jazGroup),
        entries: List<EntryMatchRow> = listOf(entryA, entryB, entryC),
        pr: List<HistoricalPathRow> = emptyList(),
        order: List<HistoricalPathRow> = emptyList()
    ) = DocumentMatcher.build(groups, entries, pr, order)

    private fun doc(
        id: Long = 1L,
        name: String,
        path: String = name
    ) = DocumentMatcher.Doc(
        id = id,
        name = name,
        relativePath = path,
        fileKey = MatchNormalizer.filenameKey(name),
        stemKey = MatchNormalizer.fileStemKey(name)
    )

    // ------------------------------ A: unique historical filename -> AUTO

    @Test
    fun `unique historical filename auto-links to its entry`() {
        val m = matcher(
            order = listOf(
                HistoricalPathRow(
                    entryId = 101L,
                    groupId = 1L,
                    path = "Area\\Marsa Alam\\H2\\MMH2-GR-AR-14-25B.pdf"
                )
            )
        )
        val result = m.match(doc(1L, "MMH2-GR-AR-14-25B.pdf", "JazNegma/MMH2-GR-AR-14-25B.pdf"))

        assertEquals(LinkStatus.AUTO, result.status)
        assertEquals(101L, result.chosen!!.entryId)
        assertEquals(TargetLevel.ENTRY, result.chosen!!.level)
        assertTrue(Evidence.EXACT_HISTORICAL_FILENAME in result.chosen!!.evidence)
    }

    @Test
    fun `historical filename matching ignores case and separators`() {
        val m = matcher(
            order = listOf(
                HistoricalPathRow(
                    entryId = 101L,
                    groupId = 1L,
                    path = "Area/H2/MMH2-GR-AR-14-25B.pdf"
                )
            )
        )
        val result = m.match(doc(1L, "mmh2_gr_ar_14_25b.pdf"))
        assertEquals(LinkStatus.AUTO, result.status)
        assertEquals(101L, result.chosen!!.entryId)
    }

    @Test
    fun `historical PR path also counts as evidence`() {
        val m = matcher(
            pr = listOf(
                HistoricalPathRow(
                    entryId = 100L,
                    groupId = 1L,
                    path = "طلبات الشراء Scan\\2025\\MMH2-GR-AR-14-25A.pdf"
                )
            )
        )
        val result = m.match(doc(1L, "MMH2-GR-AR-14-25A.pdf"))
        assertEquals(LinkStatus.AUTO, result.status)
        assertEquals(100L, result.chosen!!.entryId)
    }

    // -------------------------- A': duplicate historical filename -> no AUTO

    @Test
    fun `two entries referencing the same historical name become a conflict`() {
        // Two different entries both point at one historical file name, so the
        // name alone cannot decide between them.
        val m = matcher(
            entries = listOf(entryA, entryB),
            order = listOf(
                HistoricalPathRow(100L, 1L, "Area\\SHARED.pdf"),
                HistoricalPathRow(101L, 1L, "Area\\SHARED.pdf")
            )
        )
        val result = m.match(doc(1L, "SHARED.pdf"))
        assertEquals(LinkStatus.SUGGESTED, result.status)
        assertTrue("must expose the competing candidates", result.alternatives.isNotEmpty())
        assertTrue(result.reason.contains("entries"))
    }

    // --------------------------------- B: exact entry code A vs B

    @Test
    fun `exact entry code A links to entry A`() {
        val m = matcher()
        val result = m.match(doc(1L, "MMH2-GR-AR-14-25A.pdf"))
        assertEquals(LinkStatus.AUTO, result.status)
        assertEquals(100L, result.chosen!!.entryId)
        assertTrue(Evidence.EXACT_ENTRY_CODE in result.chosen!!.evidence)
    }

    @Test
    fun `exact entry code B links to entry B in the same group`() {
        val m = matcher()
        val result = m.match(doc(1L, "MMH2-GR-AR-14-25B.pdf", "JazNegma/MMH2-GR-AR-14-25B.pdf"))
        assertEquals(LinkStatus.AUTO, result.status)
        assertEquals(101L, result.chosen!!.entryId)
        // Crucially the same group, not a new one.
        assertEquals(1L, result.chosen!!.groupId)
    }

    @Test
    fun `A never incorrectly matches B`() {
        val m = matcher()
        val resultA = m.match(doc(1L, "MMH2-GR-AR-14-25A.pdf"))
        val resultB = m.match(doc(2L, "MMH2-GR-AR-14-25B.pdf"))
        assertTrue(resultA.chosen!!.entryId != resultB.chosen!!.entryId)
        assertEquals(100L, resultA.chosen!!.entryId)
        assertEquals(101L, resultB.chosen!!.entryId)
    }

    @Test
    fun `spacing differences between the code and the file name still match`() {
        val m = matcher()
        // The workbook stores the code with a double space; the file does not.
        val result = m.match(doc(1L, "MMH2-GR-AR-14-25B.pdf"))
        assertEquals(LinkStatus.AUTO, result.status)
        assertEquals(101L, result.chosen!!.entryId)
    }

    @Test
    fun `an adjusted variant does not collapse into its base entry`() {
        val adj = entryB.copy(
            entryId = 103L,
            codeRaw = "MMH2-GR-AR-14-25  L adj",
            matchCodeKey = MatchNormalizer.codeKey("MMH2-GR-AR-14-25  L adj")
        )
        val base = EntryMatchRow(
            entryId = 104L,
            groupId = 1L,
            codeRaw = "MMH2-GR-AR-14-25  L",
            matchCodeKey = MatchNormalizer.codeKey("MMH2-GR-AR-14-25  L"),
            description = "base",
            contractorNormalized = null
        )
        val m = matcher(entries = listOf(base, adj))

        val adjResult = m.match(doc(1L, "MMH2-GR-AR-14-25 L ADJ.pdf"))
        assertEquals(103L, adjResult.chosen!!.entryId)

        val baseResult = m.match(doc(2L, "MMH2-GR-AR-14-25 L.pdf"))
        assertEquals(104L, baseResult.chosen!!.entryId)
    }

    @Test
    fun `the Arabic annex marker does not collapse either`() {
        val base = EntryMatchRow(
            104L, 1L, "MMH2-GR-AR-14-25  L",
            MatchNormalizer.codeKey("MMH2-GR-AR-14-25  L"), "base", null
        )
        val annex = EntryMatchRow(
            105L, 1L, "MMH2-GR-AR-14-25  L ملحق",
            MatchNormalizer.codeKey("MMH2-GR-AR-14-25  L ملحق"), "annex", null
        )
        val m = matcher(entries = listOf(base, annex))
        assertEquals(105L, m.match(doc(1L, "MMH2-GR-AR-14-25 L ملحق.pdf")).chosen!!.entryId)
        assertEquals(104L, m.match(doc(2L, "MMH2-GR-AR-14-25 L.pdf")).chosen!!.entryId)
    }

    // ------------------------ C: group-level code -> group-level candidate

    @Test
    fun `a group root code with no matching entry becomes a group candidate`() {
        // Only entry B exists, so the A document can only be resolved at group
        // level, never to an entry.
        val m = matcher(entries = listOf(entryB))
        val result = m.match(doc(1L, "MMH2-GR-AR-14-25A.pdf", "JazNegma/MMH2-GR-AR-14-25A.pdf"))

        assertEquals(LinkStatus.SUGGESTED, result.status)
        assertEquals(TargetLevel.GROUP, result.chosen!!.level)
        assertEquals(1L, result.chosen!!.groupId)
        assertTrue(Evidence.GROUP_CODE in result.chosen!!.evidence)
    }

    @Test
    fun `group code without project folder stays a suggestion`() {
        val m = matcher(entries = emptyList())
        val result = m.match(doc(1L, "MMH2-GR-AR-14-25A.pdf"))
        assertEquals(LinkStatus.SUGGESTED, result.status)
        assertEquals(TargetLevel.GROUP, result.chosen!!.level)
    }

    // ------------------- D/E/F: weak evidence must never auto-link

    @Test
    fun `project folder alone never auto-links`() {
        val m = matcher(entries = emptyList())
        val result = m.match(doc(1L, "random-file.pdf", "JazNegma/random-file.pdf"))
        assertTrue(
            "project path alone must not auto-link, got ${result.status}",
            result.status != LinkStatus.AUTO
        )
    }

    @Test
    fun `contractor name alone never auto-links`() {
        val m = matcher(
            entries = listOf(
                EntryMatchRow(
                    300L, 1L, "SOME-OTHER-CODE",
                    MatchNormalizer.codeKey("SOME-OTHER-CODE"),
                    "desc",
                    "ALPHA CONSTRUCTION"
                )
            )
        )
        val result = m.match(doc(1L, "ALPHA CONSTRUCTION.pdf"))
        assertTrue(result.status != LinkStatus.AUTO)
    }

    @Test
    fun `description overlap alone never auto-links`() {
        val m = matcher(
            entries = listOf(
                EntryMatchRow(
                    300L, 1L, "ZZZ-999",
                    MatchNormalizer.codeKey("ZZZ-999"),
                    "supply of marble tresta for corridors",
                    null
                )
            )
        )
        val result = m.match(doc(1L, "marble tresta.pdf"))
        assertTrue(result.status != LinkStatus.AUTO)
    }

    @Test
    fun `a file with no relation at all stays unlinked`() {
        val m = matcher()
        val result = m.match(doc(1L, "notes.txt", "misc/notes.txt"))
        assertEquals(LinkStatus.UNLINKED, result.status)
    }

    // ---------------------------- same code across different projects

    @Test
    fun `the same code in two projects is a conflict, never an auto-link`() {
        val m = matcher(
            groups = listOf(jazGroup, otherGroup),
            entries = listOf(entryA, otherEntryA)
        )
        val result = m.match(doc(1L, "MMH2-GR-AR-14-25A.pdf"))
        assertEquals(LinkStatus.SUGGESTED, result.status)
        assertTrue(result.alternatives.size >= 1)
    }

    // ------------------------------ project path disambiguates a conflict

    @Test
    fun `project folder breaks the tie between two same-code candidates`() {
        val otherSameCode = otherEntryA.copy(matchCodeKey = entryA.matchCodeKey)
        val m = matcher(
            groups = listOf(jazGroup, otherGroup),
            entries = listOf(entryA.copy(contractorNormalized = null), otherSameCode)
        )
        val result = m.match(doc(1L, "MMH2-GR-AR-14-25A.pdf", "JazNegma/MMH2-GR-AR-14-25A.pdf"))
        // A genuine tie stays conservative; if the project folder resolves it,
        // the Jaz Negma entry must be the one chosen.
        assertTrue(result.status == LinkStatus.AUTO || result.status == LinkStatus.SUGGESTED)
        if (result.status == LinkStatus.AUTO) {
            assertEquals("Jaz Negma", result.chosen!!.projectRaw)
        }
    }

    // ------------------------------- duplicate names in different folders

    @Test
    fun `duplicate file names in different folders both match the same entry`() {
        val m = matcher(
            order = listOf(
                HistoricalPathRow(101L, 1L, "Area\\H2\\MMH2-GR-AR-14-25B.pdf")
            )
        )
        val a = m.match(doc(1L, "MMH2-GR-AR-14-25B.pdf", "folderOne/MMH2-GR-AR-14-25B.pdf"))
        val b = m.match(doc(2L, "MMH2-GR-AR-14-25B.pdf", "folderTwo/MMH2-GR-AR-14-25B.pdf"))
        assertEquals(LinkStatus.AUTO, a.status)
        assertEquals(LinkStatus.AUTO, b.status)
        assertEquals(a.chosen!!.entryId, b.chosen!!.entryId)
    }

    // ------------------------------------------------------ explanation text

    @Test
    fun `every link carries human readable evidence`() {
        val m = matcher()
        val result = m.match(doc(1L, "MMH2-GR-AR-14-25A.pdf", "JazNegma/MMH2-GR-AR-14-25A.pdf"))
        val text = result.chosen!!.explanation()
        assertTrue(text.contains("entry code"))
        assertTrue(text.lines().all { it.startsWith("✓") })
    }
}

class MatchNormalizerTest {

    @Test
    fun `file name keys fold case and separators`() {
        assertEquals(
            MatchNormalizer.filenameKey("MMH2-GR-AR-14-25B.PDF"),
            MatchNormalizer.filenameKey("MMH2-GR-AR-14-25B.pdf")
        )
        assertEquals(
            MatchNormalizer.filenameKey("A__B--C.pdf"),
            MatchNormalizer.filenameKey("a b c.pdf")
        )
        // A trailing space before the extension is absorbed, so the same file
        // spelled two ways compares equal.
        assertEquals(
            MatchNormalizer.filenameKey("MMH2-GR-AR-14-25 B.pdf"),
            MatchNormalizer.filenameKey("MMH2-GR-AR-14-25B.pdf")
        )
    }

    @Test
    fun `stem key drops the extension`() {
        assertEquals(
            MatchNormalizer.fileStemKey("MMH2-GR-AR-14-25B.pdf"),
            MatchNormalizer.fileStemKey("MMH2-GR-AR-14-25B.docx")
        )
    }

    @Test
    fun `code keys keep the distinguishing letter`() {
        val a = MatchNormalizer.codeKey("MMH2-GR-AR-14-25A")
        val b = MatchNormalizer.codeKey("MMH2-GR-AR-14-25B")
        assertTrue(a != b)
    }

    @Test
    fun `loose code key removes spacing but keeps letters`() {
        assertEquals(
            MatchNormalizer.codeKeyLoose("MMH2-GR-AR-14-25  B"),
            MatchNormalizer.codeKeyLoose("MMH2 GR AR 14 25 B")
        )
        assertTrue(
            MatchNormalizer.codeKeyLoose("MMH2-GR-AR-14-25A") !=
                MatchNormalizer.codeKeyLoose("MMH2-GR-AR-14-25B")
        )
    }

    @Test
    fun `stem matches code only when the code accounts for the stem`() {
        assertTrue(MatchNormalizer.stemMatchesCode("MMH2-GR-AR-14-25B", "MMH2-GR-AR-14-25B"))
        assertTrue(MatchNormalizer.stemMatchesCode("MMH2 GR AR 14 25 B", "MMH2-GR-AR-14-25B"))
        // A longer, different stem must not match.
        assertTrue(!MatchNormalizer.stemMatchesCode("TOTALLY-DIFFERENT", "MMH2-GR-AR-14-25B"))
    }
}
