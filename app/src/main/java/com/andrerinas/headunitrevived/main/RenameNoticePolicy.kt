package com.andrerinas.headunitrevived.main

/**
 * Decides who sees the one-time rename notice.
 *
 * Existing users (installed before the rename went live) always get it once, even if they
 * update late. Brand-new installs only get it during a short window after the release, since
 * a rename notice means nothing to someone who never knew the old name.
 */
object RenameNoticePolicy {

    // Date the renamed build went live (UTC epoch millis). Set this to the actual release date.
    const val RENAME_RELEASE_MS = 1785542400000L // 2026-08-01

    // How long after the release brand-new installs still see the notice.
    const val NEW_USER_WINDOW_MS = 30L * 24 * 60 * 60 * 1000 // 30 days

    fun shouldOffer(firstInstallTimeMs: Long, nowMs: Long): Boolean {
        val existingUser = firstInstallTimeMs in 1 until RENAME_RELEASE_MS
        val withinNewUserWindow = nowMs < RENAME_RELEASE_MS + NEW_USER_WINDOW_MS
        return existingUser || withinNewUserWindow
    }
}
