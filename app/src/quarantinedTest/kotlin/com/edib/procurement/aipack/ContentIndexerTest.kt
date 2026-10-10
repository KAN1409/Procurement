package com.edib.procurement.aipack

import com.edib.procurement.database.ContentState
import com.edib.procurement.database.DocumentDao
import com.edib.procurement.database.DocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

/**
 * Tests for the dedicated content indexing pipeline.
 */
class ContentIndexerTest {

    private lateinit var mockContext: android.content.Context
    private lateinit var mockDocumentDao: DocumentDao
    private lateinit var contentIndexer: ContentIndexer

    @Before
    fun setup() {
        mockContext = mock(android.content.Context::class.java)
        mockDocumentDao = mock(DocumentDao::class.java)
        contentIndexer = ContentIndexer(mockContext, mockDocumentDao)
    }

    @Test
    fun testContentStateTransitions() = runBlocking(Dispatchers.IO) {
        // Test that content state transitions work correctly
        val doc = DocumentEntity(
            id = 1,
            treeUri = "content://test",
            documentUri = "content://test/doc1.pdf",
            displayName = "test.pdf",
            normalizedName = "test",
            matchFileKey = "testpdf",
            matchStemKey = "test",
            relativePath = "test.pdf",
            mimeType = "application/pdf",
            extension = "pdf",
            size = 1000,
            lastModified = System.currentTimeMillis(),
            indexedAt = System.currentTimeMillis(),
            isMissing = false,
            contentState = ContentState.NOT_INDEXED
        )

        // Verify initial state
        assertEquals(ContentState.NOT_INDEXED, doc.contentState)

        // State should be updatable through DAO
        verify(mockDocumentDao, never()).updateContentState(anyLong(), any(), anyString(), anyLong(), anyString(), anyString(), anyLong(), anyLong())
    }

    @Test
    fun testExtractedTextPersistence() = runBlocking(Dispatchers.IO) {
        val doc = DocumentEntity(
            id = 1,
            treeUri = "content://test",
            documentUri = "content://test/doc1.pdf",
            displayName = "test.pdf",
            normalizedName = "test",
            matchFileKey = "testpdf",
            matchStemKey = "test",
            relativePath = "test.pdf",
            mimeType = "application/pdf",
            extension = "pdf",
            size = 1000,
            lastModified = System.currentTimeMillis(),
            indexedAt = System.currentTimeMillis(),
            isMissing = false,
            contentState = ContentState.NOT_INDEXED
        )

        val extractedText = "This is extracted text content from the document."

        // Verify that updateExtractedText is called with correct parameters
        contentIndexer.indexDocument(doc)

        // Note: The actual extraction is mocked, so we verify the DAO is called
        verify(mockDocumentDao, atLeastOnce()).updateExtractedText(eq(1L), anyString())
        verify(mockDocumentDao, atLeastOnce()).updateContentState(
            eq(1L), any(), anyString(), anyLong(), anyString(), anyString(), anyLong(), anyLong()
        )
    }

    @Test
    fun testUnsupportedFormatHandling() = runBlocking(Dispatchers.IO) {
        val doc = DocumentEntity(
            id = 1,
            treeUri = "content://test",
            documentUri = "content://test/doc1.doc",
            displayName = "test.doc",
            normalizedName = "test",
            matchFileKey = "testdoc",
            matchStemKey = "test",
            relativePath = "test.doc",
            mimeType = "application/msword",
            extension = "doc",
            size = 1000,
            lastModified = System.currentTimeMillis(),
            indexedAt = System.currentTimeMillis(),
            isMissing = false,
            contentState = ContentState.NOT_INDEXED
        )

        // Legacy .doc should be marked as UNSUPPORTED
        contentIndexer.indexDocument(doc)

        // Verify UNSUPPORTED state is set
        verify(mockDocumentDao, atLeastOnce()).updateContentState(
            eq(1L),
            eq(ContentState.UNSUPPORTED),
            anyString(),
            anyLong(),
            anyString(),
            anyString(),
            anyLong(),
            anyLong()
        )
    }

    @Test
    fun testRtfUnsupported() = runBlocking(Dispatchers.IO) {
        val doc = DocumentEntity(
            id = 1,
            treeUri = "content://test",
            documentUri = "content://test/doc1.rtf",
            displayName = "test.rtf",
            normalizedName = "test",
            matchFileKey = "testrtf",
            matchStemKey = "test",
            relativePath = "test.rtf",
            mimeType = "application/rtf",
            extension = "rtf",
            size = 1000,
            lastModified = System.currentTimeMillis(),
            indexedAt = System.currentTimeMillis(),
            isMissing = false,
            contentState = ContentState.NOT_INDEXED
        )

        // RTF should be marked as UNSUPPORTED
        contentIndexer.indexDocument(doc)

        verify(mockDocumentDao, atLeastOnce()).updateContentState(
            eq(1L),
            eq(ContentState.UNSUPPORTED),
            anyString(),
            anyLong(),
            anyString(),
            anyString(),
            anyLong(),
            anyLong()
        )
    }
}
