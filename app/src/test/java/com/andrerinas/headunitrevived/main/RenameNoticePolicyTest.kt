package com.andrerinas.headunitrevived.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenameNoticePolicyTest {

    private val release = RenameNoticePolicy.RENAME_RELEASE_MS
    private val windowEnd = release + RenameNoticePolicy.NEW_USER_WINDOW_MS

    @Test
    fun `existing user always sees it, even long after the window`() {
        val installedBeforeRelease = release - 1000
        assertTrue(RenameNoticePolicy.shouldOffer(installedBeforeRelease, windowEnd + 1000))
    }

    @Test
    fun `new install within the window sees it`() {
        val installedAfterRelease = release + 1000
        assertTrue(RenameNoticePolicy.shouldOffer(installedAfterRelease, release + 1000))
    }

    @Test
    fun `new install after the window does not see it`() {
        val installedAfterRelease = release + 1000
        assertFalse(RenameNoticePolicy.shouldOffer(installedAfterRelease, windowEnd + 1000))
    }

    @Test
    fun `window end is exclusive`() {
        val installedAfterRelease = release + 1000
        assertFalse(RenameNoticePolicy.shouldOffer(installedAfterRelease, windowEnd))
    }

    @Test
    fun `unknown install time is not treated as an existing user`() {
        assertTrue(RenameNoticePolicy.shouldOffer(0L, release + 1000))
        assertFalse(RenameNoticePolicy.shouldOffer(0L, windowEnd + 1000))
    }
}
