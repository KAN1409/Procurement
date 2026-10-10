package com.edib.procurement.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema history.
 *
 * Migrations are additive and never destructive: manual links, tags and
 * contractor corrections are the user's own work, and an app upgrade must not
 * be able to lose them. There is deliberately no `fallbackToDestructiveMigration`
 * anywhere in this database for that reason.
 */
object Migrations {

    /**
     * v1 -> v2: pre-computed matching keys and their indexes.
     *
     * The matcher compares documents against entries, so both sides need a
     * normalised key that can be indexed and looked up directly. Room validates
     * the full table definition after a migration, so the indexes that
     * accompany the new columns have to be created here too, not just the
     * columns themselves.
     *
     * Rows are left with an empty key and backfilled immediately afterwards by
     * [backfillMatchKeys], because the normalisation lives in Kotlin. No
     * existing data is rewritten and no manual link is touched.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE document ADD COLUMN matchFileKey TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE document ADD COLUMN matchStemKey TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE followup_entry ADD COLUMN matchCodeKey TEXT NOT NULL DEFAULT ''")

            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_document_matchFileKey " +
                    "ON document(matchFileKey)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_document_matchStemKey " +
                    "ON document(matchStemKey)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_followup_entry_matchCodeKey " +
                    "ON followup_entry(matchCodeKey)"
            )
        }
    }

    /**
     * Backfills the matching keys after a migration.
     *
     * The normalisation itself lives in Kotlin, so it cannot run inside a SQL
     * statement. This is called once by the repository right after opening the
     * database, and only rewrites rows whose key is still empty.
     */
    suspend fun backfillMatchKeys(followUpDao: FollowUpDao, documentDao: DocumentDao) {
        followUpDao.entriesNeedingMatchKey().forEach { row ->
            followUpDao.setMatchCodeKey(
                row.id,
                com.edib.procurement.matching.MatchNormalizer.codeKey(row.codeNormalized ?: row.codeRaw)
            )
        }
        documentDao.documentsNeedingMatchKey().forEach { doc ->
            documentDao.setMatchKeys(
                doc.id,
                com.edib.procurement.matching.MatchNormalizer.filenameKey(doc.displayName),
                com.edib.procurement.matching.MatchNormalizer.fileStemKey(doc.displayName)
            )
        }
    }

    /**
     * Removes rows belonging to superseded imports.
     *
     * A re-import creates a new `followup_import` row and archives the groups
     * of the previous one, so the old groups and their entries stay behind as
     * history. That is correct in principle, but when the very same workbook is
     * imported again the archived rows are exact duplicates of the live ones:
     * same workbook row, same signature, nothing of the user's own attached to
     * them. They are only removed when a signature-safe check proves it:
     *
     *  - the entry's group belongs to a superseded import, AND
     *  - no document link points at the entry, so nothing the user did is lost.
     *
     * Deleting by matching `codeRaw` would be wrong: the reference workbook
     * legitimately repeats codes across groups, so provenance is used instead.
     */
    suspend fun purgeSupersededImportData(
        followUpDao: FollowUpDao,
        keepImportId: Long
    ): PurgeReport {
        val groups = followUpDao.supersededGroupIds(keepImportId)
        if (groups.isEmpty()) return PurgeReport(0, 0, 0)

        val linkedEntries = followUpDao.linkedEntryIdsInGroups(groups).toSet()
        val deletableEntries = followUpDao.entriesInGroups(groups)
            .filterNot { it.id in linkedEntries }
        followUpDao.deleteEntries(deletableEntries.map { it.id })

        val stillLinked = followUpDao.groupIdsWithLinkedEntries(groups)
        val deletableGroups = groups.filterNot { it in stillLinked }
        followUpDao.deleteGroups(deletableGroups)

        return PurgeReport(
            groupsDeleted = deletableGroups.size,
            entriesDeleted = deletableEntries.size,
            groupsKeptBecauseLinked = stillLinked.size
        )
    }

    data class PurgeReport(
        val groupsDeleted: Int,
        val entriesDeleted: Int,
        val groupsKeptBecauseLinked: Int
    )

