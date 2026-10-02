package com.edib.procurement.aipack

import java.io.File

/**
 * Removes the debris a killed or cancelled multipart export can leave behind.
 *
 * Splitting writes several files, so a process death between two parts would
 * otherwise leave a directory holding part 1 and part 2 with no index and no
 * history row: files that look like a finished export but are not one.
 *
 * The rule is deliberately conservative. Only files that look like in-progress
 * output are removed:
 *  - temporary ".part" files, which by definition were never finished;
 *  - part ZIPs and an index that share an export id with NO history row, which
 *    means the export never reached the point of being recorded.
 *
 * A completed export has a history row, so it is never touched. A completed
 * single pack from phase 3.x is untouched for the same reason.
 */
class StaleMultipartCleanup(private val packDir: File, private val recordedNames: Set<String>) {

    data class Result(
        val removedTemp: List<String>,
        val removedOrphanParts: List<String>,
        val removedOrphanIndex: List<String>,
        val kept: List<String>
    ) {
        val removedAny: Boolean get() =
            removedTemp.isNotEmpty() || removedOrphanParts.isNotEmpty() || removedOrphanIndex.isNotEmpty()
    }

    fun run(): Result {
        if (!packDir.isDirectory) {
            return Result(emptyList(), emptyList(), emptyList(), emptyList())
        }
        val files = packDir.listFiles() ?: return Result(emptyList(), emptyList(), emptyList(), emptyList())

        val temps = ArrayList<String>()
        val orphans = ArrayList<String>()
        val orphanIndex = ArrayList<String>()
        val kept = ArrayList<String>()

        // Group candidate part/index files by export stem so an export with no
        // history row can be recognised as a set.
        val byStem = HashMap<String, MutableList<File>>()
        for (f in files) {
            if (f.isDirectory) continue
            val n = f.name
            when {
                // Never finished writing.
                n.startsWith(".") && n.endsWith(".part") -> {
                    if (f.delete()) temps.add(n)
                }
                // A half-rewritten manifest is also temporary debris.
                n.startsWith(".manifest-") -> {
                    if (f.delete()) temps.add(n)
                }
                stemOf(n) != null -> {
                    byStem.getOrPut(stemOf(n)!!) { ArrayList() }.add(f)
                }
                else -> kept.add(n)
            }
        }

        // A recorded file name identifies a whole completed export: derive its stem so
        // a recorded index also protects its parts.
        val recordedStems = recordedNames.mapNotNull { stemOf(it) }.toHashSet()

        for ((stem, group) in byStem) {
            if (recordedStems.contains(stem)) {
                kept.addAll(group.map { it.name })
                continue
            }
            // No history row for this export: it never completed.
            for (f in group) {
                val ok = f.delete()
                if (ok) {
                    if (f.name.endsWith("_INDEX.md")) orphanIndex.add(f.name)
                    else orphans.add(f.name)
                } else {
                    kept.add(f.name)
                }
            }
        }

        return Result(
            removedTemp = temps,
            removedOrphanParts = orphans,
            removedOrphanIndex = orphanIndex,
            kept = kept
        )
    }

    companion object {
        /**
         * A generated part file.
         *
         * Two shapes must match, because the writer appends the part number to
         * a stem that already ends in the dated base name:
         *   Scope_AI_PACK_01.zip              (plain stem)
         *   Scope_AI_PACK_2026-10-02-GROUP-1_01.zip   (dated stem, as generated)
         */
        val partFileRegex =
            Regex("^[A-Za-z0-9._-]+_\\d{2,3}\\.(zip|md)$")

        /**
         * The scope stem a generated file belongs to.
         *
         * `Jaz_Negma_AI_PACK_01.zip` and `Jaz_Negma_AI_PACK_INDEX.md` both map to
         * `Jaz_Negma`, so a history row for either protects the whole export.
         * The `_AI_PACK` suffix is stripped from both sides of the comparison,
         * which is why the index branch needs the same trim as the part branch.
         */
        fun stemOf(fileName: String): String? {
            val trimmed = when {
                fileName.endsWith("_AI_PACK_INDEX.md") ->
                    fileName.removeSuffix("_AI_PACK_INDEX.md")
                fileName.endsWith("_INDEX.md") ->
                    fileName.removeSuffix("_INDEX.md").removeSuffix("_AI_PACK")
                // A part: drop the trailing "_01.zip" part number.
                partFileRegex.matches(fileName) ->
                    fileName.substringBeforeLast('_')
                else -> return null
            }
            return trimmed.ifEmpty { null }
        }
    }
}
