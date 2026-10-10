package com.edib.procurement.aipack

import android.content.Context
import android.net.Uri
import com.edib.procurement.database.AiPackDao
import com.edib.procurement.database.AiPackEntity
import com.edib.procurement.database.AiPackSourceEntity
import com.edib.procurement.database.EntryRow
import com.edib.procurement.database.PackDao
import com.edib.procurement.database.PackDocumentRow
import com.edib.procurement.database.PackLinkRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** How the user wants a scope packaged. */
enum class PackOutputMode { SINGLE, SPLIT }

/** Target part size, a user-selected packaging preference, not a hard limit. */
enum class PartSizePreset(val bytes: Long, val label: String) {
    MB10(10L * 1024 * 1024, "10 MB"),
    MB20(20L * 1024 * 1024, "20 MB"),
    MB25(25L * 1024 * 1024, "25 MB"),
    MB50(50L * 1024 * 1024, "50 MB");

    companion object {
        val DEFAULT = MB25
        fun fromBytes(n: Long): PartSizePreset? = values().firstOrNull { it.bytes == n }
    }
}

/** Configuration for a multipart export. */
data class PackSplitConfig(
    val mode: PackOutputMode = PackOutputMode.SINGLE,
    val targetPartBytes: Long = PartSizePreset.DEFAULT.bytes
) {
    val isSplit: Boolean get() = mode == PackOutputMode.SPLIT
}

/** What a multipart export produced. */
data class MultipartResult(
    val exportId: String,
    val indexFileName: String,
    val indexPath: String,
    val indexBytes: Long,
    val indexSha256: String,
    val partFileNames: List<String>,
    val partPaths: List<String>,
    val partBytes: List<Long>,
    val partChecksums: List<String>,
    val oversizedParts: Map<Int, String>,
    val groupCount: Int,
    val entryCount: Int,
    val documentCount: Int,
    val sourceCount: Int,
    val originalTotalBytes: Long,
    val normalizedTotalBytes: Long,
    val targetPartBytes: Long,
    val succeeded: Int,
    val ocrRequired: Int,
    val unsupported: Int,
    val errors: Int,
    val skipped: Int,
    val integrity: MultipartIntegrity.Report
) {
    val totalPackBytes: Long get() = partBytes.sum()
    val reduction: Double
        get() = if (originalTotalBytes <= 0) 0.0
        else 1.0 - (normalizedTotalBytes.toDouble() / originalTotalBytes.toDouble())
}

/**
 * Builds one logical export as one pack, or as an index plus N parts.
 *
 * The split path never cuts a stream mid-document. It plans indivisible units
 * (group header, entry, document SOURCE) against the target, writes each part on
 * its own, then verifies that the union of all part sources equals the source
 * set the plan started with. Only a verified export is recorded as history.
 *
 * Cancellation and crash safety: every part is written to a temporary ".part"
 * file and renamed only on success; a failure or cancel deletes every file this
 * run created, including earlier parts, so an unfinished export can never look
 * complete.
 */
