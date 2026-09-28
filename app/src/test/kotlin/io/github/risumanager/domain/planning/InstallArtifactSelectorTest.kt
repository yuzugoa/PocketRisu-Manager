package io.github.risumanager.domain.planning

import com.google.common.truth.Truth.assertThat
import io.github.risumanager.domain.model.BuiltInVariants
import io.github.risumanager.domain.model.ReleaseAsset
import io.github.risumanager.domain.model.RisuRelease
import io.github.risumanager.domain.model.SourceRevision
import org.junit.Test

class InstallArtifactSelectorTest {
    private val release = RisuRelease(
        tag = "b6787",
        name = "b6787",
        revision = SourceRevision("a".repeat(40)),
        prerelease = false,
        draft = false,
        assets = listOf(
            ReleaseAsset("RisuAI-Termux-b6787.tar.gz", "https://github.com/nevaeh5379/HaejeokRisuai/releases/download/b6787/RisuAI-Termux-b6787.tar.gz", 1, "1".repeat(64)),
            ReleaseAsset("RisuAI-Termux-b6787.tar.gz.sha256", "https://github.com/nevaeh5379/HaejeokRisuai/releases/download/b6787/RisuAI-Termux-b6787.tar.gz.sha256", 64),
        ),
    )

    @Test fun selectsOnlyExactArchiveWithApiDigestAndOfficialChecksumAsset() {
        val selected = InstallArtifactSelector.select(BuiltInVariants.haejeokTermuxProfile, release)
        assertThat(selected!!.name).isEqualTo("RisuAI-Termux-b6787.tar.gz")
        assertThat(selected.sha256).isEqualTo("1".repeat(64))
        assertThat(selected.checksumUrl).endsWith(".sha256")
    }

    @Test fun rejectsMissingDigestOrChecksumAndUnrelatedAssets() {
        assertThat(InstallArtifactSelector.select(BuiltInVariants.haejeokTermuxProfile, release.copy(assets = release.assets.take(1)))).isNull()
        assertThat(InstallArtifactSelector.select(BuiltInVariants.haejeokTermuxProfile, release.copy(assets = release.assets.map { it.copy(sha256 = null) }))).isNull()
        assertThat(InstallArtifactSelector.select(BuiltInVariants.haejeokTermuxProfile, release.copy(assets = release.assets.map { it.copy(name = "other.zip") }))).isNull()
    }
}
