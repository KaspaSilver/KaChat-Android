package com.kachat.app.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The verify step after every backup PUT (NEXTCLOUD_SYNC.md §4.6, iOS d57019a). */
class NextcloudServiceTest {

    private fun cutOff(sent: Long, stored: Long?, putEtag: String?, currentEtag: String?) =
        NextcloudService.isUploadCutOff(sent, stored, putEtag, currentEtag)

    @Test
    fun `a stored size equal to the bytes sent is a complete upload`() {
        assertFalse(cutOff(1_000, 1_000, "e1", "e1"))
        assertFalse(cutOff(1_000, 1_000, null, null))
    }

    @Test
    fun `a short file that is still our own write is reported as cut off`() {
        assertTrue(cutOff(1_000, 400, "e1", "e1"))
    }

    @Test
    fun `without a PUT ETag a size mismatch is taken at face value`() {
        assertTrue(cutOff(1_000, 400, null, "e2"))
        assertTrue(cutOff(1_000, 400, null, null))
    }

    @Test
    fun `a size mismatch after another device replaced the file is not ours to report`() {
        assertFalse(cutOff(1_000, 400, "e1", "e2"))
        assertFalse(cutOff(1_000, 1_600, "e1", "e2"))
    }

    @Test
    fun `an ETag-less read-back cannot prove the file is still our write`() {
        assertFalse(cutOff(1_000, 400, "e1", null))
    }

    @Test
    fun `an unknown stored size is no evidence either way`() {
        assertFalse(cutOff(1_000, null, "e1", "e1"))
        assertFalse(cutOff(1_000, null, null, null))
    }
}
