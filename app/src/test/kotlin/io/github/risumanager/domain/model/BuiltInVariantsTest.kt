package io.github.risumanager.domain.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class BuiltInVariantsTest {
    @Test fun builtInsKeepRuntimeAndTrustBoundaries() {
        assertThat(BuiltInVariants.pocketRisu.runtimeType).isEqualTo(RuntimeType.TERMUX_NODE)
        assertThat(BuiltInVariants.haejeokRisuAi.runtimeType).isEqualTo(RuntimeType.TERMUX_NODE)
        assertThat(BuiltInVariants.all).containsExactly(BuiltInVariants.pocketRisu, BuiltInVariants.haejeokRisuAi).inOrder()
        assertThat(BuiltInVariants.all.map { it.trustLevel }.distinct()).containsExactly(TrustLevel.TRUSTED_BUILTIN)
    }

    @Test fun haejeokUsesVerifiedReleaseArchiveProfile() {
        val profile = BuiltInVariants.haejeokRisuAi.termuxProfile!!
        assertThat(profile.installStrategy).isEqualTo(InstallStrategy.RELEASE_ARCHIVE)
        assertThat(profile.releaseAssetPatterns).containsExactly("^RisuAI-Termux-b[0-9]+\\.tar\\.gz$")
        assertThat(profile.integrity.sha256AssetPattern).isNotNull()
        assertThat(profile.healthCheckPath).isEqualTo("/api/health")
    }

    @Test fun pocketRisuUsesAllowlistedSourceBuildProfile() {
        val profile = BuiltInVariants.pocketRisu.termuxProfile!!
        assertThat(profile.installStrategy).isEqualTo(InstallStrategy.SOURCE_BUILD)
        assertThat(profile.buildScriptId).isEqualTo(BuildScriptId.POCKETRISU_TERMUX_BUILD)
        assertThat(profile.entrypoint).isEqualTo("server/node/server.cjs")
        assertThat(profile.supportedArchitectures).containsExactly("aarch64")
        assertThat(profile.capabilities).containsAtLeast(Capability.NETWORK_SETTINGS, Capability.BACKUP)
    }

    @Test fun variantDefinitionRoundTripsThroughSerialization() {
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(VariantDefinition.serializer(), BuiltInVariants.pocketRisu)
        val decoded = json.decodeFromString(VariantDefinition.serializer(), encoded)
        assertThat(decoded).isEqualTo(BuiltInVariants.pocketRisu)
    }

    @Test fun pocketRisuChannelsHaveStableDevelopAndPinnedCommit() {
        assertThat(BuiltInVariants.pocketRisu.channels.map { it.type })
            .containsExactly(ChannelType.GITHUB_RELEASE, ChannelType.GIT_BRANCH, ChannelType.SPECIFIC_COMMIT)
            .inOrder()
    }
}
