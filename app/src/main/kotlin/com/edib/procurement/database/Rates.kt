package com.edib.procurement.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.ForeignKey.Companion.SET_NULL
import kotlinx.coroutines.flow.Flow
import java.util.Locale

/**
 * One commercial price observation.
 *
 * A rate is never stored as a naked number. The context that makes it
 * comparable travels with it: item, unit, scope, contractor, project,
 * location, date, commercial status and source evidence.
 */
@Entity(
    tableName = "rate_observation",
    foreignKeys = [
        ForeignKey(
            entity = ContractorEntity::class,
            parentColumns = ["id"],
            childColumns = ["contractorId"],
            onDelete = SET_NULL
        ),
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = SET_NULL
        ),
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceDocumentId"],
            onDelete = SET_NULL
        ),
        ForeignKey(
            entity = FollowUpEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceEntryId"],
            onDelete = SET_NULL
        )
    ],
    indices = [
        Index(value = ["itemNormalized"]),
        Index(value = ["unit"]),
        Index(value = ["scope"]),
        Index(value = ["status"]),
        Index(value = ["observedDate"]),
        Index(value = ["contractorId"]),
        Index(value = ["projectId"]),
        Index(value = ["sourceDocumentId"]),
        Index(value = ["sourceEntryId"]),
        Index(value = ["itemNormalized", "unit", "scope", "status"])
    ]
)
data class RateObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemRaw: String,
    val itemNormalized: String,
    val material: String? = null,
    val specification: String? = null,
    val dimensions: String? = null,
    val unit: String,
    /** SUPPLY / INSTALL / SUPPLY_INSTALL / OTHER. */
    val scope: String,
    val contractorId: Long? = null,
    /** Snapshot/raw value survives even if no canonical contractor is selected. */
    val contractorRaw: String? = null,
    val projectId: Long? = null,
    /** Snapshot/raw value survives even if no canonical project is selected. */
    val projectRaw: String? = null,
    val location: String? = null,
    val quantity: Double? = null,
    val rate: Double,
    val currency: String = "EGP",
    /** QUOTED / APPROVED / AWARDED / OBSERVED. */
    val status: String,
    /** Epoch millis at local start-of-day for the commercial observation date. */
    val observedDate: Long,
    val sourceDocumentId: Long? = null,
    val sourceEntryId: Long? = null,
    /** Immutable provenance snapshots. */
    val sourceFileNameSnapshot: String? = null,
    val sourceRelativePathSnapshot: String? = null,
    val sourceUriSnapshot: String? = null,
    /** e.g. "Page 2", "BOQ row 14", "Item 7". */
    val evidenceLocator: String? = null,
    /** Optional exact commercial wording captured by the user. */
    val evidenceText: String? = null,
    val notes: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

data class RateListRow(
    val id: Long,
    val itemRaw: String,
    val itemNormalized: String,
    val material: String?,
    val specification: String?,
    val dimensions: String?,
    val unit: String,
    val scope: String,
    val contractorId: Long?,
    val contractorName: String?,
    val projectId: Long?,
    val projectName: String?,
    val location: String?,
    val quantity: Double?,
    val rate: Double,
    val currency: String,
    val status: String,
    val observedDate: Long,
    val sourceDocumentId: Long?,
    val sourceFileName: String?,
    val sourceRelativePath: String?,
    val sourceUri: String?,
    val sourceMimeType: String?,
    val sourceExtension: String?,
    val evidenceLocator: String?,
    val evidenceText: String?,
    val notes: String?
)

data class RateSourceChoice(
    val id: Long,
    val displayName: String,
    val relativePath: String,
    val documentUri: String,
    val mimeType: String?,
    val extension: String?,
    val contentState: String
)

/** UI-to-repository payload. Raw user wording is intentionally retained. */
data class RateDraft(
    val itemRaw: String,
    val material: String? = null,
    val specification: String? = null,
    val dimensions: String? = null,
    val unit: String,
    val scope: String,
    val contractorId: Long? = null,
    val contractorRaw: String? = null,
    val projectId: Long? = null,
    val projectRaw: String? = null,
    val location: String? = null,
    val quantity: Double? = null,
    val rate: Double,
    val currency: String = "EGP",
    val status: String,
    val observedDate: Long,
    val sourceDocumentId: Long? = null,
    val sourceEntryId: Long? = null,
    val evidenceLocator: String? = null,
    val evidenceText: String? = null,
    val notes: String? = null
)

