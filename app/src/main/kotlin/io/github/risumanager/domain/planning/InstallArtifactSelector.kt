package io.github.risumanager.domain.planning

import io.github.risumanager.domain.model.InstallArtifact
import io.github.risumanager.domain.model.InstallStrategy
import io.github.risumanager.domain.model.RisuRelease
import io.github.risumanager.domain.model.VariantProfile
import io.github.risumanager.domain.validation.Validators

object InstallArtifactSelector {
    private val sha256 = Regex("^[0-9a-fA-F]{64}$")

    fun select(profile: VariantProfile, release: RisuRelease): InstallArtifact? {
        if (profile.installStrategy != InstallStrategy.RELEASE_ARCHIVE) return null
        val patterns = profile.releaseAssetPatterns.map(::Regex)
        val archive = release.assets.singleOrNull { asset -> patterns.any { it.matches(asset.name) } }
            ?: return null
        if (!Validators.isHttpsUrl(archive.downloadUrl)) return null
        val digest = archive.sha256?.lowercase()?.takeIf(sha256::matches) ?: return null
        val checksumPattern = profile.integrity.sha256AssetPattern?.let(::Regex)
        val checksum = checksumPattern?.let { pattern -> release.assets.singleOrNull { pattern.matches(it.name) } }
        if (checksumPattern != null && (checksum == null || !Validators.isHttpsUrl(checksum.downloadUrl))) return null
        return InstallArtifact(archive.name, archive.downloadUrl, digest, checksum?.downloadUrl)
    }
}
