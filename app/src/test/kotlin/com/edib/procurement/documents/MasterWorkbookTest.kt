package com.edib.procurement.documents

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Master-workbook exclusion.
 *
 * The Follow-up workbook is a data source, not one of the documents it
 * describes. It must not appear in Documents, Unlinked or the matcher's
 * candidates, even when the selected document tree contains the same file.
 *
 * Equally important: an unrelated spreadsheet that merely looks similar must
 * NOT be excluded, because hiding a real document is its own kind of data loss.
 */
class MasterWorkbookTest {

    private val master = MasterWorkbookIdentity(
        sourceUri = "content://com.android.providers.downloads.documents/document/msf%3A1000054488",
        sourceDocId = "msf:1000054488",
        displayName = "Following up Orders Eng. Ragaey  6 MAY 2026.xlsx",
        size = 1_744_565
    )

    @Test
    fun `the same URI is the master`() {
        assertTrue(
            MasterWorkbook.isMaster(
                master,
                "content://com.android.providers.downloads.documents/document/msf%3A1000054488",
                "msf:1000054488", 1_744_565, candidateName = master.displayName
            )
        )
    }

    @Test
    fun `the same provider document id under another tree is the master`() {
        // The same provider hands out the same document id however the file is
        // reached, so a different tree URI over the same id is the same file.
        assertTrue(
            MasterWorkbook.isMaster(
                master,
                "content://com.android.providers.downloads.documents/tree/msf%3A42/document/msf%3A1000054488",
                "msf:1000054488",
                1_744_565,
                candidateName = "Following up Orders Eng. Ragaey  6 MAY 2026.xlsx"
            )
        )
    }

    @Test
    fun `a different provider with a different id falls through to name and size`() {
        // Document ids are provider scoped, so this is not matched on id alone;
        // it is still the master because the name and size agree.
        assertTrue(
            MasterWorkbook.isMaster(
                master,
                "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fx.xlsx",
                "primary:Download/x.xlsx",
                1_744_565,
                candidateName = "Following up Orders Eng. Ragaey  6 MAY 2026.xlsx"
            )
        )
    }

    @Test
    fun `a byte-identical copy elsewhere in the tree is the master`() {
        // Different URI and document id, but the same name and the same size.
        assertTrue(
            MasterWorkbook.isMaster(
                master,
                "content://com.android.externalstorage.documents/tree/primary%3ADocs/document/primary%3ADocs%2Fcopy.xlsx",
                "primary:Docs/copy.xlsx",
                1_744_565,
                candidateName = "Following up Orders Eng. Ragaey  6 MAY 2026.xlsx"
            )
        )
    }

    @Test
    fun `whitespace differences in the name still identify the master`() {
        assertTrue(
            MasterWorkbook.isMaster(
                master,
                "content://other/doc/1",
                "other/1",
                1_744_565,
                candidateName = "Following up Orders Eng. Ragaey 6 MAY 2026.xlsx"
            )
        )
    }

    @Test
    fun `a different spreadsheet with a similar name is NOT excluded`() {
        // Same size on purpose: the name still has to agree.
        assertFalse(
            MasterWorkbook.isMaster(
                master,
                "content://other/doc/2",
                "other/2",
                1_744_565,
                candidateName = "BOQ Rev 02.xlsx"
            )
        )
    }

    @Test
    fun `an unrelated file is never excluded`() {
        assertFalse(
            MasterWorkbook.isMaster(
                master,
                "content://other/doc/3",
                "other/3",
                586,
                candidateName = "MMH2-GR-AR-14-25A.pdf"
            )
        )
    }

    @Test
    fun `no workbook selected means nothing is excluded`() {
        val none = MasterWorkbookIdentity(null, null, null, 0)
        assertFalse(
            MasterWorkbook.isMaster(
                none,
                "content://other/doc/4",
                "other/4",
                1_744_565,
                candidateName = "Following up Orders Eng. Ragaey  6 MAY 2026.xlsx"
            )
        )
    }

    @Test
    fun `a pdf that shares the workbook stem is not excluded`() {
        assertFalse(
            MasterWorkbook.isMaster(
                master,
                "content://other/doc/5",
                "other/5",
                1_744_565,
                candidateName = "Following up Orders Eng. Ragaey  6 MAY 2026.pdf"
            )
        )
    }
}
