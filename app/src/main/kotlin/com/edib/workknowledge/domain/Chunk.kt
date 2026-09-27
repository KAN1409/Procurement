package com.edib.workknowledge.domain

import com.edib.workknowledge.data.ChunkEntity
import com.edib.workknowledge.data.ChunkMetadata
import com.google.gson.Gson

/**
 * One retrievable unit of a document, with full provenance back to the source.
 *
 * Provenance is what makes answers checkable:
 *  - PDF  -> [pageNumber]
 *  - Excel-> [sheetName], [rowStart], [rowEnd], [cellRange]
 */
data class Chunk(
    val id: Long = 0,
    val projectId: Long,
    val documentId: Long,
    val chunkIndex: Int,
    val content: String,
    val embedding: FloatArray? = null,
    val pageNumber: Int? = null,
    val sheetName: String? = null,
    val rowStart: Int? = null,
    val rowEnd: Int? = null,
    val cellRange: String? = null,
    val metadata: ChunkMetadata = ChunkMetadata(),
    /** Denormalised document filename, mirrored into the full-text index. */
    val filename: String = "",
    /** Denormalised project name, mirrored into the full-text index. */
    val projectName: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    val hasPageReference: Boolean get() = pageNumber != null
    val hasSheetReference: Boolean get() = sheetName != null
    val hasRowReference: Boolean get() = rowStart != null && rowEnd != null

    /** Short label used in chat citation chips. */
    val citationString: String
        get() = when {
            hasPageReference -> "Page $pageNumber"
            hasSheetReference && hasRowReference -> "$sheetName • Rows $rowStart–$rowEnd"
            hasSheetReference -> "$sheetName"
            else -> "Section ${chunkIndex + 1}"
        }

    /** Full label used when a citation is resolved to a real document. */
    val fullCitationString: String
        get() = when {
            hasPageReference -> "Page $pageNumber"
            hasSheetReference && hasRowReference -> "$sheetName, Rows $rowStart–$rowEnd"
            hasSheetReference -> "$sheetName"
            else -> "Section ${chunkIndex + 1}"
        }

    fun toEntity(): ChunkEntity = ChunkEntity(
        id = id,
        projectId = projectId,
        documentId = documentId,
        chunkIndex = chunkIndex,
        content = content,
        embedding = embedding?.let { VectorCodec.encode(it) },
        pageNumber = pageNumber,
        sheetName = sheetName,
        rowStart = rowStart,
        rowEnd = rowEnd,
        cellRange = cellRange,
        filename = filename,
        projectName = projectName,
        metadataJson = Gson().toJson(metadata),
        createdAt = createdAt
    )

    companion object {
        fun fromEntity(entity: ChunkEntity): Chunk = Chunk(
            id = entity.id,
            projectId = entity.projectId,
            documentId = entity.documentId,
            chunkIndex = entity.chunkIndex,
            content = entity.content,
            embedding = entity.embedding?.let { VectorCodec.decode(it) },
            pageNumber = entity.pageNumber,
            sheetName = entity.sheetName,
            rowStart = entity.rowStart,
            rowEnd = entity.rowEnd,
            cellRange = entity.cellRange,
            metadata = entity.getMetadata(),
            filename = entity.filename,
            projectName = entity.projectName,
            createdAt = entity.createdAt
        )
    }
}

/**
 * Float-array <-> byte-array codec.
 *
 * Kept in one place so the layout can never drift between writer and reader,
 * and so [decode] can reject a truncated or misaligned buffer instead of
 * producing garbage vectors.
 */
object VectorCodec {
    fun encode(vector: FloatArray): ByteArray {
        val out = ByteArray(vector.size * 4)
        var i = 0
        for (v in vector) {
            val bits = java.lang.Float.floatToRawIntBits(v)
            out[i++] = (bits ushr 24).toByte()
            out[i++] = (bits ushr 16).toByte()
            out[i++] = (bits ushr 8).toByte()
            out[i++] = bits.toByte()
        }
        return out
    }

    /** Returns null when [bytes] is not a whole number of float32 values. */
    fun decode(bytes: ByteArray): FloatArray? {
        if (bytes.isEmpty() || bytes.size % 4 != 0) return null
        val out = FloatArray(bytes.size / 4)
        var i = 0
        var o = 0
        while (i < bytes.size) {
            val bits = ((bytes[i].toInt() and 0xFF) shl 24) or
                ((bytes[i + 1].toInt() and 0xFF) shl 16) or
                ((bytes[i + 2].toInt() and 0xFF) shl 8) or
                (bytes[i + 3].toInt() and 0xFF)
            out[o++] = java.lang.Float.intBitsToFloat(bits)
            i += 4
        }
        return out
    }
}
