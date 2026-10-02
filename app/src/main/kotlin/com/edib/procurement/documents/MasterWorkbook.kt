package com.edib.procurement.documents

import com.edib.procurement.followup.TextNormalizer

/**
 * Identifies the master Follow-up workbook so it is not indexed as an ordinary
 * work document.
 *
 * The workbook is a DATA SOURCE, not one of the documents it describes. When the
 * user's document tree happens to contain the same file, it must not appear in
 * Documents, Unlinked, counts or matching candidates.
 *
 * Identity is established in descending order of trust:
 *
 *  1. the same document URI the user selected for the import;
 *  2. the same document id inside a shared provider (a copy exposed under a
 *     different tree URI is still the same file);
 *  3. a conservative content signature (size + lazy SHA-256).
 *
 * Deliberately NOT used: display name alone. A repository legitimately holds
 * other spreadsheets called "Following up…xlsx", and excluding those would
 * silently hide real documents.
 */
data class MasterWorkbookIdentity(
    /** The SAF URI the workbook was imported from. */
    val sourceUri: String?,
    /** The provider-specific document id inside [sourceUri]. */
    val sourceDocId: String?,
    val displayName: String?,
    val size: Long
) {
    val isKnown: Boolean get() = !sourceUri.isNullOrBlank() && size > 0
}

object MasterWorkbook {

    /**
     * SHA-256 of the master workbook.
     *
     * Hashed lazily and only when a candidate already agrees on size, so the
     * ordinary "a different file" case costs nothing.
     */
    @Volatile
    private var cachedSha: String? = null

    fun from(sourceUri: String?, displayName: String?, size: Long): MasterWorkbookIdentity =
        MasterWorkbookIdentity(
            sourceUri = sourceUri,
            sourceDocId = docIdOf(sourceUri),
            displayName = displayName,
            size = size
        )

    /** Extracts the provider document id from a SAF URI, if there is one. */
    fun docIdOf(uri: String?): String? {
        if (uri.isNullOrBlank()) return null
        return runCatching {
            val parsed = android.net.Uri.parse(uri)
            val id = android.provider.DocumentsContract.getDocumentId(parsed)
            id.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * Is this indexed document the master workbook?
     *
     * @param candidateUri the indexed document's own SAF URI
     * @param candidateDocId provider document id of the indexed document
     * @param candidateSize its size in bytes
     * @param candidateSha its SHA-256, when it has already been computed
     * @param candidateName its display name
     */
    fun isMaster(
        master: MasterWorkbookIdentity,
        candidateUri: String,
        candidateDocId: String?,
        candidateSize: Long,
        candidateSha: String? = null,
        candidateName: String? = null
    ): Boolean {
        if (!master.isKnown) return false

        // 1. Exact same URI.
        if (master.sourceUri != null && master.sourceUri == candidateUri) return true

        // 2. Same provider document id: the same file reached through a
        //    different tree URI or a copied SAF path.
        if (master.sourceDocId != null && candidateDocId != null &&
            master.sourceDocId == candidateDocId
        ) {
            return true
        }

        // 3. Content identity. The name and size must both agree AND, when the
        //    master's hash is already known, so must the content hash. This
        //    catches a copy of the workbook filed elsewhere in the repository,
        //    which the URI and document-id rules cannot see.
        if (master.size > 0 && candidateSize == master.size &&
            namesAgree(master.displayName, candidateName)
        ) {
            val expected = sourceSha()
            if (expected == null || (candidateSha != null && expected == candidateSha)) {
                return true
            }
        }
        return false
    }

    /**
     * Do the two display names denote the same file?
     *
     * Whitespace, case and unicode presentation differences are folded. The
     * extension is kept, because two spreadsheets with different extensions are
     * different documents even when their stems agree.
     */
    private fun namesAgree(a: String?, b: String?): Boolean {
        val na = TextNormalizer.normalize(a)
        val nb = TextNormalizer.normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return false
        return na == nb
    }

    fun sourceSha(): String? = cachedSha

    fun setSourceSha(sha: String?) {
        cachedSha = sha
    }
}
