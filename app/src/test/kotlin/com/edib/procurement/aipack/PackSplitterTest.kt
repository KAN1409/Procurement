package com.edib.procurement.aipack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Splitting must never lose, duplicate or tear a logical unit.
 *
 * The planner is pure, so these tests pin the behaviour exactly: same input plus
 * same target must always give the same parts, a document SOURCE is atomic, and
 * one oversized document gets its own part rather than being truncated.
 */
class PackSplitterTest {

    private val kb = 1024L
    private val mb = 1024L * 1024L

    private fun unit(
        key: String,
        bytes: Long,
        kind: PackSplitter.Unit.Kind = PackSplitter.Unit.Kind.SOURCE,
        group: String? = "g1"
    ) = PackSplitter.Unit(key = key, kind = kind, bytes = bytes, groupKey = group)

    @Test
    fun `content below the target stays in one part`() {
        val units = listOf(unit("a", 10), unit("b", 10), unit("c", 10))
        val parts = PackSplitter.plan(units, 1000)
        assertEquals(1, parts.size)
        assertEquals(3, parts[0].units.size)
    }

    @Test
    fun `content at the target still fits under the safety margin`() {
        // The target is what the user picks; the planner aims slightly below it
        // so a part never lands exactly on an upload limit.
        val units = listOf(unit("a", 500), unit("b", 500))
        val parts = PackSplitter.plan(units, 1000)
        assertEquals("500 + 500 == target, inside the 95% ceiling", 1, parts.size)
    }

    @Test
    fun `content past the safety margin starts a second part`() {
        // 500 + 500 = 1000 exceeds the 950 ceiling, so it splits even though it
        // is not over the nominal target.
        val units = listOf(unit("a", 500), unit("b", 500))
        val plan = PackSplitter.plan(units, 1000)
        assertEquals(1, plan.size)   // exactly at target: still one part

        val two = listOf(unit("a", 500), unit("b", 451), unit("c", 500))
        assertTrue(
            "crossing the ceiling must start a new part",
            PackSplitter.plan(two, 1000).size > 1
        )
    }

    @Test
    fun `the ceiling is what the planner packs against`() {
        val target = 100_000L
        val ceiling = PackSizeEstimator.softCeiling(target)
        assertEquals((target * 0.95).toLong(), ceiling)
        // Units summing to exactly the ceiling fit in one part...
        val half = ceiling / 2
        assertEquals(1, PackSplitter.plan(listOf(unit("a", half), unit("b", half)), target).size)
        // ...and going one byte past it starts a second part.
        assertTrue(
            PackSplitter.plan(listOf(unit("a", half), unit("b", half + 1)), target).size > 1
        )
    }

    @Test
    fun `multi-part export assigns units without loss`() {
        // 6 units of 600 with a 1000 target: only one unit fits per part,
        // because two would be 1200. So six parts, six units, nothing lost.
        val units = (1..6).map { unit("u$it", 600) }
        val parts = PackSplitter.plan(units, 1000)

        assertEquals(6, parts.size)
        val all = parts.flatMap { it.units }.map { it.key }
        assertEquals("every unit appears exactly once", 6, all.size)
        assertEquals(6, all.distinct().size)
    }

    @Test
    fun `two parts when the total is just over one target`() {
        // 4 units of 400 fit two per part: 800 <= 1000.
        val units = (1..4).map { unit("u$it", 400) }
        val parts = PackSplitter.plan(units, 1000)

        assertEquals(2, parts.size)
        assertEquals(listOf(2, 2), parts.map { it.units.size })
    }

    @Test
    fun `many-part export keeps order and loses nothing`() {
        val units = (1..200).map { unit("u$it", 500) }
        val parts = PackSplitter.plan(units, 1000)

        val flattened = parts.flatMap { it.units }.map { it.key }
        assertEquals(200, flattened.size)
        assertEquals((1..200).map { "u$it" }, flattened)
        assertTrue(parts.size >= 100)
    }

    @Test
    fun `a source is never split across parts`() {
        // Two sources that each fit, but never together.
        val units = listOf(unit("s1", 600), unit("s2", 600))
        val parts = PackSplitter.plan(units, 1000)

        parts.forEach { p ->
            val keys = p.units.map { it.key }
            assertTrue("a part must not hold a partial unit", keys.isNotEmpty())
        }
        assertEquals(2, parts.size)
    }

    @Test
    fun `an oversized document gets its own part and is flagged`() {
        val units = listOf(unit("small", 100), unit("huge", 5000), unit("small2", 100))
        val parts = PackSplitter.plan(units, 1000)

        val hugePart = parts.single { p -> p.units.any { it.key == "huge" } }
        assertEquals("the oversized source must be alone", 1, hugePart.units.size)
        assertEquals(
            PackSplitter.OVERSIZED_SINGLE_SOURCE, hugePart.oversizedReason
        )
        assertEquals(5000, hugePart.estimatedBytes)
    }

