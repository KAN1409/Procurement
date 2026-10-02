package com.edib.openwhispr

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts an arbitrary picked audio container into the 16 kHz mono PCM16 WAV
 * that the rest of the app stores and expects.
 *
 * This is the *same* decode step the recorded-audio path already performs
 * (MediaExtractor + MediaCodec, no VAD, no re-synthesis, no transcription).
 * It exists only so an imported m4a/mp3/opus file becomes a normal .wav before
 * a Note is created, and it deliberately performs no language detection,
 * no resampling heuristics beyond the fixed 16 kHz target the app already
 * records at, and absolutely no transcription.
 */
internal object AudioImport {

    private const val TARGET_RATE = 16000

    fun ensureWav16kMono(context: Context, bytes: ByteArray): ByteArray {
        if (isWav(bytes)) return bytes
        return transcodeToWav(context, bytes)
    }

    private fun transcodeToWav(context: Context, bytes: ByteArray): ByteArray {
        val tmp = java.io.File(
            context.cacheDir,
            "import-${System.currentTimeMillis()}.src"
        )
        try {
            tmp.writeBytes(bytes)

            val extractor = MediaExtractor()
            var track = -1
            var format: MediaFormat? = null
            try {
                extractor.setDataSource(tmp.absolutePath)
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("audio/")) { track = i; format = f; break }
                }
                if (track < 0 || format == null) {
                    throw IllegalStateException("No audio track in the selected file")
                }
                extractor.selectTrack(track)

                val mime = format.getString(MediaFormat.KEY_MIME)
                    ?: throw IllegalStateException("Selected file has no audio stream")
                val codec = MediaCodec.createDecoderByType(mime)
                val pcm = try {
                    decodeToPcm(extractor, codec, format)
                } finally {
                    runCatching { codec.stop() }
                    codec.release()
                }
                if (pcm.isEmpty()) {
                    throw IllegalStateException("Could not decode the selected audio")
                }
                return WavWriter.encode(pcm, sampleRate = TARGET_RATE)
            } finally {
                runCatching { extractor.release() }
            }
        } finally {
            tmp.delete()
        }
    }

    private fun decodeToPcm(
        extractor: MediaExtractor,
        codec: MediaCodec,
        format: MediaFormat
    ): ByteArray {
        codec.configure(format, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        val out = java.io.ByteArrayOutputStream()
        var inputDone = false
        var outputDone = false
        val srcRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
            .getOrDefault(TARGET_RATE)

        while (!outputDone) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10000)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx)
                    val size = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10000)
            when {
                outIdx >= 0 -> {
                    if (info.size > 0) {
                        val ob = codec.getOutputBuffer(outIdx)
                        if (ob != null) {
                            val bytes = ByteArray(info.size)
                            ob.position(info.offset)
                            ob.get(bytes)
                            out.write(bytes, 0, info.size)
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
            }
        }
        return out.toByteArray()
    }

    private fun isWav(b: ByteArray): Boolean =
        b.size > 44 &&
            b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
            b[8] == 'W'.code.toByte() && b[9] == 'A'.code.toByte() &&
            b[10] == 'V'.code.toByte() && b[11] == 'E'.code.toByte()
}
