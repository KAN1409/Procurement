package com.edib.procurement.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.ForeignKey.Companion.SET_NULL
import kotlinx.coroutines.flow.Flow

object RateCandidateStatus {
    const val PENDING = "PENDING"
    const val CONFIRMED = "CONFIRMED"
    const val DISMISSED = "DISMISSED"
    const val STALE = "STALE"
}

@Entity(
    tableName = "rate_candidate",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceDocumentId"],
            onDelete = SET_NULL
        ),
        ForeignKey(
            entity = RateObservationEntity::class,
            parentColumns = ["id"],
            childColumns = ["confirmedRateId"],
            onDelete = SET_NULL
        )
    ],
    indices = [
        Index(value = ["sourceKey"], unique = true),
        Index(value = ["sourceDocumentId"]),
        Index(value = ["status"]),
        Index(value = ["confidence"]),
        Index(value = ["confirmedRateId"])
    ]
)
data class RateCandidateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Stable within one source revision + evidence row. Prevents duplicate scans. */
    val sourceKey: String,
    val sourceDocumentId: Long? = null,
    val sourceFileNameSnapshot: String,
    val sourceRelativePathSnapshot: String,
    val sourceUriSnapshot: String,
    val sourceModifiedAt: Long,
    val sourceExtension: String? = null,
    val sourceMimeType: String? = null,
    val itemRaw: String,
    val unitRaw: String? = null,
    val quantity: Double? = null,
    val rate: Double,
    val currency: String? = null,
    /** Nullable because extraction must not invent supply/install scope. */
    val inferredScope: String? = null,
    val evidenceLocator: String,
    val evidenceText: String,
    /** 0..100 extraction confidence, never a commercial approval. */
    val confidence: Int,
    val detectionMethod: String,
    val parserVersion: String,
    val status: String = RateCandidateStatus.PENDING,
    val confirmedRateId: Long? = null,
    val createdAt: Long,
    val reviewedAt: Long? = null
)

data class RateCandidateRow(
    val id: Long,
    val sourceKey: String,
    val sourceDocumentId: Long?,
    val sourceFileName: String,
    val sourceRelativePath: String,
    val sourceUri: String,
    val sourceExtension: String?,
    val sourceMimeType: String?,
    val sourceModifiedAt: Long,
    val itemRaw: String,
    val unitRaw: String?,
    val quantity: Double?,
    val rate: Double,
    val currency: String?,
    val inferredScope: String?,
    val evidenceLocator: String,
    val evidenceText: String,
    val confidence: Int,
    val detectionMethod: String,
    val status: String,
    val confirmedRateId: Long?
)

/** Indexed source text consumed read-only by the candidate scanner. */
data class RateCorpusDocument(
    val id: Long,
    val displayName: String,
    val relativePath: String,
    val documentUri: String,
    val mimeType: String?,
    val extension: String?,
    val lastModified: Long,
    val extractedText: String
)

