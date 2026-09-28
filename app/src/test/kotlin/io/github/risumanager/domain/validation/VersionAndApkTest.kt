package io.github.risumanager.domain.validation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VersionAndApkTest {
    @Test fun semanticVersionsCompareNumerically() {
        assertThat(VersionComparator.compare("v1.11.0", "1.10.9")).isGreaterThan(0)
        assertThat(VersionComparator.compare("1.2", "1.2.0")).isEqualTo(0)
        assertThat(VersionComparator.compare("1.2.0-beta", "1.2.1")).isLessThan(0)
    }

    @Test fun apkMetadataRequiresHttpsPackageAndValidHash() {
        val verified = ApkMetadata(
            downloadUrl = "https://github.com/example/app.apk",
            packageName = "co.aiclient.risu",
            versionCode = 42,
            versionName = "0.0.42",
            sha256 = "a".repeat(64),
        )
        assertThat(ApkMetadataValidator.validate(verified, "co.aiclient.risu")).isEqualTo(ApkValidation.Verified)
        assertThat(ApkMetadataValidator.validate(verified.copy(packageName = "evil.package"), "co.aiclient.risu"))
            .isInstanceOf(ApkValidation.Failed::class.java)
        assertThat(ApkMetadataValidator.validate(verified.copy(sha256 = null), "co.aiclient.risu"))
            .isInstanceOf(ApkValidation.PartiallyVerified::class.java)
    }
}
