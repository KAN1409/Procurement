package com.edib.procurement.aipack

import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.charset.StandardCharsets

/**
 * Streaming writer for the AI Pack text.
 *
 * Everything is written straight through to the output; the pack is never
 * assembled as one large String. A pack may stand in for hundreds of megabytes
 * of originals, so the whole pipeline is bounded-memory by construction.
 */
class PackTextWriter(out: OutputStream) {

    private val writer: Writer = BufferedWriter(OutputStreamWriter(out, StandardCharsets.UTF_8), 64 * 1024)

    private var bytesWritten = 0L
    private var lineOpen = false

    val size: Long get() = bytesWritten

    private fun write(s: String) {
        writer.write(s)
        bytesWritten += s.toByteArray(StandardCharsets.UTF_8).size
    }

    /**
     * A line, with runs of blank lines collapsed to one.
     *
     * A pack is read by a model, where three consecutive blank lines cost
     * tokens and carry no information.
     */
    fun line(text: String = "") {
        if (text.isEmpty()) {
            if (lastWasBlank) return
            lastWasBlank = true
        } else {
            lastWasBlank = false
        }
        if (lineOpen) {
            write("\n")
            lineOpen = false
        }
        write(text)
        lineOpen = true
    }

    private var lastWasBlank = false

    /** Ends the current line without starting a new one. */
    fun endLine() {
        if (lineOpen) {
            write("\n")
            lineOpen = false
            lastWasBlank = false
        }
    }

    fun heading(text: String) {
        endLine()
        line(text)
    }

    fun rule(char: Char = '=', count: Int = 50) {
        endLine()
        line(char.toString().repeat(count))
    }

    fun flush() {
        endLine()
        writer.flush()
    }

    fun close() {
        try {
            endLine()
            writer.flush()
            writer.close()
        } catch (_: Exception) {
            // The caller decides what a failed close means.
        }
    }
}
