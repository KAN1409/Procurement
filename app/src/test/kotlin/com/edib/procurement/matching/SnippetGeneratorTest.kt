package com.edib.procurement.matching

import com.edib.procurement.matching.SnippetGenerator.Snippet
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for "Why This Matched" snippet generation.
 */
class SnippetGeneratorTest {

    @Test
    fun testGenerateSnippetBasic() {
        val text = "This is a sample document with توريد وتركيب رخام جلالة ارتفاع 15 سم and more content."
        val query = "رخام"

        val snippet = requireNotNull(SnippetGenerator.generateSnippet(text, query))
        assertTrue(snippet.text.contains("رخام"))
        assertTrue(snippet.matchStart >= 0)
        assertTrue(snippet.matchEnd > snippet.matchStart)
    }

    @Test
    fun testGenerateSnippetWithHighlight() {
        val text = "Here is some content with توريد وتركيب رخام جلالة in the middle."
        val query = "رخام"

        val snippet = requireNotNull(SnippetGenerator.generateSnippet(text, query))
        val highlighted = snippet.highlightedText
        assertTrue(highlighted.contains("«رخام»"))
    }

    @Test
    fun testGenerateSnippetAtStart() {
        val text = "رخام جلالة is at the beginning of this text."
        val query = "رخام"

        val snippet = requireNotNull(SnippetGenerator.generateSnippet(text, query))
        assertEquals(0, snippet.matchStart)
        assertTrue(snippet.text.startsWith("رخام"))
    }

    @Test
    fun testGenerateSnippetAtEnd() {
        val text = "This text ends with رخام"
        val query = "رخام"

        val snippet = requireNotNull(SnippetGenerator.generateSnippet(text, query))
        assertTrue(snippet.text.endsWith("رخام"))
    }

    @Test
    fun testGenerateSnippetNoMatch() {
        val text = "This text has no matching terms."
        val query = "رخام"

        val snippet = SnippetGenerator.generateSnippet(text, query)

        assertNull(snippet)
    }

    @Test
    fun testGenerateSnippetEmptyQuery() {
        val text = "Some text content."
        val query = ""

        val snippet = SnippetGenerator.generateSnippet(text, query)

        assertNull(snippet)
    }

    @Test
    fun testGenerateSnippetEmptyText() {
        val text = ""
        val query = "رخام"

        val snippet = SnippetGenerator.generateSnippet(text, query)

        assertNull(snippet)
    }

    @Test
    fun testGenerateDocumentSnippetPriority() {
        val displayName = "document.pdf"
        val relativePath = "folder/document.pdf"
        val extractedText = "This document contains توريد وتركيب رخام جلالة for the project."
        val query = "رخام"

        val snippet = requireNotNull(SnippetGenerator.generateDocumentSnippet(
            displayName, relativePath, extractedText, query
        ))
        // Should find match in extractedText (priority 1)
        assertTrue(snippet.fullText.contains("رخام"))
    }

    @Test
    fun testGenerateDocumentSnippetFallbackToDisplayName() {
        val displayName = "رخام_جلالة_document.pdf"
        val relativePath = "folder/document.pdf"
        val extractedText = ""
        val query = "رخام"

        val snippet = requireNotNull(SnippetGenerator.generateDocumentSnippet(
            displayName, relativePath, extractedText, query
        ))
        // Should fall back to displayName
        assertTrue(snippet.fullText.contains("رخام"))
    }

    @Test
    fun testGenerateDocumentSnippetFallbackToPath() {
        val displayName = "document.pdf"
        val relativePath = "رخام/folder/document.pdf"
        val extractedText = ""
        val query = "رخام"

        val snippet = requireNotNull(SnippetGenerator.generateDocumentSnippet(
            displayName, relativePath, extractedText, query
        ))
        // Should fall back to relativePath
        assertTrue(snippet.fullText.contains("رخام"))
    }

    @Test
    fun testSnippetLengthLimit() {
        val text = "A".repeat(500) + " رخام " + "B".repeat(500)
        val query = "رخام"

        val snippet = requireNotNull(SnippetGenerator.generateSnippet(text, query))
        // Snippet should be limited to reasonable length
        assertTrue(snippet.text.length <= 250)
    }

    @Test
    fun testCaseInsensitiveMatch() {
        val text = "This has MARBLE and stone."
        val query = "marble"

        val snippet = requireNotNull(SnippetGenerator.generateSnippet(text, query))
        assertTrue(snippet.text.contains("MARBLE"))
    }

    @Test
    fun testMultipleOccurrencesUsesFirst() {
        val text = "First رخام occurrence. Middle رخام occurrence. Last رخام occurrence."
        val query = "رخام"

        val snippet = requireNotNull(SnippetGenerator.generateSnippet(text, query))
        // Should use the first occurrence
        assertTrue(snippet.text.startsWith("First رخام"))
    }
}
