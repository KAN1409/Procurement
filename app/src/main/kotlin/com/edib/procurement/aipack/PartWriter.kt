package com.edib.procurement.aipack

import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes one part of a multipart AI Pack export.
 *
 * A part is a complete, independently readable pack: it repeats the minimum
 * header a reader needs (export id, "Part X of Y", scope) so nobody has to open
 * the index first, and it states explicitly when a group starts, continues or
 * ends so continuity never has to be inferred.
 *
 * It writes to a temporary ".part" file and only renames on success, so an
 * interrupted run can never leave a file that looks finished.
 */
class PartWriter(
    private val exportId: String,
    private val partNumber: Int,
    private val partCount: Int,
    private val width: Int,
    private val targetPartBytes: Long
) {

    /** Where this part's context boundaries lie, computed by the planner. */
    data class Context(
        /** Groups whose content first appears in this part. */
        val starts: List<String>,
        /** Groups that began in an earlier part and are written again here. */
        val continues: List<String>,
        /** Groups whose content is still incomplete after this part. */
        val carriesOn: List<String>,
        val ends: List<String>
    )

    class PartFile(
        val file: File,
        val actualBytes: Long,
        val sha256: String,
        val normalizedBytes: Long
    )

    /**
     * Writes [body] into a zip at [outFile], adding the part header, the
     * manifest and the continuity notes around it.
     *
     * [body] writes only the middle of the pack (group/entry/source content) so
     * this function owns the framing and cannot be skipped.
     */
    fun write(
        outFile: File,
        tempFile: File,
        scope: PackScope,
        generatedAt: String,
        context: Context,
        groupsLabel: List<String>,
        entryCodes: List<String>,
        originalBytes: Long,
        documentCount: Int,
        groupCount: Int,
        entryCount: Int,
        oversizedReason: String?,
        sources: List<PackSource>,
        body: (PackTextWriter) -> Unit
    ): PartFile {
        tempFile.outputStream().buffered(64 * 1024).use { raw ->
            ZipOutputStream(raw).use { zip ->
                zip.putNextEntry(ZipEntry("AI_PACK.md"))
                val text = PackTextWriter(zip)

                // ---- framing header: makes the part standalone ----
                text.line("# PROCUREMENT AI PACK")
                text.line()
                text.line("Format-Version: 1")
                text.line("Export-ID: $exportId")
                text.line("Export: MULTIPART")
                text.line("${PackSplitter.partLabel(partNumber, width)} of $partCount")
                text.line("Generated: $generatedAt")
                text.line("Scope-Type: ${scope.type.name}")
                text.line("Scope-Name: ${scope.name}")
                text.line()
                text.line("This is part ${partNumber} of $partCount of a single logical export.")
                text.line("The INDEX file for this export lists every part and maps every")
                text.line("SOURCE id to the part that holds it. SOURCE ids are global to the")
                text.line("export and are not restarted per part.")
                text.line()
                text.line("Target Part Size: ${formatBytes(targetPartBytes)}")
                text.line("Groups in this part: $groupCount")
                text.line("Entries in this part: $entryCount")
                text.line("Documents in this part: $documentCount")
                text.line()

                // ---- continuity, stated explicitly ----
                text.line("CONTEXT")
                text.line("--------")
                text.line()
                context.continues.forEach { text.line("GROUP CONTINUED FROM AN EARLIER PART: $it") }
                context.starts.forEach { text.line("GROUP STARTS IN THIS PART: $it") }
                context.carriesOn.forEach { text.line("GROUP CONTINUES IN A LATER PART: $it") }
                context.ends.forEach { text.line("GROUP ENDS IN THIS PART: $it") }
                if (context.starts.isEmpty() && context.continues.isEmpty() &&
                    context.carriesOn.isEmpty() && context.ends.isEmpty()
                ) {
                    text.line("(document-only export: no Follow-up groups in scope)")
                }
                text.line()

                if (oversizedReason != null) {
                    text.line("OVERSIZED PART")
                    text.line("---------------")
                    text.line()
                    text.line("Reason: $oversizedReason")
                    text.line("A single document in this part is larger than the target part")
                    text.line("size. It was placed here whole and was NOT truncated or split.")
                    text.line()
                }

                // ---- the logical content ----
                body(text)

                text.endLine()
                text.line()
                val normalized = text.size
                text.line("Normalized Text Size: ${formatBytes(normalized)}")
                text.flush()
                zip.closeEntry()

                // ---- per-part manifest ----
                zip.putNextEntry(ZipEntry("manifest.json"))
                val manifest = MultipartManifest.build(
                    exportId = exportId,
                    partNumber = partNumber,
                    partCount = partCount,
                    targetPartBytes = targetPartBytes,
                    scope = scope,
                    generatedAt = generatedAt,
                    groupsInPart = groupsLabel,
                    entriesInPart = entryCodes,
                    originalBytes = originalBytes,
                    normalizedBytes = normalized,
                    documentCount = documentCount,
                    oversizedReason = oversizedReason,
                    sources = sources
                )
                zip.write(manifest.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        if (outFile.exists()) outFile.delete()
        if (!tempFile.renameTo(outFile)) {
            tempFile.copyTo(outFile, overwrite = true)
            tempFile.delete()
        }
        return PartFile(
            file = outFile,
            actualBytes = outFile.length(),
            sha256 = sha256(outFile),
            normalizedBytes = normalizedBytesOf(outFile)
        )
    }

    companion object {
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().buffered(64 * 1024).use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** Bytes of normalized text inside a zip, not the compressed file size. */
        fun normalizedBytesOf(f: File): Long = try {
            java.util.zip.ZipFile(f).use { z ->
                val e = z.getEntry("AI_PACK.md")
                if (e == null) 0L else z.getInputStream(e).use { s ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = s.read(buf)
                        if (n <= 0) break
                        total += n
                    }
                    total
                }
            }
        } catch (t: Throwable) {
            0L
        }
    }
}

/**
 * The manifest that ships inside each part.
 *
 * It states plainly that it belongs to a multipart export and carries the
 * export id, so a part found on its own still explains where it came from and
 * which siblings exist.
 */
object MultipartManifest {

    private fun esc(s: String?): String {
        val raw = s ?: ""
        return buildString {
            raw.forEach { c ->
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
                }
            }
        }
    }

    fun build(
        exportId: String,
        partNumber: Int,
        partCount: Int,
        targetPartBytes: Long,
        scope: PackScope,
        generatedAt: String,
        groupsInPart: List<String>,
        entriesInPart: List<String>,
        originalBytes: Long,
        normalizedBytes: Long,
        /** Byte size of the finished ZIP; 0 while the part is still being written. */
        finalArtifactBytes: Long = 0,
        documentCount: Int,
        oversizedReason: String?,
        sources: List<PackSource>
    ): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"formatVersion\": 1,\n")
        sb.append("  \"exportKind\": \"MULTIPART\",\n")
        sb.append("  \"belongsToMultipartExport\": true,\n")
        sb.append("  \"exportId\": \"${esc(exportId)}\",\n")
        sb.append("  \"generatedAt\": \"${esc(generatedAt)}\",\n")
        sb.append("  \"partNumber\": $partNumber,\n")
        sb.append("  \"partCount\": $partCount,\n")
        sb.append("  \"targetPartBytes\": $targetPartBytes,\n")
        // Placeholder: the real final ZIP size is written by the builder after the
        // manifest rewrite, when the artifact is final and its bytes are known.
        sb.append("  \"finalPartBytes\": $finalArtifactBytes,\n")
        if (oversizedReason != null) {
            sb.append("  \"oversizedReason\": \"${esc(oversizedReason)}\",\n")
        } else {
            sb.append("  \"oversizedReason\": null,\n")
        }
        sb.append("  \"scope\": {\n")
        sb.append("    \"type\": \"${esc(scope.type.name)}\",\n")
        sb.append("    \"name\": \"${esc(scope.name)}\"\n")
        sb.append("  },\n")
        sb.append("  \"counts\": {\n")
        sb.append("    \"groups\": ${groupsInPart.size},\n")
        sb.append("    \"entries\": ${entriesInPart.size},\n")
        sb.append("    \"documents\": $documentCount\n")
        sb.append("  },\n")
        sb.append("  \"originalBytes\": $originalBytes,\n")
        sb.append("  \"normalizedUncompressedBytes\": $normalizedBytes,\n")
        // v1 field name, retained so existing readers keep working.
        sb.append("  \"actualPartBytes\": $finalArtifactBytes,\n")
        sb.append("  \"groups\": [\n")
        groupsInPart.forEachIndexed { i, g ->
            sb.append("    \"${esc(g)}\"")
            if (i < groupsInPart.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ],\n")
        sb.append("  \"entries\": [\n")
        entriesInPart.forEachIndexed { i, e ->
            sb.append("    \"${esc(e)}\"")
            if (i < entriesInPart.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ],\n")
        sb.append("  \"documents\": [\n")
        sources.forEachIndexed { i, s ->
            sb.append("    {\n")
            sb.append("      \"sourceId\": \"${esc(s.sourceId)}\",\n")
            sb.append("      \"documentId\": ${s.documentId ?: "null"},\n")
            sb.append("      \"filename\": \"${esc(s.filename)}\",\n")
            sb.append("      \"relativePath\": \"${esc(s.relativePath)}\",\n")
            sb.append("      \"sizeBytes\": ${s.sizeBytes},\n")
            sb.append("      \"linkType\": \"${esc(s.linkType)}\",\n")
            sb.append("      \"linkedManually\": ${s.linkedManually},\n")
            sb.append("      \"extractionStatus\": \"${esc(s.status.name)}\",\n")
            sb.append("      \"detail\": \"${esc(s.detail)}\"\n")
            sb.append("    }")
            if (i < sources.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }
}
