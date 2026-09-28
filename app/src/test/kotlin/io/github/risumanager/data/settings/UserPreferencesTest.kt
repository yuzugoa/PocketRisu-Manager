package io.github.risumanager.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class UserPreferencesTest {
    @Test
    fun `recovery retention defaults to seven days and clamps to supported range`() {
        assertEquals(7, DEFAULT_UPDATE_RECOVERY_RETENTION_DAYS)
        assertEquals(1, sanitizeUpdateRecoveryRetentionDays(-1))
        assertEquals(30, sanitizeUpdateRecoveryRetentionDays(30))
        assertEquals(365, sanitizeUpdateRecoveryRetentionDays(999))
    }

    @Test
    fun `top level tab restoration accepts only durable app tabs`() {
        assertEquals("home", sanitizeTopLevelTab(null))
        assertEquals("home", sanitizeTopLevelTab("unknown"))
        assertEquals("install", sanitizeTopLevelTab("install"))
        assertEquals("settings", sanitizeTopLevelTab("settings"))
    }
}