object RateStatus {
    const val QUOTED = "QUOTED"
    const val APPROVED = "APPROVED"
    const val AWARDED = "AWARDED"
    const val OBSERVED = "OBSERVED"

    val all = listOf(QUOTED, APPROVED, AWARDED, OBSERVED)
    val sourceRequired = setOf(QUOTED, APPROVED, AWARDED)
}

object RateScope {
    const val SUPPLY = "SUPPLY"
    const val INSTALL = "INSTALL"
    const val SUPPLY_INSTALL = "SUPPLY_INSTALL"
    const val OTHER = "OTHER"

    val all = listOf(SUPPLY, INSTALL, SUPPLY_INSTALL, OTHER)
}

/**
 * Conservative normalization for lookup only.
 *
 * No synonym substitution, stemming, dimension conversion or material guessing.
 * "وزرة رخام 10 سم" and "وزرة رخام 15 سم" therefore remain different keys.
 */
fun normalizeRateItem(value: String): String =
    value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

fun normalizeRateUnit(value: String): String {
    val compact = value
        .trim()
        .lowercase(Locale.ROOT)
        .map { ch ->
            when (ch) {
                '٠' -> '0'
                '١' -> '1'
                '٢' -> '2'
                '٣' -> '3'
                '٤' -> '4'
                '٥' -> '5'
                '٦' -> '6'
                '٧' -> '7'
                '٨' -> '8'
                '٩' -> '9'
                else -> ch
            }
        }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .replace("²", "2")
        .replace("³", "3")
        .replace("^", "")
        .replace(".", "")
        .trim()

    return when (compact) {
        "m2", "sqm", "sq m", "square meter", "square meters",
        "square metre", "square metres", "م2", "م²", "متر مربع", "مترمربع" -> "m2"

        "m3", "cum", "cu m", "cubic meter", "cubic meters",
        "cubic metre", "cubic metres", "م3", "م³", "متر مكعب", "مترمكعب" -> "m3"

        "lm", "l m", "linear meter", "linear meters",
        "linear metre", "linear metres", "م ط", "متر طولي" -> "lm"

        else -> compact
    }
}

