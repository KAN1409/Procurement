package com.edib.procurement.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ForeignKey.Companion.CASCADE
import androidx.room.ForeignKey.Companion.SET_NULL

/**
 * One record of every Follow-up workbook ever imported.
 *
 * Only one import is [isCurrent]. Previous imports stay for provenance so a
 * group can always be traced back to the workbook/sheet/row it came from.
 */
@Entity(
    tableName = "followup_import",
    indices = [Index(value = ["isCurrent"]), Index(value = ["workbookFingerprint"])]
)
data class FollowUpImportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceUri: String,
    val displayName: String,
    val importDate: Long,
    val fileModifiedDate: Long,
    /** Layout fingerprint: header names + sheet names. Detects a layout change. */
    val schemaFingerprint: String,
    /** Content fingerprint: number+hash of each row. Detects a data revision. */
    val workbookFingerprint: String,
    val isCurrent: Boolean,
    val groupCount: Int = 0,
    val entryCount: Int = 0
)

/**
 * A canonical project name. [normalizedName] is the lookup key so that
 * "Jaz Negma " and "JAZ NEGMA" resolve to one project.
 */
@Entity(
    tableName = "project",
    indices = [Index(value = ["normalizedName"], unique = true)]
)
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val canonicalName: String,
    val normalizedName: String
)

/**
 * A Follow-up GROUP: one anchor row plus its continuation rows.
 *
 * The workbook does not have this concept — it is derived structurally (see
 * `followup/RowGrouper`). [stableKey] is the durable identity used across
 * re-imports; [originalExcelRow] is provenance only and may move between
 * workbook versions.
 */
@Entity(
    tableName = "followup_group",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = SET_NULL
        )
    ],
    indices = [
        Index(value = ["stableKey"]),
        Index(value = ["projectId"]),
        Index(value = ["rootCodeNormalized"]),
        Index(value = ["sourceImportId"]),
        Index(value = ["isPresentInCurrentImport"])
    ]
)
data class FollowUpGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Durable composite fingerprint. Never derived from the Excel row number. */
    val stableKey: String,
    val sourceImportId: Long,
    /** Provenance: 1-based row in the workbook sheet. Not an identity. */
    val originalExcelRow: Int,
    val number: String?,
    val projectId: Long?,
    val projectRaw: String?,
    val area: String?,
    val relatedTo: String?,
    val rootCodeRaw: String?,
    val rootCodeNormalized: String?,
    val prDate: String?,
    val notes: String?,
    /** Comma-separated UserTag ids chosen by the user. Preserved on re-import. */
    val userTags: String = "",
    val isFavorite: Boolean = false,
    /** Set by the user when the workbook project is wrong. Preserved. */
    val manualProjectOverride: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** False once a newer workbook no longer contains this group. */
    val isPresentInCurrentImport: Boolean = true
)

/**
 * A Follow-up ENTRY: one logical PR/order line inside a group.
 *
 * The anchor row produces the first entry of its group; each continuation row
 * produces another one. These must never be presented as separate groups.
 */
@Entity(
    tableName = "followup_entry",
    foreignKeys = [
        ForeignKey(
            entity = FollowUpGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = CASCADE
        )
    ],
    indices = [
        Index(value = ["groupId"]),
        Index(value = ["codeNormalized"]),
        Index(value = ["matchCodeKey"]),
        Index(value = ["canonicalContractorId"]),
        Index(value = ["sourceSignature"], unique = true)
    ]
)
data class FollowUpEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    /** Provenance: 1-based row in the workbook sheet. */
    val sourceExcelRow: Int,
    val codeRaw: String?,
    val codeNormalized: String?,
    /**
     * Pre-computed matching key for this code, separators folded to spaces.
     *
     * Distinct from [codeNormalized]: this is what the matcher indexes against,
     * so a lookup never has to re-normalise every entry row.
     */
    val matchCodeKey: String,
    /**
     * Full historical Windows path. The cell text is usually truncated, so this
     * is resolved from the sheet's hyperlink relationship when one exists.
     */
    val prPathRaw: String?,
    val orderPathRaw: String?,
    val companyRaw: String?,
    val canonicalContractorId: Long?,
    /** User-corrected contractor mapping wins over the workbook-derived one. */
    val manualContractorId: Long? = null,
    val description: String?,
    val prDate: String?,
    val orderDate: String?,
    val prWithEngineer: String?,
    val notes: String?,
    val financialReceivedDate: String?,
    val finalApprovalDate: String?,
    val checkValue: String?,
    val oldCompanyRaw: String?,
    /** A / B / C / L ADJ / ملحق … derived for display and ordering. */
    val entryVariant: String?,
    /** Stable per-entry fingerprint used to reconcile a newer workbook. */
    val sourceSignature: String
)

/** A resolved contractor. Alias spellings point at one of these. */
@Entity(
    tableName = "contractor",
    indices = [Index(value = ["normalizedName"], unique = true)]
)
data class ContractorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val canonicalName: String,
    val normalizedName: String,
    /** User-created names (from manual mapping of an unresolved alias). */
    val isUserCreated: Boolean = false
)

/**
 * raw alias -> canonical contractor, built from the SUPPLIER LIST sheet plus
 * every manual mapping the user has made.
 */
@Entity(
    tableName = "contractor_alias",
    foreignKeys = [
        ForeignKey(
            entity = ContractorEntity::class,
            parentColumns = ["id"],
            childColumns = ["contractorId"],
            onDelete = CASCADE
        )
    ],
    indices = [
        Index(value = ["aliasNormalized"], unique = true),
        Index(value = ["contractorId"])
    ]
)
data class ContractorAliasEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contractorId: Long,
    val aliasRaw: String,
    val aliasNormalized: String,
    val origin: String = "SUPPLIER_LIST"
)

