package com.edib.workknowledge.data

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.PrimaryKey

/**
 * Full-text search index over chunk content.
 *
 * Deliberately a **standalone** index rather than an external-content virtual
 * table: the index is written explicitly in the same step that writes a chunk,
 * so it can never drift from [ChunkEntity] and behaves identically on a fresh
 * install and after a migration. The cost is a second copy of the text, which
 * is negligible next to the vector blobs and buys predictable delete and
 * re-index behaviour.
 *
 * [Fts4] with the unicode61 tokenizer is used because it is the option Room
 * maps declaratively and SQLite always provides. Identifiers such as `MR-104`
 * are indexed as the tokens `mr` and `104`; `Piatra Grey` additionally
 * supports phrase queries.
 *
 * The primary key property must be named `rowid` (or carry
 * `@ColumnInfo(name = "rowid")`) for an FTS entity - Room rejects anything
 * else - so it is deliberately lowercase here.
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "chunks_fts")
data class ChunkFtsEntity(
    @PrimaryKey
    val rowid: Long = 0,
    val content: String = "",
    val filename: String = "",
    val projectName: String = "",
    val sheetName: String = ""
) {
    /** Kotlin-style alias; the property name is fixed by Room's FTS rules. */
    val rowId: Long get() = rowid
}
