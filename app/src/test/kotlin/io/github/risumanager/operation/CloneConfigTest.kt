package io.github.risumanager.operation

import com.google.common.truth.Truth.assertThat
import io.github.risumanager.domain.model.BindScope
import io.github.risumanager.domain.model.CertificateMode
import io.github.risumanager.domain.model.CloneOperation
import io.github.risumanager.domain.model.ServerProtocol
import org.junit.Test

class CloneConfigTest {
    @Test fun `clone config preserves content tuning and resets exposure TLS and applied runtime state`() {
        val operation = CloneOperation(
            operationId = "clone_12345678",
            sourceInstanceId = "pocketrisu-source",
            targetInstanceId = "pocketrisu-target",
            targetDisplayName = "Independent copy",
            targetPort = 6010,
            sourceRevision = "a".repeat(40),
            bodyLimitMb = 321,
            saveIntervalSeconds = 12,
            snapshotIntervalMinutes = 17,
            snapshotRetentionCount = 27,
            snapshotCapacityMb = 987,
            imageQuality = 72,
            createdAt = 1,
            updatedAt = 1,
        )
        val config = operation.toTargetConfig()
        assertThat(config.instanceId).isEqualTo("pocketrisu-target")
        assertThat(config.port).isEqualTo(6010)
        assertThat(config.bindScope).isEqualTo(BindScope.LOOPBACK)
        assertThat(config.protocol).isEqualTo(ServerProtocol.HTTP)
        assertThat(config.certificateMode).isEqualTo(CertificateMode.MANUAL)
        assertThat(config.autoRenew).isFalse()
        assertThat(config.tlsDomain).isNull()
        assertThat(config.appliedPort).isNull()
        assertThat(config.bodyLimitMb).isEqualTo(321)
        assertThat(config.snapshotCapacityMb).isEqualTo(987)
        assertThat(config.imageQuality).isEqualTo(72)
    }
}
