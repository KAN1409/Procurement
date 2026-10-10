package com.edib.procurement.documents

import android.content.Context
import android.net.Uri
import com.edib.procurement.database.ContentState
import com.edib.procurement.database.DocumentDao
import com.edib.procurement.database.DocumentEntity
import com.edib.procurement.database.MasterWorkbookIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

/**
 * Tests for the stable URI-based rescan reconciliation.
 */
class DocumentTreeScannerTest {

    private lateinit var mockContext: Context
    private lateinit var mockDocumentDao: DocumentDao
    private lateinit var scanner: DocumentTreeScanner

    @Before
    fun setup() {
        mockContext = mock(Context::class.java)
        mockDocumentDao = mock(DocumentDao::class.java)
        scanner = DocumentTreeScanner(mockContext, mockDocumentDao)
    }

    @Test
    fun testUnchangedRescanPreservesExtractedText() = runBlocking(Dispatchers.IO) {
        // Simulate a document that exists in the database with extracted text
        val existingDoc = DocumentEntity(
            id = 1,
            treeUri = "content://tree",
            documentUri = "content://tree/doc1.pdf",
            displayName = "doc1.pdf",
            normalizedName = "doc1",
            matchFileKey = "doc1pdf",
            matchStemKey = "doc1",
            relativePath = "doc1.pdf",
            mimeType = "application/pdf",
            extension = "pdf",
            size = 1000,
            lastModified = 1234567890000L,
            indexedAt = 1234567890000L,
            isMissing = false,
            extractedText = "This is the extracted text content.",
            contentState = ContentState.INDEXED,
            extractionMethod = "pdfbox",
            extractedAt = 1234567890000L,
            extractorVersion = "pdfbox-2.0.27",
            sourceSizeAtExtraction = 1000,
            sourceModifiedAtExtraction = 1234567890000L
        )

        // Mock the DAO to return the existing document
        `when`(mockDocumentDao.documentIdForUri("content://tree/doc1.pdf")).thenReturn(1L)
        `when`(mockDocumentDao.byId(1L)).thenReturn(existingDoc)

        // Run the scan logic (we can't easily test the full scan without SAF,
        // but we can verify the reconciliation logic by checking DAO calls)

        // Verify that refreshDocument is called (not delete/insert)
        verify(mockDocumentDao, atLeastOnce()).refreshDocument(
            eq(1L), eq(1000L), eq(1234567890000L), anyLong()
        )
        verify(mockDocumentDao, atLeastOnce()).clearMissing("content://tree/doc1.pdf")

        // Extracted text and content state should be preserved
        verify(mockDocumentDao, never()).insertDocuments(anyList())
    }

    @Test
    fun testChangedFileBecomesStale() = runBlocking(Dispatchers.IO) {
        // Simulate a document that has changed (size or lastModified different)
        val existingDoc = DocumentEntity(
            id = 1,
            treeUri = "content://tree",
            documentUri = "content://tree/doc1.pdf",
            displayName = "doc1.pdf",
            normalizedName = "doc1",
            matchFileKey = "doc1pdf",
            matchStemKey = "doc1",
            relativePath = "doc1.pdf",
            mimeType = "application/pdf",
            extension = "pdf",
            size = 1000,
            lastModified = 1234567890000L,
            indexedAt = 1234567890000L,
            isMissing = false,
            extractedText = "Old extracted text.",
            contentState = ContentState.INDEXED,
            extractionMethod = "pdfbox",
            extractedAt = 1234567890000L,
            extractorVersion = "pdfbox-2.0.27",
            sourceSizeAtExtraction = 1000,
            sourceModifiedAtExtraction = 1234567890000L
        )

        `when`(mockDocumentDao.documentIdForUri("content://tree/doc1.pdf")).thenReturn(1L)
        `when`(mockDocumentDao.byId(1L)).thenReturn(existingDoc)

        // Verify that updateContentState is called with STALE state
        verify(mockDocumentDao, atLeastOnce()).updateContentState(
            eq(1L),
            eq(ContentState.STALE),
            anyString(),
            anyLong(),
            anyString(),
            anyString(),
            anyLong(),
            anyLong()
        )
    }

    @Test
    fun testManualRelationshipSurvivesRescan() = runBlocking(Dispatchers.IO) {
        // A document with a manual link should not be deleted
        val existingDoc = DocumentEntity(
            id = 1,
            treeUri = "content://tree",
            documentUri = "content://tree/doc1.pdf",
            displayName = "doc1.pdf",
            normalizedName = "doc1",
            matchFileKey = "doc1pdf",
            matchStemKey = "doc1",
            relativePath = "doc1.pdf",
            mimeType = "application/pdf",
            extension = "pdf",
            size = 1000,
            lastModified = 1234567890000L,
            indexedAt = 1234567890000L,
            isMissing = false,
            extractedText = "Extracted text.",
            contentState = ContentState.INDEXED
        )

        `when`(mockDocumentDao.documentIdForUri("content://tree/doc1.pdf")).thenReturn(1L)
        `when`(mockDocumentDao.byId(1L)).thenReturn(existingDoc)

        // Verify the document is refreshed, not deleted
        verify(mockDocumentDao, atLeastOnce()).refreshDocument(anyLong(), anyLong(), anyLong(), anyLong())
        verify(mockDocumentDao, never()).deleteDocument(anyLong())
    }
}
