package io.github.risumanager.ui

import io.github.risumanager.domain.model.RecoveryPoint
import io.github.risumanager.domain.model.RecoveryPointId
import io.github.risumanager.domain.model.RestorationState
import io.github.risumanager.domain.model.UpdateOperation
import io.github.risumanager.domain.model.UpdateResult
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdatePresentationTest {
    @Test
    fun `failed restoration takes precedence over retention flags`() {
        val point = point(pinned = true, inUse = true, expiresAt = 1)
        val operation = operation(restorationState = RestorationState.FAILED)

        assertEquals(
            RecoveryRetentionStatus.RECOVERY_REQUIRED,
            recoverySummary(point, operation, now = 2).status,
        )
    }

    @Test
    fun `retention status prioritizes in-use then pinned then expiration`() {
        assertEquals(RecoveryRetentionStatus.IN_USE, recoverySummary(point(inUse = true, pinned = true), null, 10).status)
        assertEquals(RecoveryRetentionStatus.PINNED, recoverySummary(point(pinned = true, expiresAt = 1), null, 10).status)
        assertEquals(RecoveryRetentionStatus.EXPIRED_CLEANUP_PENDING, recoverySummary(point(expiresAt = 1), null, 10).status)
        assertEquals(RecoveryRetentionStatus.VALID, recoverySummary(point(expiresAt = 20), null, 10).status)
    }

    private fun point(
        pinned: Boolean = false,
        inUse: Boolean = false,
        expiresAt: Long? = 20,
    ) = RecoveryPoint(
        id = RecoveryPointId("recovery"),
        instanceId = "pocketrisu-test",
        createdAt = 1,
        serverRevision = "a".repeat(40),
        dataDigestSha256 = "b".repeat(64),
        configurationDigestSha256 = "c".repeat(64),
        formatVersion = 1,
        variantId = "pocketrisu",
        layoutVersion = 1,
        pinned = pinned,
        inUse = inUse,
        expiresAt = expiresAt,
    )

    private fun operation(restorationState: RestorationState) = UpdateOperation(
        operationId = "update_test",
        instanceId = "pocketrisu-test",
        variantId = "pocketrisu",
        layoutVersion = 1,
        targetRevision = "d".repeat(40),
        currentRevision = "a".repeat(40),
        recoveryPointId = RecoveryPointId("recovery"),
        result = UpdateResult.FAILURE,
        restorationState = restorationState,
        createdAt = 1,
        updatedAt = 2,
    )
}
