package io.github.risumanager.runtime.termux

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class TermuxCommand(
    val executable: String,
    val arguments: List<String> = emptyList(),
    val stdin: String? = null,
    val workingDirectory: String = TermuxContract.HOME,
    val label: String,
    val description: String,
    val timeoutMillis: Long = 30_000,
)

data class TermuxCommandResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val internalError: Int,
    val internalErrorMessage: String?,
)

sealed class TermuxCommandException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotInstalled : TermuxCommandException("Termux is not installed")
    class PermissionDenied(cause: Throwable? = null) : TermuxCommandException("RUN_COMMAND permission is not granted", cause)
    class TimedOut : TermuxCommandException("Termux did not return a command result")
    class StartFailed(cause: Throwable) : TermuxCommandException("Termux command service could not be started", cause)
}

class TermuxCommandClient(private val context: Context) {
    fun isInstalled(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(TermuxContract.PACKAGE, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(TermuxContract.PACKAGE, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, TermuxContract.PERMISSION) == PackageManager.PERMISSION_GRANTED

    suspend fun execute(command: TermuxCommand): TermuxCommandResult {
        if (!isInstalled()) throw TermuxCommandException.NotInstalled()
        if (!hasPermission()) throw TermuxCommandException.PermissionDenied()

        val requestId = UUID.randomUUID().toString()
        val deferred = TermuxResultRouter.register(requestId)
        val callbackIntent = Intent(context, TermuxResultReceiver::class.java).apply {
            action = TermuxResultReceiver.ACTION_RESULT
            data = Uri.Builder().scheme("risumanager").authority("termux-result").appendPath(requestId).build()
            putExtra(TermuxResultReceiver.EXTRA_REQUEST_ID, requestId)
        }
        val pendingFlags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val callback = PendingIntent.getBroadcast(context, REQUEST_CODES.incrementAndGet(), callbackIntent, pendingFlags)
        val intent = Intent(TermuxContract.ACTION_RUN_COMMAND).apply {
            setClassName(TermuxContract.PACKAGE, TermuxContract.SERVICE)
            putExtra(TermuxContract.EXTRA_COMMAND_PATH, command.executable)
            putExtra(TermuxContract.EXTRA_ARGUMENTS, command.arguments.toTypedArray())
            putExtra(TermuxContract.EXTRA_WORKDIR, command.workingDirectory)
            putExtra(TermuxContract.EXTRA_BACKGROUND, true)
            putExtra(TermuxContract.EXTRA_COMMAND_LABEL, command.label)
            putExtra(TermuxContract.EXTRA_COMMAND_DESCRIPTION, command.description)
            putExtra(TermuxContract.EXTRA_COMMAND_HELP, "https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent")
            putExtra(TermuxContract.EXTRA_PENDING_INTENT, callback)
            command.stdin?.let { putExtra(TermuxContract.EXTRA_STDIN, it) }
        }

        try {
            // Termux's RUN_COMMAND receiver manages its own foreground handoff.
            // Starting this short-lived receiver as a foreground service leaves
            // Android waiting for a promotion after Termux has already stopped it.
            context.startService(intent)
            return withTimeout(command.timeoutMillis) { deferred.await() }
        } catch (exception: SecurityException) {
            throw TermuxCommandException.PermissionDenied(exception)
        } catch (_: TimeoutCancellationException) {
            throw TermuxCommandException.TimedOut()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: TermuxCommandException) {
            throw exception
        } catch (exception: Exception) {
            throw TermuxCommandException.StartFailed(exception)
        } finally {
            TermuxResultRouter.remove(requestId)
            callback.cancel()
        }
    }

    private companion object {
        val REQUEST_CODES = AtomicInteger(10_000)
    }
}

internal object TermuxResultRouter {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<TermuxCommandResult>>()

    fun register(requestId: String): CompletableDeferred<TermuxCommandResult> =
        CompletableDeferred<TermuxCommandResult>().also { pending[requestId] = it }

    fun complete(requestId: String, result: TermuxCommandResult) {
        pending.remove(requestId)?.complete(result)
    }

    fun remove(requestId: String) {
        pending.remove(requestId)?.cancel()
    }
}
