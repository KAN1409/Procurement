package com.edib.procurement.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.paging.PagingSource
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Row projection used by the follow-up screens. */
data class GroupListRow(
    val id: Long,
    val stableKey: String,
    val number: String?,
    val projectRaw: String?,
    val area: String?,
    val relatedTo: String?,
    val rootCodeRaw: String?,
    val rootCodeNormalized: String?,
    val prDate: String?,
    val notes: String?,
    val userTags: String,
    val manualProjectOverride: String?,
    val isFavorite: Boolean,
    val isPresentInCurrentImport: Boolean,
    val entryCount: Int,
    val documentCount: Int
)

data class EntryRow(
    val id: Long,
    val groupId: Long,
    val sourceExcelRow: Int,
    val codeRaw: String?,
    val codeNormalized: String?,
    val prPathRaw: String?,
    val orderPathRaw: String?,
    val companyRaw: String?,
    val contractorName: String?,
    val manualContractorName: String?,
    val description: String?,
    val prDate: String?,
    val orderDate: String?,
    val prWithEngineer: String?,
    val notes: String?,
    val financialReceivedDate: String?,
    val finalApprovalDate: String?,
    val checkValue: String?,
    val oldCompanyRaw: String?,
    val entryVariant: String?
)

data class LinkedDocumentRow(
    val documentId: Long,
    val displayName: String,
    val relativePath: String,
    val documentUri: String,
    val mimeType: String?,
    val size: Long,
    val extension: String?,
    val linkType: String,
    val matchMethod: String,
    val confidence: Int,
    val evidence: String?,
    val isManual: Boolean,
    val entryId: Long?,
    val entryCodeRaw: String?
)

data class DocumentListRow(
    val id: Long,
    val displayName: String,
    val relativePath: String,
    val documentUri: String,
    val mimeType: String?,
    val size: Long,
    val lastModified: Long,
    val extension: String?,
    val isMissing: Boolean,
    val linkCount: Int
)

data class UnlinkedDocumentRow(
    val id: Long,
    val displayName: String,
    val relativePath: String,
    val documentUri: String,
    val mimeType: String?,
    val size: Long,
    val extension: String?
)

data class ProjectWithCount(
    val id: Long,
    val canonicalName: String,
    val normalizedName: String,
    val groupCount: Int,
    val documentCount: Int
)

data class ContractorWithCount(
    val id: Long,
    val canonicalName: String,
    val normalizedName: String,
    val entryCount: Int,
    val unresolvedCount: Int
)

data class HomeCounts(
    val projects: Int,
    val groups: Int,
    val entries: Int,
    val documents: Int,
    val linkedDocuments: Int,
    val unlinkedDocuments: Int,
    val suggestedLinks: Int,
    val unresolvedContractors: Int,
    val contractors: Int
)

/** Minimal entry projection used by the matcher. */
data class EntryMatchRow(
    val entryId: Long,
    val groupId: Long,
    val codeRaw: String?,
    val matchCodeKey: String?,
    val description: String?,
    val contractorNormalized: String?
)

/** Minimal group projection used by the matcher. */
data class GroupMatchRow(
    val groupId: Long,
    val projectRaw: String?,
    val rootCodeRaw: String?,
    val rootCodeNormalized: String?
)

/** A historical workbook path paired with the entry that referenced it. */
data class HistoricalPathRow(
    val entryId: Long,
    val groupId: Long,
    val path: String?
)

/** An unresolved raw contractor string with how often it occurs. */
data class UnresolvedContractorRow(
    val companyRaw: String,
    val usageCount: Int
)

/** An entry awaiting a matching-key backfill. */
data class EntryKeyRow(
    val id: Long,
    val codeRaw: String?,
    val codeNormalized: String?
)

/** A document awaiting a matching-key backfill. */
data class DocumentKeyRow(
    val id: Long,
    val displayName: String
)

/** A document selected for an AI Pack, with the bytes it will contribute. */
data class PackDocumentRow(
    val id: Long,
    val displayName: String,
    val relativePath: String,
    val documentUri: String,
    val mimeType: String?,
    val size: Long,
    val extension: String?,
    val lazySha256: String?
)

/** One stored link, used to describe how a pack source was connected. */
data class PackLinkRow(
    val documentId: Long,
    val groupId: Long?,
    val entryId: Long?,
    val linkType: String,
    val matchMethod: String,
    val confidence: Int,
    val evidence: String?,
    val isManual: Boolean
)

/**
 * A complete group header for AI Pack output.
 *
 * Carries the provenance fields the pack prints verbatim, including the source
 * workbook row, so every exported record can be traced back to the file.
 */
data class PackGroupRow(    val groupId: Long,
    val projectRaw: String?,
    val rootCodeRaw: String?,
    val rootCodeNormalized: String?,
    val area: String?,
    val relatedTo: String?,
    val prDate: String?,
    val notes: String?,
    val number: String?,
    val originalExcelRow: Int
)

/** A suggested (not yet accepted) document link, for Needs Review. */
data class SuggestionRow(
    val id: Long,
    val displayName: String,
    val relativePath: String,
    val documentUri: String,
    val mimeType: String?,
    val size: Long,
    val extension: String?,
    val matchMethod: String?,
    val confidence: Int,
    val evidence: String?,
    val isManual: Boolean,
    val projectRaw: String?,
    val codeRaw: String?
)

/** A group offered as a manual link target. */
data class LinkTargetRow(
    val groupId: Long,
    val projectRaw: String?,
    val rootCodeRaw: String?,
    val area: String?,
    val relatedTo: String?,
    val entryCount: Int
)

/** An entry offered as a manual link target. */
data class LinkEntryRow(
    val entryId: Long,
    val groupId: Long,
    val codeRaw: String?,
    val description: String?,
    val companyRaw: String?,
    val projectRaw: String?,
    val rootCodeRaw: String?
)

/** Full detail for one stored link, shown by "View match details". */
data class LinkDetailRow(
    val linkId: Long,
    val documentId: Long,
    val groupId: Long?,
    val entryId: Long?,
    val linkType: String,
    val matchMethod: String,
    val confidence: Int,
    val evidence: String?,
    val isManual: Boolean,
    val isLocked: Boolean,
    val projectRaw: String?,
    val rootCodeRaw: String?,
    val entryCodeRaw: String?,
    val entryDescription: String?,
    val entryCompanyRaw: String?
)

@Dao
interface FollowUpDao {

    // Parameter is named `record`, not `import`: Room generates Java and
    // `import` is a reserved word there.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImport(record: FollowUpImportEntity): Long

    @Update
    suspend fun updateImport(record: FollowUpImportEntity)

    @Query("SELECT * FROM followup_import WHERE isCurrent = 1 ORDER BY importDate DESC LIMIT 1")
    suspend fun currentImport(): FollowUpImportEntity?