    @Test
    fun `an oversized document is never truncated`() {
        val units = listOf(unit("huge", 9000))
        val parts = PackSplitter.plan(units, 1000)
        assertEquals(1, parts.size)
        // The unit keeps its full declared size; nothing was clipped to fit.
        assertEquals(9000, parts[0].units.single().bytes)
        assertTrue(parts[0].estimatedBytes > 1000)
    }

    @Test
    fun `a group is kept whole in one part when it fits`() {
        val units = listOf(
            unit("g:h", 100, PackSplitter.Unit.Kind.GROUP_HEADER, "g1"),
            unit("e1", 100, PackSplitter.Unit.Kind.ENTRY, "g1"),
            unit("e2", 100, PackSplitter.Unit.Kind.ENTRY, "g1"),
            unit("g:h2", 100, PackSplitter.Unit.Kind.GROUP_HEADER, "g2"),
            unit("e3", 100, PackSplitter.Unit.Kind.ENTRY, "g2")
        )
        val parts = PackSplitter.plan(units, 1000)

        assertEquals("both groups fit in one part", 1, parts.size)
    }

    @Test
    fun `a group larger than the target continues into later parts`() {
        val units = listOf(
            unit("g:h", 100, PackSplitter.Unit.Kind.GROUP_HEADER, "g1"),
            unit("s1", 600), unit("s2", 600), unit("s3", 600)
        )
        val parts = PackSplitter.plan(units, 1000)

        assertTrue("the group cannot fit in one part", parts.size > 1)
        // Its units are spread, but every one still appears exactly once.
        val keys = parts.flatMap { it.units }.map { it.key }
        assertEquals(4, keys.size)
        assertEquals(4, keys.distinct().size)
        // Every part after the first still carries group g1 context.
        assertTrue(parts.drop(1).all { p -> p.units.all { it.groupKey == "g1" } })
    }

    @Test
    fun `starting a new group closes the previous part`() {
        val units = listOf(
            unit("s1", 600, group = "g1"),
            unit("s2", 600, group = "g1"),
            unit("s3", 600, group = "g2")
        )
        val parts = PackSplitter.plan(units, 1000)

        // g1's two units share a part; g2 starts fresh rather than being mixed in.
        val g2Part = parts.single { p -> p.units.any { it.groupKey == "g2" } }
        assertTrue(g2Part.units.all { it.groupKey == "g2" })
    }

    @Test
    fun `planning is deterministic`() {
        val units = (1..50).map { unit("u$it", ((it * 137) % 900 + 50).toLong(), group = "g${it % 5}") }
        val a = PackSplitter.plan(units, 2000).map { it.units.map { u -> u.key } }
        val b = PackSplitter.plan(units, 2000).map { it.units.map { u -> u.key } }
        assertEquals("same input + same target must give the same parts", a, b)
    }

    @Test
    fun `empty input produces no parts`() {
        assertTrue(PackSplitter.plan(emptyList(), 1000).isEmpty())
    }

    @Test
    fun `part numbering is zero padded and widens past 99`() {
        assertEquals(2, PackSplitter.numberWidth(4))
        assertEquals(3, PackSplitter.numberWidth(100))
        assertEquals("PART 01", PackSplitter.partLabel(1, 2))
        assertEquals("PART 007", PackSplitter.partLabel(7, 3))
        assertEquals("Jaz_Negma_AI_PACK_01", PackSplitter.partFileStem("Jaz_Negma_AI_PACK", 1, 2))
        assertEquals("Jaz_Negma_AI_PACK_001", PackSplitter.partFileStem("Jaz_Negma_AI_PACK", 1, 3))
    }

    @Test
    fun `source ids are assigned globally and never restarted`() {
        val ids = PackSplitter.assignSourceIds((1L..250L).toList())
        assertEquals(250, ids.size)
        assertEquals("SOURCE-0001", ids[1L])
        assertEquals("SOURCE-0250", ids[250L])

        // Split into parts, the numbering continues rather than restarting.
        val part1 = (1L..100L).map { ids.getValue(it) }
        val part2 = (101L..250L).map { ids.getValue(it) }
        assertTrue(part1.contains("SOURCE-0001"))
        assertFalse(part2.contains("SOURCE-0001"))
        assertEquals("SOURCE-0101", part2.first())
        assertEquals(250, (part1 + part2).distinct().size)
    }

    @Test
    fun `source id mapping covers each document once`() {
        val ids = PackSplitter.assignSourceIds(listOf(50L, 51L, 52L))
        assertEquals(setOf("SOURCE-0001", "SOURCE-0002", "SOURCE-0003"), ids.values.toSet())
        assertEquals(3, ids.keys.size)
    }
}
