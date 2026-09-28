package io.github.risumanager.operation

import android.content.Context
import android.net.Uri
import io.github.risumanager.domain.model.OperationState
import io.github.risumanager.domain.model.ProgressSink
import io.github.risumanager.domain.model.RisuInstance
import io.github.risumanager.runtime.termux.HelperProtocolException
import io.github.risumanager.runtime.termux.HelperResponse
import io.github.risumanager.runtime.termux.TermuxHelperClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.net.Proxy
import java.util.concurrent.TimeUnit

data class BackupExportResult(
    val size: Long,
    val sha256: String,
    val formatVersion: Int,
)

class BackupException(val code: String, message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Coordinates a private Termux export with a streaming copy to a Storage Access
 * Framework document. No archive bytes cross the Termux command-result Binder.
 */
class BackupService(
    context: Context,
    private val helper: TermuxHelperClient,
    private val operationGate: InstanceOperationGate,
) {
    private val resolver = context.applicationContext.contentResolver
    private val loopbackClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    suspend fun export(
        instance: RisuInstance,
        operationId: String,
        destination: Uri,
        progress: ProgressSink,
    ): BackupExportResult = operationGate.withInstance(instance.instanceId) {
        if (destination.scheme != "content") {
            throw BackupException("INVALID_DESTINATION", "The selected backup destination is not a document URI.")
        }
        var ready: HelperResponse? = null
        try {
            helper.ensureInstalled()
            progress.report(OperationState.Running("Preparing", "Preparing a consistent PocketRisu snapshot", null))
            val dispatched = helper.dispatchBackup(operationId, instance.instanceId)
            if (!dispatched.ok) {
                throw BackupException(
                    dispatched.code ?: "BACKUP_DISPATCH_FAILED",
                    dispatched.message ?: "The Termux helper refused the backup request.",
                )
            }
            ready = awaitReady(operationId, progress)
            val transfer = requireTransfer(ready)
            progress.report(OperationState.Running("Saving", "Copying backup to the selected document", 0f))
            download(destination, transfer, progress)
            BackupExportResult(transfer.size, transfer.sha256, transfer.formatVersion)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: BackupException) {
            throw exception
        } catch (exception: HelperProtocolException) {
            throw BackupException(exception.code, exception.message ?: "The Termux backup helper failed.", exception)
        } catch (exception: Exception) {
            throw BackupException("BACKUP_FAILED", exception.message ?: "PocketRisu backup failed.", exception)
        } finally {
            val response = ready
            val token = response?.token
            if (token != null) {
                withContext(NonCancellable) {
                    runCatching { helper.cleanupBackup(operationId, instance.instanceId, token) }
                }
            }
        }
    }

    private suspend fun awaitReady(operationId: String, progress: ProgressSink): HelperResponse = try {
        withTimeout(PREPARE_TIMEOUT_MILLIS) {
            while (true) {
                val response = helper.operation(operationId)
                when (response.state) {
                    "QUEUED" -> progress.report(OperationState.Running("Queued", "Waiting for Termux", null))
                    "RUNNING" -> progress.report(
                        OperationState.Running(stageLabel(response.stage), response.detail, null),
                    )
                    "SUCCEEDED" -> return@withTimeout response
                    "FAILED" -> throw BackupException(
                        response.code ?: "BACKUP_EXPORT_FAILED",
                        response.message ?: "PocketRisu could not create the backup.",
                    )
                    else -> throw BackupException("INVALID_HELPER_STATE", "The Termux helper returned an unknown backup state.")
                }
                delay(1_000)
            }
            @Suppress("UNREACHABLE_CODE")
            throw BackupException("BACKUP_EXPORT_FAILED", "Backup preparation ended unexpectedly.")
        }
    } catch (exception: TimeoutCancellationException) {
        throw BackupException("BACKUP_PREPARE_TIMEOUT", "PocketRisu did not finish preparing the backup in time.", exception)
    }

    private fun requireTransfer(response: HelperResponse): TransferDescriptor {
        val port = response.port ?: throw invalidDescriptor()
        val token = response.token ?: throw invalidDescriptor()
        val size = response.size ?: throw invalidDescriptor()
        val sha256 = response.sha256 ?: throw invalidDescriptor()
        val formatVersion = response.formatVersion ?: throw invalidDescriptor()
        if (port !in 1024..65535 || !TOKEN.matches(token) || size <= 0L || size > MAX_BACKUP_BYTES ||
            !SHA256.matches(sha256) || formatVersion != SUPPORTED_FORMAT_VERSION
        ) {
            throw invalidDescriptor()
        }
        return TransferDescriptor(port, token, size, sha256, formatVersion)
    }

    private fun invalidDescriptor() = BackupException(
        "INVALID_BACKUP_DESCRIPTOR",
        "The Termux helper returned invalid backup transfer metadata.",
    )

    private suspend fun download(destination: Uri, transfer: TransferDescriptor, progress: ProgressSink) {
        val request = Request.Builder()
            .url("http://127.0.0.1:${transfer.port}/backup")
            .header("Authorization", "Bearer ${transfer.token}")
            .get()
            .build()
        withContext(Dispatchers.IO) {
            loopbackClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw BackupException("BACKUP_TRANSFER_FAILED", "The private backup transfer returned HTTP ${response.code}.")
                }
                if (response.header("Content-Length")?.toLongOrNull() != transfer.size ||
                    response.header("X-Risu-SHA256") != transfer.sha256
                ) {
                    throw BackupException("BACKUP_TRANSFER_METADATA_MISMATCH", "The backup transfer metadata did not match the prepared file.")
                }
                val body = response.body ?: throw BackupException("EMPTY_BACKUP_TRANSFER", "The backup transfer returned no data.")
                val output = resolver.openOutputStream(destination, "wt")
                    ?: throw BackupException("DESTINATION_OPEN_FAILED", "The selected document could not be opened for writing.")
                body.byteStream().use { input ->
                    output.buffered().use { target ->
                        BackupStreamVerifier.copyAndVerify(
                            input = input,
                            output = target,
                            expectedSize = transfer.size,
                            expectedSha256 = transfer.sha256,
                        ) { copied ->
                            currentCoroutineContext().ensureActive()
                            val ratio = (copied.toDouble() / transfer.size.toDouble()).coerceIn(0.0, 1.0).toFloat()
                            progress.report(OperationState.Running("Saving", "Saved ${formatBytes(copied)} of ${formatBytes(transfer.size)}", ratio))
                        }
                    }
                }
            }
        }
    }

    private fun stageLabel(stage: String?): String = when (stage) {
        "STOPPING" -> "Stopping PocketRisu"
        "EXPORTING" -> "Creating backup"
        "STARTING" -> "Restarting PocketRisu"
        "SERVING" -> "Preparing transfer"
        else -> stage?.replace('_', ' ')?.lowercase()?.replaceFirstChar(Char::uppercase) ?: "Preparing"
    }

    private fun formatBytes(value: Long): String = when {
        value >= 1024L * 1024L * 1024L -> "%.1f GiB".format(value / (1024.0 * 1024.0 * 1024.0))
        value >= 1024L * 1024L -> "%.1f MiB".format(value / (1024.0 * 1024.0))
        value >= 1024L -> "%.1f KiB".format(value / 1024.0)
        else -> "$value B"
    }

    private data class TransferDescriptor(
        val port: Int,
        val token: String,
        val size: Long,
        val sha256: String,
        val formatVersion: Int,
    )

    private companion object {
        const val PREPARE_TIMEOUT_MILLIS = 60 * 60 * 1000L
        const val SUPPORTED_FORMAT_VERSION = 1
        const val MAX_BACKUP_BYTES = 512L * 1024L * 1024L * 1024L
        val TOKEN = Regex("^[0-9a-f]{64}$")
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

internal object BackupStreamVerifier {
    suspend fun copyAndVerify(
        input: InputStream,
        output: OutputStream,
        expectedSize: Long,
        expectedSha256: String,
        onProgress: suspend (Long) -> Unit = {},
    ) {
        require(expectedSize >= 0)
        require(Regex("^[0-9a-f]{64}$").matches(expectedSha256))
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        var copied = 0L
        var reportedAt = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            copied += read
            if (copied > expectedSize) {
                throw BackupException("BACKUP_SIZE_MISMATCH", "The backup transfer exceeded its declared size.")
            }
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
            if (copied - reportedAt >= 1024L * 1024L || copied == expectedSize) {
                onProgress(copied)
                reportedAt = copied
            }
        }
        output.flush()
        if (copied != expectedSize) {
            throw BackupException("BACKUP_SIZE_MISMATCH", "The backup transfer ended at $copied bytes; expected $expectedSize.")
        }
        val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        if (actualSha256 != expectedSha256) {
            throw BackupException("BACKUP_HASH_MISMATCH", "The saved backup failed SHA-256 verification.")
        }
    }
}
