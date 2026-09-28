package io.github.risumanager.app

import android.content.Context
import io.github.risumanager.data.github.GitHubSourceAdapter
import io.github.risumanager.data.local.RisuDatabase
import io.github.risumanager.data.repository.InstanceRepository
import io.github.risumanager.data.repository.SqliteInstanceRepository
import io.github.risumanager.data.settings.UserPreferences
import io.github.risumanager.domain.runtime.RuntimeRegistry
import io.github.risumanager.operation.OperationCoordinator
import io.github.risumanager.operation.InstanceOperationGate
import io.github.risumanager.operation.BackupService
import io.github.risumanager.operation.CloneCoordinator
import io.github.risumanager.operation.CloneService
import io.github.risumanager.operation.UpdateCoordinator
import io.github.risumanager.operation.UpdateService
import io.github.risumanager.operation.RollbackCoordinator
import io.github.risumanager.operation.RollbackService
import io.github.risumanager.runtime.nativeapk.NativeApkRuntimeAdapter
import io.github.risumanager.runtime.termux.TermuxCommandClient
import io.github.risumanager.runtime.termux.TermuxHelperClient
import io.github.risumanager.runtime.termux.TermuxRuntimeAdapter
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    val applicationContext: Context = context.applicationContext
    // Settings transactions must finish even if their navigation entry is removed.
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val appContext = applicationContext
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    private val database = RisuDatabase(appContext)
    val instances: InstanceRepository = SqliteInstanceRepository(database)
    val preferences = UserPreferences(appContext)
    val sources = GitHubSourceAdapter(httpClient, json)

    val termuxCommandClient = TermuxCommandClient(appContext)
    private val termuxHelper = TermuxHelperClient(appContext, termuxCommandClient, json)
    private val instanceOperationGate = InstanceOperationGate()
    val termuxRuntime = TermuxRuntimeAdapter(termuxCommandClient, termuxHelper, instanceOperationGate)
    val backups = BackupService(appContext, termuxHelper, instanceOperationGate)
    val cloneService = CloneService(termuxHelper, instances, instanceOperationGate)
    val clones = CloneCoordinator(appContext, instances)
    val updateService = UpdateService(termuxHelper, instances, instanceOperationGate)
    val updates = UpdateCoordinator(appContext, instances, updateService)
    val rollbackService = RollbackService(termuxHelper, instances, instanceOperationGate)
    val rollbacks = RollbackCoordinator(appContext, instances, rollbackService, clones)
    val nativeApkRuntime = NativeApkRuntimeAdapter()
    val runtimes = RuntimeRegistry(setOf(termuxRuntime, nativeApkRuntime))
    val operations = OperationCoordinator(appContext)

    init {
        applicationScope.launch { runCatching { rollbacks.reconcilePending() } }
    }
}
