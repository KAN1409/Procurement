package com.edib.procurement.aipack

import java.io.OutputStream

/**
 * Writes the INDEX that makes a multipart export usable.
 *
 * The whole point is that the reader (usually a language model) can learn what
 * is in the export, and which part holds it, WITHOUT opening and uploading every
 * part first. So the index carries, per part: the file, its size, the groups it
 * contains and the SOURCE id range it covers; and a global SOURCE map that names
 * the file, the group, the entry and the part for every single source.
 */
class PackIndexWriter(
    private val exportId: String,
    private val scope: PackScope,
    private val generatedAt: String,
    private val parts: List<PackIndexWriter.PartEntry>,
    private val groups: Int,
    private val entries: Int,
    private val documents: Int,
    private val originalBytes: Long,
    private val normalizedBytes: Long,
    private val targetPartBytes: Long
) {

    /** One part, as described in the index. */
    data class PartEntry(
        val number: Int,
        val fileName: String,
        val actualBytes: Long,
        val checksum: String?,
        val groups: List<String>,
        val groupKeysContinued: List<String>,
        val groupKeysContinuing: List<String>,
        val entryCodes: List<String>,
        /** SOURCE ids present in this part. */
        val sourceIds: List<String>,
        val oversizedReason: String?
    )

    /** One row of the global source map. */
    data class SourceRow(
        val sourceId: String,
        val filename: String,
        val groupLabel: String?,
        val entryCode: String?,
        val partNumber: Int
    )

    lateinit var sources: List<SourceRow>

    fun write(out: OutputStream) {
        val w = PackTextWriter(out)
        val width = PackSplitter.numberWidth(parts.size)

        w.line("# PROCUREMENT AI PACK — MULTIPART INDEX")
        w.line()
        w.line("Format-Version: 1")
        w.line("Export-ID: $exportId")
        w.line("Generated: $generatedAt")
        w.line("Scope-Type: ${scope.type.name}")
        w.line("Scope-Name: ${scope.name}")
        w.line()
        w.line("Parts: ${parts.size}")
        w.line("Groups: $groups")
        w.line("Entries: $entries")
        w.line("Documents: $documents")
        w.line()
        w.line("Original Total Size: ${formatBytes(originalBytes)}")
        w.line("Normalized Total Size: ${formatBytes(normalizedBytes)}")
        w.line("Target Part Size: ${formatBytes(targetPartBytes)}")
        w.line()
        w.line("This export is split into ${parts.size} parts. Each part is a complete,")
        w.line("independently readable AI Pack. SOURCE ids are global across the whole")
        w.line("export and are never restarted, so a source id below always refers to")
        w.line("exactly one document in exactly one part.")
        w.line()

        for (p in parts) {
            w.rule()
            w.line(PackSplitter.partLabel(p.number, width))
            w.rule()
            w.line()
            w.line("File: ${p.fileName}")
            w.line("Size: ${formatBytes(p.actualBytes)}")
            p.checksum?.let { w.line("SHA-256: $it") }
            w.line()
            if (p.oversizedReason != null) {
                w.line("WARNING: $p.oversizedReason")
                w.line("A single document exceeded the target part size and was placed")
                w.line("here whole rather than split. It was not truncated.")
                w.line()
            }
            if (p.groupKeysContinued.isNotEmpty()) {
                w.line("Groups continued from an earlier part:")
                p.groupKeysContinued.forEach { w.line("- $it") }
                w.line()
            }
            if (p.groupKeysContinuing.isNotEmpty()) {
                w.line("Groups that continue into a later part:")
                p.groupKeysContinuing.forEach { w.line("- $it") }
                w.line()
            }
            w.line("Groups (${p.groups.size}):")
            if (p.groups.isEmpty()) w.line("- (none)") else p.groups.forEach { w.line("- $it") }
            w.line()
            w.line("Entries (${p.entryCodes.size}):")
            if (p.entryCodes.isEmpty()) w.line("- (none)") else p.entryCodes.forEach { w.line("- $it") }
            w.line()
            w.line("Sources: ${sourceRange(p.sourceIds)}")
            w.line()
        }

        w.rule()
        w.line("SOURCE MAP")
        w.rule()
        w.line()
        w.line("Every source in this export, and the part that holds it.")
        w.line()
        for (s in sources) {
            w.line(s.sourceId)
            w.line("Filename: ${s.filename}")
            w.line("Group: ${s.groupLabel ?: "(none)"}")
            w.line("Entry: ${s.entryCode ?: "(none)"}")
            w.line("Part: ${PackSplitter.partLabel(s.partNumber, width)}")
            w.line()
        }
        w.flush()
        w.close()
    }

    private fun sourceRange(ids: List<String>): String =
        if (ids.isEmpty()) "(none)"
        else if (ids.size == 1) ids.first()
        else "${ids.first()} → ${ids.last()}"
}
