package io.github.risumanager.diagnostic

import io.github.risumanager.domain.model.BindScope
import io.github.risumanager.domain.model.ConfigApplyState
import io.github.risumanager.domain.model.EnvironmentStatus
import io.github.risumanager.domain.model.InstanceServerConfig
import io.github.risumanager.domain.model.LogLine
import io.github.risumanager.domain.model.OperationRecord
import io.github.risumanager.domain.model.RollbackEvent
import io.github.risumanager.domain.model.RisuInstance
import io.github.risumanager.domain.model.RuntimeStatus

/** Structured, allowlisted data collected before a report is rendered. */
data class DiagnosticEnvironment(
    val managerVersion: String,
    val androidRelease: String,
    val androidSdk: Int,
    val environments: List<EnvironmentStatus> = emptyList(),
)

data class DiagnosticInstance(
    val instance: RisuInstance,
    val config: InstanceServerConfig?,
    val runtime: RuntimeStatus?,
    val operations: List<OperationRecord> = emptyList(),
    val rollbackEvents: List<RollbackEvent> = emptyList(),
    val logs: List<LogLine> = emptyList(),
)

data class DiagnosticSnapshot(
    val generatedAt: Long,
    val environment: DiagnosticEnvironment,
    val instances: List<DiagnosticInstance>,
)

object DiagnosticRedactor {
    private val sensitiveKey = Regex(
        "(?i)(authorization|api[-_ ]?key|access[-_ ]?key|secret|password|passwd|cookie|session(?:[-_ ]?token)?|refresh[-_ ]?token|backup[-_ ]?token|database[-_ ]?password|private[-_ ]?key|credential)"
    )
    private val sensitiveAssignment = Regex(
        "(?i)(\\b(?:authorization|api[-_ ]?key|access[-_ ]?key|secret|password|passwd|cookie|session(?:[-_ ]?token)?|refresh[-_ ]?token|backup[-_ ]?token|private[-_ ]?key|credential)\\b\\s*[:=]\\s*)([^\\s,;]+)"
    )
    private val querySecret = Regex(
        "(?i)([?&](?:token|access_token|refresh_token|api[_-]?key|key|password|secret|signature|credential)=)([^&#\\s]+)"
    )
    private val bearer = Regex("(?i)(\\bBearer\\s+)[A-Za-z0-9._~+/=-]+")
    private val connectionPassword = Regex("(?i)(://[^:/@\\s]+:)([^@/\\s]+)(@)")
    private val email = Regex("\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b", RegexOption.IGNORE_CASE)
    private val ipv4 = Regex("(?<![\\d.])(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}(?![\\d.])")
    private val ipv6 = Regex("(?i)(?<![a-f0-9:])(?:[a-f0-9]{1,4}:){2,7}[a-f0-9]{0,4}(?![a-f0-9:])")
    private val hostname = Regex("(?i)(?<![@/])\\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,}(?::\\d+)?\\b")

    fun field(key: String, value: String?): String = when {
        value == null -> "<not available>"
        sensitiveKey.containsMatchIn(key) -> "<redacted>"
        else -> text(value)
    }

    fun text(value: String): String = value
        .replace(bearer) { "${it.groupValues[1]}<redacted>" }
        .replace(sensitiveAssignment) { "${it.groupValues[1]}<redacted>" }
        .replace(querySecret) { "${it.groupValues[1]}<redacted>" }
        .replace(connectionPassword) { "${it.groupValues[1]}<redacted>${it.groupValues[3]}" }
        .replace(email, "<redacted-email>")
        .replace(ipv4, "<redacted-ip>")
        .replace(ipv6, "<redacted-ip>")
        .replace(hostname, "<redacted-domain>")

    fun name(value: String?): String = value?.let { "<redacted-name-${stableMarker(it)}>" } ?: "<not set>"

    private fun stableMarker(value: String): String = value.hashCode().toUInt().toString(16).takeLast(8)
}

