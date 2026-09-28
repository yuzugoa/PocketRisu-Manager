package io.github.risumanager.diagnostic

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.risumanager.BuildConfig
import io.github.risumanager.app.AppContainer
import io.github.risumanager.domain.model.RuntimeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DiagnosticUiState(
    val loading: Boolean = false,
    val report: String? = null,
    val error: String? = null,
)

class DiagnosticViewModel(private val container: AppContainer) : ViewModel() {
    private val mutableState = MutableStateFlow(DiagnosticUiState())
    val state: StateFlow<DiagnosticUiState> = mutableState.asStateFlow()

    fun generate() {
        if (mutableState.value.loading) return
        viewModelScope.launch {
            mutableState.value = DiagnosticUiState(loading = true)
            runCatching { collectReport() }
                .onSuccess { mutableState.value = DiagnosticUiState(report = it) }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    mutableState.value = DiagnosticUiState(error = error.message ?: "The diagnostic report could not be generated.")
                }
        }
    }

    private suspend fun collectReport(): String = withContext(Dispatchers.IO) {
        val instances = container.instances.observeInstances().first()
        // PocketRisu is the primary supported target, so keep the Termux check useful even
        // before the first instance has been installed. Other installed runtimes are included.
        val runtimeTypes = (setOf(RuntimeType.TERMUX_NODE) + instances.map { it.runtimeType }).toSet()
        val environments = runtimeTypes.map { type ->
            runCatching { container.runtimes.get(type).checkEnvironment() }
                .getOrElse { exception ->
                    io.github.risumanager.domain.model.EnvironmentStatus(
                        state = io.github.risumanager.domain.model.EnvironmentState.TERMUX_ERROR,
                        message = exception.message ?: "Environment check failed.",
                    )
                }
        }
        val snapshots = instances.map { instance ->
            val runtime = container.runtimes.get(instance.runtimeType)
            DiagnosticInstance(
                instance = instance,
                config = runCatching { container.instances.getServerConfig(instance.instanceId) }.getOrNull(),
                runtime = runCatching { runtime.status(instance) }.getOrNull(),
                operations = runCatching { container.instances.operations(instance.instanceId) }.getOrDefault(emptyList()),
                rollbackEvents = runCatching { container.instances.rollbackEvents(instance.instanceId) }.getOrDefault(emptyList()),
            )
        }
        DiagnosticReportGenerator().generate(
            DiagnosticSnapshot(
                generatedAt = System.currentTimeMillis(),
                environment = DiagnosticEnvironment(
                    managerVersion = BuildConfig.VERSION_NAME,
                    androidRelease = Build.VERSION.RELEASE ?: "unknown",
                    androidSdk = Build.VERSION.SDK_INT,
                    environments = environments,
                ),
                instances = snapshots,
            )
        )
    }
}

class DiagnosticViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = DiagnosticViewModel(container) as T
}