@Dao
interface RateDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(rate: RateObservationEntity): Long

    @Update
    suspend fun update(rate: RateObservationEntity)

    @Query("DELETE FROM rate_observation WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM rate_observation")
    fun countFlow(): Flow<Int>

    @Query(
        """
        SELECT
            r.id AS id,
            r.itemRaw AS itemRaw,
            r.itemNormalized AS itemNormalized,
            r.material AS material,
            r.specification AS specification,
            r.dimensions AS dimensions,
            r.unit AS unit,
            r.scope AS scope,
            r.contractorId AS contractorId,
            COALESCE(c.canonicalName, r.contractorRaw) AS contractorName,
            r.projectId AS projectId,
            COALESCE(p.canonicalName, r.projectRaw) AS projectName,
            r.location AS location,
            r.quantity AS quantity,
            r.rate AS rate,
            r.currency AS currency,
            r.status AS status,
            r.observedDate AS observedDate,
            r.sourceDocumentId AS sourceDocumentId,
            COALESCE(d.displayName, r.sourceFileNameSnapshot) AS sourceFileName,
            COALESCE(d.relativePath, r.sourceRelativePathSnapshot) AS sourceRelativePath,
            COALESCE(d.documentUri, r.sourceUriSnapshot) AS sourceUri,
            d.mimeType AS sourceMimeType,
            d.extension AS sourceExtension,
            r.evidenceLocator AS evidenceLocator,
            r.evidenceText AS evidenceText,
            r.notes AS notes
        FROM rate_observation r
        LEFT JOIN contractor c ON c.id = r.contractorId
        LEFT JOIN project p ON p.id = r.projectId
        LEFT JOIN document d ON d.id = r.sourceDocumentId
        WHERE :query = ''
           OR r.itemRaw LIKE '%' || :query || '%'
           OR r.itemNormalized LIKE '%' || :query || '%'
           OR r.material LIKE '%' || :query || '%'
           OR r.specification LIKE '%' || :query || '%'
           OR r.dimensions LIKE '%' || :query || '%'
           OR r.unit LIKE '%' || :query || '%'
           OR r.scope LIKE '%' || :query || '%'
           OR r.status LIKE '%' || :query || '%'
           OR COALESCE(c.canonicalName, r.contractorRaw, '') LIKE '%' || :query || '%'
           OR COALESCE(p.canonicalName, r.projectRaw, '') LIKE '%' || :query || '%'
           OR r.location LIKE '%' || :query || '%'
           OR COALESCE(d.displayName, r.sourceFileNameSnapshot, '') LIKE '%' || :query || '%'
        ORDER BY r.observedDate DESC, r.id DESC
        """
    )
    fun ratesFlow(query: String): Flow<List<RateListRow>>

    @Query(
        """
        SELECT
            d.id AS id,
            d.displayName AS displayName,
            d.relativePath AS relativePath,
            d.documentUri AS documentUri,
            d.mimeType AS mimeType,
            d.extension AS extension,
            d.contentState AS contentState
        FROM document d
        WHERE d.isMissing = 0
          AND (:query = ''
               OR d.displayName LIKE '%' || :query || '%'
               OR d.relativePath LIKE '%' || :query || '%'
               OR d.extractedText LIKE '%' || :query || '%')
        ORDER BY
            CASE WHEN :query != '' AND d.displayName LIKE '%' || :query || '%' THEN 0 ELSE 1 END,
            d.indexedAt DESC,
            d.id DESC
        LIMIT 40
        """
    )
    fun sourceDocuments(query: String): Flow<List<RateSourceChoice>>

    /**
     * Strict comparator seed: exact normalized item + exact normalized unit +
     * exact scope + awarded only. It deliberately does not average or scale.
     */
    @Query(
        """
        SELECT
            r.id AS id,
            r.itemRaw AS itemRaw,
            r.itemNormalized AS itemNormalized,
            r.material AS material,
            r.specification AS specification,
            r.dimensions AS dimensions,
            r.unit AS unit,
            r.scope AS scope,
            r.contractorId AS contractorId,
            COALESCE(c.canonicalName, r.contractorRaw) AS contractorName,
            r.projectId AS projectId,
            COALESCE(p.canonicalName, r.projectRaw) AS projectName,
            r.location AS location,
            r.quantity AS quantity,
            r.rate AS rate,
            r.currency AS currency,
            r.status AS status,
            r.observedDate AS observedDate,
            r.sourceDocumentId AS sourceDocumentId,
            COALESCE(d.displayName, r.sourceFileNameSnapshot) AS sourceFileName,
            COALESCE(d.relativePath, r.sourceRelativePathSnapshot) AS sourceRelativePath,
            COALESCE(d.documentUri, r.sourceUriSnapshot) AS sourceUri,
            d.mimeType AS sourceMimeType,
            d.extension AS sourceExtension,
            r.evidenceLocator AS evidenceLocator,
            r.evidenceText AS evidenceText,
            r.notes AS notes
        FROM rate_observation r
        LEFT JOIN contractor c ON c.id = r.contractorId
        LEFT JOIN project p ON p.id = r.projectId
        LEFT JOIN document d ON d.id = r.sourceDocumentId
        WHERE r.status = 'AWARDED'
          AND r.itemNormalized = :itemNormalized
          AND LOWER(TRIM(r.unit)) = :unitNormalized
          AND r.scope = :scope
          AND (:contractorId IS NULL OR r.contractorId = :contractorId)
        ORDER BY r.observedDate DESC, r.id DESC
        LIMIT 1
        """
    )
    suspend fun latestComparableAwarded(
        itemNormalized: String,
        unitNormalized: String,
        scope: String,
        contractorId: Long?
    ): RateListRow?

    /**
     * Candidate pool for Rate Check.
     *
     * SQL admits awarded observations with exact item/scope/currency. Unit,
     * material, specification and dimensions are gated in Kotlin so controlled
     * unit aliases such as m2 / m² can compare safely without mutating raw data.
     * No averages, width scaling or scope conversion happen here.
     */
    @Query(
        """
        SELECT
            r.id AS id,
            r.itemRaw AS itemRaw,
            r.itemNormalized AS itemNormalized,
            r.material AS material,
            r.specification AS specification,
            r.dimensions AS dimensions,
            r.unit AS unit,
            r.scope AS scope,
            r.contractorId AS contractorId,
            COALESCE(c.canonicalName, r.contractorRaw) AS contractorName,
            r.projectId AS projectId,
            COALESCE(p.canonicalName, r.projectRaw) AS projectName,
            r.location AS location,
            r.quantity AS quantity,
            r.rate AS rate,
            r.currency AS currency,
            r.status AS status,
            r.observedDate AS observedDate,
            r.sourceDocumentId AS sourceDocumentId,
            COALESCE(d.displayName, r.sourceFileNameSnapshot) AS sourceFileName,
            COALESCE(d.relativePath, r.sourceRelativePathSnapshot) AS sourceRelativePath,
            COALESCE(d.documentUri, r.sourceUriSnapshot) AS sourceUri,
            d.mimeType AS sourceMimeType,
            d.extension AS sourceExtension,
            r.evidenceLocator AS evidenceLocator,
            r.evidenceText AS evidenceText,
            r.notes AS notes
        FROM rate_observation r
        LEFT JOIN contractor c ON c.id = r.contractorId
        LEFT JOIN project p ON p.id = r.projectId
        LEFT JOIN document d ON d.id = r.sourceDocumentId
        WHERE r.status = 'AWARDED'
          AND r.itemNormalized = :itemNormalized
          AND r.scope = :scope
          AND UPPER(TRIM(r.currency)) = :currency
        ORDER BY r.observedDate DESC, r.id DESC
        LIMIT :limit
        """
    )
    suspend fun awardedCandidates(
        itemNormalized: String,
        scope: String,
        currency: String,
        limit: Int = 200
    ): List<RateListRow>

    /**
     * Existing awarded observations offered as safe templates in Rate Check.
     * Selecting one copies only its commercial signature into the proposal
     * form. It does not choose its historical price as the proposed price.
     */
    @Query(
        """
        SELECT
            r.id AS id,
            r.itemRaw AS itemRaw,
            r.itemNormalized AS itemNormalized,
            r.material AS material,
            r.specification AS specification,
            r.dimensions AS dimensions,
            r.unit AS unit,
            r.scope AS scope,
            r.contractorId AS contractorId,
            COALESCE(c.canonicalName, r.contractorRaw) AS contractorName,
            r.projectId AS projectId,
            COALESCE(p.canonicalName, r.projectRaw) AS projectName,
            r.location AS location,
            r.quantity AS quantity,
            r.rate AS rate,
            r.currency AS currency,
            r.status AS status,
            r.observedDate AS observedDate,
            r.sourceDocumentId AS sourceDocumentId,
            COALESCE(d.displayName, r.sourceFileNameSnapshot) AS sourceFileName,
            COALESCE(d.relativePath, r.sourceRelativePathSnapshot) AS sourceRelativePath,
            COALESCE(d.documentUri, r.sourceUriSnapshot) AS sourceUri,
            d.mimeType AS sourceMimeType,
            d.extension AS sourceExtension,
            r.evidenceLocator AS evidenceLocator,
            r.evidenceText AS evidenceText,
            r.notes AS notes
        FROM rate_observation r
        LEFT JOIN contractor c ON c.id = r.contractorId
        LEFT JOIN project p ON p.id = r.projectId
        LEFT JOIN document d ON d.id = r.sourceDocumentId
        WHERE r.status = 'AWARDED'
          AND (
              :query = ''
              OR r.itemRaw LIKE '%' || :query || '%'
              OR r.material LIKE '%' || :query || '%'
              OR r.specification LIKE '%' || :query || '%'
              OR r.dimensions LIKE '%' || :query || '%'
              OR r.unit LIKE '%' || :query || '%'
          )
        ORDER BY
            CASE WHEN :query != '' AND r.itemRaw LIKE :query || '%' THEN 0 ELSE 1 END,
            r.observedDate DESC,
            r.id DESC
        LIMIT 40
        """
    )
    fun awardedChoices(query: String): Flow<List<RateListRow>>
}
