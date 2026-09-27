package com.edib.workknowledge.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vector codec is the one place where a silent mistake would corrupt every
 * ranking result, so it is tested directly.
 */
class VectorCodecTest {

    @Test
    fun `round trip preserves every float exactly`() {
        val original = floatArrayOf(1f, -2.5f, 3.14159f, 0f, -0.001f, 1e-8f, 12345.678f)
        val decoded = VectorCodec.decode(VectorCodec.encode(original))
        assertNotNull(decoded)
        assertArrayEquals(original, decoded!!, 0f)
    }

    @Test
    fun `round trip preserves length`() {
        val original = FloatArray(768) { it / 768f }
        val decoded = VectorCodec.decode(VectorCodec.encode(original))
        assertEquals(768, decoded!!.size)
    }

    @Test
    fun `encoding is four bytes per dimension`() {
        assertEquals(768 * 4, VectorCodec.encode(FloatArray(768)).size)
    }

    @Test
    fun `decode rejects a buffer that is not a whole number of floats`() {
        // A truncated write must not be read back as garbage numbers.
        assertNull(VectorCodec.decode(byteArrayOf(1, 2, 3)))
        assertNull(VectorCodec.decode(ByteArray(0)))
    }

    @Test
    fun `decode rejects a dimension count that differs from the query vector`() {
        val small = VectorCodec.encode(FloatArray(4))
        assertNull(VectorCodec.decode(small)?.takeIf { it.size == 768 })
    }

    @Test
    fun `subnormals and infinities survive the round trip`() {
        val edge = floatArrayOf(
            Float.MIN_VALUE,
            -Float.MAX_VALUE,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            0.0f,
            -0.0f
        )
        val decoded = VectorCodec.decode(VectorCodec.encode(edge))!!
        assertArrayEquals(edge, decoded, 0f)
    }

    @Test
    fun `chunk citation strings name the real source location`() {
        val pdfChunk = Chunk(
            projectId = 1, documentId = 1, chunkIndex = 0,
            content = "x", pageNumber = 6
        )
        assertEquals("Page 6", pdfChunk.citationString)
        assertEquals("Page 6", pdfChunk.fullCitationString)

        val excelChunk = Chunk(
            projectId = 1, documentId = 1, chunkIndex = 0,
            content = "x", sheetName = "Master Bathroom", rowStart = 42, rowEnd = 47
        )
        assertEquals("Master Bathroom • Rows 42–47", excelChunk.citationString)
        assertEquals("Master Bathroom, Rows 42–47", excelChunk.fullCitationString)
    }

    @Test
    fun `chunk to entity carries provenance and embedding`() {
        val chunk = Chunk(
            id = 0,
            projectId = 3,
            documentId = 9,
            chunkIndex = 4,
            content = "Galala Marble Wall Cladding",
            embedding = floatArrayOf(1f, 2f, 3f),
            pageNumber = 2,
            sheetName = "Master Bathroom",
            rowStart = 42,
            rowEnd = 47,
            cellRange = "A42:F47"
        )
        val entity = chunk.toEntity()
        assertEquals(3L, entity.projectId)
        assertEquals(9L, entity.documentId)
        assertEquals(2, entity.pageNumber)
        assertEquals("Master Bathroom", entity.sheetName)
        assertEquals(42, entity.rowStart)
        assertEquals("A42:F47", entity.cellRange)
        assertEquals(12, entity.embedding!!.size)

        val back = Chunk.fromEntity(entity)
        assertTrue(back.embedding!!.contentEquals(chunk.embedding!!))
        assertEquals("Master Bathroom", back.sheetName)
    }
}
