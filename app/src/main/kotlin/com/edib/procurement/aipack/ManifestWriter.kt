package com.edib.procurement.aipack

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the pack manifest.
 *
 * Hand-written rather than serialised with a JSON library: the shape is fixed
 * and small, and this keeps the dependency surface unchanged. Every extraction
 * outcome, including failures, is recorded so a pack is self-describing.
 */
object ManifestWriter {

    fun build(
        scope: PackScope,
        groups: Int,
        entries: Int,
        documents: Int,
        originalTotalBytes: Long,
        normalizedBytes: Long,
        sources: List<PackSource>,
        /** Size of the finished artifact; 0 until the archive is closed. */
        finalArtifactBytes: Long = 0
    ): String {
        val sb = StringBuilder()
        val generatedAt =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())

        sb.append("{\n")
        sb.append("  \"formatVersion\": 1,\n")
        sb.append("  \"generatedAt\": \"").append(generatedAt).append("\",\n")

        sb.append("  \"scope\": {\n")
        sb.append("    \"type\": \"").append(escape(scope.type.name)).append("\",\n")
        sb.append("    \"name\": \"").append(escape(scope.name)).append("\",\n")
        appendIds(sb, "groupIds", scope.groupIds, last = false)
        appendIds(sb, "entryIds", scope.entryIds, last = false)
        appendIds(sb, "documentIds", scope.documentIds, last = true)
        sb.append("  },\n")

        sb.append("  \"counts\": {\n")
        sb.append("    \"groups\": ").append(groups).append(",\n")
        sb.append("    \"entries\": ").append(entries).append(",\n")
        sb.append("    \"documents\": ").append(documents).append(",\n")
        sb.append("    \"extractionSucceeded\": ")
            .append(sources.count { it.status == ExtractionStatus.SUCCESS }).append(",\n")
        sb.append("    \"extractionOcrRequired\": ")
            .append(sources.count { it.status == ExtractionStatus.OCR_REQUIRED }).append(",\n")
        sb.append("    \"extractionUnsupported\": ")
            .append(sources.count { it.status == ExtractionStatus.UNSUPPORTED }).append(",\n")
        sb.append("    \"extractionErrors\": ")
            .append(sources.count { it.status == ExtractionStatus.ERROR }).append(",\n")
        sb.append("    \"extractionSkipped\": ")
            .append(sources.count { it.status == ExtractionStatus.SKIPPED }).append("\n")
        sb.append("  },\n")

        // Three quantities that are genuinely different, named so a reader
        // cannot confuse them:
        //   originalSourceBytes         - the source files on disk
        //   normalizedUncompressedBytes - the extracted text, uncompressed
        //   finalArtifactBytes          - the generated file the user uploads
        // The original v1 names are kept alongside them so a v1 manifest stays
        // readable by anything already consuming this format.
        sb.append("  \"originalSourceBytes\": ").append(originalTotalBytes).append(",\n")
        sb.append("  \"normalizedUncompressedBytes\": ").append(normalizedBytes).append(",\n")
        sb.append("  \"originalTotalBytes\": ").append(originalTotalBytes).append(",\n")
        sb.append("  \"normalizedBytes\": ").append(normalizedBytes).append(",\n")
        // The generated file's own size, recorded once the archive is closed.
        // 0 while the pack is still being written.
        sb.append("  \"finalArtifactBytes\": ").append(finalArtifactBytes).append(",\n")

        sb.append("  \"documents\": [\n")
        sources.forEachIndexed { i, s ->
            sb.append("    {\n")
            sb.append("      \"sourceId\": \"").append(escape(s.sourceId)).append("\",\n")
            sb.append("      \"filename\": \"").append(escape(s.filename)).append("\",\n")
            sb.append("      \"relativePath\": \"").append(escape(s.relativePath)).append("\",\n")
            sb.append("      \"mimeType\": ").append(quoteOrNull(s.mimeType)).append(",\n")
            sb.append("      \"sizeBytes\": ").append(s.sizeBytes).append(",\n")
            sb.append("      \"linkType\": ").append(quoteOrNull(s.linkType)).append(",\n")
            sb.append("      \"linkedManually\": ").append(s.linkedManually).append(",\n")
            sb.append("      \"matchEvidence\": ")
                .append(quoteOrNull(s.matchEvidence?.replace("\n", "; "))).append(",\n")
            sb.append("      \"extractionStatus\": \"")
                .append(s.status.name).append("\",\n")
            sb.append("      \"detail\": ").append(quoteOrNull(s.detail)).append(",\n")
            sb.append("      \"checksumSha256\": ").append(quoteOrNull(s.checksum)).append("\n")
            sb.append("    }")
            if (i != sources.lastIndex) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun appendIds(sb: StringBuilder, name: String, ids: List<Long>, last: Boolean) {
        sb.append("    \"").append(name).append("\": [")
        ids.forEachIndexed { i, id ->
            if (i > 0) sb.append(", ")
            sb.append(id)
        }
        sb.append("]")
        if (!last) sb.append(",")
        sb.append("\n")
    }

    private fun quoteOrNull(value: String?): String =
        if (value == null) "null" else "\"" + escape(value) + "\""

    private fun escape(s: String): String = buildString(s.length) {
        for (ch in s) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
    }
}