class DiagnosticReportGenerator(
    private val maxLogLinesPerInstance: Int = 80,
    private val maxLogCharactersPerInstance: Int = 12_000,
) {
    fun generate(snapshot: DiagnosticSnapshot): String = buildString {
        appendLine("Risu Manager diagnostic report")
        appendLine("Generated at: ${snapshot.generatedAt}")
        appendLine("Privacy: structured operation fields only; server log text and operation details are excluded")
        appendLine()

        appendLine("[Manager and Android]")
        appendLine("Manager version: ${DiagnosticRedactor.field("version", snapshot.environment.managerVersion)}")
        appendLine("Android release: ${DiagnosticRedactor.field("version", snapshot.environment.androidRelease)}")
        appendLine("Android SDK: ${snapshot.environment.androidSdk}")
        if (snapshot.environment.environments.isEmpty()) {
            appendLine("Runtime environments: <not available>")
        } else {
            snapshot.environment.environments.forEachIndexed { index, environment ->
                appendLine("Runtime environment ${index + 1}: ${environment.state}")
                appendLine("  Status: ${DiagnosticRedactor.text(environment.message)}")
                appendLine("  Helper version: ${environment.helperVersion ?: "<not available>"}")
                environment.diagnostics.toSortedMap().forEach { (key, value) ->
                    appendLine("  $key: ${DiagnosticRedactor.field(key, value)}")
                }
            }
        }
        appendLine()

        appendLine("[Instances]")
        if (snapshot.instances.isEmpty()) appendLine("No instances found.")
        snapshot.instances.forEach { item ->
            val instance = item.instance
            appendLine("Instance: ${DiagnosticRedactor.name(instance.displayName ?: instance.instanceId)}")
            appendLine("  Variant: ${DiagnosticRedactor.field("variant", instance.variantId)}")
            appendLine("  Runtime: ${instance.runtimeType}")
            appendLine("  Installed version: ${DiagnosticRedactor.field("version", instance.installedVersion)}")
            appendLine("  Installed revision: ${DiagnosticRedactor.field("revision", instance.installedRevision)}")
            appendLine("  Execution state: ${item.runtime?.state ?: instance.lastKnownStatus}")
            item.runtime?.let { runtime ->
                appendLine("  PID present: ${runtime.pid != null}")
                appendLine("  Port: ${runtime.port ?: instance.port}")
                appendLine("  Runtime revision: ${DiagnosticRedactor.field("revision", runtime.revision)}")
            }
            item.config?.let { config ->
                appendLine("  Server configuration:")
                appendLine("    Port: ${config.port}")
                appendLine("    Bind scope: ${config.bindScope}")
                appendLine("    Protocol: ${config.protocol}")
                appendLine("    Certificate mode: ${config.certificateMode}")
                appendLine("    TLS domain: ${DiagnosticRedactor.field("domain", config.tlsDomain)}")
                appendLine("    ACME email: ${DiagnosticRedactor.field("email", config.acmeEmail)}")
                appendLine("    ACME challenge: ${config.acmeChallenge}")
                appendLine("    ACME challenge port: ${config.acmeChallengePort}")
                appendLine("    Certificate expiry: ${config.certificateExpiresAt ?: "<not available>"}")
                appendLine("    Auto renew: ${config.autoRenew}")
                appendLine("    Body limit MB: ${config.bodyLimitMb}")
                appendLine("    Save interval seconds: ${config.saveIntervalSeconds}")
                appendLine("    Snapshot interval minutes: ${config.snapshotIntervalMinutes}")
                appendLine("    Snapshot retention count: ${config.snapshotRetentionCount}")
                appendLine("    Snapshot capacity MB: ${config.snapshotCapacityMb}")
                appendLine("    Image quality: ${config.imageQuality}")
                appendLine("    Apply state: ${config.applyState}")
                appendLine("    Applied port: ${config.appliedPort ?: "<not available>"}")
            } ?: appendLine("  Server configuration: <not available>")

            appendLine("  Recent operations:")
            if (item.operations.isEmpty()) appendLine("    <none>")
            item.operations.take(20).forEach { operation ->
                val safeStage = operation.stage?.takeIf { it.matches(Regex("[A-Z_]{1,48}")) } ?: "<not set>"
                val safeCode = operation.errorCode?.takeIf { it.matches(Regex("[A-Z0-9_]{1,64}")) } ?: "<none>"
                appendLine(
                    "    ${operation.type} result=${operation.result} stage=$safeStage errorCode=$safeCode"
                )
            }
            appendLine("  Rollback steps (recent first):")
            if (item.rollbackEvents.isEmpty()) appendLine("    <none>")
            item.rollbackEvents.take(40).forEach { event ->
                val safeCode = event.errorCode?.takeIf { it.matches(Regex("[A-Z0-9_]{1,64}")) } ?: "<none>"
                appendLine("    at=${event.recordedAt} stage=${event.stage} result=${event.result} errorCode=$safeCode")
            }
            appendLine("  Server logs and free-form details: excluded to protect user data")
            appendLine()
        }
    }
}