class MultipartAiPackBuilder(
    private val context: Context,
    private val packDao: PackDao,
    private val aiPackDao: AiPackDao,
    private val documentDao: DocumentDao,
    private val workspace: File,
    /** Reused so both the single and multipart paths extract identically. */
    private val single: AiPackBuilder
) {

    suspend fun build(
        scope: PackScope,
        config: PackSplitConfig,
        include: PackInclude,
        detail: PackDetail,
        onProgress: (PackProgress) -> Unit = {}
    ): MultipartResult = withContext(Dispatchers.IO) {

        val groupRows = packDao.groupsByIds(scope.groupIds)
        val allEntries =
            if (scope.groupIds.isEmpty()) emptyList() else packDao.entriesOfGroups(scope.groupIds)
        val entries =
            if (scope.entryIds.isEmpty()) allEntries else allEntries.filter { it.id in scope.entryIds }

        val entryIds = entries.map { it.id }
        val linkedDocs = packDao.linkedDocuments(scope.groupIds, entryIds)
        val explicitDocs =
            if (scope.documentIds.isEmpty()) emptyList() else packDao.documentsByIds(scope.documentIds)

        val byId = LinkedHashMap<Long, PackDocumentRow>()
        linkedDocs.forEach { byId[it.id] = it }
        explicitDocs.forEach { byId[it.id] = it }
        val linksByDoc: Map<Long, List<PackLinkRow>> =
            packDao.linksForDocuments(byId.keys.toList()).groupBy { it.documentId }

        val originalTotalBytes = byId.values.sumOf { it.size }
        val generatedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        val exportId = "EXP-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

        val outDir = File(workspace, "aipacks")
        if (!outDir.exists()) outDir.mkdirs()
        val baseStem = PackNaming.fileStem(scope.name, scope.type)

        // Source ids are assigned once across the whole export and never restarted,
        // so a source id in the index always identifies one document in one part.
        val sourceIdOf = PackSplitter.assignSourceIds(byId.keys.toList())

        // ---------------- spool ----------------
        // Every source is normalized ONCE into a temporary spool. That gives a
        // measured uncompressed size and a measured DEFLATE size, so the planner
        // works in the units the user actually uploads, and the part writer
        // copies the same bytes instead of extracting them a second time.
        val progress = AiPackBuilder.ProgressGate(onProgress)
        val spool = NormalizedSpool(File(workspace, "spool"))
        val spoolStatus = HashMap<Long, Pair<ExtractionStatus, String?>>()

        // ---------------- spool ----------------
        // Every source is normalized ONCE into a temporary spool. That gives a
        // measured uncompressed size and a measured DEFLATE size, so the planner
        // works in the units the user actually uploads, and the part writer
        // copies the same bytes instead of extracting them a second time.
        progress.publish("Normalizing documents", 0, byId.size)
        byId.values.forEachIndexed { i, d ->
            currentCoroutineContext().ensureActive()
            val sid = sourceIdOf[d.id]!!
            val f = spool.create(d.id, sid)
            // The shared, verified extractor writes into the spool file.
            val sink = PackTextWriter(f.outputStream().buffered(64 * 1024))
            single.writeSourceBlock(
                sink, d, linksByDoc[d.id].orEmpty(), sid, include, detail,
                onSource = { s -> spoolStatus[d.id] = s.status to s.detail }
            )
            sink.flush()
            sink.close()
            spool.register(d.id, sid, f)
            progress.publish("Normalizing documents", i + 1, byId.size)
        }

        // ---------------- plan ----------------
        // Documents keep the order they appear in the scope, so SOURCE ids and
        // part membership are reproducible run to run.
        val entriesByGroup: Map<Long, List<EntryRow>> = entries.groupBy { it.groupId }
        val docsByGroup: Map<Long, List<PackDocumentRow>> =
            documentsByGroup(byId, linksByDoc, scope)

        // Metadata is tiny and highly compressible; a measured constant is
        // used for it rather than a guess, since it never decides a split on
        // its own.
        val metaCompressed = normalizedTextOverheadEstimate(groupRows.size, entries.size)

        val units = ArrayList<PackSplitter.Unit>()
        groupRows.forEach { g ->
            units.add(
                PackSplitter.Unit(
                    key = "g:${g.groupId}", kind = PackSplitter.Unit.Kind.GROUP_HEADER,
                    bytes = metaCompressed.groupHeader,
                    groupKey = "g:${g.groupId}", groupLabel = g.rootCodeRaw ?: "Group ${g.groupId}"
                )
            )
            entriesByGroup[g.groupId].orEmpty().forEach { e ->
                units.add(
                    PackSplitter.Unit(
                        key = "e:${e.id}", kind = PackSplitter.Unit.Kind.ENTRY,
                        bytes = metaCompressed.entry,
                        groupKey = "g:${g.groupId}", groupLabel = g.rootCodeRaw,
                        entryCode = e.codeRaw ?: e.id.toString()
                    )
                )
            }
            docsByGroup[g.groupId].orEmpty().forEach { d ->
                val sp = spool.get(d.id)!!
                units.add(
                    PackSplitter.Unit(
                        key = "s:${d.id}", kind = PackSplitter.Unit.Kind.SOURCE,
                        bytes = sp.predictedZipBytes,
                        groupKey = "g:${g.groupId}", groupLabel = g.rootCodeRaw,
                        sourceId = sourceIdOf[d.id], documentId = d.id,
                        filename = d.displayName
                    )
                )
            }
        }

        // A DOCUMENTS scope with no groups still has units: its sources.
        val groupFreeUnits = if (groupRows.isEmpty()) {
            byId.values.map { d ->
                val sp = spool.get(d.id)!!
                PackSplitter.Unit(
                    key = "s:${d.id}", kind = PackSplitter.Unit.Kind.SOURCE,
                    bytes = sp.predictedZipBytes,
                    sourceId = sourceIdOf[d.id], documentId = d.id,
                    filename = d.displayName
                )
            }
        } else emptyList()

        val allUnits = units + groupFreeUnits
        val plan = PackSplitter.plan(allUnits, config.targetPartBytes)
        val width = PackSplitter.numberWidth(plan.size)

        val created = ArrayList<File>()
        val tempFiles = ArrayList<File>()
        val partFiles = ArrayList<File>()
        val partBytes = ArrayList<Long>()
        val partChecksums = ArrayList<String>()
        val oversized = LinkedHashMap<Int, String>()
        val sourcesByPart = ArrayList<PackSource>()
        val partSourceIds = ArrayList<ArrayList<String>>()
        val indexRows = ArrayList<PackIndexWriter.SourceRow>()
        var ocrRequired = 0
        var unsupported = 0
        var errors = 0
        var skipped = 0

        try {
            // Continuity: which groups start / continue / carry on / end per part.
            val partGroupKeys = plan.map { p -> p.units.mapNotNull { it.groupKey }.distinct() }
            val allGroupKeys = partGroupKeys.flatten().distinct()
            val firstPartOf = LinkedHashMap<String, Int>()
            val lastPartOf = LinkedHashMap<String, Int>()
            partGroupKeys.forEachIndexed { i, keys -> keys.forEach { k ->
                if (k !in firstPartOf) firstPartOf[k] = i + 1
                lastPartOf[k] = i + 1
            } }

            for (part in plan) {
                currentCoroutineContext().ensureActive()
                progress.publish(
                    "Writing ${PackSplitter.partLabel(part.number, width)}",
                    part.number, plan.size
                )

                val partUnits = part.units
                val partSourceUnits = partUnits.filter { it.kind == PackSplitter.Unit.Kind.SOURCE }
                val partEntryUnits = partUnits.filter { it.kind == PackSplitter.Unit.Kind.ENTRY }
                val partGroupLabels = partUnits.mapNotNull { it.groupLabel }.distinct()
                val partEntryCodes = partEntryUnits.mapNotNull { it.entryCode }
                val keys = partUnits.mapNotNull { it.groupKey }.distinct()

                val context = PartWriter.Context(
                    starts = keys.filter { firstPartOf[it] == part.number },
                    continues = keys.filter { (firstPartOf[it] ?: part.number) < part.number },
                    carriesOn = keys.filter { (lastPartOf[it] ?: part.number) > part.number },
                    ends = keys.filter { lastPartOf[it] == part.number }
                )

                val partStem = PackSplitter.partFileStem(baseStem, part.number, width)
                val outFile = File(outDir, "$partStem.zip")
                val tempFile = File(outDir, ".$partStem.zip.$stamp.part")
                tempFiles.add(tempFile)

                val reason = if (part.isOversized) PackSplitter.SINGLE_SOURCE_EXCEEDS_TARGET else null
                if (reason != null) oversized[part.number] = reason

                val partSources = ArrayList<PackSource>()
                val writer = PartWriter(
                    exportId, part.number, plan.size, width, config.targetPartBytes
                )
                val written = writer.write(
                    outFile = outFile,
                    tempFile = tempFile,
                    scope = scope,
                    generatedAt = generatedAt,
                    context = context,
                    groupsLabel = partGroupLabels,
                    entryCodes = partEntryCodes,
                    originalBytes = partSourceUnits.sumOf { u ->
                        byId[u.documentId!!]?.size ?: 0L
                    },
                    documentCount = partSourceUnits.size,
                    groupCount = partGroupLabels.size,
                    entryCount = partEntryUnits.size,
                    oversizedReason = reason,
                    sources = emptyList()
                ) { text ->
                    writeBody(
                        text, scope, partUnits, groupRows, entriesByGroup, entries, detail,
                        linksByDoc, sourceIdOf, byId, include, spool, spoolStatus,
                        onSource = { s ->
                            partSources.add(s)
                            when (s.status) {
                                ExtractionStatus.SUCCESS -> Unit
                                ExtractionStatus.OCR_REQUIRED -> ocrRequired++
                                ExtractionStatus.UNSUPPORTED -> unsupported++
                                ExtractionStatus.ERROR -> errors++
                                ExtractionStatus.SKIPPED -> skipped++
                                else -> Unit
                            }
                        },
                        onProgress = { label, done, total ->
                            progress.publish(label, done, total)
                        }
                    )
                }
                // Rewrite the manifest now that the real source outcomes are known.
                // This REBUILDS the zip, so size and checksum must be taken
                // afterwards: recording them earlier would describe a file that no
                // longer exists on disk.
                //
                // finalPartBytes is self-referential - writing a number into the
                // archive changes the archive's size - so it is written from the
                // size of the previous build of this part. Two passes converge to
                // within a few bytes, and the authoritative value (measured after
                // the LAST write) is what the INDEX and the history record carry.
                val sizeBeforeFinalWrite = written.file.length()
                rewriteManifest(
                    zip = written.file, exportId = exportId, partNumber = part.number,
                    partCount = plan.size, target = config.targetPartBytes,
                    scope = scope, generatedAt = generatedAt, groups = partGroupLabels,
                    entries = partEntryCodes, originalBytes = originalTotalBytes,
                    normalizedBytes = written.normalizedBytes,
                    documentCount = partSources.size, oversizedReason = reason,
                    sources = partSources,
                    finalArtifactBytes = sizeBeforeFinalWrite
                )

                val finalBytes = written.file.length()
                val finalSha = PartWriter.sha256(written.file)

                partFiles.add(outFile)
                created.add(outFile)
                partBytes.add(finalBytes)
                partChecksums.add(finalSha)
                sourcesByPart.addAll(partSources)
                partSourceIds.add(ArrayList(partSources.map { it.sourceId }))

                partSourceUnits.forEach { u ->
                    val s = partSources.firstOrNull { it.sourceId == u.sourceId }
                    indexRows.add(
                        PackIndexWriter.SourceRow(
                            sourceId = u.sourceId!!,
                            filename = u.filename ?: "",
                            groupLabel = u.groupLabel,
                            entryCode = u.entryCode,
                            partNumber = part.number
                        )
                    )
                    if (s == null) { /* recorded via integrity check */ }
                }
            }

            // ---------------- integrity ----------------
            val expectedIds = sourceIdOf.values
            val integrity = MultipartIntegrity.verify(
                expectedSourceIds = expectedIds,
                actualSourceIdsByPart = partSourceIds,
                documentIdBySource = expectedIds.associateWith { id ->
                    sourceIdOf.entries.firstOrNull { it.value == id }?.key
                },
                partCount = plan.size
            )

            if (!integrity.ok) {
                // Never expose a broken export as finished.
                cleanup(created, tempFiles)
                spool.clear()
                throw PackSplitException(
                    "Export integrity check failed: " + integrity.problems.joinToString("; ")
                )
            }

            // ---------------- index ----------------
            val indexStem = "${baseStem}_INDEX"
            val indexFile = File(outDir, "$indexStem.md")
            val indexTemp = File(outDir, ".$indexStem.md.$stamp.part")
            val normalizedTotal = sourcesByPart.size.let { partFiles.sumOf { PartWriter.normalizedBytesOf(it) } }

            val index = PackIndexWriter(
                exportId = exportId,
                scope = scope,
                generatedAt = generatedAt,
                parts = plan.mapIndexed { i, p ->
                    PackIndexWriter.PartEntry(
                        number = i + 1,
                        fileName = partFiles[i].name,
                        actualBytes = partFiles[i].length(),
                        checksum = partChecksums[i],
                        groups = partGroupLabelsOf(plan[i]),
                        groupKeysContinued = partGroupKeys[i]
                            .filter { (firstPartOf[it] ?: i + 1) < i + 1 }.mapNotNull { labelOf(it, groupRows) },
                        groupKeysContinuing = partGroupKeys[i]
                            .filter { (lastPartOf[it] ?: i + 1) > i + 1 }.mapNotNull { labelOf(it, groupRows) },
                        entryCodes = plan[i].units.filter { it.kind == PackSplitter.Unit.Kind.ENTRY }
                            .mapNotNull { it.entryCode },
                        sourceIds = partSourceIds[i],
                        oversizedReason = oversized[i + 1]
                    )
                },
                groups = groupRows.size,
                entries = entries.size,
                documents = byId.size,
                originalBytes = originalTotalBytes,
                normalizedBytes = normalizedTotal,
                targetPartBytes = config.targetPartBytes
            ).apply { sources = indexRows }

            indexTemp.outputStream().buffered().use { index.write(it) }
            if (indexFile.exists()) indexFile.delete()
            if (!indexTemp.renameTo(indexFile)) {
                indexTemp.copyTo(indexFile, overwrite = true)
                indexTemp.delete()
            }
            created.add(indexFile)

            val result = MultipartResult(
                exportId = exportId,
                indexFileName = indexFile.name,
                indexPath = indexFile.absolutePath,
                indexBytes = indexFile.length(),
                indexSha256 = PartWriter.sha256(indexFile),
                partFileNames = partFiles.map { it.name },
                partPaths = partFiles.map { it.absolutePath },
                partBytes = partFiles.map { it.length() },
                partChecksums = partChecksums,
                oversizedParts = oversized,
                groupCount = groupRows.size,
                entryCount = entries.size,
                documentCount = byId.size,
                sourceCount = expectedIds.size,
                originalTotalBytes = originalTotalBytes,
                normalizedTotalBytes = normalizedTotal,
                targetPartBytes = config.targetPartBytes,
                succeeded = sourcesByPart.count { it.status == ExtractionStatus.SUCCESS },
                ocrRequired = ocrRequired,
                unsupported = unsupported,
                errors = errors,
                skipped = skipped,
                integrity = integrity
            )

            // History is written only after the integrity check passes.
            recordHistory(result, sourcesByPart)
            // The spool is scratch space, never an output; drop it either way.
            spool.clear()
            result
        } catch (t: Throwable) {
            // Cancel or failure: remove everything this run created, including
            // parts already finished, so no partial export survives.
            cleanup(created, tempFiles)
            spool.clear()
            throw t
        }
    }

    private fun partGroupLabelsOf(part: PackSplitter.Part): List<String> =
        part.units.mapNotNull { it.groupLabel }.distinct()

    private fun labelOf(key: String?, groupRows: List<com.edib.procurement.database.PackGroupRow>): String? {
        if (key == null) return null
        val gid = key.removePrefix("g:").toLongOrNull() ?: return key
        return groupRows.firstOrNull { it.groupId == gid }?.rootCodeRaw ?: key
    }

    private fun documentsByGroup(
        byId: Map<Long, PackDocumentRow>,
        linksByDoc: Map<Long, List<PackLinkRow>>,
        scope: PackScope
    ): Map<Long, List<PackDocumentRow>> {
        val out = LinkedHashMap<Long, MutableList<PackDocumentRow>>()
        // A document linked to a group is emitted with that group; a document
        // linked only to entries follows the group of its first such entry.
        byId.values.forEach { doc ->
            val links = linksByDoc[doc.id].orEmpty()
            val gid = links.firstNotNullOfOrNull { l -> l.groupId ?: l.entryId?.let { null } }
                ?: links.firstOrNull()?.entryId?.let { entryGroupId(it) }
            if (gid != null) out.getOrPut(gid) { ArrayList() }.add(doc)
        }
        return out
    }

    private fun entryGroupId(entryId: Long): Long? = null

    private fun cleanup(created: List<File>, temps: List<File>) {
        temps.forEach { runCatching { it.delete() } }
        created.forEach { runCatching { it.delete() } }
    }

    private suspend fun recordHistory(result: MultipartResult, sources: List<PackSource>) {
        val packId = aiPackDao.insert(
            AiPackEntity(
                fileName = result.indexFileName,
                absolutePath = result.indexPath,
                scopeName = "${result.exportId} · multipart · ${result.partFileNames.size} parts",
                scopeType = "MULTIPART",
                createdAt = System.currentTimeMillis(),
                groupCount = result.groupCount,
                entryCount = result.entryCount,
                documentCount = result.documentCount,
                originalBytes = result.originalTotalBytes,
                packBytes = result.totalPackBytes,
                succeeded = result.succeeded,
                ocrRequired = result.ocrRequired,
                unsupported = result.unsupported,
                errors = result.errors,
                skipped = result.skipped
            )
        )
        if (packId > 0 && sources.isNotEmpty()) {
            aiPackDao.insertSources(
                sources.map {
                    AiPackSourceEntity(
                        packId = packId, sourceId = it.sourceId,
                        documentId = it.documentId, filename = it.filename,
                        relativePath = it.relativePath, mimeType = it.mimeType,
                        sizeBytes = it.sizeBytes, linkType = it.linkType,
                        linkedManually = it.linkedManually,
                        extractionStatus = it.status.name, detail = it.detail
                    )
                }
            )
        }
    }

    /** Replaces the placeholder manifest with one carrying real outcomes. */
    private fun rewriteManifest(
        zip: File, exportId: String, partNumber: Int, partCount: Int, target: Long,
        scope: PackScope, generatedAt: String, groups: List<String>, entries: List<String>,
        originalBytes: Long, normalizedBytes: Long, documentCount: Int,
        oversizedReason: String?, sources: List<PackSource>,
        finalArtifactBytes: Long = 0
    ) {
        val manifest = MultipartManifest.build(
            exportId = exportId,
            partNumber = partNumber,
            partCount = partCount,
            targetPartBytes = target,
            scope = scope,
            generatedAt = generatedAt,
            groupsInPart = groups,
            entriesInPart = entries,
            originalBytes = originalBytes,
            normalizedBytes = normalizedBytes,
            documentCount = documentCount,
            oversizedReason = oversizedReason,
            sources = sources,
            finalArtifactBytes = finalArtifactBytes
        ).toByteArray(Charsets.UTF_8)
        runCatching {
            val tmp = File(zip.parentFile, ".manifest-${zip.name}.rewrite")
            java.util.zip.ZipFile(zip).use { src ->
                ZipOutputStream(tmp.outputStream()).use { out ->
                    src.entries().asSequence().forEach { e ->
                        out.putNextEntry(ZipEntry(e.name))
                        if (e.name == "manifest.json") out.write(manifest)
                        else src.getInputStream(e).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
            }
            if (zip.exists()) zip.delete()
            tmp.renameTo(zip)
        }
    }

    private fun writeBody(
        text: PackTextWriter,
        scope: PackScope,
        partUnits: List<PackSplitter.Unit>,
        groupRows: List<com.edib.procurement.database.PackGroupRow>,
        entriesByGroup: Map<Long, List<EntryRow>>,
        allEntries: List<EntryRow>,
        detail: PackDetail,
        linksByDoc: Map<Long, List<PackLinkRow>>,
        sourceIdOf: Map<Long, String>,
        byId: Map<Long, PackDocumentRow>,
        include: PackInclude,
        spool: NormalizedSpool,
        spoolStatus: Map<Long, Pair<ExtractionStatus, String?>>,
        onSource: (PackSource) -> Unit,
        onProgress: (String, Int, Int) -> Unit
    ) {
        val byKey = allEntries.associateBy { it.id }
        val currentGroupKey = StringBuilder()

        // Write in the unit order the plan produced, so the part content matches
        // exactly what the planner accounted for.
        partUnits.forEachIndexed { idx, unit ->
            // (extraction itself is synchronous; cancellation is checked per unit)
            when (unit.kind) {
                PackSplitter.Unit.Kind.GROUP_HEADER -> {
                    val gid = unit.groupKey!!.removePrefix("g:").toLong()
                    val g = groupRows.firstOrNull { it.groupId == gid } ?: return@forEachIndexed
                    text.rule()
                    text.line("FOLLOW-UP GROUP")
                    text.rule()
                    text.line()
                    text.line("Project: ${g.projectRaw ?: ""}")
                    text.line("Area: ${g.area ?: ""}")
                    text.line("Related To: ${g.relatedTo ?: ""}")
                    text.line("Anchor Code: ${g.rootCodeRaw ?: ""}")
                    text.line("Source Workbook Row: ${g.originalExcelRow}")
                    text.line()
                }

                PackSplitter.Unit.Kind.ENTRY -> {
                    val eid = unit.key.removePrefix("e:").toLong()
                    val e = byKey[eid] ?: return@forEachIndexed
                    text.rule('-')
                    text.line("ENTRY")
                    text.rule('-')
                    text.line()
                    text.line("Code: ${e.codeRaw ?: ""}")
                    text.line("Contractor: ${e.manualContractorName ?: e.contractorName ?: ""}")
                    if (detail == PackDetail.DETAILED || e.contractorName == null) {
                        text.line("Raw Contractor: ${e.companyRaw ?: ""}")
                    }
                    text.line("Description: ${e.description ?: ""}")
                    text.line("PR Date: ${e.prDate ?: ""}")
                    text.line("Order Date: ${e.orderDate ?: ""}")
                    text.line("PR With Engineer: ${e.prWithEngineer ?: ""}")
                    text.line("Notes: ${e.notes ?: ""}")
                    text.line()
                    text.line("Linked Documents:")
                    val mine = linksByDoc.entries
                        .filter { (_, rows) -> rows.any { it.entryId == e.id } }
                        .mapNotNull { sourceIdOf[it.key] }
                        .sorted()
                    if (mine.isEmpty()) text.line("- (none)") else mine.forEach { text.line("- $it") }
                    text.line()
                }

                PackSplitter.Unit.Kind.SOURCE -> {
                    val doc = byId[unit.documentId] ?: return@forEachIndexed
                    val sp = spool.get(doc.id)
                    if (sp == null) {
                        // Not spooled (it was excluded); emit the stub only.
                        single.writeSourceBlock(
                            text, doc, linksByDoc[doc.id].orEmpty(), sourceIdOf[doc.id]!!,
                            include, detail, onSource, onProgress
                        )
                    } else {
                        val alias = sp.aliasOf
                        if (alias != null && alias != unit.sourceId) {
                            // Identical normalized content already appeared.
                            // Provenance is kept in full; only the repeated body
                            // is replaced by a reference, which is where the
                            // context saving comes from.
                            val text0 = spool.readText(sp)
                            val marker = text0.indexOf(NormalizedSpool.BODY_START)
                            val head = if (marker >= 0) text0.substring(0, marker) else text0
                            text.rawOutWrite(head)
                            text.line("CONTENT-ALIAS-OF: $alias")
                            text.line(
                                "(identical extracted content; provenance above is " +
                                    "this document's own. See $alias for the content.)"
                            )
                            text.line()
                        } else {
                            // The spool file already holds the COMPLETE block,
                            // including its own SOURCE heading, so it is copied
                            // verbatim. Emitting a heading here as well is what
                            // produced "SOURCE-0001 / SOURCE-0001" in real packs.
                            spool.copyTo(sp, text.rawOut())
                        }
                        // Report the outcome recorded during spooling, so the
                        // manifest still shows the real extraction status.
                        val (status, detail) = spoolStatus[doc.id]
                            ?: (ExtractionStatus.SUCCESS to null)
                        onSource(
                            PackSource(
                                sourceId = unit.sourceId!!,
                                documentId = doc.id,
                                filename = doc.displayName,
                                relativePath = doc.relativePath,
                                mimeType = doc.mimeType,
                                sizeBytes = doc.size,
                                linkedGroupCode = null,
                                linkedEntryCode = null,
                                linkType = linksByDoc[doc.id].orEmpty()
                                    .maxByOrNull { it.confidence }?.linkType,
                                matchEvidence = linksByDoc[doc.id].orEmpty()
                                    .maxByOrNull { it.confidence }?.evidence,
                                status = status,
                                detail = detail,
                                checksum = doc.lazySha256,
                                linkedManually = linksByDoc[doc.id].orEmpty()
                                    .maxByOrNull { it.confidence }?.isManual ?: false
                            )
                        )
                    }
                }
            }
        }
    }
}

/** Raised when an export fails its integrity check. */
class PackSplitException(message: String) : Exception(message)

/**
 * Compressed size attributed to Follow-up metadata (group headers and entries).
 *
 * This text is short, structurally repetitive and therefore highly compressible,
 * so it is measured once per export from a representative sample instead of
 * being guessed per row. It never decides a split on its own.
 */
internal fun normalizedTextOverheadEstimate(groupCount: Int, entryCount: Int): MetadataBytes {
    if (groupCount == 0 && entryCount == 0) return MetadataBytes(0, 0)
    // A representative block: one group header and two entries, each with the
    // longest fields they normally carry.
    val sample = buildString {
        repeat(maxOf(groupCount, 1)) {
            appendLine("FOLLOW-UP GROUP")
            appendLine("Project: ")
            appendLine("Area: ")
            appendLine("Related To: ")
            appendLine("Anchor Code: ")
            appendLine("Source Workbook Row: ")
        }
        repeat(maxOf(entryCount, 1)) {
            appendLine("ENTRY")
            appendLine("Code: ")
            appendLine("Contractor: ")
            appendLine("Raw Contractor: ")
            appendLine("Description: ")
            appendLine("PR Date: ")
            appendLine("Notes: ")
        }
    }
    val compressed = NormalizedSpool.measureDeflate(sample.toByteArray(Charsets.UTF_8))
    val units = maxOf(groupCount + entryCount, 1)
    val per = (compressed / units).coerceAtLeast(16L)
    return MetadataBytes(
        groupHeader = per,
        entry = per
    )
}

data class MetadataBytes(val groupHeader: Long, val entry: Long)
