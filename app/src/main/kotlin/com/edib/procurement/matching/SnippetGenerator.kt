package com.edib.procurement.matching

/**
 * Generates "Why This Matched" snippets for search results.
 *
 * When a query term is found in extractedText, shows a short snippet
 * around the first matching occurrence with the matching term
 * visually distinguishable.
 */
object SnippetGenerator {

    private const val SNIPPET_RADIUS = 60 // characters before and after match
    private const val MAX_SNIPPET_LENGTH = 200

    /**
     * Generates a snippet around the first occurrence of query in text.
     *
     * @param text The full text to search in (e.g., extractedText)
     * @param query The search query
     * @return A Snippet object with the snippet text and match position, or null if no match
     */
    fun generateSnippet(text: String, query: String): Snippet? {
        if (text.isBlank() || query.isBlank()) return null

        val lowerText = text.lowercase()
        val lowerQuery = query.trim().lowercase()

        val index = lowerText.indexOf(lowerQuery)
        if (index < 0) return null

        // Calculate snippet bounds
        val start = maxOf(0, index - SNIPPET_RADIUS)
        val end = minOf(text.length, index + lowerQuery.length + SNIPPET_RADIUS)

        var snippetText = text.substring(start, end)

        // Add ellipsis if we're not at the start/end of the text
        if (start > 0) snippetText = "…$snippetText"
        if (end < text.length) snippetText = "$snippetText…"

        // Calculate the match position within the snippet
        val matchStartInSnippet = index - start + if (start > 0) 1 else 0
        val matchEndInSnippet = matchStartInSnippet + lowerQuery.length

        return Snippet(
            text = snippetText,
            matchStart = matchStartInSnippet,
            matchEnd = matchEndInSnippet,
            fullText = text,
            query = query.trim()
        )
    }

    /**
     * Generates a snippet for a DocumentListRow based on the query.
     * Checks both extractedText and other searchable fields.
     */
    fun generateDocumentSnippet(
        displayName: String,
        relativePath: String,
        extractedText: String,
        query: String
    ): Snippet? {
        // Priority: extractedText > displayName > relativePath
        return generateSnippet(extractedText, query)
            ?: generateSnippet(displayName, query)
            ?: generateSnippet(relativePath, query)
    }

    data class Snippet(
        val text: String,
        val matchStart: Int,
        val matchEnd: Int,
        val fullText: String,
        val query: String
    ) {
        /** The snippet text with the match highlighted (for UI rendering) */
        val highlightedText: String
            get() {
                if (matchStart < 0 || matchEnd > text.length) return text
                return text.substring(0, matchStart) +
                       "«${text.substring(matchStart, matchEnd)}»" +
                       text.substring(matchEnd)
            }
    }
}
