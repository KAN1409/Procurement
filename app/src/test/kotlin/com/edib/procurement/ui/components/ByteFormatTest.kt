package com.edib.procurement.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Byte-size formatting.
 *
 * Regression: the MB branch divided by 1024 instead of 1024^2, so a 1.7 MB
 * workbook rendered as "1703.7 MB".
 */
class ByteFormatTest {

    @Test
    fun `zero and negative sizes show as a dash`() {
        assertEquals("—", ByteFormat.size(0))
        assertEquals("—", ByteFormat.size(-1))
    }

    @Test
    fun `small files show whole bytes`() {
        assertEquals("1 B", ByteFormat.size(1))
        assertEquals("586 B", ByteFormat.size(586))
        assertEquals("1023 B", ByteFormat.size(1023))
    }

    @Test
    fun `exactly one kibibyte starts the KB range`() {
        assertEquals("1 KB", ByteFormat.size(1024))
        assertEquals("1.5 KB", ByteFormat.size(1536))
    }

    @Test
    fun `the real workbook renders as about 1_7 MB`() {
        // 1,744,565 bytes is the attached reference workbook.
        assertEquals("1.7 MB", ByteFormat.size(1_744_565))
    }

    @Test
    fun `megabyte range divides by 1024 squared`() {
        assertEquals("1 MB", ByteFormat.size(1024L * 1024))
        assertEquals("1.1 MB", ByteFormat.size(1_174_405))
        assertEquals("500 MB", ByteFormat.size(1024L * 1024 * 500))
        // Just under 1 GB still reads as MB, not GB.
        assertEquals("1000 MB", ByteFormat.size(1024L * 1024 * 1000))
    }

    @Test
    fun `gigabyte range`() {
        assertEquals("1 GB", ByteFormat.size(1024L * 1024 * 1024))
        assertEquals("1.5 GB", ByteFormat.size(1024L * 1024 * 1024 * 3 / 2))
        assertEquals("108 GB", ByteFormat.size(108L * 1024 * 1024 * 1024))
    }

    @Test
    fun `terabyte range`() {
        assertEquals("1 TB", ByteFormat.size(1024L * 1024 * 1024 * 1024))
    }

    @Test
    fun `every unit boundary is consistent`() {
        // The bug this guards against produced a value 1024x too large.
        assertEquals("1 KB", ByteFormat.size(1024))
        assertEquals("1 MB", ByteFormat.size(1024 * 1024))
        assertEquals("1 GB", ByteFormat.size(1024L * 1024 * 1024))
    }

    @Test
    fun `the convenience alias matches the object`() {
        assertEquals(ByteFormat.size(1_744_565), formatSize(1_744_565))
    }
}
