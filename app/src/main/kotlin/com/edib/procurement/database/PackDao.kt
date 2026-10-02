package com.edib.procurement.database

import androidx.room.Dao
import androidx.room.Query

/**
 * Read-only queries the AI Pack builder needs.
 *
 * They live in their own DAO rather than being spread across the write DAOs so
 * that pack building has one clearly read-only surface: it must never modify a
 * source file, and having no insert/update/delete method here makes that
 * structural rather than a matter of discipline.
 */
@Dao
interface PackDao {

    /** Group headers for a set of ids, in a stable order. */
    @Query(
        """
        SELECT id AS groupId, projectRaw AS projectRaw, rootCodeRaw AS rootCodeRaw,
               rootCodeNormalized AS rootCodeNormalized, area AS area, relatedTo AS relatedTo,
               prDate AS prDate, notes AS notes, number AS number,
               originalExcelRow AS originalExcelRow
        FROM followup_group
        WHERE id IN (:ids)
        ORDER BY id ASC
        """
    )
    suspend fun groupsByIds(ids: List<Long>): List<PackGroupRow>

    /** Groups holding at least one entry attributed to a contractor. */
    @Query(
        """
        SELECT DISTINCT groupId FROM followup_entry
        WHERE canonicalContractorId = :contractorId OR manualContractorId = :contractorId
        """
    )
    suspend fun groupIdsForContractor(contractorId: Long): List<Long>

    /**
     * Entry ids attributed to a contractor.
     *
     * A CONTRACTOR pack is pinned to these ids rather than to whole groups: a
     * group often holds entries from several suppliers, and exporting the whole
     * group would drag in a different contractor's work. The groups are still
     * resolved, so each entry keeps its project and group context in the pack,
     * but only this contractor's entries are written.
     */
    @Query(
        """
        SELECT id FROM followup_entry
        WHERE canonicalContractorId = :contractorId OR manualContractorId = :contractorId
        ORDER BY id ASC
        """
    )
    suspend fun entryIdsForContractor(contractorId: Long): List<Long>

    /** Group ids of a project, current import only. */
    @Query(
        """
        SELECT id FROM followup_group
        WHERE projectId = :projectId AND isPresentInCurrentImport = 1
        ORDER BY id ASC
        """
    )
    suspend fun groupIdsOfProject(projectId: Long): List<Long>

    /** Entries of one group. */
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
        ORDER BY e.id ASC
        """
    )
    suspend fun entriesOfGroup(groupId: Long): List<EntryRow>

    /** Entries of several groups, so a project pack is a single query. */
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
        WHERE e.groupId IN (:groupIds)
        ORDER BY e.id ASC
        """
    )
    suspend fun entriesOfGroups(groupIds: List<Long>): List<EntryRow>

    /**
     * Documents linked to any of these groups or entries, one row per document.
     *
     * The DISTINCT is what stops a file that is linked to two selected entries
     * from contributing its content to the pack twice.
     */
    @Query(
        """
        SELECT DISTINCT d.id AS id, d.displayName AS displayName,
               d.relativePath AS relativePath, d.documentUri AS documentUri,
               d.mimeType AS mimeType, d.size AS size, d.extension AS extension,
               d.lazySha256 AS lazySha256
        FROM document d
        JOIN document_link l ON l.documentId = d.id
        WHERE d.isMissing = 0
          AND (l.groupId IN (:groupIds) OR l.entryId IN (:entryIds))
        ORDER BY d.relativePath ASC
        """
    )
    suspend fun linkedDocuments(groupIds: List<Long>, entryIds: List<Long>): List<PackDocumentRow>

    /** Documents chosen by hand, whether or not they carry a link. */
    @Query(
        """
        SELECT id AS id, displayName AS displayName, relativePath AS relativePath,
               documentUri AS documentUri, mimeType AS mimeType, size AS size,
               extension AS extension, lazySha256 AS lazySha256
        FROM document
        WHERE isMissing = 0 AND id IN (:ids)
        ORDER BY relativePath ASC
        """
    )
    suspend fun documentsByIds(ids: List<Long>): List<PackDocumentRow>

    /** How each selected document is linked, for the per-source evidence. */
    @Query(
        """
        SELECT l.documentId AS documentId, l.groupId AS groupId, l.entryId AS entryId,
               l.linkType AS linkType, l.matchMethod AS matchMethod,
               l.confidence AS confidence, l.evidence AS evidence,
               l.isManual AS isManual
        FROM document_link l
        WHERE l.documentId IN (:documentIds)
        ORDER BY l.documentId, l.confidence DESC
        """
    )
    suspend fun linksForDocuments(documentIds: List<Long>): List<PackLinkRow>

    /** Total linked-document count for a set of groups, for the config sheet. */
    @Query(
        """
        SELECT COUNT(DISTINCT d.id)
        FROM document d
        JOIN document_link l ON l.documentId = d.id
        WHERE d.isMissing = 0 AND (l.groupId IN (:groupIds) OR l.entryId IN (:entryIds))
        """
    )
    suspend fun linkedDocumentCount(groupIds: List<Long>, entryIds: List<Long>): Int

    /** Display name of a project, for a pack scope label. */
    @Query("SELECT canonicalName FROM project WHERE id = :id")
    suspend fun projectName(id: Long): String?

    /** Display name of a contractor, for a pack scope label. */
    @Query("SELECT canonicalName FROM contractor WHERE id = :id")
    suspend fun contractorName(id: Long): String?
}
