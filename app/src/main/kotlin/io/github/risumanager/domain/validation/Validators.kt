package io.github.risumanager.domain.validation

import java.net.URI
import io.github.risumanager.domain.model.CertificateMode
import io.github.risumanager.domain.model.InstanceServerConfig
import io.github.risumanager.domain.model.ServerProtocol

object Validators {
    private val repository = Regex("^[A-Za-z0-9](?:[A-Za-z0-9_.-]{0,38})/[A-Za-z0-9](?:[A-Za-z0-9_.-]{0,99})$")
    private val fullSha = Regex("^[0-9a-fA-F]{40}$")
    private val shortOrFullSha = Regex("^[0-9a-fA-F]{7,40}$")
    private val sha256 = Regex("^[0-9a-fA-F]{64}$")
    private val instanceId = Regex("^[a-z0-9](?:[a-z0-9-]{1,62})[a-z0-9]$")
    private val operationId = Regex("^[A-Za-z0-9_-]{8,80}$")
    private val safeRefCharacters = Regex("^[A-Za-z0-9._/-]{1,128}$")
    private val domainLabel = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
    private val emailLocal = Regex("^[A-Za-z0-9.!#${'$'}%&'*+/=?^_`{|}~-]+${'$'}")

    fun isRepository(value: String): Boolean = repository.matches(value) && !value.endsWith(".git", ignoreCase = true)
    fun isFullCommitSha(value: String): Boolean = fullSha.matches(value)
    fun isCommitInput(value: String): Boolean = shortOrFullSha.matches(value)
    fun isSha256(value: String): Boolean = sha256.matches(value)
    fun isInstanceId(value: String): Boolean = instanceId.matches(value)
    fun isOperationId(value: String): Boolean = operationId.matches(value)
    fun isSafeGitRef(value: String): Boolean =
        safeRefCharacters.matches(value) &&
            !value.startsWith('-') &&
            !value.endsWith('/') &&
            !value.contains("..") &&
            !value.contains("@{") &&
            !value.contains("//")

    fun isHttpsUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)

    /** Validate all server settings in one place before they reach SQLite or Termux. */
    fun validate(config: InstanceServerConfig): ServerConfigValidation =
        ServerConfigValidator.validate(config)

    /** Returns a canonical public DNS name, or null for URLs, wildcards, IPs, and invalid names. */
    fun normalizeTlsDomain(value: String?): String? {
        val candidate = value?.trim()?.removeSuffix(".")?.lowercase() ?: return null
        if (candidate.isEmpty() || candidate.length > 253 || candidate.contains('*') ||
            candidate.contains('/') || candidate.contains(':') || candidate.contains('@') ||
            candidate.any(Char::isWhitespace) || candidate.count { it == '.' } < 1
        ) return null
        // A strict ASCII DNS name intentionally excludes IPv4, IPv6, and IDN input here.
        if (candidate.toByteArray().any { it.toInt() !in 0x21..0x7e }) return null
        val labels = candidate.split('.')
        if (labels.any { it.length !in 1..63 || !domainLabel.matches(it) }) return null
        if (candidate.split('.').all { it.all(Char::isDigit) }) return null
        return candidate
    }

    fun isValidTlsDomain(value: String?): Boolean = normalizeTlsDomain(value) != null

    /** Compatibility-friendly name used by UI and runtime callers. */
    fun isTlsDomain(value: String?): Boolean = isValidTlsDomain(value)

    /** Returns a canonical email address suitable for ACME registration, or null if invalid. */
    fun normalizeAcmeEmail(value: String?): String? {
        val candidate = value?.trim() ?: return null
        if (candidate.length > 254 || candidate.count { it == '@' } != 1) return null
        val at = candidate.lastIndexOf('@')
        val local = candidate.substring(0, at)
        val domain = normalizeTlsDomain(candidate.substring(at + 1)) ?: return null
        if (local.isEmpty() || local.length > 64 || local.startsWith('.') || local.endsWith('.') ||
            local.contains("..") || !emailLocal.matches(local)
        ) return null
        return "${local.lowercase()}@$domain"
    }

    fun isValidAcmeEmail(value: String?): Boolean = normalizeAcmeEmail(value) != null

    /** Compatibility-friendly name used by UI and runtime callers. */
    fun isEmail(value: String?): Boolean = isValidAcmeEmail(value)
}

data class ServerConfigValidation(val errors: List<String>) {
    val isValid: Boolean get() = errors.isEmpty()
}

