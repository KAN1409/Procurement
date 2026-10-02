package com.edib.procurement.aipack

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Where one document's normalized text lives during a multipart export.
 *
 * The point of this class is to extract each source EXACTLY ONCE. Phase 4
 * estimated part sizes from the original file's byte size times a fixed ratio,
 * which meant the planner was guessing at something it had not measured and had
 * no idea how the final ZIP would compress.
 *
 * Instead the normalized block is written to a temporary spool. That gives:
 *  - a real uncompressed byte count, measured rather than assumed;
 *  - a real DEFLATE measurement, so the predicted FINAL zip size comes from the
 *    same compressor the output ZIP uses;
 *  - the exact bytes to copy into the part, so the same extraction is never
 *    produced twice.
 *
 * The spool is always cleaned: on success, on cancellation and on failure.
 */
class NormalizedSpool(private val dir: File) {

    /** One document's normalized block, with both sizes measured. */
    data class Entry(
        val documentId: Long,
        val sourceId: String,
        val file: File,
        /** Uncompressed normalized bytes. */
        val normalizedBytes: Long,
        /** Bytes this block is expected to occupy after DEFLATE. */
        val predictedZipBytes: Long
    ) {
        /** What actually landed in the output, filled in after writing. */
        var actualZipBytes: Long = 0
    }

    private val entries = LinkedHashMap<Long, Entry>()

    fun get(documentId: Long): Entry? = entries[documentId]

    val all: Collection<Entry> get() = entries.values

    val totalNormalizedBytes: Long get() = entries.values.sumOf { it.normalizedBytes }

    val totalPredictedZipBytes: Long get() = entries.values.sumOf { it.predictedZipBytes }

    /** Reserves a spool file and lets the caller write the normalized block into it. */
    fun create(documentId: Long, sourceId: String): File {
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, "src-${documentId}.txt")
        f.delete()
        return f
    }

    /**
     * Records a finished block: measured uncompressed size, plus a DEFLATE
     * measurement of the same bytes so the final zip size is predicted from real
     * compression rather than a global ratio.
     */
    fun register(documentId: Long, sourceId: String, file: File): Entry {
        val normalized = file.length()
        val e = Entry(
            documentId = documentId,
            sourceId = sourceId,
            file = file,
            normalizedBytes = normalized,
            predictedZipBytes = measureDeflate(file, normalized)
        )
        entries[documentId] = e
        return e
    }

    /** Copies a spooled block into [out]; the source file is only read. */
    fun copyTo(entry: Entry, out: OutputStream) {
        entry.file.inputStream().buffered(64 * 1024).use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        }
    }

    fun readText(entry: Entry): String = entry.file.readText()

    /** Removes every spool file. Safe to call more than once. */
    fun clear() {
        entries.values.forEach { runCatching { it.file.delete() } }
        entries.clear()
        runCatching { dir.deleteRecursively() }
    }

    companion object {
        /**
         * Bytes this content occupies after DEFLATE, measured.
         *
         * Uses the same level the ZIP entries use. Measuring is O(size) but
         * bounded by the normalized text, and it is done once per document
         * during a pass that already has the bytes in hand.
         */
        fun measureDeflate(file: File, rawSize: Long): Long {
            if (rawSize == 0L) return 0L
            val counter = CountingOutputStream()
            val def = Deflater(Deflater.DEFAULT_COMPRESSION)
            try {
                DeflaterOutputStream(counter, def, 64 * 1024).use { z ->
                    file.inputStream().buffered(64 * 1024).use { ins ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            z.write(buf, 0, n)
                        }
                    }
                }
            } finally {
                def.end()
            }
            return counter.count
        }

        /** Measures in-memory content, for tests and small blocks. */
        fun measureDeflate(bytes: ByteArray): Long {
            if (bytes.isEmpty()) return 0L
            val counter = CountingOutputStream()
            val def = Deflater(Deflater.DEFAULT_COMPRESSION)
            try {
                DeflaterOutputStream(counter, def, 64 * 1024).use { z -> z.write(bytes) }
            } finally {
                def.end()
            }
            return counter.count
        }
    }
}

/** Counts bytes without holding them: the point is to measure, not store. */
private class CountingOutputStream : OutputStream() {
    var count: Long = 0
        private set

    override fun write(b: Int) {
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        count += len
    }
}