    @Query("SELECT * FROM followup_import ORDER BY importDate DESC")
    fun importsFlow(): Flow<List<FollowUpImportEntity>>

    @Query("UPDATE followup_import SET isCurrent = 0 WHERE isCurrent = 1")
    suspend fun clearCurrentImport()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertProject(project: ProjectEntity): Long

    @Query("SELECT id FROM project WHERE normalizedName = :normalized LIMIT 1")
    suspend fun projectIdByNormalized(normalized: String): Long?

    @Query("SELECT * FROM project WHERE normalizedName = :normalized LIMIT 1")
    suspend fun projectByNormalized(normalized: String): ProjectEntity?

    @Query("UPDATE project SET canonicalName = :canonical WHERE id = :id")
    suspend fun renameProject(id: Long, canonical: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGroup(group: FollowUpGroupEntity): Long

    @Update
    suspend fun updateGroup(group: FollowUpGroupEntity)

    @Query("UPDATE followup_group SET isPresentInCurrentImport = 0 WHERE sourceImportId != :importId")
    suspend fun markAllAbsent(importId: Long)

    /**
     * Marks an existing group as present in the current workbook and refreshes
     * only the workbook-derived columns.
     *
     * `userTags`, `isFavorite` and `manualProjectOverride` are intentionally not
     * in the SET list: those are the user's own data and must survive a
     * workbook replacement.
     */
    @Query(
        """
        UPDATE followup_group SET
            isPresentInCurrentImport = 1,
            sourceImportId = :sourceImportId,
            originalExcelRow = :originalExcelRow,
            number = :number,
            projectId = :projectId,
            projectRaw = :projectRaw,
            area = :area,
            relatedTo = :relatedTo,
            rootCodeRaw = :rootCodeRaw,
            rootCodeNormalized = :rootCodeNormalized,
            prDate = :prDate,
            notes = :notes,
            updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun reassertGroup(
        id: Long,
        sourceImportId: Long,
        originalExcelRow: Int,
        number: String?,
        projectId: Long?,
        projectRaw: String?,
        area: String?,
        relatedTo: String?,
        rootCodeRaw: String?,
        rootCodeNormalized: String?,
        prDate: String?,
        notes: String?,
        updatedAt: Long
    )

    @Query("SELECT * FROM followup_group WHERE stableKey = :key LIMIT 1")
    suspend fun groupByStableKey(key: String): FollowUpGroupEntity?

    @Query("SELECT * FROM followup_group WHERE id = :id")
    fun groupById(id: Long): Flow<FollowUpGroupEntity?>

    @Query("SELECT * FROM followup_group WHERE id = :id")
    suspend fun groupOnce(id: Long): FollowUpGroupEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntry(entry: FollowUpEntryEntity): Long

    @Query("SELECT * FROM followup_entry WHERE sourceSignature = :sig LIMIT 1")
    suspend fun entryBySignature(sig: String): FollowUpEntryEntity?

    @Query("SELECT * FROM followup_entry WHERE id = :id")
    suspend fun entryOnce(id: Long): FollowUpEntryEntity?

    @Query("UPDATE followup_entry SET groupId = :newGroupId WHERE groupId = :oldGroupId")
    suspend fun reassignEntries(oldGroupId: Long, newGroupId: Long)

    @Query("DELETE FROM followup_entry WHERE id = :id")
    suspend fun deleteEntry(id: Long)

    /**
     * Removes entries of a re-imported group whose signature the new workbook
     * did not re-assert.
     *
     * An entry that still carries a link is never removed: a manual link is the
     * user's own decision and must not be collateral damage of a re-import.
     */
    @Query(
        """
        DELETE FROM followup_entry
        WHERE groupId = :groupId
          AND sourceSignature NOT IN (:signatures)
          AND id NOT IN (
              SELECT entryId FROM document_link WHERE entryId IS NOT NULL
          )
        """
    )
    suspend fun dropUnlinkedEntriesNotIn(groupId: Long, signatures: List<String>)

    @Query(
        """
        SELECT g.id AS id, g.stableKey AS stableKey, g.number AS number,
               g.projectRaw AS projectRaw, g.area AS area, g.relatedTo AS relatedTo,
               g.rootCodeRaw AS rootCodeRaw, g.rootCodeNormalized AS rootCodeNormalized,
               g.prDate AS prDate, g.notes AS notes, g.userTags AS userTags,
               g.manualProjectOverride AS manualProjectOverride,
               g.isFavorite AS isFavorite,
               g.isPresentInCurrentImport AS isPresentInCurrentImport,
               (SELECT COUNT(*) FROM followup_entry e WHERE e.groupId = g.id) AS entryCount,
               (SELECT COUNT(DISTINCT l.documentId) FROM document_link l
                  WHERE l.groupId = g.id OR l.entryId IN
                    (SELECT id FROM followup_entry WHERE groupId = g.id)) AS documentCount
        FROM followup_group g
        WHERE g.isPresentInCurrentImport = 1
          AND (:projectId IS NULL OR g.projectId = :projectId)
          AND (:query = '' OR
               g.rootCodeNormalized LIKE '%' || :query || '%' OR
               g.projectRaw LIKE '%' || :query || '%' OR
               g.area LIKE '%' || :query || '%' OR
               g.relatedTo LIKE '%' || :query || '%' OR
               g.notes LIKE '%' || :query || '%' OR
               EXISTS (SELECT 1 FROM followup_entry e2 WHERE e2.groupId = g.id AND
                 (e2.codeNormalized LIKE '%' || :query || '%' OR
                  e2.description LIKE '%' || :query || '%' OR
                  e2.companyRaw LIKE '%' || :query || '%' OR
                  e2.prPathRaw LIKE '%' || :query || '%' OR
                  e2.orderPathRaw LIKE '%' || :query || '%')))
        ORDER BY g.originalExcelRow ASC
        """
    )
    fun groupsFlow(
        projectId: Long?,
        query: String
    ): Flow<List<GroupListRow>>

    @Query(
        """
        SELECT e.id AS id, e.groupId AS groupId, e.sourceExcelRow AS sourceExcelRow,
               e.codeRaw AS codeRaw, e.codeNormalized AS codeNormalized,
               e.prPathRaw AS prPathRaw, e.orderPathRaw AS orderPathRaw,
               e.companyRaw AS companyRaw,
               c.canonicalName AS contractorName,
               mc.canonicalName AS manualContractorName,
               e.description AS description, e.prDate AS prDate,
               e.orderDate AS orderDate, e.prWithEngineer AS prWithEngineer,
               e.notes AS notes, e.financialReceivedDate AS financialReceivedDate,
               e.finalApprovalDate AS finalApprovalDate, e.checkValue AS checkValue,
               e.oldCompanyRaw AS oldCompanyRaw, e.entryVariant AS entryVariant
        FROM followup_entry e
        LEFT JOIN contractor c ON c.id = e.canonicalContractorId
        LEFT JOIN contractor mc ON mc.id = e.manualContractorId
        WHERE e.groupId = :groupId
        ORDER BY e.sourceExcelRow ASC
        """
    )
    fun entriesFlow(groupId: Long): Flow<List<EntryRow>>

    @Query("SELECT canonicalName FROM contractor WHERE id = :id")
    suspend fun contractorName(id: Long): String?

    @Query("SELECT aliasNormalized FROM contractor_alias WHERE origin = 'MANUAL'")
    suspend fun manualAliasKeys(): List<String>

    @Query("UPDATE followup_entry SET manualContractorId = :contractorId WHERE id = :id")
    suspend fun setManualContractor(id: Long, contractorId: Long?)

    /** Groups that belong to an import other than the current one. */
    @Query("SELECT id FROM followup_group WHERE sourceImportId != :keepImportId")
    suspend fun supersededGroupIds(keepImportId: Long): List<Long>

    @Query("SELECT * FROM followup_entry WHERE groupId IN (:groupIds)")
    suspend fun entriesInGroups(groupIds: List<Long>): List<FollowUpEntryEntity>

    /** Entry ids in these groups that a document link still points at. */
    @Query(
        """
        SELECT DISTINCT entryId FROM document_link
        WHERE entryId IS NOT NULL
          AND entryId IN (SELECT id FROM followup_entry WHERE groupId IN (:groupIds))
        """
    )
    suspend fun linkedEntryIdsInGroups(groupIds: List<Long>): List<Long>

    /** Groups in this set that still carry a document link. */
    @Query(
        """
        SELECT DISTINCT groupId FROM document_link
        WHERE groupId IS NOT NULL AND groupId IN (:groupIds)
        """
    )
    suspend fun groupIdsWithLinkedEntries(groupIds: List<Long>): List<Long>

    @Query("DELETE FROM followup_entry WHERE id IN (:ids)")
    suspend fun deleteEntries(ids: List<Long>)

    @Query("DELETE FROM followup_group WHERE id IN (:ids)")
    suspend fun deleteGroups(ids: List<Long>)

    @Query("SELECT * FROM followup_entry WHERE manualContractorId IS NOT NULL")
    suspend fun entriesWithManualContractor(): List<FollowUpEntryEntity>

    /** Entries whose match key is still empty, i.e. rows awaiting a backfill. */
    @Query(
        """
        SELECT id, codeRaw, codeNormalized FROM followup_entry
        WHERE matchCodeKey = ''
        """
    )
    suspend fun entriesNeedingMatchKey(): List<EntryKeyRow>

    @Query("UPDATE followup_entry SET matchCodeKey = :key WHERE id = :id")
    suspend fun setMatchCodeKey(id: Long, key: String)

    /** Entries whose contractor cell holds exactly this raw text. */
    @Query("SELECT * FROM followup_entry WHERE companyRaw = :raw")
    suspend fun entriesWithRawCompany(raw: String): List<FollowUpEntryEntity>

    /** Highest-usage unresolved contractor values, for the review queue. */
    @Query(
        """
        SELECT companyRaw AS companyRaw, COUNT(*) AS usageCount
        FROM followup_entry
        WHERE canonicalContractorId IS NULL AND manualContractorId IS NULL
          AND companyRaw IS NOT NULL AND TRIM(companyRaw) <> ''
        GROUP BY companyRaw
        ORDER BY usageCount DESC
        LIMIT :limit
        """
    )
    fun unresolvedContractorsByUsageFlow(limit: Int): Flow<List<UnresolvedContractorRow>>

    @Query("UPDATE followup_group SET userTags = :tags, updatedAt = :now WHERE id = :id")
    suspend fun setGroupTags(id: Long, tags: String, now: Long)

    @Query("UPDATE followup_group SET isFavorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)
    // ------------------------------------------------------------ matching

    /**
     * Entries whose pre-computed match key equals [key].
     *
     * Backed by the `matchCodeKey` index, so this is a lookup rather than a
     * scan. Callers that need the looser (spacing-free) comparison pass the
     * already-folded key.
     */
    @Query(
        """
        SELECT e.id AS entryId, e.groupId AS groupId, e.codeRaw AS codeRaw,
               e.matchCodeKey AS matchCodeKey, e.description AS description,
               c.normalizedName AS contractorNormalized
        FROM followup_entry e
        LEFT JOIN contractor c ON c.id = e.canonicalContractorId
        WHERE e.matchCodeKey = :key
        """
    )
    suspend fun entriesByMatchKey(key: String): List<EntryMatchRow>

    /** Same, but with all spacing removed, for the looser comparison. */
    @Query(
        """
        SELECT e.id AS entryId, e.groupId AS groupId, e.codeRaw AS codeRaw,
               e.matchCodeKey AS matchCodeKey, e.description AS description,
               c.normalizedName AS contractorNormalized
        FROM followup_entry e
        LEFT JOIN contractor c ON c.id = e.canonicalContractorId
        WHERE REPLACE(e.matchCodeKey, ' ', '') = :key
        """
    )
    suspend fun entriesByLooseMatchKey(key: String): List<EntryMatchRow>

    /** Group headers for a set of ids, used to decorate entry candidates. */
    @Query(
        """
        SELECT id AS groupId, projectRaw AS projectRaw, rootCodeRaw AS rootCodeRaw,
               rootCodeNormalized AS rootCodeNormalized
        FROM followup_group WHERE id IN (:groupIds)
        """
    )
    suspend fun groupsByIds(groupIds: List<Long>): List<GroupMatchRow>

    /** Every locked manual link, counted during a run. */
    @Query("SELECT COUNT(*) FROM document_link WHERE isManual = 1 AND isLocked = 1")
    suspend fun manualLinkCount(): Int

    /** Groups whose root code key equals [key], for group-level candidates. */
    @Query(
        """
        SELECT id AS groupId, projectRaw AS projectRaw, rootCodeRaw AS rootCodeRaw,
               rootCodeNormalized AS rootCodeNormalized
        FROM followup_group
        WHERE rootCodeNormalized IS NOT NULL
        """
    )
    suspend fun allGroupsWithRootCode(): List<GroupMatchRow>

    /** Every entry, used once to build the in-memory index for a run. */
    @Query(
        """
        SELECT e.id AS entryId, e.groupId AS groupId, e.codeRaw AS codeRaw,
               e.matchCodeKey AS matchCodeKey, e.description AS description,
               c.normalizedName AS contractorNormalized
        FROM followup_entry e
        LEFT JOIN contractor c ON c.id = e.canonicalContractorId
        """
    )
    suspend fun allEntryMatchRows(): List<EntryMatchRow>

    /** Historical file names referenced by the workbook, for exact-name matching. */
    @Query(
        """
        SELECT prPathRaw AS path FROM followup_entry WHERE prPathRaw IS NOT NULL AND TRIM(prPathRaw) <> ''
        UNION ALL
        SELECT orderPathRaw FROM followup_entry WHERE orderPathRaw IS NOT NULL AND TRIM(orderPathRaw) <> ''
        """
    )
    suspend fun allHistoricalPaths(): List<String>

    /** Historical file names paired with their entry, for exact-name matching. */
    @Query(
        """
        SELECT id AS entryId, groupId AS groupId, prPathRaw AS path
        FROM followup_entry WHERE prPathRaw IS NOT NULL AND TRIM(prPathRaw) <> ''
        """
    )
    suspend fun historicalPrPaths(): List<HistoricalPathRow>

    @Query(
        """
        SELECT id AS entryId, groupId AS groupId, orderPathRaw AS path
        FROM followup_entry WHERE orderPathRaw IS NOT NULL AND TRIM(orderPathRaw) <> ''
        """
    )
    suspend fun historicalOrderPaths(): List<HistoricalPathRow>

    /** Documents that are not missing, for a matching run. */
    @Query("SELECT * FROM document WHERE isMissing = 0")
    suspend fun allActiveDocuments(): List<DocumentEntity>

    // ---------------------------------------------------------- pagination

    /**
     * Paging source for the Follow-up list.
     *
     * A `PagingSource` rather than a fixed first page: the query runs against
     * the whole table, so scrolling reaches every group and a search term
     * matches rows far beyond any page boundary. ORDER BY is on the immutable
     * primary key, which is also the paging key.
     */
    @Query(
        """
        SELECT g.id AS id, g.stableKey AS stableKey, g.number AS number,
               g.projectRaw AS projectRaw, g.area AS area, g.relatedTo AS relatedTo,
               g.rootCodeRaw AS rootCodeRaw, g.rootCodeNormalized AS rootCodeNormalized,
               g.prDate AS prDate, g.notes AS notes, g.userTags AS userTags,
               g.manualProjectOverride AS manualProjectOverride,
               g.isFavorite AS isFavorite,
               g.isPresentInCurrentImport AS isPresentInCurrentImport,
               (SELECT COUNT(*) FROM followup_entry e WHERE e.groupId = g.id) AS entryCount,
               (SELECT COUNT(DISTINCT l.documentId) FROM document_link l
                  WHERE l.groupId = g.id OR l.entryId IN
                    (SELECT id FROM followup_entry WHERE groupId = g.id)) AS documentCount
        FROM followup_group g
        WHERE g.isPresentInCurrentImport = 1
          AND (:projectId IS NULL OR g.projectId = :projectId)
          AND (:query = '' OR
               g.rootCodeNormalized LIKE '%' || :query || '%' OR
               g.projectRaw LIKE '%' || :query || '%' OR
               g.area LIKE '%' || :query || '%' OR
               g.relatedTo LIKE '%' || :query || '%' OR
               g.notes LIKE '%' || :query || '%' OR
               EXISTS (SELECT 1 FROM followup_entry e2 WHERE e2.groupId = g.id AND
                 (e2.codeNormalized LIKE '%' || :query || '%' OR
                  e2.matchCodeKey LIKE '%' || :query || '%' OR
                  e2.description LIKE '%' || :query || '%' OR
                  e2.companyRaw LIKE '%' || :query || '%' OR
                  e2.prPathRaw LIKE '%' || :query || '%' OR
                  e2.orderPathRaw LIKE '%' || :query || '%')))
        ORDER BY g.id ASC
        """
    )
    fun groupsPagingSource(projectId: Long?, query: String): PagingSource<Int, GroupListRow>

    /**
     * Paging source for the document library.
     *
     * Keyed on the document id so a rescan that changes a file's metadata does
     * not reshuffle the list under the reader.
     */
    @Query(
        """
        SELECT d.id AS id, d.displayName AS displayName, d.relativePath AS relativePath,
               d.documentUri AS documentUri, d.mimeType AS mimeType, d.size AS size,
               d.lastModified AS lastModified, d.extension AS extension,
               d.isMissing AS isMissing,
               (SELECT COUNT(*) FROM document_link l WHERE l.documentId = d.id) AS linkCount
        FROM document d
        WHERE d.isMissing = 0
          AND (:query = '' OR d.normalizedName LIKE '%' || :query || '%'
               OR d.relativePath LIKE '%' || :query || '%')
        ORDER BY d.id ASC
        """
    )
    fun documentsPagingSource(query: String): PagingSource<Int, DocumentListRow>

    /**
     * Paging source for the entries of one group, used by AI Pack selection.
     */
    @Query(
        """
        SELECT e.id AS id, e.groupId AS groupId, e.sourceExcelRow AS sourceExcelRow,
               e.codeRaw AS codeRaw, e.codeNormalized AS codeNormalized,
               e.prPathRaw AS prPathRaw, e.orderPathRaw AS orderPathRaw,
               e.companyRaw AS companyRaw,
               c.canonicalName AS contractorName,
               mc.canonicalName AS manualContractorName,
               e.description AS description, e.prDate AS prDate,
               e.orderDate AS orderDate, e.prWithEngineer AS prWithEngineer,
               e.notes AS notes, e.financialReceivedDate AS financialReceivedDate,
               e.finalApprovalDate AS finalApprovalDate, e.checkValue AS checkValue,
               e.oldCompanyRaw AS oldCompanyRaw, e.entryVariant AS entryVariant
        FROM followup_entry e
        LEFT JOIN contractor c ON c.id = e.canonicalContractorId
        LEFT JOIN contractor mc ON mc.id = e.manualContractorId
        WHERE e.groupId = :groupId
          AND (:query = '' OR e.codeRaw LIKE '%' || :query || '%'
               OR e.description LIKE '%' || :query || '%'
               OR e.companyRaw LIKE '%' || :query || '%')
        ORDER BY e.id ASC
        """
    )
    fun entriesPagingSource(groupId: Long, query: String): PagingSource<Int, EntryRow>

    /** Paging source for the contractor list. */
    @Query(
        """
        SELECT c.id AS id, c.canonicalName AS canonicalName, c.normalizedName AS normalizedName,
               (SELECT COUNT(*) FROM followup_entry e WHERE e.canonicalContractorId = c.id
                  OR e.manualContractorId = c.id) AS entryCount,
               (SELECT COUNT(*) FROM followup_entry e WHERE e.canonicalContractorId IS NULL
                  AND e.manualContractorId IS NULL AND e.companyRaw IS NOT NULL
                  AND TRIM(e.companyRaw) <> '') AS unresolvedCount
        FROM contractor c
        WHERE (:query = '' OR c.canonicalName LIKE '%' || :query || '%'
               OR c.normalizedName LIKE '%' || :query || '%')
        ORDER BY c.id ASC
        """
    )
    fun contractorsPagingSource(query: String): PagingSource<Int, ContractorWithCount>

    /** Paging source for the "Link to…" entry picker. */
    @Query(
        """
        SELECT e.id AS entryId, e.groupId AS groupId, e.codeRaw AS codeRaw,
               e.description AS description, e.companyRaw AS companyRaw,
               g.projectRaw AS projectRaw, g.rootCodeRaw AS rootCodeRaw
        FROM followup_entry e
        JOIN followup_group g ON g.id = e.groupId
        WHERE g.isPresentInCurrentImport = 1
          AND (:query = '' OR e.codeRaw LIKE '%' || :query || '%'
               OR e.description LIKE '%' || :query || '%'
               OR e.companyRaw LIKE '%' || :query || '%'
               OR g.projectRaw LIKE '%' || :query || '%')
        ORDER BY e.id ASC
        """
    )
    fun linkEntriesPagingSource(query: String): PagingSource<Int, LinkEntryRow>

    /** Paging source for the "Link to…" group picker. */
    @Query(
        """
        SELECT g.id AS groupId, g.projectRaw AS projectRaw, g.rootCodeRaw AS rootCodeRaw,
               g.area AS area, g.relatedTo AS relatedTo,
               (SELECT COUNT(*) FROM followup_entry e WHERE e.groupId = g.id) AS entryCount
        FROM followup_group g
        WHERE g.isPresentInCurrentImport = 1
          AND (:query = '' OR g.projectRaw LIKE '%' || :query || '%'
               OR g.rootCodeRaw LIKE '%' || :query || '%'
               OR g.area LIKE '%' || :query || '%'
               OR g.relatedTo LIKE '%' || :query || '%')
        ORDER BY g.id ASC
        """
    )
    fun linkGroupsPagingSource(query: String): PagingSource<Int, LinkTargetRow>

    /** Paging source for the projects list. */
    @Query(
        """
        SELECT p.id AS id, p.canonicalName AS canonicalName, p.normalizedName AS normalizedName,
               (SELECT COUNT(*) FROM followup_group g
                  WHERE g.projectId = p.id AND g.isPresentInCurrentImport = 1) AS groupCount,
               (SELECT COUNT(DISTINCT l.documentId) FROM document_link l
                  WHERE l.groupId IN (SELECT g.id FROM followup_group g WHERE g.projectId = p.id)
                    OR l.entryId IN (SELECT e.id FROM followup_entry e
                        JOIN followup_group g2 ON g2.id = e.groupId WHERE g2.projectId = p.id)
               ) AS documentCount
        FROM project p
        WHERE (:query = '' OR p.canonicalName LIKE '%' || :query || '%'
               OR p.normalizedName LIKE '%' || :query || '%')
        ORDER BY p.id ASC
        """
    )
    fun projectsPagingSource(query: String): PagingSource<Int, ProjectWithCount>

    /** One page of groups, read synchronously for pack building. */
    @Query(
        """
        SELECT g.id AS id, g.stableKey AS stableKey, g.number AS number,
               g.projectRaw AS projectRaw, g.area AS area, g.relatedTo AS relatedTo,
               g.rootCodeRaw AS rootCodeRaw, g.rootCodeNormalized AS rootCodeNormalized,
               g.prDate AS prDate, g.notes AS notes, g.userTags AS userTags,
               g.manualProjectOverride AS manualProjectOverride,
               g.isFavorite AS isFavorite,
               g.isPresentInCurrentImport AS isPresentInCurrentImport,
               (SELECT COUNT(*) FROM followup_entry e WHERE e.groupId = g.id) AS entryCount,
               (SELECT COUNT(DISTINCT l.documentId) FROM document_link l
                  WHERE l.groupId = g.id OR l.entryId IN
                    (SELECT id FROM followup_entry WHERE groupId = g.id)) AS documentCount
        FROM followup_group g
        WHERE g.isPresentInCurrentImport = 1
          AND (:projectId IS NULL OR g.projectId = :projectId)
        ORDER BY g.id ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun groupsPageOnce(
        projectId: Long?,
        limit: Int,
        offset: Int
    ): List<GroupListRow>

    /** Group list rows for a set of ids, used when scoping an AI Pack. */
    @Query(
        """
        SELECT g.id AS id, g.stableKey AS stableKey, g.number AS number,
               g.projectRaw AS projectRaw, g.area AS area, g.relatedTo AS relatedTo,
               g.rootCodeRaw AS rootCodeRaw, g.rootCodeNormalized AS rootCodeNormalized,
               g.prDate AS prDate, g.notes AS notes, g.userTags AS userTags,
               g.manualProjectOverride AS manualProjectOverride,
               g.isFavorite AS isFavorite,
               g.isPresentInCurrentImport AS isPresentInCurrentImport,
               (SELECT COUNT(*) FROM followup_entry e WHERE e.groupId = g.id) AS entryCount,
               (SELECT COUNT(DISTINCT l.documentId) FROM document_link l
                  WHERE l.groupId = g.id OR l.entryId IN
                    (SELECT id FROM followup_entry WHERE groupId = g.id)) AS documentCount
        FROM followup_group g
        WHERE g.id IN (:ids)
        ORDER BY g.id ASC
        """
    )
    suspend fun groupListByIds(ids: List<Long>): List<GroupListRow>

    /**
     * One page of groups.
     *
     * Retained for callers that genuinely want a fixed window. The scrolling
     * lists use the PagingSource above.
     */
    @Query(
        """
        SELECT g.id AS id, g.stableKey AS stableKey, g.number AS number,
               g.projectRaw AS projectRaw, g.area AS area, g.relatedTo AS relatedTo,
               g.rootCodeRaw AS rootCodeRaw, g.rootCodeNormalized AS rootCodeNormalized,
               g.prDate AS prDate, g.notes AS notes, g.userTags AS userTags,
               g.manualProjectOverride AS manualProjectOverride,
               g.isFavorite AS isFavorite,
               g.isPresentInCurrentImport AS isPresentInCurrentImport,
               (SELECT COUNT(*) FROM followup_entry e WHERE e.groupId = g.id) AS entryCount,
               (SELECT COUNT(DISTINCT l.documentId) FROM document_link l
                  WHERE l.groupId = g.id OR l.entryId IN
                    (SELECT id FROM followup_entry WHERE groupId = g.id)) AS documentCount
        FROM followup_group g
        WHERE g.isPresentInCurrentImport = 1
          AND (:projectId IS NULL OR g.projectId = :projectId)
          AND (:query = '' OR
               g.rootCodeNormalized LIKE '%' || :query || '%' OR
               g.projectRaw LIKE '%' || :query || '%' OR
               g.area LIKE '%' || :query || '%' OR
               g.relatedTo LIKE '%' || :query || '%' OR
               g.notes LIKE '%' || :query || '%' OR
               EXISTS (SELECT 1 FROM followup_entry e2 WHERE e2.groupId = g.id AND
                 (e2.codeNormalized LIKE '%' || :query || '%' OR
                  e2.matchCodeKey LIKE '%' || :query || '%' OR
                  e2.description LIKE '%' || :query || '%' OR
                  e2.companyRaw LIKE '%' || :query || '%' OR
                  e2.prPathRaw LIKE '%' || :query || '%' OR
                  e2.orderPathRaw LIKE '%' || :query || '%')))
        ORDER BY g.originalExcelRow ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun groupsPage(
        projectId: Long?,
        query: String,
        limit: Int,
        offset: Int
    ): Flow<List<GroupListRow>>

    /** Total number of rows a paged query would return. */
    @Query(
        """
        SELECT COUNT(*) FROM followup_group g
        WHERE g.isPresentInCurrentImport = 1
          AND (:projectId IS NULL OR g.projectId = :projectId)
          AND (:query = '' OR
               g.rootCodeNormalized LIKE '%' || :query || '%' OR
               g.projectRaw LIKE '%' || :query || '%' OR
               g.area LIKE '%' || :query || '%' OR
               g.relatedTo LIKE '%' || :query || '%' OR
               g.notes LIKE '%' || :query || '%' OR
               EXISTS (SELECT 1 FROM followup_entry e2 WHERE e2.groupId = g.id AND
                 (e2.codeNormalized LIKE '%' || :query || '%' OR
                  e2.matchCodeKey LIKE '%' || :query || '%' OR
                  e2.description LIKE '%' || :query || '%' OR
                  e2.companyRaw LIKE '%' || :query || '%')))
        """
    )
    fun groupCountFlow(projectId: Long?, query: String): Flow<Int>

}

@Dao
interface ContractorDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertContractor(contractor: ContractorEntity): Long

    @Query("SELECT id FROM contractor WHERE normalizedName = :normalized LIMIT 1")
    suspend fun idByNormalized(normalized: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlias(alias: ContractorAliasEntity): Long

    @Query("SELECT * FROM contractor_alias WHERE aliasNormalized = :normalized LIMIT 1")
    suspend fun aliasByNormalized(normalized: String): ContractorAliasEntity?

    @Query("SELECT * FROM contractor_alias")
    fun allAliasesFlow(): Flow<List<ContractorAliasEntity>>

    @Query("SELECT * FROM contractor_alias")
    suspend fun allAliases(): List<ContractorAliasEntity>

    @Query("SELECT * FROM contractor_alias WHERE origin = 'MANUAL'")
    suspend fun manualAliases(): List<ContractorAliasEntity>

    @Query("SELECT canonicalName FROM contractor WHERE id = :id")
    suspend fun contractorName(id: Long): String?

    @Query("SELECT * FROM contractor ORDER BY canonicalName COLLATE NOCASE ASC")
    suspend fun allContractors(): List<ContractorEntity>

    @Query(
        """
        SELECT c.id AS id, c.canonicalName AS canonicalName, c.normalizedName AS normalizedName,
               (SELECT COUNT(*) FROM followup_entry e WHERE e.canonicalContractorId = c.id
                  OR e.manualContractorId = c.id) AS entryCount,
               (SELECT COUNT(*) FROM followup_entry e WHERE e.canonicalContractorId IS NULL
                  AND e.manualContractorId IS NULL AND e.companyRaw IS NOT NULL
                  AND TRIM(e.companyRaw) <> '') AS unresolvedCount
        FROM contractor c
        ORDER BY c.canonicalName ASC
        """
    )
    fun contractorsFlow(): Flow<List<ContractorWithCount>>

    @Query(
        """
        SELECT DISTINCT companyRaw FROM followup_entry
        WHERE canonicalContractorId IS NULL AND manualContractorId IS NULL
          AND companyRaw IS NOT NULL AND TRIM(companyRaw) <> ''
        ORDER BY companyRaw ASC
        """
    )
    fun unresolvedRawNamesFlow(): Flow<List<String>>

    @Query(
        """
        SELECT DISTINCT companyRaw FROM followup_entry
        WHERE canonicalContractorId IS NULL AND manualContractorId IS NULL
          AND companyRaw IS NOT NULL AND TRIM(companyRaw) <> ''
        """
    )
    suspend fun unresolvedRawNames(): List<String>
}

@Dao
interface DocumentDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDocuments(docs: List<DocumentEntity>): List<Long>

    /**
     * Clears the index for a tree before a rescan.
     *
     * A document row is only removed once the automatic links that pointed at
     * it are gone AND no manual link survives. A manual link is the user's own
     * decision, so rescanning must never take it away, even when the file has
     * temporarily disappeared from the tree.
     */
    @Transaction
    suspend fun deleteByTree(treeUri: String) {
        deleteAutomaticLinksInTree(treeUri)
        // Documents still referenced by a manual link keep their row so the link
        // stays valid; everything else is dropped and re-indexed.
        deleteOrphansInTree(treeUri)
    }

    @Query(
        """
        DELETE FROM document_link
        WHERE isManual = 0 AND isLocked = 0
          AND documentId IN (
              SELECT id FROM document WHERE treeUri = :treeUri
          )
        """
    )
    suspend fun deleteAutomaticLinksInTree(treeUri: String)

    @Query(
        """
        DELETE FROM document
        WHERE treeUri = :treeUri
          AND id NOT IN (
              SELECT documentId FROM document_link WHERE documentId IS NOT NULL
          )
        """
    )
    suspend fun deleteOrphansInTree(treeUri: String)

    @Query("SELECT * FROM document WHERE treeUri = :treeUri")
    suspend fun documentsInTree(treeUri: String): List<DocumentEntity>

    @Query("DELETE FROM document WHERE id = :id")
    suspend fun deleteDocument(id: Long)

    @Query("SELECT COUNT(*) FROM document WHERE treeUri = :treeUri")
    suspend fun countByTree(treeUri: String): Int

    @Query("UPDATE document SET isMissing = 1 WHERE treeUri = :treeUri")
    suspend fun markAllMissing(treeUri: String)

    @Query("UPDATE document SET isMissing = 0 WHERE documentUri = :uri")
    suspend fun clearMissing(uri: String)

    @Query("UPDATE document SET lazySha256 = :sha WHERE id = :id")
    suspend fun setSha(id: Long, sha: String)

    @Query("SELECT id FROM document WHERE documentUri = :uri LIMIT 1")
    suspend fun documentIdForUri(uri: String): Long?

    @Query(
        """
        UPDATE document SET size = :size, lastModified = :lastModified,
                            indexedAt = :indexedAt, isMissing = 0
        WHERE id = :id
        """
    )
    suspend fun refreshDocument(id: Long, size: Long, lastModified: Long, indexedAt: Long)

    @Query("SELECT * FROM document WHERE documentUri = :uri LIMIT 1")
    suspend fun byUri(uri: String): DocumentEntity?

    /** Documents whose match keys are still empty, awaiting a backfill. */
    @Query("SELECT id, displayName FROM document WHERE matchFileKey = ''")
    suspend fun documentsNeedingMatchKey(): List<DocumentKeyRow>

    @Query("UPDATE document SET matchFileKey = :fileKey, matchStemKey = :stemKey WHERE id = :id")
    suspend fun setMatchKeys(id: Long, fileKey: String, stemKey: String)

    @Query("SELECT * FROM document WHERE id = :id")
    suspend fun byId(id: Long): DocumentEntity?

    @Query(
        """
        SELECT d.id AS id, d.displayName AS displayName, d.relativePath AS relativePath,
               d.documentUri AS documentUri, d.mimeType AS mimeType, d.size AS size,
               d.lastModified AS lastModified, d.extension AS extension,
               d.isMissing AS isMissing,
               (SELECT COUNT(*) FROM document_link l WHERE l.documentId = d.id) AS linkCount
        FROM document d
        WHERE d.isMissing = 0
          AND (:query = '' OR d.normalizedName LIKE '%' || :query || '%'
               OR d.relativePath LIKE '%' || :query || '%')
        ORDER BY d.relativePath ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun documentsFlow(query: String, limit: Int, offset: Int): Flow<List<DocumentListRow>>

    @Query("SELECT COUNT(*) FROM document WHERE isMissing = 0")
    fun documentCountFlow(): Flow<Int>

    @Query(
        """
        SELECT d.id AS id, d.displayName AS displayName, d.relativePath AS relativePath,
               d.documentUri AS documentUri, d.mimeType AS mimeType, d.size AS size,
               d.extension AS extension
        FROM document d
        WHERE d.isMissing = 0
          AND NOT EXISTS (
            SELECT 1 FROM document_link l
            WHERE l.documentId = d.id AND (l.isManual = 1 OR l.confidence >= 80)
          )
        ORDER BY d.relativePath ASC
        LIMIT :limit
        """
    )
    fun unlinkedFlow(limit: Int): Flow<List<UnlinkedDocumentRow>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLink(link: DocumentLinkEntity): Long

    @Update
    suspend fun updateLink(link: DocumentLinkEntity)

    @Query("SELECT * FROM document_link WHERE id = :id")
    suspend fun linkById(id: Long): DocumentLinkEntity?

    @Query("DELETE FROM document_link WHERE id = :id")
    suspend fun deleteLink(id: Long)

    @Query("SELECT COUNT(*) FROM project")
    fun projectCountFlow(): Flow<Int>

    @Query(
        """
        SELECT COUNT(DISTINCT companyRaw) FROM followup_entry
        WHERE canonicalContractorId IS NULL AND manualContractorId IS NULL
          AND companyRaw IS NOT NULL AND TRIM(companyRaw) <> ''
        """
    )
    fun unresolvedContractorCountFlow(): Flow<Int>

    @Query("SELECT * FROM document_link WHERE documentId = :docId")
    suspend fun linksForDocument(docId: Long): List<DocumentLinkEntity>

    /** Never call this on a manual/locked link — that is the preservation rule. */
    @Query(
        """
        DELETE FROM document_link
        WHERE documentId = :docId AND isManual = 0 AND isLocked = 0
        """
    )
    suspend fun deleteAutomaticLinksFor(docId: Long)

    /**
     * Removes the automatic rows for a document except one accepted link.
     *
     * Used when the user accepts a suggestion: the accepted row survives as a
     * locked manual link while its automatic siblings are cleared.
     */
    @Query(
        """
        DELETE FROM document_link
        WHERE documentId = :docId AND id != :keepId AND isManual = 0 AND isLocked = 0
        """
    )
    suspend fun deleteOtherAutomaticLinksFor(docId: Long, keepId: Long)

    @Query(
        """
        SELECT COUNT(DISTINCT documentId) FROM document_link
        WHERE isManual = 1 OR confidence >= 95
        """
    )
    fun linkedDocumentCountFlow(): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM document_link
        WHERE isManual = 0 AND confidence >= 80 AND confidence < 95
        """
    )
    fun suggestedCountFlow(): Flow<Int>

    @Query(
        """
        SELECT COUNT(DISTINCT d.id) FROM document d
        WHERE d.isMissing = 0 AND NOT EXISTS (
            SELECT 1 FROM document_link l
            WHERE l.documentId = d.id AND (l.isManual = 1 OR l.confidence >= 80)
        )
        """
    )
    fun unlinkedCountFlow(): Flow<Int>

    @Query(
        """
        SELECT doc.id AS documentId, doc.displayName AS displayName,
               doc.relativePath AS relativePath, doc.documentUri AS documentUri,
               doc.mimeType AS mimeType, doc.size AS size, doc.extension AS extension,
               l.linkType AS linkType, l.matchMethod AS matchMethod,
               l.confidence AS confidence, l.evidence AS evidence,
               l.isManual AS isManual, l.entryId AS entryId, e.codeRaw AS entryCodeRaw
        FROM document_link l
        JOIN document doc ON doc.id = l.documentId
        LEFT JOIN followup_entry e ON e.id = l.entryId
        WHERE (l.groupId = :groupId OR l.entryId IN
                 (SELECT id FROM followup_entry WHERE groupId = :groupId))
        ORDER BY l.isManual DESC, l.confidence DESC, doc.relativePath ASC
        """
    )
    fun linkedDocumentsFlow(groupId: Long): Flow<List<LinkedDocumentRow>>

    @Query(
        """
        SELECT p.id AS id, p.canonicalName AS canonicalName, p.normalizedName AS normalizedName,
               (SELECT COUNT(*) FROM followup_group g
                  WHERE g.projectId = p.id AND g.isPresentInCurrentImport = 1) AS groupCount,
               (SELECT COUNT(DISTINCT l.documentId) FROM document_link l
                  WHERE l.groupId IN (SELECT g.id FROM followup_group g WHERE g.projectId = p.id)
                    OR l.entryId IN (SELECT e.id FROM followup_entry e
                        JOIN followup_group g2 ON g2.id = e.groupId WHERE g2.projectId = p.id)
               ) AS documentCount
        FROM project p
        ORDER BY p.canonicalName COLLATE NOCASE ASC
        """
    )
    fun projectsFlow(): Flow<List<ProjectWithCount>>

    @Query(
        """
        SELECT COUNT(*) FROM followup_group WHERE isPresentInCurrentImport = 1
        """
    )
    fun groupCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM followup_entry")
    fun entryCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM contractor")
    fun contractorCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM contractor_alias")
    fun contractorAliasCountFlow(): Flow<Int>

    /** Unresolved raw contractor strings, ordered by how often they occur. */
    @Query(
        """
        SELECT companyRaw AS companyRaw, COUNT(*) AS usageCount
        FROM followup_entry
        WHERE canonicalContractorId IS NULL AND manualContractorId IS NULL
          AND companyRaw IS NOT NULL AND TRIM(companyRaw) <> ''
        GROUP BY companyRaw
        ORDER BY usageCount DESC, companyRaw ASC
        """
    )
    fun allUnresolvedContractorsByUsageFlow(): Flow<List<UnresolvedContractorRow>>

    /**
     * Suggested and unlinked documents with their evidence, for Needs Review.
     */
    @Query(
        """
        SELECT d.id AS id, d.displayName AS displayName, d.relativePath AS relativePath,
               d.documentUri AS documentUri, d.mimeType AS mimeType, d.size AS size,
               d.extension AS extension,
               l.matchMethod AS matchMethod, l.confidence AS confidence,
               l.evidence AS evidence, l.isManual AS isManual,
               g.projectRaw AS projectRaw, e.codeRaw AS codeRaw
        FROM document_link l
        JOIN document d ON d.id = l.documentId
        LEFT JOIN followup_group g ON g.id = l.groupId
        LEFT JOIN followup_entry e ON e.id = l.entryId
        WHERE l.isManual = 0 AND d.isMissing = 0
        ORDER BY l.confidence DESC, d.relativePath ASC
        """
    )
    fun suggestionsFlow(): Flow<List<SuggestionRow>>

    /** Documents with no link at all. */
    @Query(
        """
        SELECT d.id AS id, d.displayName AS displayName, d.relativePath AS relativePath,
               d.documentUri AS documentUri, d.mimeType AS mimeType, d.size AS size,
               d.extension AS extension,
               NULL AS matchMethod, 0 AS confidence, NULL AS evidence, 0 AS isManual,
               NULL AS projectRaw, NULL AS codeRaw
        FROM document d
        WHERE d.isMissing = 0
          AND NOT EXISTS (SELECT 1 FROM document_link l WHERE l.documentId = d.id)
        ORDER BY d.relativePath ASC
        """
    )
    fun unlinkedOnlyFlow(): Flow<List<SuggestionRow>>

    /** Search targets for the manual "Link to…" picker. */
    @Query(
        """
        SELECT g.id AS groupId, g.projectRaw AS projectRaw, g.rootCodeRaw AS rootCodeRaw,
               g.area AS area, g.relatedTo AS relatedTo,
               (SELECT COUNT(*) FROM followup_entry e WHERE e.groupId = g.id) AS entryCount
        FROM followup_group g
        WHERE g.isPresentInCurrentImport = 1
          AND (:query = '' OR g.projectRaw LIKE '%' || :query || '%'
               OR g.rootCodeRaw LIKE '%' || :query || '%'
               OR g.area LIKE '%' || :query || '%'
               OR g.relatedTo LIKE '%' || :query || '%')
        ORDER BY g.originalExcelRow ASC
        LIMIT 80
        """
    )
    fun searchGroupsForLinking(query: String): Flow<List<LinkTargetRow>>

    @Query(
        """
        SELECT e.id AS entryId, e.groupId AS groupId, e.codeRaw AS codeRaw,
               e.description AS description, e.companyRaw AS companyRaw,
               g.projectRaw AS projectRaw, g.rootCodeRaw AS rootCodeRaw
        FROM followup_entry e
        JOIN followup_group g ON g.id = e.groupId
        WHERE (:query = '' OR e.codeRaw LIKE '%' || :query || '%'
               OR e.description LIKE '%' || :query || '%'
               OR e.companyRaw LIKE '%' || :query || '%'
               OR g.projectRaw LIKE '%' || :query || '%')
        ORDER BY e.sourceExcelRow ASC
        LIMIT 120
        """
    )
    fun searchEntriesForLinking(query: String): Flow<List<LinkEntryRow>>

    /** A document's links with full detail, for "View match details". */
    @Query(
        """
        SELECT l.id AS linkId, l.documentId AS documentId, l.groupId AS groupId,
               l.entryId AS entryId, l.linkType AS linkType, l.matchMethod AS matchMethod,
               l.confidence AS confidence, l.evidence AS evidence,
               l.isManual AS isManual, l.isLocked AS isLocked,
               g.projectRaw AS projectRaw, g.rootCodeRaw AS rootCodeRaw,
               e.codeRaw AS entryCodeRaw, e.description AS entryDescription,
               e.companyRaw AS entryCompanyRaw
        FROM document_link l
        LEFT JOIN followup_group g ON g.id = l.groupId
        LEFT JOIN followup_entry e ON e.id = l.entryId
        WHERE l.documentId = :documentId
        ORDER BY l.isManual DESC, l.confidence DESC
        """
    )
    fun linksWithDetail(documentId: Long): Flow<List<LinkDetailRow>>

    /** Entry target rows for a set of ids, used when writing manual links. */
    @Query(
        """
        SELECT e.id AS entryId, e.groupId AS groupId, e.codeRaw AS codeRaw,
               e.description AS description, e.companyRaw AS companyRaw,
               g.projectRaw AS projectRaw, g.rootCodeRaw AS rootCodeRaw
        FROM followup_entry e
        JOIN followup_group g ON g.id = e.groupId
        WHERE e.id = :id
        """
    )
    suspend fun entryTarget(id: Long): LinkEntryRow?

    @Query("DELETE FROM document_link WHERE id = :id")
    suspend fun deleteLinkById(id: Long)

    @Query("SELECT name FROM user_tag ORDER BY name")
    fun tagsFlow(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: UserTagEntity): Long

    @Query("SELECT id FROM user_tag WHERE name = :name LIMIT 1")
    suspend fun tagId(name: String): Long?

    @Query("SELECT * FROM app_setting WHERE `key` = :key")
    suspend fun setting(key: String): AppSettingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSetting(setting: AppSettingEntity)

    @Query("SELECT * FROM app_setting")
    fun allSettingsFlow(): Flow<List<AppSettingEntity>>
}

@Dao
interface FollowUpImportCleanupDao {
    @Query("DELETE FROM followup_entry")
    suspend fun clearEntries()

    @Query("DELETE FROM followup_group")
    suspend fun clearGroups()

    @Query("DELETE FROM document_link WHERE isManual = 0 AND isLocked = 0")
    suspend fun clearAutomaticLinks()
}