object ServerConfigValidator {
    const val MIN_BODY_LIMIT_MB = 1
    const val MAX_BODY_LIMIT_MB = 1024
    const val MIN_PORT = 1024
    const val MAX_PORT = 65535
    const val MIN_SAVE_INTERVAL_SECONDS = 1
    const val MAX_SAVE_INTERVAL_SECONDS = 30
    const val MIN_SNAPSHOT_INTERVAL_MINUTES = 1
    const val MAX_SNAPSHOT_INTERVAL_MINUTES = 24 * 60
    const val MIN_SNAPSHOT_RETENTION_COUNT = 1
    const val MAX_SNAPSHOT_RETENTION_COUNT = 100
    const val MIN_SNAPSHOT_CAPACITY_MB = 10
    const val MAX_SNAPSHOT_CAPACITY_MB = 50 * 1024
    const val MIN_IMAGE_QUALITY = 1
    const val MAX_IMAGE_QUALITY = 100
    const val MIN_ACME_CHALLENGE_PORT = 1024
    const val MAX_ACME_CHALLENGE_PORT = 65535

    fun validate(config: InstanceServerConfig): ServerConfigValidation {
        val errors = buildList {
            if (config.bodyLimitMb !in MIN_BODY_LIMIT_MB..MAX_BODY_LIMIT_MB) {
                add("bodyLimitMb must be between $MIN_BODY_LIMIT_MB and $MAX_BODY_LIMIT_MB")
            }
            if (config.port !in MIN_PORT..MAX_PORT) add("port must be between $MIN_PORT and $MAX_PORT")
            if (config.saveIntervalSeconds !in MIN_SAVE_INTERVAL_SECONDS..MAX_SAVE_INTERVAL_SECONDS) {
                add("saveIntervalSeconds must be between $MIN_SAVE_INTERVAL_SECONDS and $MAX_SAVE_INTERVAL_SECONDS")
            }
            if (config.snapshotIntervalMinutes !in MIN_SNAPSHOT_INTERVAL_MINUTES..MAX_SNAPSHOT_INTERVAL_MINUTES) {
                add("snapshotIntervalMinutes must be between $MIN_SNAPSHOT_INTERVAL_MINUTES and $MAX_SNAPSHOT_INTERVAL_MINUTES")
            }
            if (config.snapshotRetentionCount !in MIN_SNAPSHOT_RETENTION_COUNT..MAX_SNAPSHOT_RETENTION_COUNT) {
                add("snapshotRetentionCount must be between $MIN_SNAPSHOT_RETENTION_COUNT and $MAX_SNAPSHOT_RETENTION_COUNT")
            }
            if (config.snapshotCapacityMb !in MIN_SNAPSHOT_CAPACITY_MB..MAX_SNAPSHOT_CAPACITY_MB) {
                add("snapshotCapacityMb must be between $MIN_SNAPSHOT_CAPACITY_MB and $MAX_SNAPSHOT_CAPACITY_MB")
            }
            if (config.imageQuality !in MIN_IMAGE_QUALITY..MAX_IMAGE_QUALITY) {
                add("imageQuality must be between $MIN_IMAGE_QUALITY and $MAX_IMAGE_QUALITY")
            }
            if (config.certificateMode == CertificateMode.LETS_ENCRYPT) {
                if (config.protocol != ServerProtocol.HTTPS) {
                    add("Let's Encrypt certificates require HTTPS protocol")
                }
                if (Validators.normalizeTlsDomain(config.tlsDomain) == null) {
                    add("tlsDomain must be a single public DNS name")
                }
                if (Validators.normalizeAcmeEmail(config.acmeEmail) == null) {
                    add("acmeEmail must be a valid email address")
                }
                if (config.acmeChallengePort !in MIN_ACME_CHALLENGE_PORT..MAX_ACME_CHALLENGE_PORT) {
                    add("acmeChallengePort must be between $MIN_ACME_CHALLENGE_PORT and $MAX_ACME_CHALLENGE_PORT")
                }
            }
            if (config.renewFailureCount < 0) add("renewFailureCount must not be negative")
        }
        return ServerConfigValidation(errors)
    }

    fun requireValid(config: InstanceServerConfig): InstanceServerConfig {
        val validation = validate(config)
        require(validation.isValid) { validation.errors.joinToString("; ") }
        return config
    }

    /** Convert seconds without an intermediate Int multiplication or overflow. */
    fun saveIntervalMillis(seconds: Int): Long {
        require(seconds in MIN_SAVE_INTERVAL_SECONDS..MAX_SAVE_INTERVAL_SECONDS) {
            "save interval must be between $MIN_SAVE_INTERVAL_SECONDS and $MAX_SAVE_INTERVAL_SECONDS seconds"
        }
        return Math.multiplyExact(seconds.toLong(), 1_000L)
    }

