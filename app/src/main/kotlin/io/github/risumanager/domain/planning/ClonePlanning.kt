package io.github.risumanager.domain.planning

import io.github.risumanager.domain.model.BuiltInVariants
import io.github.risumanager.domain.model.RisuInstance
import io.github.risumanager.domain.model.RuntimeType
import io.github.risumanager.domain.validation.Validators

data class ClonePlan(
    val source: RisuInstance,
    val targetInstanceId: String,
    val targetDisplayName: String,
    val targetPort: Int,
)

object ClonePlanner {
    const val MIN_PORT = 1024
    const val MAX_PORT = 65535

    fun normalizeName(value: String): String? = value.trim()
        .takeIf { it.isNotEmpty() && it.length <= 80 && it.none(Char::isISOControl) }

    fun validateSource(source: RisuInstance): String? = when {
        source.variantId != BuiltInVariants.pocketRisu.id -> "Cloning is currently supported only for PocketRisu."
        source.runtimeType != RuntimeType.TERMUX_NODE -> "PocketRisu cloning requires the Termux runtime."
        !Validators.isFullCommitSha(source.installedRevision.orEmpty()) -> "The source instance has no verified installed revision."
        else -> null
    }

    /** Chooses deterministically; the helper performs the authoritative OS availability check. */
    fun allocatePort(
        usedPorts: Set<Int>,
        preferredPort: Int,
        isAvailable: (Int) -> Boolean = { true },
    ): Int? {
        val start = preferredPort.coerceIn(MIN_PORT, MAX_PORT)
        for (port in start..MAX_PORT) if (port !in usedPorts && isAvailable(port)) return port
        for (port in MIN_PORT until start) if (port !in usedPorts && isAvailable(port)) return port
        return null
    }
}
