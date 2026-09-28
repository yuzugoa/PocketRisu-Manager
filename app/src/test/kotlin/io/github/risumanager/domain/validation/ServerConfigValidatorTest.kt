package io.github.risumanager.domain.validation

import com.google.common.truth.Truth.assertThat
import io.github.risumanager.domain.model.InstanceServerConfig
import org.junit.Test

class ServerConfigValidatorTest {
    @Test fun defaultsAreValidAndConvertSaveIntervalSafely() {
        val config = InstanceServerConfig("demo")
        assertThat(ServerConfigValidator.validate(config).isValid).isTrue()
        assertThat(ServerConfigValidator.saveIntervalMillis(config.saveIntervalSeconds)).isEqualTo(5_000L)
    }

    @Test fun allUserControlledBoundsAreCentralized() {
        val invalid = InstanceServerConfig(
            instanceId = "demo",
            bodyLimitMb = 0,
            port = 65536,
            saveIntervalSeconds = 31,
            snapshotIntervalMinutes = 0,
            snapshotRetentionCount = 0,
            snapshotCapacityMb = 0,
            imageQuality = 101,
        )
        val errors = ServerConfigValidator.validate(invalid).errors
        assertThat(errors).hasSize(7)
        assertThat(ServerConfigValidator.saveIntervalMillisOrNull(Int.MAX_VALUE)).isNull()
    }

    @Test fun validEdgesAreAccepted() {
        val config = InstanceServerConfig(
            instanceId = "demo",
            bodyLimitMb = 1,
            port = 1024,
            saveIntervalSeconds = 1,
            snapshotIntervalMinutes = 1,
            snapshotRetentionCount = 1,
            snapshotCapacityMb = 10,
            imageQuality = 1,
        )
        assertThat(Validators.validate(config).isValid).isTrue()
        assertThat(ServerConfigValidator.saveIntervalMillis(30)).isEqualTo(30_000L)
    }
}
