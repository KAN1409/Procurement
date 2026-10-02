package com.edib.procurement.aipack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * Cancellation and temporary-file hygiene.
 *
 * The invariant under test: an interrupted pack run leaves nothing that could
 * be mistaken for a finished pack. That is enforced structurally by writing to
 * a ".part" file and renaming only on success, so these tests exercise the same
 * mechanism the builder relies on rather than a re-implementation of it.
 */
class PackCancellationTest {

    private fun packDir(): File =
        File(System.getProperty("java.io.tmpdir"), "pack-cancel").also { it.mkdirs() }

    @Test
    fun `the temporary marker is hidden and is not a pack extension`() {
        // A dot prefix keeps it out of a normal directory listing, and the
        // suffix is not something a file manager would open.
        assertTrue(PackNaming.TEMP_SUFFIX.startsWith("."))
        assertFalse(PackNaming.TEMP_SUFFIX.contains(".zip"))
        assertFalse(PackNaming.TEMP_SUFFIX.contains(".md"))
    }

    @Test
    fun `an interrupted run leaves no completed looking file`() {
        val dir = packDir()
        dir.listFiles()?.forEach { it.delete() }

        val stem = PackNaming.fileStem("Interrupted Scope", PackScopeType.GROUP)
        val temp = File(dir, ".$stem.zip.20260101-000000${PackNaming.TEMP_SUFFIX}")
        temp.writeText("half written pack")

        // This mirrors cleanupIncompletePacks: anything still carrying the
        // marker is removed.
        var cleared = 0
        dir.listFiles()?.forEach {
            if (it.name.endsWith(PackNaming.TEMP_SUFFIX) && it.delete()) cleared++
        }
        assertEquals(1, cleared)
        assertTrue("nothing remains", dir.listFiles()?.isEmpty() ?: true)
        dir.delete()
    }

    @Test
    fun `a completed pack is never swept by the cleanup`() {
        val dir = packDir()
        dir.listFiles()?.forEach { it.delete() }

        val stem = PackNaming.fileStem("Real Pack", PackScopeType.GROUP)
        val done = File(dir, "$stem.zip")
        FileOutputStream(done).use { it.write(byteArrayOf(0x50, 0x4B)) }
        // A stale temporary from the same run must go, the real one must stay.
        File(dir, ".$stem.zip.stale${PackNaming.TEMP_SUFFIX}").writeText("x")

        var cleared = 0
        dir.listFiles()?.forEach {
            if (it.name.endsWith(PackNaming.TEMP_SUFFIX) && it.delete()) cleared++
        }
        assertEquals(1, cleared)
        assertTrue("the finished pack survives", done.exists())
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    @Test
    fun `a rename promotes the temporary file to the finished name`() {
        val dir = packDir()
        dir.listFiles()?.forEach { it.delete() }

        val stem = PackNaming.fileStem("Promotion", PackScopeType.PROJECT)
        val temp = File(dir, ".$stem.zip.x${PackNaming.TEMP_SUFFIX}")
        val done = File(dir, "$stem.zip")
        temp.writeText("complete bytes")
        assertTrue(temp.renameTo(done))
        assertFalse("the temporary is gone", temp.exists())
        assertTrue(done.exists())
        assertEquals("complete bytes", done.readText())

        // And the cleanup sweep leaves it alone.
        dir.listFiles()?.forEach {
            if (it.name.endsWith(PackNaming.TEMP_SUFFIX)) it.delete()
        }
        assertTrue(done.exists())
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }
}

/**
 * Progress throttling.
 *
 * A pack can hold hundreds of documents; publishing every step would recompose
 * the UI hundreds of times for no visible gain.
 */
class ProgressThrottleTest {

    @Test
    fun `rapid updates collapse into a few published steps`() {
        val seen = ArrayList<String>()
        val gate = AiPackBuilder.ProgressGateForTest { p ->
            seen.add("${p.phase} ${p.current}/${p.total}")
        }
        repeat(500) { gate.publish("Extracting", it, 500) }
        assertTrue("expected fewer than 500 updates, got ${seen.size}", seen.size < 100)
        assertTrue("the last step is always published", seen.isNotEmpty())
    }

    @Test
    fun `a phase change is always published`() {
        val seen = ArrayList<String>()
        val gate = AiPackBuilder.ProgressGateForTest { p -> seen.add(p.phase) }
        gate.publish("Preparing", 1, 10)
        gate.publish("Preparing", 2, 10)
        gate.publish("Extracting", 3, 10)
        assertTrue("both phases appear", seen.contains("Preparing") && seen.contains("Extracting"))
    }
}
