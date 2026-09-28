package io.github.risumanager.domain.model

object BuiltInVariants {
    private val pocketChannels = listOf(
        ChannelDefinition("stable", "Stable", ChannelType.GITHUB_RELEASE),
        ChannelDefinition("develop", "Develop", ChannelType.GIT_BRANCH, ref = "develop", advanced = true),
        ChannelDefinition("specific-commit", "Specific commit", ChannelType.SPECIFIC_COMMIT, advanced = true),
    )

    val pocketRisuProfile = VariantProfile(
        id = "pocketrisu",
        displayName = "PocketRisu",
        repository = "PocketRisu/PocketRisu",
        channels = pocketChannels,
        selection = ReleaseSelection.LATEST_STABLE,
        installStrategy = InstallStrategy.SOURCE_BUILD,
        supportedArchitectures = setOf("aarch64"),
        minimumNodeVersion = "22.12.0",
        buildScriptId = BuildScriptId.POCKETRISU_TERMUX_BUILD,
        workDirectory = "repo",
        entrypoint = "server/node/server.cjs",
        defaultPort = 6001,
        healthCheckPath = "/",
        dataPaths = DataLayout(
            databasePaths = listOf("save/risuai.db", "save/risuai.db-wal", "save/risuai.db-shm"),
            assetPaths = listOf("save/inlays"),
            configPaths = listOf("save"),
            backupPaths = listOf("backups"),
        ),
        layoutVersion = 1,
        capabilities = setOf(
            Capability.NETWORK_SETTINGS,
            Capability.BODY_LIMIT_SETTINGS,
            Capability.AUTOSAVE_SETTINGS,
            Capability.SERVER_SNAPSHOTS,
            Capability.IMAGE_OPTIMIZATION,
            Capability.HTTPS,
            Capability.BACKUP,
        ),
        integrity = IntegrityDefinition(requireHttps = true),
    )

    val pocketRisu = VariantDefinition(
        id = "pocketrisu",
        displayName = "PocketRisu",
        description = "Self-hosted Node server running in Termux",
        repository = "PocketRisu/PocketRisu",
        runtimeType = RuntimeType.TERMUX_NODE,
        channels = pocketChannels,
        trustLevel = TrustLevel.TRUSTED_BUILTIN,
        healthCheck = HealthCheckDefinition("http://127.0.0.1:6001/"),
        dataLayout = DataLayout(
            databasePaths = listOf("save/risuai.db", "save/risuai.db-wal", "save/risuai.db-shm"),
            assetPaths = listOf("save/inlays"),
            configPaths = listOf("save"),
            backupPaths = listOf("backups"),
        ),
        termuxProfile = pocketRisuProfile,
    )

    val haejeokTermuxProfile = VariantProfile(
        id = "haejeok-risuai-termux",
        displayName = "Haejeok RisuAI (Termux)",
        repository = "nevaeh5379/HaejeokRisuai",
        channels = listOf(ChannelDefinition("stable", "Stable", ChannelType.GITHUB_RELEASE)),
        selection = ReleaseSelection.LATEST_STABLE,
        installStrategy = InstallStrategy.RELEASE_ARCHIVE,
        releaseAssetPatterns = listOf("^RisuAI-Termux-b[0-9]+\\.tar\\.gz$"),
        supportedArchitectures = setOf("aarch64", "arm", "x86_64", "x86"),
        minimumNodeVersion = "20.19.0",
        buildScriptId = null,
        workDirectory = "repo",
        entrypoint = "server/node/server.cjs",
        defaultPort = 6001,
        healthCheckPath = "/api/health",
        dataPaths = DataLayout(
            databasePaths = listOf("postgres/data"),
            assetPaths = listOf("save"),
            configPaths = listOf("manager/server.conf", "manager/db.conf"),
            backupPaths = listOf("backups"),
        ),
        layoutVersion = 1,
        capabilities = setOf(Capability.NETWORK_SETTINGS),
        integrity = IntegrityDefinition(
            requireHttps = true,
            sha256AssetPattern = "^RisuAI-Termux-b[0-9]+\\.tar\\.gz\\.sha256$",
        ),
    )

    /** Verified upstream profiles; only fully integrated variants appear in [all]. */
    val verifiedTermuxProfiles: List<VariantProfile> = listOf(pocketRisuProfile, haejeokTermuxProfile)

    val haejeokRisuAi = VariantDefinition(
        id = haejeokTermuxProfile.id,
        displayName = haejeokTermuxProfile.displayName,
        description = "Verified release archive running with isolated PostgreSQL in Termux",
        repository = "nevaeh5379/HaejeokRisuai",
        runtimeType = RuntimeType.TERMUX_NODE,
        channels = listOf(ChannelDefinition("stable", "Stable", ChannelType.GITHUB_RELEASE)),
        trustLevel = TrustLevel.TRUSTED_BUILTIN,
        dataLayout = haejeokTermuxProfile.dataPaths,
        termuxProfile = haejeokTermuxProfile,
    )

    // APK/PWA variants are intentionally excluded; both catalog entries use Termux Node.
    val all: List<VariantDefinition> = listOf(pocketRisu, haejeokRisuAi)

    fun find(id: String): VariantDefinition? = all.firstOrNull { it.id == id }
}