/**
 * Content indexing state for a document.
 */
enum class ContentState {
    NOT_INDEXED,
    INDEXED,
    NEEDS_OCR,
    STALE,
    UNSUPPORTED,
    FAILED
}

/**
 * One indexed file in the user's document tree. Content is never parsed during
 * a normal scan — only metadata is stored, and SHA-256 is filled in lazily.
 */
@Entity(
    tableName = "document",
    indices = [
        Index(value = ["treeUri"]),
        Index(value = ["normalizedName"]),
        Index(value = ["matchFileKey"]),
        Index(value = ["matchStemKey"]),
        Index(value = ["relativePath"]),
        Index(value = ["extension"]),
        Index(value = ["isMissing"]),
        Index(value = ["contentState"]),
        Index(value = ["indexedAt"])
    ]
)
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Root tree this file was found under. */
    val treeUri: String,
    val documentUri: String,
    val displayName: String,
    val normalizedName: String,
    /**
     * Pre-computed matching key for the file name, extension included.
     *
     * Stored rather than derived on the fly so matching is an index lookup
     * rather than a full scan of every document against every entry.
     */
    val matchFileKey: String,
    /** [matchFileKey] with the extension removed, used for code comparison. */
    val matchStemKey: String,
    val relativePath: String,
    val mimeType: String?,
    val extension: String?,
    val size: Long,
    val lastModified: Long,
    /** Empty until something actually needs content identity. */
    val lazySha256: String? = null,
    val indexedAt: Long,
    val isMissing: Boolean = false,
    /** Extracted text content for search (PDF text, OCR results, etc.) */
    val extractedText: String = "",
    /** Content indexing state - separate from metadata scan */
    val contentState: ContentState = ContentState.NOT_INDEXED,
    /** Method used for extraction: "pdfbox", "tesseract", "docx", "poi", "text", "unsupported" */
    val extractionMethod: String? = null,
    /** When extraction was performed */
    val extractedAt: Long? = null,
    /** Version of the extractor (e.g., "pdfbox-2.0.27", "tesseract-4.1.1") */
    val extractorVersion: String? = null,
    /** Error message if extraction failed */
    val extractionError: String? = null,
    /** Size of source file at time of extraction */
    val sourceSizeAtExtraction: Long? = null,
    /** Last-modified of source file at time of extraction */
    val sourceModifiedAtExtraction: Long? = null
)

/**
 * A relationship between a document and a group and/or an entry.
 *
 * [isManual] + [isLocked] mean the user made this link; rescans and re-imports
 * must never remove or downgrade it.
 */
@Entity(
    tableName = "document_link",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = CASCADE
        ),
        ForeignKey(
            entity = FollowUpGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = CASCADE
        ),
        ForeignKey(
            entity = FollowUpEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entryId"],
            onDelete = CASCADE
        )
    ],
    indices = [
        Index(value = ["documentId"]),
        Index(value = ["groupId"]),
        Index(value = ["entryId"]),
        Index(value = ["isManual", "isLocked"])
    ]
)
data class DocumentLinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val groupId: Long?,
    val entryId: Long?,
    /** PR / ORDER / PR_ATTACHMENT / OTHER / MANUAL. */
    val linkType: String,
    /** EXACT_FILENAME / NORMALIZED_FILENAME / CODE / CONTEXT / MANUAL. */
    val matchMethod: String,
    /** 0..100. >=95 auto, 80..94 suggested, <80 left unlinked. */
    val confidence: Int,
    val evidence: String?,
    val isManual: Boolean = false,
    val isLocked: Boolean = false,
    val createdAt: Long
)

/** A free-form tag the user can attach to groups. */
@Entity(tableName = "user_tag", indices = [Index(value = ["name"], unique = true)])
data class UserTagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)

/** Key/value settings: the chosen workbook URI, the chosen tree URI, flags. */
@Entity(tableName = "app_setting")
data class AppSettingEntity(
    @PrimaryKey val key: String,
    val value: String
)

/**
 * One generated AI Pack.
 *
 * This is a record of a file the app produced. It deliberately holds no
 * reference to any source document: deleting a pack must never be able to
 * affect an original, so there is nothing here that could cascade outward.
 */
@Entity(
    tableName = "ai_pack",
    indices = [Index(value = ["createdAt"])]
)
data class AiPackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Filesystem-safe name, e.g. MMH2-GR-AR-14-25_AI_PACK.zip. */
    val fileName: String,
    /** Absolute path inside the app's own storage. */
    val absolutePath: String,
    /** The human scope name, kept verbatim even when the file name is sanitised. */
    val scopeName: String,
    val scopeType: String,
    val createdAt: Long,
    val groupCount: Int,
    val entryCount: Int,
    val documentCount: Int,
    val originalBytes: Long,
    val packBytes: Long,
    val succeeded: Int,
    val ocrRequired: Int,
    val unsupported: Int,
    val errors: Int,
    val skipped: Int,
    /** Format version of the pack body; 1 for everything produced so far. */
    val formatVersion: Int = 1
) {
    /** Fraction of the original size the pack does not carry, 0..1. */
    val reduction: Double
        get() = if (originalBytes <= 0) 0.0
        else (1.0 - packBytes.toDouble() / originalBytes).coerceIn(0.0, 1.0)
}