    fun saveIntervalMillisOrNull(seconds: Int): Long? =
        runCatching { saveIntervalMillis(seconds) }.getOrNull()
}

enum class CertificateHealth { UNKNOWN, HEALTHY, WARNING, CRITICAL, EXPIRED }

/**
 * Classifies an expiry timestamp using the product's renewal warning thresholds.
 * Timestamps are epoch milliseconds and null means that no certificate is known.
 */
fun certificateHealth(expiresAt: Long?, now: Long = System.currentTimeMillis()): CertificateHealth {
    val expiry = expiresAt ?: return CertificateHealth.UNKNOWN
    val remaining = expiry - now
    return when {
        remaining <= 0L -> CertificateHealth.EXPIRED
        remaining <= 7L * 24 * 60 * 60 * 1000 -> CertificateHealth.CRITICAL
        remaining <= 21L * 24 * 60 * 60 * 1000 -> CertificateHealth.WARNING
        else -> CertificateHealth.HEALTHY
    }
}

/** Pure URL and WebView-origin policy shared by UI and tests. */
object ServerUrlPolicy {
    fun url(config: InstanceServerConfig): String = url(
        protocol = config.protocol,
        port = config.port,
        certificateMode = config.certificateMode,
        tlsDomain = config.tlsDomain,
    )

    fun url(
        protocol: ServerProtocol,
        port: Int,
        certificateMode: CertificateMode = CertificateMode.MANUAL,
        tlsDomain: String? = null,
    ): String {
        val normalizedDomain = Validators.normalizeTlsDomain(tlsDomain)
        val useTlsDomain = protocol == ServerProtocol.HTTPS &&
            certificateMode == CertificateMode.LETS_ENCRYPT && normalizedDomain != null
        val host = if (useTlsDomain) normalizedDomain else "127.0.0.1"
        return "${protocol.name.lowercase()}://$host:$port/"
    }

    fun isAllowedOrigin(
        config: InstanceServerConfig,
        scheme: String,
        host: String,
        port: Int,
    ): Boolean {
        val expectedScheme = config.protocol.name.lowercase()
        if (!scheme.equals(expectedScheme, ignoreCase = true)) return false
        if (port != -1 && port != config.port) return false
        val normalizedDomain = Validators.normalizeTlsDomain(config.tlsDomain)
        val useTlsDomain = config.protocol == ServerProtocol.HTTPS &&
            config.certificateMode == CertificateMode.LETS_ENCRYPT && normalizedDomain != null
        return if (useTlsDomain) host.equals(normalizedDomain, ignoreCase = true)
        else host.equals("127.0.0.1", ignoreCase = true) || host.equals("localhost", ignoreCase = true)
    }
}

/** Pure convenience wrapper for callers that do not need to construct a config first. */
fun serverUrl(config: InstanceServerConfig?, fallbackPort: Int): String = config?.let {
    ServerUrlPolicy.url(it)
} ?: "http://127.0.0.1:$fallbackPort/"

fun isAllowedServerOrigin(
    config: InstanceServerConfig,
    scheme: String,
    host: String,
    port: Int,
): Boolean = ServerUrlPolicy.isAllowedOrigin(config, scheme, host, port)

object VersionComparator {
    fun compare(left: String, right: String): Int {
        val leftParts = normalize(left)
        val rightParts = normalize(right)
        val size = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until size) {
            val l = leftParts.getOrElse(index) { 0 }
            val r = rightParts.getOrElse(index) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    private fun normalize(value: String): List<Int> = value.trim().removePrefix("v").substringBefore('-')
        .split('.')
        .map { it.toIntOrNull() ?: 0 }
}

data class ApkMetadata(
    val downloadUrl: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val sha256: String?,
)

sealed interface ApkValidation {
    data object Verified : ApkValidation
    data class PartiallyVerified(val reason: String) : ApkValidation
    data class Failed(val reason: String) : ApkValidation
}

object ApkMetadataValidator {
    private val sha256 = Regex("^[0-9a-fA-F]{64}$")

    fun validate(metadata: ApkMetadata, expectedPackage: String): ApkValidation {
        if (!Validators.isHttpsUrl(metadata.downloadUrl)) return ApkValidation.Failed("APK URL must use HTTPS")
        if (metadata.packageName != expectedPackage) return ApkValidation.Failed("Unexpected Android package")
        if (metadata.versionCode <= 0) return ApkValidation.Failed("Invalid version code")
        val hash = metadata.sha256 ?: return ApkValidation.PartiallyVerified("No trusted SHA-256 metadata")
        if (!sha256.matches(hash)) return ApkValidation.Failed("Invalid SHA-256 metadata")
        return ApkValidation.Verified
    }
}