@Dao
interface RateCandidateDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<RateCandidateEntity>): List<Long>

    @Query("""
        UPDATE rate_candidate SET status = 'STALE'
        WHERE status = 'PENDING' AND parserVersion <> :currentVersion
    """)
    suspend fun markSupersededPendingStale(currentVersion: String)

    @Query("SELECT COUNT(*) FROM rate_candidate WHERE status = 'PENDING'")
    fun pendingCountFlow(): Flow<Int>

    @Query(
        """
        SELECT
            c.id AS id,
            c.sourceKey AS sourceKey,
            c.sourceDocumentId AS sourceDocumentId,
            COALESCE(d.displayName, c.sourceFileNameSnapshot) AS sourceFileName,
            COALESCE(d.relativePath, c.sourceRelativePathSnapshot) AS sourceRelativePath,
            COALESCE(d.documentUri, c.sourceUriSnapshot) AS sourceUri,
            COALESCE(d.extension, c.sourceExtension) AS sourceExtension,
            COALESCE(d.mimeType, c.sourceMimeType) AS sourceMimeType,
            c.sourceModifiedAt AS sourceModifiedAt,
            c.itemRaw AS itemRaw,
            c.unitRaw AS unitRaw,
            c.quantity AS quantity,
            c.rate AS rate,
            c.currency AS currency,
            c.inferredScope AS inferredScope,
            c.evidenceLocator AS evidenceLocator,
            c.evidenceText AS evidenceText,
            c.confidence AS confidence,
            c.detectionMethod AS detectionMethod,
            c.status AS status,
            c.confirmedRateId AS confirmedRateId
        FROM rate_candidate c
        LEFT JOIN document d ON d.id = c.sourceDocumentId
        WHERE c.status = 'PENDING'
          AND (:scope = '' OR COALESCE(c.inferredScope, '') = :scope)
          AND (:minConfidence = 0 OR c.confidence >= :minConfidence)
          AND (
              :query = ''
              OR REPLACE(c.itemRaw, 'ة', 'ه') LIKE '%' || REPLACE(:query, 'ة', 'ه') || '%'
              OR REPLACE(c.evidenceText, 'ة', 'ه') LIKE '%' || REPLACE(:query, 'ة', 'ه') || '%'
              OR c.sourceFileNameSnapshot LIKE '%' || :query || '%'
              OR c.unitRaw LIKE '%' || :query || '%'
          )
        ORDER BY
            CASE WHEN :sort = 'RATE_ASC' THEN c.rate END ASC,
            CASE WHEN :sort = 'RATE_DESC' THEN c.rate END DESC,
            CASE WHEN :sort = 'SOURCE' THEN c.sourceRelativePathSnapshot END COLLATE NOCASE ASC,
            c.confidence DESC, c.id ASC
        """
    )
    fun pendingFlow(query: String, scope: String = "", minConfidence: Int = 0, sort: String = "CONFIDENCE"): Flow<List<RateCandidateRow>>

    @Query(
        """
        SELECT id, displayName, relativePath, documentUri, mimeType, extension,
               lastModified, extractedText
        FROM document
        WHERE isMissing = 0
          AND contentState = 'INDEXED'
          AND TRIM(extractedText) <> ''
        ORDER BY id ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun indexedCorpusDocuments(limit: Int, offset: Int): List<RateCorpusDocument>

    @Query(
        """
        UPDATE rate_candidate
        SET status = 'STALE'
        WHERE sourceDocumentId = :documentId
          AND sourceModifiedAt <> :sourceModifiedAt
          AND status = 'PENDING'
        """
    )
    suspend fun markOldPendingStale(documentId: Long, sourceModifiedAt: Long)

    @Query(
        """
        UPDATE rate_candidate
        SET status = 'STALE'
        WHERE status = 'PENDING'
          AND sourceDocumentId IS NOT NULL
          AND EXISTS (
              SELECT 1 FROM document d
              WHERE d.id = rate_candidate.sourceDocumentId
                AND (d.isMissing = 1 OR d.lastModified <> rate_candidate.sourceModifiedAt)
          )
        """
    )
    suspend fun markChangedOrMissingPendingStale()

    @Query(
        """
        UPDATE rate_candidate
        SET status = 'DISMISSED', reviewedAt = :now
        WHERE id = :id AND status = 'PENDING'
        """
    )
    suspend fun dismiss(id: Long, now: Long)

    @Query(
        """
        UPDATE rate_candidate
        SET status = 'CONFIRMED', confirmedRateId = :rateId, reviewedAt = :now
        WHERE id = :id AND status = 'PENDING'
        """
    )
    suspend fun confirm(id: Long, rateId: Long, now: Long)

    @Query(
        """
        SELECT
            c.id AS id,
            c.sourceKey AS sourceKey,
            c.sourceDocumentId AS sourceDocumentId,
            COALESCE(d.displayName, c.sourceFileNameSnapshot) AS sourceFileName,
            COALESCE(d.relativePath, c.sourceRelativePathSnapshot) AS sourceRelativePath,
            COALESCE(d.documentUri, c.sourceUriSnapshot) AS sourceUri,
            COALESCE(d.extension, c.sourceExtension) AS sourceExtension,
            COALESCE(d.mimeType, c.sourceMimeType) AS sourceMimeType,
            c.sourceModifiedAt AS sourceModifiedAt,
            c.itemRaw AS itemRaw,
            c.unitRaw AS unitRaw,
            c.quantity AS quantity,
            c.rate AS rate,
            c.currency AS currency,
            c.inferredScope AS inferredScope,
            c.evidenceLocator AS evidenceLocator,
            c.evidenceText AS evidenceText,
            c.confidence AS confidence,
            c.detectionMethod AS detectionMethod,
            c.status AS status,
            c.confirmedRateId AS confirmedRateId
        FROM rate_candidate c
        LEFT JOIN document d ON d.id = c.sourceDocumentId
        WHERE c.id = :id
        LIMIT 1
        """
    )
    suspend fun byId(id: Long): RateCandidateRow?
}
