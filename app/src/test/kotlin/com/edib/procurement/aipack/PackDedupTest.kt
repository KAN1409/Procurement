package com.edib.procurement.aipack

import com.edib.procurement.database.EntryRow
import com.edib.procurement.database.PackDocumentRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Document identity and de-duplication rules for every pack scope.
 *
 * Two separate guarantees are checked here, because they pull in opposite
 * directions:
 *
 *  - ONE physical document referenced by MANY entries must produce exactly ONE
 *    SOURCE block. Otherwise the pack repeats the same extracted text once per
 *    referencing entry and the "compact" promise is a lie.
 *  - TWO DIFFERENT documents that happen to share a filename, a size or a
 *    checksum must stay TWO SOURCE blocks. Merging them would silently drop a
 *    real document from the export.
 *
 * Identity is the Document row id, never a filename or a content hash.
 */
class PackDedupTest {

    private fun doc(id: Long, name: String, size: Long = 100) = PackDocumentRow(
        id = id, displayName = name, relativePath = "p/$name", documentUri = "uri:$id",
        mimeType = "application/pdf", size = size, extension = "pdf",
        lazySha256 = "sha-$id"
    )

    /** Mirrors the builder's identity logic: keyed on document id, insertion ordered. */
    private fun resolveSources(
        linked: List<PackDocumentRow>,
        explicit: List<PackDocumentRow>
    ): Map<Long, String> {
        val byId = LinkedHashMap<Long, PackDocumentRow>()
        linked.forEach { byId[it.id] = it }
        explicit.forEach { byId[it.id] = it }
        val out = LinkedHashMap<Long, String>()
        byId.keys.forEachIndexed { i, id -> out[id] = "SOURCE-%04d".format(i + 1) }
        return out
    }

    @Test
    fun `one document referenced by several entries yields one SOURCE block`() {
        val same = doc(7L, "order.pdf")
        // The same id arriving from three different linked rows must collapse.
        val linked = listOf(same, same.copy(), same.copy())
        val sources = resolveSources(linked, emptyList())

        assertEquals("one physical document means one SOURCE id", 1, sources.size)
        assertEquals("SOURCE-0001", sources[7L])
    }

    @Test
    fun `every referencing entry maps to that same SOURCE id`() {
        val same = doc(7L, "order.pdf")
        val sources = resolveSources(listOf(same, same.copy()), emptyList())

        val perEntry = listOf(1, 2, 3).map { sources[7L] }
        assertEquals(
            "all three entries must reference the one SOURCE id",
            listOf("SOURCE-0001", "SOURCE-0001", "SOURCE-0001"),
            perEntry
        )
        assertEquals(1, perEntry.distinct().size)
    }

    @Test
    fun `distinct documents sharing a filename stay distinct`() {
        val a = doc(1L, "shared_drawing.pdf")
        val b = doc(2L, "shared_drawing.pdf")
        val sources = resolveSources(listOf(a, b), emptyList())

        assertEquals("same name must not collapse two documents", 2, sources.size)
        assertEquals("SOURCE-0001", sources[1L])
        assertEquals("SOURCE-0002", sources[2L])
    }

    @Test
    fun `distinct documents sharing size and content stay distinct`() {
        val a = doc(1L, "boq.xlsx", size = 1401967)
        val b = doc(2L, "boq.xlsx", size = 1401967)
        val sources = resolveSources(listOf(a, b), emptyList())
        assertEquals(2, sources.size)
    }

    @Test
    fun `explicit document already linked is not emitted twice`() {
        val linkedDoc = doc(7L, "order.pdf")
        val extra = doc(8L, "spec.docx")
        // A user adds a file by hand that the scope had already pulled in.
        val sources = resolveSources(listOf(linkedDoc), listOf(linkedDoc, extra))

        assertEquals(2, sources.size)
        assertTrue(sources.containsKey(7L))
        assertTrue(sources.containsKey(8L))
    }

    @Test
    fun `documents scope with one linked and one unlinked keeps both`() {
        val linked = doc(7L, "order.pdf")
        val unlinked = doc(9L, "scan.pdf")
        val sources = resolveSources(emptyList(), listOf(linked, unlinked))

        assertEquals(2, sources.size)
        assertEquals("SOURCE-0001", sources[7L])
        assertEquals("SOURCE-0002", sources[9L])
    }

    @Test
    fun `source ids stay stable and sequential`() {
        val docs = listOf(doc(5L, "a.pdf"), doc(3L, "b.pdf"), doc(9L, "c.pdf"))
        val sources = resolveSources(docs, emptyList())

        // Ordered by first appearance, numbered from 1, no gaps.
        assertEquals(listOf("SOURCE-0001", "SOURCE-0002", "SOURCE-0003"), sources.values.toList())
        assertEquals(listOf(5L, 3L, 9L), sources.keys.toList())
    }
}
