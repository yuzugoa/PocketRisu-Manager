package io.github.risumanager.domain.validation

import com.google.common.truth.Truth.assertThat
import io.github.risumanager.domain.model.CertificateMode
import io.github.risumanager.domain.model.InstanceServerConfig
import io.github.risumanager.domain.model.ServerProtocol
import org.junit.Test

class ValidatorsTest {
    @Test fun repositoryValidationRejectsCommandLikeInput() {
        assertThat(Validators.isRepository("PocketRisu/PocketRisu")).isTrue()
        assertThat(Validators.isRepository("owner/repo;rm -rf x")).isFalse()
        assertThat(Validators.isRepository("https://github.com/owner/repo")).isFalse()
        assertThat(Validators.isRepository("owner/repo.git")).isFalse()
    }

    @Test fun refsRejectOptionAndTraversalSyntax() {
        assertThat(Validators.isSafeGitRef("develop")).isTrue()
        assertThat(Validators.isSafeGitRef("release/v1.2.3")).isTrue()
        assertThat(Validators.isSafeGitRef("--upload-pack=evil")).isFalse()
        assertThat(Validators.isSafeGitRef("refs/../secret")).isFalse()
        assertThat(Validators.isSafeGitRef("main@{0}")).isFalse()
    }

    @Test fun commitAndInstanceIdentifiersAreStrict() {
        assertThat(Validators.isFullCommitSha("a".repeat(40))).isTrue()
        assertThat(Validators.isFullCommitSha("a".repeat(39))).isFalse()
        assertThat(Validators.isCommitInput("deadbee")).isTrue()
        assertThat(Validators.isInstanceId("pocketrisu-ab12cd34")).isTrue()
        assertThat(Validators.isInstanceId("../pocketrisu")).isFalse()
    }

    @Test fun onlyHttpsDownloadUrlsPass() {
        assertThat(Validators.isHttpsUrl("https://github.com/example/app.apk")).isTrue()
        assertThat(Validators.isHttpsUrl("http://github.com/example/app.apk")).isFalse()
        assertThat(Validators.isHttpsUrl("https://user:token@github.com/app.apk")).isFalse()
    }

    @Test fun tlsDomainAndEmailAreNormalizedButRejectUrlsWildcardsAndIps() {
        assertThat(Validators.normalizeTlsDomain(" Example.COM. ")).isEqualTo("example.com")
        assertThat(Validators.isTlsDomain("https://example.com")).isFalse()
        assertThat(Validators.isTlsDomain("*.example.com")).isFalse()
        assertThat(Validators.isTlsDomain("127.0.0.1")).isFalse()
        assertThat(Validators.normalizeAcmeEmail(" Me+acme@Example.COM ")).isEqualTo("me+acme@example.com")
        assertThat(Validators.isEmail("not-an-email")).isFalse()
    }

    @Test fun letsEncryptConfigRequiresDomainEmailAndValidChallengePort() {
        val config = InstanceServerConfig(
            instanceId = "demo",
            protocol = ServerProtocol.HTTPS,
            certificateMode = CertificateMode.LETS_ENCRYPT,
            tlsDomain = "example.com",
            acmeEmail = "admin@example.com",
            acmeChallengePort = 8080,
        )
        assertThat(Validators.validate(config).isValid).isTrue()
        assertThat(Validators.validate(config.copy(tlsDomain = "127.0.0.1")).isValid).isFalse()
        assertThat(Validators.validate(config.copy(acmeChallengePort = 80)).isValid).isFalse()
    }

    @Test fun certificateHealthUses21And7DayThresholds() {
        val now = 1_000_000L
        val day = 24L * 60 * 60 * 1000
        assertThat(certificateHealth(null, now)).isEqualTo(CertificateHealth.UNKNOWN)
        assertThat(certificateHealth(now - 1, now)).isEqualTo(CertificateHealth.EXPIRED)
        assertThat(certificateHealth(now + 7 * day, now)).isEqualTo(CertificateHealth.CRITICAL)
        assertThat(certificateHealth(now + 21 * day, now)).isEqualTo(CertificateHealth.WARNING)
        assertThat(certificateHealth(now + 22 * day, now)).isEqualTo(CertificateHealth.HEALTHY)
    }

    @Test fun serverUrlUsesTlsDomainOnlyForValidLetsEncryptHttps() {
        val config = InstanceServerConfig(
            instanceId = "demo",
            port = 6001,
            protocol = ServerProtocol.HTTPS,
            certificateMode = CertificateMode.LETS_ENCRYPT,
            tlsDomain = "risu.example.com",
        )
        assertThat(ServerUrlPolicy.url(config)).isEqualTo("https://risu.example.com:6001/")
        assertThat(ServerUrlPolicy.url(config.copy(certificateMode = CertificateMode.MANUAL)))
            .isEqualTo("https://127.0.0.1:6001/")
        assertThat(ServerUrlPolicy.isAllowedOrigin(config, "https", "risu.example.com", 6001)).isTrue()
        assertThat(ServerUrlPolicy.isAllowedOrigin(config, "https", "127.0.0.1", 6001)).isFalse()
        assertThat(serverUrl(config, fallbackPort = 9999)).isEqualTo("https://risu.example.com:6001/")
        assertThat(serverUrl(null, fallbackPort = 7777)).isEqualTo("http://127.0.0.1:7777/")
    }
}