    /**
     * v2 -> v3: AI Pack history.
     *
     * Two new tables and nothing touched. The pack history is a record of files
     * the app generated; it holds no handle to any source document, so adding it
     * cannot affect existing follow-up data or manual links.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ai_pack` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `fileName` TEXT NOT NULL,
                    `absolutePath` TEXT NOT NULL,
                    `scopeName` TEXT NOT NULL,
                    `scopeType` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `groupCount` INTEGER NOT NULL,
                    `entryCount` INTEGER NOT NULL,
                    `documentCount` INTEGER NOT NULL,
                    `originalBytes` INTEGER NOT NULL,
                    `packBytes` INTEGER NOT NULL,
                    `succeeded` INTEGER NOT NULL,
                    `ocrRequired` INTEGER NOT NULL,
                    `unsupported` INTEGER NOT NULL,
                    `errors` INTEGER NOT NULL,
                    `skipped` INTEGER NOT NULL,
                    `formatVersion` INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_ai_pack_createdAt` ON `ai_pack` (`createdAt`)"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ai_pack_source` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `packId` INTEGER NOT NULL,
                    `sourceId` TEXT NOT NULL,
                    `documentId` INTEGER,
                    `filename` TEXT NOT NULL,
                    `relativePath` TEXT NOT NULL,
                    `mimeType` TEXT,
                    `sizeBytes` INTEGER NOT NULL,
                    `linkType` TEXT,
                    `linkedManually` INTEGER NOT NULL,
                    `extractionStatus` TEXT NOT NULL,
                    `detail` TEXT,
                    FOREIGN KEY(`packId`) REFERENCES `ai_pack`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_ai_pack_source_packId` ON `ai_pack_source` (`packId`)"
            )
        }
    }

/**
 * v3 -> v4: Add extracted document text used by content search.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE document ADD COLUMN extractedText TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v4 -> v5: Add content indexing state fields to document table.
 *
 * This enables document-level content indexing separate from AI Pack generation.
 * Fields added:
 * - contentState: NOT_INDEXED, INDEXED, NEEDS_OCR, STALE, UNSUPPORTED, FAILED
 * - extractionMethod: which extractor was used
 * - extractedAt: when extraction was performed
 * - extractorVersion: version of the extractor
 * - extractionError: error message if failed
 * - sourceSizeAtExtraction: source file size at extraction time
 * - sourceModifiedAtExtraction: source file last-modified at extraction time
 *
 * All fields are nullable with defaults so existing rows remain valid.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE document ADD COLUMN contentState TEXT NOT NULL DEFAULT 'NOT_INDEXED'")
        db.execSQL("ALTER TABLE document ADD COLUMN extractionMethod TEXT")
        db.execSQL("ALTER TABLE document ADD COLUMN extractedAt INTEGER")
        db.execSQL("ALTER TABLE document ADD COLUMN extractorVersion TEXT")
        db.execSQL("ALTER TABLE document ADD COLUMN extractionError TEXT")
        db.execSQL("ALTER TABLE document ADD COLUMN sourceSizeAtExtraction INTEGER")
        db.execSQL("ALTER TABLE document ADD COLUMN sourceModifiedAtExtraction INTEGER")

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_document_contentState " +
                "ON document(contentState)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_document_indexedAt " +
                "ON document(indexedAt)"
        )
    }
}

/**
 * v5 -> v6: Trusted commercial rate observations.
 *
 * Additive only. Existing procurement, document, link and content-index data is
 * untouched. Commercial observations keep source snapshots so provenance
 * remains readable even if a source file later disappears.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS rate_observation (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                itemRaw TEXT NOT NULL,
                itemNormalized TEXT NOT NULL,
                material TEXT,
                specification TEXT,
                dimensions TEXT,
                unit TEXT NOT NULL,
                scope TEXT NOT NULL,
                contractorId INTEGER,
                contractorRaw TEXT,
                projectId INTEGER,
                projectRaw TEXT,
                location TEXT,
                quantity REAL,
                rate REAL NOT NULL,
                currency TEXT NOT NULL,
                status TEXT NOT NULL,
                observedDate INTEGER NOT NULL,
                sourceDocumentId INTEGER,
                sourceEntryId INTEGER,
                sourceFileNameSnapshot TEXT,
                sourceRelativePathSnapshot TEXT,
                sourceUriSnapshot TEXT,
                evidenceLocator TEXT,
                evidenceText TEXT,
                notes TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                FOREIGN KEY(contractorId) REFERENCES contractor(id)
                    ON UPDATE NO ACTION ON DELETE SET NULL,
                FOREIGN KEY(projectId) REFERENCES project(id)
                    ON UPDATE NO ACTION ON DELETE SET NULL,
                FOREIGN KEY(sourceDocumentId) REFERENCES document(id)
                    ON UPDATE NO ACTION ON DELETE SET NULL,
                FOREIGN KEY(sourceEntryId) REFERENCES followup_entry(id)
                    ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_rate_observation_itemNormalized ON rate_observation(itemNormalized)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_rate_observation_unit ON rate_observation(unit)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_rate_observation_scope ON rate_observation(scope)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_rate_observation_status ON rate_observation(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_rate_observation_observedDate ON rate_observation(observedDate)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_rate_observation_contractorId ON rate_observation(contractorId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_rate_observation_projectId ON rate_observation(projectId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_rate_observation_sourceDocumentId ON rate_observation(sourceDocumentId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_rate_observation_sourceEntryId ON rate_observation(sourceEntryId)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_rate_observation_itemNormalized_unit_scope_status " +
                "ON rate_observation(itemNormalized, unit, scope, status)"
        )
    }
}
}
