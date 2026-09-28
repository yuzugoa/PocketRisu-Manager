package io.github.risumanager.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import android.webkit.DownloadListener
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.risumanager.app.AppContainer
import io.github.risumanager.diagnostic.DiagnosticReportScreen
import io.github.risumanager.diagnostic.DiagnosticViewModel
import io.github.risumanager.diagnostic.DiagnosticViewModelFactory
import io.github.risumanager.data.settings.ThemeMode
import io.github.risumanager.data.settings.DEFAULT_UPDATE_RECOVERY_RETENTION_DAYS
import io.github.risumanager.domain.model.BuiltInVariants
import io.github.risumanager.domain.model.BindScope
import io.github.risumanager.domain.model.Capability
import io.github.risumanager.domain.model.CertificateMode
import io.github.risumanager.domain.model.EnvironmentState
import io.github.risumanager.domain.model.InstanceServerConfig
import io.github.risumanager.domain.model.OperationState
import io.github.risumanager.domain.model.OperationLiveness
import io.github.risumanager.domain.model.RecoveryPoint
import io.github.risumanager.domain.model.RisuInstance
import io.github.risumanager.domain.model.RuntimeState
import io.github.risumanager.domain.model.RuntimeType
import io.github.risumanager.domain.model.ServerProtocol
import io.github.risumanager.domain.validation.CertificateHealth
import io.github.risumanager.domain.validation.certificateHealth
import io.github.risumanager.domain.validation.isAllowedServerOrigin
import io.github.risumanager.domain.validation.serverUrl as validatedServerUrl
import io.github.risumanager.runtime.termux.TermuxContract
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class TopLevelDestination(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val topLevelDestinations = listOf(
    TopLevelDestination("home", "Home", Icons.Default.Home),
    TopLevelDestination("install", "Install", Icons.Default.AddCircle),
    TopLevelDestination("settings", "Settings", Icons.Default.Settings),
)

private data class InstanceRouteState(
    val instance: RisuInstance?,
    val config: InstanceServerConfig?,
)

@Composable
fun RisuManagerApp(container: AppContainer, viewModelFactory: AppViewModelFactory) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val savedTopLevelTab by container.preferences.selectedTopLevelTab.collectAsStateWithLifecycle(initialValue = "home")
    val showBottomBar = currentRoute in topLevelDestinations.map { it.route }

    LaunchedEffect(savedTopLevelTab) {
        if (currentRoute == "home" && savedTopLevelTab != "home") {
            navController.navigate(savedTopLevelTab) {
                popUpTo("home") { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    fun selectTopLevelTab(route: String) {
        container.applicationScope.launch { container.preferences.setSelectedTopLevelTab(route) }
        navController.navigate(route) {
            popUpTo("home") { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    topLevelDestinations.forEach { destination ->
                        val selected = backStackEntry?.destination?.hierarchy?.any { it.route == destination.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                selectTopLevelTab(destination.route)
                            },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { contentPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(contentPadding),
        ) {
            composable("home") {
                val vm: HomeViewModel = viewModel(factory = viewModelFactory)
                val state by vm.state.collectAsStateWithLifecycle()
                HomeScreen(
                    state = state,
                    onRefresh = vm::refresh,
                    onInstall = { selectTopLevelTab("install") },
                    onStart = vm::start,
                    onStop = vm::stop,
                    onRestart = vm::restart,
                    onClone = vm::clone,
                    onCheckUpdate = vm::prepareUpdate,
                    onConfirmUpdate = vm::confirmUpdate,
                    onDismissUpdate = vm::dismissUpdate,
                    onBackup = vm::backup,
                    onBackupDestinationError = vm::destinationError,
                    onOpenInternal = { navController.navigate("web/${it.instanceId}") },
                    onOpenExternal = {
                        val config = state.configs[it.instanceId]
                        openUrl(context, validatedServerUrl(config, it.port))
                    },
                    onLogs = { navController.navigate("logs/${it.instanceId}") },
                    onServerSettings = { navController.navigate("server-config/${it.instanceId}") },
                    onRecoveryPoints = { navController.navigate("recovery-points/${it.instanceId}") },
                    onMessageShown = vm::clearMessage,
                )
            }
            composable("install") { entry ->
                val vm: InstallViewModel = viewModel(factory = viewModelFactory)
                val state by vm.state.collectAsStateWithLifecycle()
                // Navigation Compose can recreate this destination after its lifecycle has
                // already reached RESUMED. Refresh on re-entry instead of waiting for an
                // ON_RESUME event that may already have been delivered.
                LaunchedEffect(entry) { vm.checkEnvironment() }
                InstallScreen(
                    state = state,
                    onSelectVariant = vm::selectVariant,
                    onSelectChannel = vm::selectChannel,
                    onCommitChange = vm::setRequestedCommit,
                    onInstall = vm::install,
                    onRefreshLog = vm::refreshInstallLog,
                    onTermuxSetup = { navController.navigate("termux") },
                )
            }
            composable("settings") {
                val vm: SettingsViewModel = viewModel(factory = viewModelFactory)
                val theme by vm.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
                val updateRetentionDays by vm.updateRecoveryRetentionDays.collectAsStateWithLifecycle(
                    initialValue = DEFAULT_UPDATE_RECOVERY_RETENTION_DAYS,
                )
                SettingsScreen(
                    themeMode = theme,
                    onThemeMode = vm::setThemeMode,
                    updateRetentionDays = updateRetentionDays,
                    onUpdateRetentionDays = vm::setUpdateRecoveryRetentionDays,
                    onTermux = { navController.navigate("termux") },
                    onDiagnostic = { navController.navigate("diagnostic") },
                )
            }
            composable("diagnostic") {
                val vm: DiagnosticViewModel = viewModel(factory = DiagnosticViewModelFactory(container))
                val state by vm.state.collectAsStateWithLifecycle()
                DiagnosticReportScreen(
                    state = state,
                    onBack = navController::popBackStack,
                    onGenerate = vm::generate,
                    onShare = { report -> shareDiagnosticReport(context, report) },
                )
            }
            composable("termux") {
                val vm: TermuxIntegrationViewModel = viewModel(factory = viewModelFactory)
                val state by vm.state.collectAsStateWithLifecycle()
                TermuxIntegrationScreen(state = state, onBack = navController::popBackStack, onCheck = vm::check)
            }
            composable("logs/{instanceId}") { entry ->
                val instanceId = entry.arguments?.getString("instanceId") ?: return@composable
                val vm: LogsViewModel = viewModel(factory = LogsViewModelFactory(container, instanceId))
                val state by vm.state.collectAsStateWithLifecycle()
                LogsScreen(state = state, onBack = navController::popBackStack, onRefresh = vm::refresh, onTogglePause = vm::togglePause)
            }
            composable("server-config/{instanceId}") { entry ->
                val instanceId = entry.arguments?.getString("instanceId") ?: return@composable
                val vm: ServerConfigViewModel = viewModel(factory = ServerConfigViewModelFactory(container, instanceId))
                val state by vm.state.collectAsStateWithLifecycle()
                ServerConfigScreen(
                    state = state,
                    onBack = navController::popBackStack,
                    onPort = vm::setPort,
                    onBindScope = vm::setBindScope,
                    onCertificateMode = vm::setCertificateMode,
                    onTlsDomain = vm::setTlsDomain,
                    onAcmeEmail = vm::setAcmeEmail,
                    onAcmeChallengePort = vm::setAcmeChallengePort,
                    onAcmePreflight = vm::checkAcmePreflight,
                    onAcmeIssue = vm::issueAndEnableHttps,
                    onAcmeRenew = vm::renewCertificateNow,
                    onAcmeDisable = vm::disableManagedHttps,
                    onBodyLimit = vm::setBodyLimit,
                    onSaveInterval = vm::setSaveInterval,
                    onSnapshotInterval = vm::setSnapshotInterval,
                    onSnapshotRetention = vm::setSnapshotRetention,
                    onSnapshotCapacity = vm::setSnapshotCapacity,
                    onImageQuality = vm::setImageQuality,
                    onOptimizeImages = { quality -> navController.navigate("image-optimization/$instanceId/$quality") },
                    onSave = vm::save,
                    onMessageShown = vm::clearMessage,
                )
            }
            composable("recovery-points/{instanceId}") { entry ->
                val instanceId = entry.arguments?.getString("instanceId") ?: return@composable
                val vm: RecoveryPointsViewModel = viewModel(
                    factory = RecoveryPointsViewModelFactory(container, instanceId),
                )
                val state by vm.state.collectAsStateWithLifecycle()
                RecoveryPointsScreen(
                    state = state,
                    onBack = navController::popBackStack,
                    onRefresh = vm::refresh,
                    onReviewRollback = vm::prepareRollback,
                    onDismissRollback = vm::dismissRollback,
                    onConfirmRollback = vm::confirmRollback,
                    onSetPinned = vm::setPinned,
                    onDelete = vm::delete,
                    onClone = vm::clone,
                    onMessageShown = vm::clearMessage,
                )
            }
            composable("web/{instanceId}") { entry ->
                val instanceId = entry.arguments?.getString("instanceId") ?: return@composable
                val routeState by produceState<InstanceRouteState?>(initialValue = null, instanceId) {
                    val instance = container.instances.get(instanceId)
                    val stored = container.instances.getServerConfig(instanceId)
                    val config = if (instance?.runtimeType == RuntimeType.TERMUX_NODE) {
                        runCatching { container.termuxRuntime.readServerConfig(instanceId).desired }.getOrNull()
                            ?.withTlsStateFrom(stored) ?: stored
                    } else stored
                    value = InstanceRouteState(instance, config)
                }
                routeState?.instance?.let { InternalWebViewScreen(it, routeState?.config, navController::popBackStack) }
                    ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            composable("image-optimization/{instanceId}/{quality}") { entry ->
                val instanceId = entry.arguments?.getString("instanceId") ?: return@composable
                val quality = entry.arguments?.getString("quality")?.toIntOrNull()?.takeIf { it in 1..100 } ?: return@composable
                val routeState by produceState<InstanceRouteState?>(initialValue = null, instanceId) {
                    val instance = container.instances.get(instanceId)
                    val stored = container.instances.getServerConfig(instanceId)
                    val config = if (instance?.runtimeType == RuntimeType.TERMUX_NODE) {
                        runCatching { container.termuxRuntime.readServerConfig(instanceId).desired }.getOrNull()
                            ?.withTlsStateFrom(stored) ?: stored
                    } else stored
                    value = InstanceRouteState(instance, config)
                }
                routeState?.instance?.let { ImageOptimizationScreen(it, routeState?.config, quality, navController::popBackStack) }
                    ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    state: HomeUiState,
    onRefresh: () -> Unit,
    onInstall: () -> Unit,
    onStart: (RisuInstance) -> Unit,
    onStop: (RisuInstance) -> Unit,
    onRestart: (RisuInstance) -> Unit,
    onClone: (RisuInstance, String) -> Unit,
    onCheckUpdate: (RisuInstance) -> Unit,
    onConfirmUpdate: () -> Unit,
    onDismissUpdate: () -> Unit,
    onBackup: (RisuInstance, Uri) -> Unit,
    onBackupDestinationError: (String) -> Unit,
    onOpenInternal: (RisuInstance) -> Unit,
    onOpenExternal: (RisuInstance) -> Unit,
    onLogs: (RisuInstance) -> Unit,
    onServerSettings: (RisuInstance) -> Unit,
    onRecoveryPoints: (RisuInstance) -> Unit,
    onMessageShown: () -> Unit,
) {
    val context = LocalContext.current
    var pendingBackup by remember { mutableStateOf<RisuInstance?>(null) }
    var pendingClone by remember { mutableStateOf<RisuInstance?>(null) }
    var cloneName by remember { mutableStateOf("") }
    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val instance = pendingBackup
        pendingBackup = null
        if (uri != null && instance != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                onBackup(instance, uri)
            } catch (_: SecurityException) {
                onBackupDestinationError("The selected document provider did not grant persistent write access. Choose another location.")
            }
        }
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); onMessageShown() }
    }
    pendingClone?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingClone = null },
            title = { Text("Clone PocketRisu") },
            text = {
                Column {
                    OutlinedTextField(
                        value = cloneName,
                        onValueChange = { cloneName = it },
                        label = { Text("Clone name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("The source will be stopped while a consistent snapshot is copied, then returned to its previous running or stopped state.")
                    Spacer(Modifier.height(8.dp))
                    Text("The clone is independent, starts stopped, receives an automatic port, and defaults to local HTTP access. Public hostname, TLS, and certificate renewal ownership are not copied.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClone(source, cloneName)
                        pendingClone = null
                    },
                    enabled = cloneName.trim().isNotEmpty() && cloneName.trim().length <= 80,
                ) { Text("Clone") }
            },
            dismissButton = { TextButton(onClick = { pendingClone = null }) { Text("Cancel") } },
        )
    }
    state.updateConfirmation?.let { confirmation ->
        val estimate = confirmation.estimate
        AlertDialog(
            onDismissRequest = onDismissUpdate,
            title = { Text("Confirm PocketRisu update") },
            text = {
                Column {
                    DetailRow("Installed version", confirmation.instance.installedVersion ?: "Unknown")
                    DetailRow("Installed commit", confirmation.instance.installedRevision.shortRevision())
                    DetailRow("Target version", confirmation.target.displayVersion ?: confirmation.target.releaseName ?: "Commit")
                    DetailRow("Target commit", confirmation.target.sha.shortRevision())
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    DetailRow("Estimated recovery size", formatBytes(estimate.estimatedSizeBytes))
                    DetailRow("Required free space", formatBytes(estimate.requiredBytes))
                    DetailRow("Available space", formatBytes(estimate.availableBytes))
                    DetailRow("Retention", "${confirmation.retentionDays} days")
                    Spacer(Modifier.height(8.dp))
                    Text("A complete, verified recovery point is created before the installed instance is changed.")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Manager recovery points are private internal backups and can contain credentials. " +
                            "Low disk space never deletes an existing recovery point or permits this update without a verified backup.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!estimate.hasEnoughSpace) {
                        Spacer(Modifier.height(8.dp))
                        Text("Not enough free space. The update cannot start.", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(onClick = onConfirmUpdate, enabled = estimate.hasEnoughSpace) {
                    Text("Create recovery point and update")
                }
            },
            dismissButton = { TextButton(onClick = onDismissUpdate) { Text("Cancel") } },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Risu Manager") },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !state.refreshing) {
                        if (state.refreshing) CircularProgressIndicator(Modifier.padding(10.dp))
                        else Icon(Icons.Default.Refresh, contentDescription = "Refresh status")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.instances.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No Risu instance is installed", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text("PocketRisu continues to run inside Termux. Risu Manager controls it without embedding Node.js.")
                Spacer(Modifier.height(24.dp))
                Button(onClick = onInstall) { Text("Install Risu") }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.instances, key = { it.instanceId }) { instance ->
                    InstanceCard(
                        instance = instance,
                        status = state.statuses[instance.instanceId]?.state ?: instance.lastKnownStatus,
                        busy = state.busyInstanceId == instance.instanceId,
                        onStart = { onStart(instance) },
                        onStop = { onStop(instance) },
                        onRestart = { onRestart(instance) },
                        onClone = {
                            cloneName = "${instance.displayName ?: "PocketRisu"} copy"
                            pendingClone = instance
                        },
                        onUpdate = { onCheckUpdate(instance) },
                        checkingUpdate = state.updateCheckInstanceId == instance.instanceId,
                        onBackup = {
                            pendingBackup = instance
                            createBackup.launch("PocketRisu-${instance.instanceId}-${System.currentTimeMillis()}.bin")
                        },
                        backupOperation = state.backupOperation.takeIf { state.busyInstanceId == instance.instanceId },
                        cloneOperation = state.cloneOperation.takeIf { state.busyInstanceId == instance.instanceId },
                        cloneTargetPort = state.cloneTargetPort.takeIf { state.busyInstanceId == instance.instanceId },
                        updateOperation = state.updateOperation.takeIf { state.busyInstanceId == instance.instanceId },
                        rollbackOperation = state.rollbackOperation.takeIf { state.busyInstanceId == instance.instanceId },
                        recoverySummary = state.recoverySummaries[instance.instanceId],
                        onOpenInternal = { onOpenInternal(instance) },
                        onOpenExternal = { onOpenExternal(instance) },
                        onLogs = { onLogs(instance) },
                        onServerSettings = { onServerSettings(instance) },
                        onRecoveryPoints = { onRecoveryPoints(instance) },
                    )
                }
            }
        }
    }
}

@Composable
private fun InstanceCard(
    instance: RisuInstance,
    status: RuntimeState,
    busy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onClone: () -> Unit,
    onUpdate: () -> Unit,
    checkingUpdate: Boolean,
    onBackup: () -> Unit,
    backupOperation: OperationState?,
    cloneOperation: OperationState?,
    cloneTargetPort: Int?,
    updateOperation: OperationState?,
    rollbackOperation: OperationState?,
    recoverySummary: RecoverySummary?,
    onOpenInternal: () -> Unit,
    onOpenExternal: () -> Unit,
    onLogs: () -> Unit,
    onServerSettings: () -> Unit,
    onRecoveryPoints: () -> Unit,
) {
    val variant = BuiltInVariants.find(instance.variantId)
    ElevatedStatusCard(status = status) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(instance.displayName ?: variant?.displayName ?: instance.variantId, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(instance.repository, style = MaterialTheme.typography.bodySmall)
            }
            StatusText(status)
        }
        Spacer(Modifier.height(16.dp))
        DetailRow("Runtime", if (instance.runtimeType == RuntimeType.TERMUX_NODE) "Termux + Node.js" else "Native APK")
        DetailRow("Channel", instance.channelId)
        DetailRow("Installed", instance.installedVersion ?: instance.installedRevision.shortRevision())
        DetailRow("Latest", instance.latestRevision.shortRevision())
        DetailRow("Port", instance.port.toString())
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpenInternal, enabled = status == RuntimeState.RUNNING && !busy) { Text("Open") }
            IconButton(onClick = onOpenExternal, enabled = status == RuntimeState.RUNNING && !busy) {
                Icon(Icons.Default.OpenInBrowser, contentDescription = "Open in browser")
            }
            IconButton(onClick = onLogs) { Icon(Icons.Default.Description, contentDescription = "Logs") }
            IconButton(onClick = onServerSettings) { Icon(Icons.Default.Settings, contentDescription = "Server settings") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status == RuntimeState.STOPPED || status == RuntimeState.FAILED || status == RuntimeState.UNKNOWN) {
                OutlinedButton(onClick = onStart, enabled = !busy) { Icon(Icons.Default.PlayArrow, null); Text("Start") }
            } else {
                OutlinedButton(onClick = onRestart, enabled = !busy) { Icon(Icons.Default.Refresh, null); Text("Restart") }
                OutlinedButton(onClick = onStop, enabled = !busy) { Icon(Icons.Default.Stop, null); Text("Stop") }
            }
            if (busy) CircularProgressIndicator(Modifier.padding(8.dp))
        }
        if (Capability.BACKUP in variant?.termuxProfile?.capabilities.orEmpty()) {
            OutlinedButton(onClick = onBackup, enabled = !busy && instance.runtimeType == RuntimeType.TERMUX_NODE) {
                Icon(Icons.Default.Archive, contentDescription = null)
                Text("Backup (.bin)")
            }
        }
        if (instance.variantId == BuiltInVariants.pocketRisu.id && instance.runtimeType == RuntimeType.TERMUX_NODE) {
            OutlinedButton(onClick = onClone, enabled = !busy) {
                Icon(Icons.Default.AddCircle, contentDescription = null)
                Text("Clone")
            }
            OutlinedButton(onClick = onUpdate, enabled = !busy && !checkingUpdate) {
                if (checkingUpdate) CircularProgressIndicator(Modifier.padding(end = 8.dp))
                else Icon(Icons.Default.Refresh, contentDescription = null)
                Text(if (checkingUpdate) "Checking update…" else "Update")
            }
            OutlinedButton(onClick = onRecoveryPoints, enabled = !busy) {
                Icon(Icons.Default.Archive, contentDescription = null)
                Text("Recovery points")
            }
        }
        if (backupOperation is OperationState.Running) {
            Spacer(Modifier.height(8.dp))
            backupOperation.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(backupOperation.detail ?: backupOperation.stage, style = MaterialTheme.typography.bodySmall)
        }
        if (cloneOperation is OperationState.Running) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(cloneOperation.detail ?: cloneOperation.stage, style = MaterialTheme.typography.bodySmall)
            cloneTargetPort?.let { Text("Reserved port: $it", style = MaterialTheme.typography.bodySmall) }
        }
        if (updateOperation is OperationState.Running) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(updateOperation.detail ?: updateOperation.stage, style = MaterialTheme.typography.bodySmall)
        }
        if (rollbackOperation is OperationState.Running) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(rollbackOperation.detail ?: rollbackOperation.stage, style = MaterialTheme.typography.bodySmall)
        }
        recoverySummary?.let { recovery ->
            Spacer(Modifier.height(8.dp))
            val label = when (recovery.status) {
                RecoveryRetentionStatus.VALID -> recovery.expiresAt?.let { "Recovery retained until ${formatTimestamp(it)}" }
                    ?: "Recovery retained"
                RecoveryRetentionStatus.EXPIRED_CLEANUP_PENDING -> "Recovery expired — cleanup pending"
                RecoveryRetentionStatus.PINNED -> "Recovery pinned"
                RecoveryRetentionStatus.IN_USE -> "Recovery in use"
                RecoveryRetentionStatus.RECOVERY_REQUIRED -> "Recovery required — automatic restoration did not complete"
            }
            Text(label, style = MaterialTheme.typography.bodySmall,
                color = if (recovery.status == RecoveryRetentionStatus.RECOVERY_REQUIRED) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecoveryPointsScreen(
    state: RecoveryPointsUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onReviewRollback: (RecoveryPoint) -> Unit,
    onDismissRollback: () -> Unit,
    onConfirmRollback: () -> Unit,
    onSetPinned: (RecoveryPoint, Boolean) -> Unit,
    onDelete: (RecoveryPoint) -> Unit,
    onClone: (RecoveryPoint, String) -> Unit,
    onMessageShown: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<RecoveryPoint?>(null) }
    var pendingClone by remember { mutableStateOf<RecoveryPoint?>(null) }
    var cloneName by remember { mutableStateOf("") }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); onMessageShown() }
    }

    state.rollbackConfirmation?.let { confirmation ->
        val point = confirmation.point
        val preview = confirmation.preview
        val enoughSpace = preview.requiredBytes == null || preview.availableBytes == null ||
            preview.availableBytes >= preview.requiredBytes
        AlertDialog(
            onDismissRequest = onDismissRollback,
            title = { Text("Restore this point in time?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DetailRow("Restore point", formatTimestamp(preview.createdAt))
                    DetailRow("Current version", preview.currentVersion ?: "Unknown")
                    DetailRow("Restored version", preview.targetVersion ?: "Unknown")
                    DetailRow("Current commit", preview.currentRevision.shortRevision())
                    DetailRow("Restored commit", preview.targetRevision.shortRevision())
                    DetailRow("Recovery size", formatBytes(preview.sizeBytes))
                    preview.requiredBytes?.let { DetailRow("Required free space", formatBytes(it)) }
                    preview.availableBytes?.let { DetailRow("Available free space", formatBytes(it)) }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("What changes", style = MaterialTheme.typography.titleMedium)
                    DetailRow(
                        "Server files",
                        recoveryDifferenceLabel(
                            preview.currentRevision?.equals(preview.targetRevision, ignoreCase = true)?.not(),
                        ),
                    )
                    DetailRow("Conversations, saves, assets", recoveryDifferenceLabel(preview.dataChanged))
                    DetailRow("Settings", recoveryDifferenceLabel(preview.configurationChanged))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Conversations, saves, assets, and configuration changes made after ${formatTimestamp(point.createdAt)} will not be retained in this instance.",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Before anything is restored, Manager creates and verifies a separate recovery point of the current state. " +
                            "If that backup fails, rollback does not proceed. The pre-rollback point is retained for 7 days.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Success is reported only after the selected server and bundled data are active and normal launch and health checks pass.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!preview.available || !enoughSpace) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            preview.availabilityReason
                                ?: if (!enoughSpace) "There is not enough free space to preserve the current state before rollback."
                                else "This recovery point is not currently available.",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = onConfirmRollback,
                    enabled = preview.available && enoughSpace,
                ) { Text("Back up current state and restore") }
            },
            dismissButton = { TextButton(onClick = onDismissRollback) { Text("Cancel") } },
        )
    }

    pendingDelete?.let { point ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete recovery point?") },
            text = {
                Column {
                    Text("Recovery from ${formatTimestamp(point.createdAt)} will be permanently removed from Manager-owned storage.")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This cannot be undone. Pinned, in-use, and operation-required points cannot be deleted.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(onClick = { pendingDelete = null; onDelete(point) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }

    pendingClone?.let { point ->
        AlertDialog(
            onDismissRequest = { pendingClone = null },
            title = { Text("Create instance from recovery point") },
            text = {
                Column {
                    DetailRow("Source state", formatTimestamp(point.createdAt))
                    DetailRow("Server version", point.serverVersion ?: "Unknown")
                    OutlinedTextField(
                        value = cloneName,
                        onValueChange = { cloneName = it },
                        label = { Text("New instance name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The new instance receives an independent identity and port, starts stopped, and defaults to local-only access. " +
                            "The original instance and recovery point are preserved.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { pendingClone = null; onClone(point, cloneName) },
                    enabled = cloneName.trim().isNotEmpty() && cloneName.trim().length <= 80,
                ) { Text("Create instance") }
            },
            dismissButton = { TextButton(onClick = { pendingClone = null }) { Text("Cancel") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recovery Points") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !state.refreshing && !state.busy) {
                        if (state.refreshing) CircularProgressIndicator(Modifier.padding(10.dp))
                        else Icon(Icons.Default.Refresh, "Refresh recovery points")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        state.instance?.displayName ?: "PocketRisu",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Each point is a private, complete bundle of server files, conversations and saves, assets, and settings.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.rollbackOperation !is OperationState.Idle) {
                    item { RecoveryOperationCard("Rollback", state.rollbackOperation) }
                }
                if (state.cloneOperation !is OperationState.Idle) {
                    item { RecoveryOperationCard("Recovery-point clone", state.cloneOperation) }
                }
                if (state.points.isEmpty()) {
                    item {
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("No recovery points", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Verified recovery points are created before supported updates and rollbacks.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                } else {
                    items(state.points, key = { it.id.value }) { point ->
                        val availability = recoveryPointAvailability(point)
                        val mutating = state.mutatingPointId == point.id
                        RecoveryPointCard(
                            point = point,
                            availability = availability,
                            busy = state.busy,
                            mutating = mutating,
                            inspecting = state.inspectingPointId == point.id,
                            onReviewRollback = { onReviewRollback(point) },
                            onSetPinned = { onSetPinned(point, !point.pinned) },
                            onDelete = { pendingDelete = point },
                            onClone = {
                                cloneName = "${state.instance?.displayName ?: "PocketRisu"} at ${formatTimestamp(point.createdAt)}"
                                pendingClone = point
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecoveryPointCard(
    point: RecoveryPoint,
    availability: RecoveryPointAvailability,
    busy: Boolean,
    mutating: Boolean,
    inspecting: Boolean,
    onReviewRollback: () -> Unit,
    onSetPinned: () -> Unit,
    onDelete: () -> Unit,
    onClone: () -> Unit,
) {
    val usable = availability == RecoveryPointAvailability.AVAILABLE
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(formatTimestamp(point.createdAt), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(recoveryAvailabilityLabel(availability)) },
                )
            }
            DetailRow("Server version", point.serverVersion ?: "Unknown")
            DetailRow("Server commit", point.serverRevision.shortRevision())
            DetailRow("Size", formatBytes(point.sizeBytes))
            DetailRow("Expires", point.expiresAt?.let(::formatTimestamp) ?: "No expiration")
            DetailRow("Pinned", if (point.pinned) "Yes — automatic cleanup is disabled" else "No")
            if (point.pinned && point.expiresAt != null && point.expiresAt <= System.currentTimeMillis()) {
                Text("The original expiration has passed, but this point remains protected while pinned.", style = MaterialTheme.typography.bodySmall)
            }
            if (!usable) {
                Text(recoveryAvailabilityExplanation(availability), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onReviewRollback,
                modifier = Modifier.fillMaxWidth(),
                enabled = usable && !busy,
            ) {
                if (inspecting) CircularProgressIndicator(Modifier.padding(end = 8.dp))
                Text(if (inspecting) "Checking recovery point…" else "Review differences and restore")
            }
            OutlinedButton(
                onClick = onClone,
                modifier = Modifier.fillMaxWidth(),
                enabled = usable && !busy,
            ) { Text("Create new instance from this point") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSetPinned, enabled = !busy && !point.inUse, modifier = Modifier.weight(1f)) {
                    Text(if (point.pinned) "Unpin" else "Pin")
                }
                TextButton(
                    onClick = onDelete,
                    enabled = !busy && !point.pinned && !point.inUse,
                    modifier = Modifier.weight(1f),
                ) { Text("Delete") }
            }
            if (mutating) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun RecoveryOperationCard(label: String, operation: OperationState) {
    when (operation) {
        OperationState.Idle -> Unit
        is OperationState.Running -> Card {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("$label · ${operation.stage}", fontWeight = FontWeight.SemiBold)
                operation.detail?.let { Text(it) }
                Spacer(Modifier.height(8.dp))
                if (operation.progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { operation.progress }, modifier = Modifier.fillMaxWidth())
                Text("Stages without a reliable total use indeterminate progress.", style = MaterialTheme.typography.bodySmall)
            }
        }
        is OperationState.Success -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Text(operation.message, Modifier.padding(16.dp))
        }
        is OperationState.Failure -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(16.dp)) {
                Text(operation.code, fontWeight = FontWeight.SemiBold)
                Text(operation.message)
            }
        }
    }
}

private fun recoveryAvailabilityLabel(availability: RecoveryPointAvailability): String = when (availability) {
    RecoveryPointAvailability.AVAILABLE -> "Available"
    RecoveryPointAvailability.IN_USE -> "In use"
    RecoveryPointAvailability.CREATING -> "Creating"
    RecoveryPointAvailability.RESTORING -> "Restoring"
    RecoveryPointAvailability.CLEANUP_PENDING -> "Cleanup pending"
    RecoveryPointAvailability.FAILED -> "Unavailable"
    RecoveryPointAvailability.UNAVAILABLE -> "Unavailable"
}

private fun recoveryAvailabilityExplanation(availability: RecoveryPointAvailability): String = when (availability) {
    RecoveryPointAvailability.AVAILABLE -> "Available for verified restoration."
    RecoveryPointAvailability.IN_USE -> "This point is required by an operation and cannot be restored, cloned, or deleted now."
    RecoveryPointAvailability.CREATING -> "Creation and integrity verification have not completed."
    RecoveryPointAvailability.RESTORING -> "This point is currently being restored."
    RecoveryPointAvailability.CLEANUP_PENDING -> "The point has expired and cleanup is pending."
    RecoveryPointAvailability.FAILED -> "The point was not created or verified successfully."
    RecoveryPointAvailability.UNAVAILABLE -> "The point is not available for a new restore or clone operation."
}

private fun recoveryDifferenceLabel(changed: Boolean?): String = when (changed) {
    true -> "Changed — will be replaced"
    false -> "No difference detected"
    null -> "Bundled state will be restored"
}

@Composable
private fun ElevatedStatusCard(status: RuntimeState, content: @Composable ColumnScope.() -> Unit) {
    val color = when (status) {
        RuntimeState.RUNNING -> MaterialTheme.colorScheme.primaryContainer
        RuntimeState.UNHEALTHY, RuntimeState.FAILED -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    Card(colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), content = content)
    }
}

@Composable
private fun StatusText(status: RuntimeState) {
    Text(
        text = "● ${status.name.lowercase().replaceFirstChar(Char::uppercase)}",
        color = when (status) {
            RuntimeState.RUNNING -> MaterialTheme.colorScheme.primary
            RuntimeState.UNHEALTHY, RuntimeState.FAILED -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstallScreen(
    state: InstallUiState,
    onSelectVariant: (String) -> Unit,
    onSelectChannel: (String) -> Unit,
    onCommitChange: (String) -> Unit,
    onInstall: () -> Unit,
    onRefreshLog: () -> Unit,
    onTermuxSetup: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var installAfterNotificationPermission by remember { mutableStateOf(false) }
    LaunchedEffect(state.operationId, state.operation is OperationState.Running) {
        while (state.operation is OperationState.Running) {
            delay(3_000)
            onRefreshLog()
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (installAfterNotificationPermission) onInstall()
        installAfterNotificationPermission = false
    }
    val selected = state.variants.first { it.id == state.selectedVariantId }
    Scaffold(topBar = { TopAppBar(title = { Text("Install Risu") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("Recommended", style = MaterialTheme.typography.titleMedium) }
            items(state.variants, key = { it.id }) { variant ->
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth().selectable(selected = variant.id == state.selectedVariantId, onClick = { onSelectVariant(variant.id) }),
                    colors = if (variant.id == state.selectedVariantId) CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.outlinedCardColors(),
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = variant.id == state.selectedVariantId, onClick = null)
                        Column(Modifier.padding(start = 8.dp).weight(1f)) {
                            Text(variant.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(variant.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(selected.displayName, style = MaterialTheme.typography.headlineSmall)
                DetailRow("Source", selected.repository)
                DetailRow("Runtime", if (selected.runtimeType == RuntimeType.TERMUX_NODE) "Termux + Node.js" else "Native Android")
            }
            item {
                Text("Channel", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    selected.channels.forEach { channel ->
                        FilterChip(
                            selected = channel.id == state.selectedChannelId,
                            onClick = { onSelectChannel(channel.id) },
                            label = { Text(channel.displayName) },
                        )
                    }
                }
                if (state.selectedChannelId != "stable") {
                    Text("Advanced channels may contain unfinished changes.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (state.selectedChannelId == "specific-commit") {
                    OutlinedTextField(
                        value = state.requestedCommit,
                        onValueChange = onCommitChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Commit SHA") },
                        singleLine = true,
                    )
                }
            }
            if (selected.runtimeType == RuntimeType.TERMUX_NODE) {
                item {
                    OutlinedCard {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("Termux integration", style = MaterialTheme.typography.titleMedium)
                            Text(state.environment?.message ?: "Checking Termux…")
                            if (state.environment?.state != EnvironmentState.TERMUX_READY) {
                                TextButton(onClick = onTermuxSetup) { Text("Connect Termux") }
                            }
                        }
                    }
                }
            } else {
                item {
                    Text(
                        "The repository contains a Capacitor Android project, but current stable releases publish no APK asset. Installation is disabled instead of guessing an artifact or signing identity.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                OperationCard(state.operation)
                if (state.operationId != null) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Detailed installer log", style = MaterialTheme.typography.titleMedium)
                                    Text(state.operationId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { clipboard.setText(AnnotatedString(state.logLines.joinToString("\n") { "[${it.source}] ${it.text}" })) }) {
                                    Icon(Icons.Default.ContentCopy, "Copy installer log")
                                }
                                IconButton(onClick = onRefreshLog) { Icon(Icons.Default.Refresh, "Refresh installer log") }
                            }
                            if (state.logsLoading && state.logLines.isEmpty()) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            } else if (state.logLines.isEmpty()) {
                                Text("The installer has not produced log output yet.", style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text(
                                    state.logLines.joinToString("\n") { "[${it.source}] ${it.text}" },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            state.logError?.let { error ->
                                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            state.lastLogRefreshAt?.let { refreshedAt ->
                                Text(
                                    "Last log update: ${java.text.DateFormat.getTimeInstance().format(java.util.Date(refreshedAt))}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val needsPermission = Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        if (needsPermission) {
                            installAfterNotificationPermission = true
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else onInstall()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.operation !is OperationState.Running,
                ) {
                    Text(
                        when {
                            selected.runtimeType != RuntimeType.TERMUX_NODE -> "Check release"
                            state.operation is OperationState.Failure -> "Clean leftovers and retry"
                            else -> "Install"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun OperationCard(operation: OperationState) {
    when (operation) {
        OperationState.Idle -> Unit
        is OperationState.Running -> Card {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(operation.stage, fontWeight = FontWeight.SemiBold)
                Text(
                    when (operation.liveness) {
                        OperationLiveness.LOADING -> "Loading / working"
                        OperationLiveness.BLOCKED -> "Blocked: waiting for the operation scheduler"
                        OperationLiveness.RECONNECTING -> "Reconnecting: the helper or log transport is not responding"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                operation.detail?.let { Text(it) }
                Spacer(Modifier.height(8.dp))
                if (operation.progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { operation.progress }, modifier = Modifier.fillMaxWidth())
                Text("Build stages without reliable totals use indeterminate progress.", style = MaterialTheme.typography.bodySmall)
            }
        }
        is OperationState.Success -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Text(operation.message, Modifier.padding(16.dp))
        }
        is OperationState.Failure -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(16.dp)) {
                Text(operation.code, fontWeight = FontWeight.SemiBold)
                Text(operation.message)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    updateRetentionDays: Int,
    onUpdateRetentionDays: (Int) -> Unit,
    onTermux: () -> Unit,
    onDiagnostic: () -> Unit,
) {
    val context = LocalContext.current
    var retentionInput by rememberSaveable(updateRetentionDays) { mutableStateOf(updateRetentionDays.toString()) }
    val parsedRetention = retentionInput.toIntOrNull()
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            ThemeMode.entries.forEach { mode ->
                Row(
                    Modifier.fillMaxWidth().selectable(themeMode == mode) { onThemeMode(mode) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = themeMode == mode, onClick = null)
                    Text(mode.name.lowercase().replaceFirstChar(Char::uppercase), Modifier.padding(start = 8.dp))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text("Update recovery", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = retentionInput,
                onValueChange = { retentionInput = it.filter(Char::isDigit).take(3) },
                label = { Text("Retention (days)") },
                supportingText = { Text("1–365 days; default 7. Applies to future PocketRisu updates.") },
                isError = retentionInput.isNotBlank() && parsedRetention !in 1..365,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { parsedRetention?.let(onUpdateRetentionDays) },
                enabled = parsedRetention in 1..365 && parsedRetention != updateRetentionDays,
            ) { Text("Save retention") }
            Text(
                "Manager recovery points are private internal backups and can contain credentials. " +
                    "Low disk space never causes existing recovery points to be deleted automatically.",
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text("Integration", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onTermux, modifier = Modifier.fillMaxWidth()) { Text("Termux integration") }
            OutlinedButton(onClick = onDiagnostic, modifier = Modifier.fillMaxWidth()) { Text("Diagnostic Report") }
            OutlinedButton(
                onClick = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.BatteryChargingFull, null)
                Text("Battery optimization settings")
            }
            Text(
                "A wake lock improves reliability, but Android or vendor memory policies may still stop Termux. Risu Manager makes no background-process guarantee.",
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text("About", style = MaterialTheme.typography.titleMedium)
            Text("Risu Manager manages compatible Risu applications. PocketRisu executes inside Termux, not inside this APK.")
            Spacer(Modifier.height(8.dp))
            Text("No analytics, telemetry, advertisements, cloud account, or manager-operated backend.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun shareDiagnosticReport(context: android.content.Context, report: String) {
    val reportDirectory = context.cacheDir.resolve("diagnostic-reports").apply { mkdirs() }
    reportDirectory.listFiles()?.forEach { file -> file.delete() }
    val reportFile = reportDirectory.resolve("risu-manager-diagnostic.txt")
    reportFile.writeText(report, Charsets.UTF_8)
    val reportUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", reportFile)
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, reportUri)
        clipData = ClipData.newRawUri("Diagnostic report", reportUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Share diagnostic report"))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerConfigScreen(
    state: ServerConfigUiState,
    onBack: () -> Unit,
    onPort: (String) -> Unit,
    onBindScope: (BindScope) -> Unit,
    onCertificateMode: (CertificateMode) -> Unit,
    onTlsDomain: (String) -> Unit,
    onAcmeEmail: (String) -> Unit,
    onAcmeChallengePort: (String) -> Unit,
    onAcmePreflight: () -> Unit,
    onAcmeIssue: () -> Unit,
    onAcmeRenew: () -> Unit,
    onAcmeDisable: () -> Unit,
    onBodyLimit: (String) -> Unit,
    onSaveInterval: (String) -> Unit,
    onSnapshotInterval: (String) -> Unit,
    onSnapshotRetention: (String) -> Unit,
    onSnapshotCapacity: (String) -> Unit,
    onImageQuality: (String) -> Unit,
    onOptimizeImages: (Int) -> Unit,
    onSave: () -> Unit,
    onMessageShown: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); onMessageShown() } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Instance server settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Network", style = MaterialTheme.typography.titleLarge)
            NumericSetting("Port", "1024–65535", state.port, onPort)
            Text("Access scope", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(state.bindScope == BindScope.LOOPBACK, { onBindScope(BindScope.LOOPBACK) }, label = { Text("This device only") })
                FilterChip(state.bindScope == BindScope.LOCAL_NETWORK, { onBindScope(BindScope.LOCAL_NETWORK) }, label = { Text("Same network") })
            }
            if (state.bindScope == BindScope.LOCAL_NETWORK) {
                Text("Anyone on the same network may reach this port. Configure server authentication before enabling network access.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            DetailRow("Protocol", state.protocol.name)
            if (Capability.HTTPS in state.capabilities && state.instance?.variantId == BuiltInVariants.pocketRisu.id) {
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                Text("HTTPS settings", style = MaterialTheme.typography.titleLarge)
                Text("Certificate method", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.certificateMode == CertificateMode.MANUAL,
                        onClick = { onCertificateMode(CertificateMode.MANUAL) },
                        label = { Text("Manual") },
                    )
                    FilterChip(
                        selected = state.certificateMode == CertificateMode.LETS_ENCRYPT,
                        onClick = { onCertificateMode(CertificateMode.LETS_ENCRYPT) },
                        label = { Text("Let's Encrypt") },
                    )
                }
                if (state.certificateMode == CertificateMode.MANUAL) {
                    Text(
                        "Existing manually installed certificates remain supported. HTTPS becomes active only after the helper verifies a matching certificate and private key.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    OutlinedTextField(
                        value = state.tlsDomain,
                        onValueChange = onTlsDomain,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        label = { Text("Domain") },
                        supportingText = { Text("Single public hostname, for example risu.example.com") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.acmeEmail,
                        onValueChange = onAcmeEmail,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        label = { Text("ACME account email") },
                        singleLine = true,
                    )
                    NumericSetting(
                        "HTTP challenge internal port",
                        "1024–65535 · router external TCP 80 must forward here",
                        state.acmeChallengePort,
                        onAcmeChallengePort,
                    )
                    Text(
                        "Requirements: A/AAAA records must reach this network; a wrong AAAA record can break validation. CGNAT may make HTTP-01 impossible, dynamic addresses may need DDNS, and router port forwarding must be configured manually.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = onAcmePreflight,
                        enabled = !state.acmeBusy,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text("Check connection prerequisites") }
                    if (state.preflightChecked) {
                        DetailRow("DNS A", state.dnsA.ifEmpty { listOf("None") }.joinToString())
                        DetailRow("DNS AAAA", state.dnsAAAA.ifEmpty { listOf("None") }.joinToString())
                        DetailRow(
                            "Internal challenge port",
                            when (state.internalChallengePortAvailable) {
                                true -> "Available"
                                false -> "In use"
                                null -> "Not checked"
                            },
                        )
                        DetailRow("External TCP 80", state.externalPortCheck ?: "Manual verification required")
                    }
                    Button(
                        onClick = onAcmeIssue,
                        enabled = !state.acmeBusy,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        if (state.acmeBusy) CircularProgressIndicator(Modifier.padding(end = 8.dp))
                        Text("Issue certificate and enable HTTPS")
                    }

                    if (state.autoRenew || state.certificateExpiresAt != null) {
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        val health = certificateHealth(state.certificateExpiresAt)
                        val healthColor = when (health) {
                            CertificateHealth.HEALTHY -> MaterialTheme.colorScheme.primary
                            CertificateHealth.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.error
                        }
                        Text("Certificate status: ${health.name.lowercase().replaceFirstChar(Char::uppercase)}", color = healthColor)
                        DetailRow("Domain", state.tlsDomain.ifBlank { "Unknown" })
                        DetailRow("Expires", formatTimestamp(state.certificateExpiresAt))
                        DetailRow("Automatic renewal", if (state.autoRenew) "On" else "Off")
                        DetailRow("Last renewal check", formatTimestamp(state.lastRenewCheckAt))
                        DetailRow("Last renewal success", formatTimestamp(state.lastRenewSuccessAt))
                        state.lastRenewError?.let { Text("Last renewal error: $it", color = MaterialTheme.colorScheme.error) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onAcmeRenew, enabled = !state.acmeBusy, modifier = Modifier.weight(1f)) {
                                Text("Check renewal now")
                            }
                            TextButton(onClick = onAcmeDisable, enabled = !state.acmeBusy, modifier = Modifier.weight(1f)) {
                                Text("Disable HTTPS")
                            }
                        }
                    }
                }
            } else {
                Text("HTTPS is shown only after a verified certificate and matching private key are installed.", style = MaterialTheme.typography.bodySmall)
            }
            if (Capability.BODY_LIMIT_SETTINGS in state.capabilities) {
                NumericSetting("Request body limit", "MB · 1–1024", state.bodyLimitMb, onBodyLimit)
                if ((state.bodyLimitMb.toIntOrNull() ?: 0) > 512) {
                    Text("Limits above 512 MB can cause Termux to run out of memory.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (Capability.AUTOSAVE_SETTINGS in state.capabilities || Capability.SERVER_SNAPSHOTS in state.capabilities) {
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                Text("Storage and backups", style = MaterialTheme.typography.titleLarge)
                if (Capability.AUTOSAVE_SETTINGS in state.capabilities) {
                    NumericSetting("Autosave interval", "seconds · 1–30", state.saveIntervalSeconds, onSaveInterval)
                    Text("Autosave batches recent changes. A longer interval can lose more recent changes after an abnormal exit.", style = MaterialTheme.typography.bodySmall)
                }
                if (Capability.SERVER_SNAPSHOTS in state.capabilities) {
                    NumericSetting("Server snapshot interval", "minutes · 1–1440", state.snapshotIntervalMinutes, onSnapshotInterval)
                    NumericSetting("Snapshots to keep", "1–100", state.snapshotRetentionCount, onSnapshotRetention)
                    NumericSetting("Maximum snapshot storage", "MB · 10–51200", state.snapshotCapacityMb, onSnapshotCapacity)
                    Text("Server snapshots are periodic database recovery points. They are separate from autosave and exported .bin backups.", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (Capability.IMAGE_OPTIMIZATION in state.capabilities) {
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                Text("Storage maintenance", style = MaterialTheme.typography.titleLarge)
                NumericSetting("Image optimization quality", "1–100", state.imageQuality, onImageQuality)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("70", "85", "95").forEach { quality ->
                        AssistChip(onClick = { onImageQuality(quality) }, label = { Text(quality) })
                    }
                }
                Text("Image optimization is a one-time server task that may replace images with WebP. Create a backup first. It must run from an authenticated active session.", style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = { state.imageQuality.toIntOrNull()?.let(onOptimizeImages) },
                    enabled = state.imageQuality.toIntOrNull() in 1..100,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Open image optimization") }
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text("Diagnostics", style = MaterialTheme.typography.titleLarge)
            DetailRow("Saved port", state.port)
            DetailRow("Applied port", state.applied?.port?.toString() ?: "Not confirmed")
            DetailRow("Applied protocol", state.applied?.protocol?.name ?: "Not confirmed")
            DetailRow("Applied bind scope", state.applied?.bindScope?.name ?: "Not confirmed")
            state.lastApplyError?.let { Text("Last apply error: $it", color = MaterialTheme.colorScheme.error) }
            state.validationErrors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onSave, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
                if (state.saving) CircularProgressIndicator(Modifier.padding(end = 8.dp))
                Text("Save and apply")
            }
            Text("Port, access scope, body limit, autosave, and snapshot interval require a server restart. A stopped instance stays stopped.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun NumericSetting(label: String, supporting: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        label = { Text(label) },
        supportingText = { Text(supporting) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TermuxIntegrationScreen(state: io.github.risumanager.domain.model.EnvironmentStatus?, onBack: () -> Unit, onCheck: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onCheck() }
    val setupCommand = "mkdir -p ~/.termux && touch ~/.termux/termux.properties && (grep -qxF 'allow-external-apps=true' ~/.termux/termux.properties || printf '\\nallow-external-apps=true\\n' >> ~/.termux/termux.properties) && termux-reload-settings"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect Termux") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(state?.message ?: "Checking Termux…", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            when (state?.state) {
                EnvironmentState.TERMUX_NOT_INSTALLED -> Text("Install Termux from F-Droid or its official GitHub releases. A current package is required for Node.js 22+.")
                EnvironmentState.TERMUX_PERMISSION_MISSING -> {
                    Text("Android must grant the Termux RUN_COMMAND permission before the harmless handshake can run.")
                    Button(onClick = { permissionLauncher.launch(TermuxContract.PERMISSION) }) { Text("Grant permission") }
                }
                EnvironmentState.TERMUX_EXTERNAL_COMMANDS_DISABLED, EnvironmentState.TERMUX_ERROR -> {
                    Text("1. Open Termux\n2. Run the setup command below\n3. Return here and check again")
                    OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Text(setupCommand, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { clipboard.setText(AnnotatedString(setupCommand)) }) {
                        Icon(Icons.Default.ContentCopy, null)
                        Text("Copy setup command")
                    }
                }
                EnvironmentState.TERMUX_READY -> {
                    Text("The verified handshake succeeded and helper version ${state.helperVersion ?: 1} is installed.")
                    state.diagnostics.forEach { (key, value) -> DetailRow(key, value) }
                }
                else -> CircularProgressIndicator()
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val launch = context.packageManager.getLaunchIntentForPackage(TermuxContract.PACKAGE)
                    if (launch != null) context.startActivity(launch) else openUrl(context, "https://github.com/termux/termux-app/releases")
                }) { Text("Open Termux") }
                Button(onClick = onCheck) { Icon(Icons.Default.Refresh, null); Text("Check again") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogsScreen(state: LogsUiState, onBack: () -> Unit, onRefresh: () -> Unit, onTogglePause: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(state.paused) {
        while (!state.paused) {
            delay(3_000)
            onRefresh()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recent server logs") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = onTogglePause) { Icon(if (state.paused) Icons.Default.PlayArrow else Icons.Default.Pause, "Pause refresh") }
                    IconButton(onClick = { clipboard.setText(AnnotatedString(state.lines.joinToString("\n") { it.text })) }) {
                        Icon(Icons.Default.ContentCopy, "Copy logs")
                    }
                    IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh logs") }
                },
            )
        },
    ) { padding ->
        if (state.loading && state.lines.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            ) {
                items(state.lines) { line ->
                    Text("[${line.source}] ${line.text}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ImageOptimizationScreen(
    instance: RisuInstance,
    config: io.github.risumanager.domain.model.InstanceServerConfig?,
    quality: Int,
    onBack: () -> Unit,
) {
    data class Progress(
        val state: String = "ready",
        val current: Int = 0,
        val total: Int = 0,
        val compressed: Int = 0,
        val skipped: Int = 0,
        val savedBytes: Long = 0,
        val message: String? = null,
    )

    val context = LocalContext.current
    val port = config?.port ?: instance.port
    val protocol = config?.protocol ?: ServerProtocol.HTTP
    val effectiveConfig = config ?: InstanceServerConfig(instance.instanceId, port = port, protocol = protocol)
    val url = validatedServerUrl(config, port)
    var loaded by remember(url) { mutableStateOf(false) }
    var started by remember(url) { mutableStateOf(false) }
    var confirmed by remember(url) { mutableStateOf(false) }
    var progress by remember(url) { mutableStateOf(Progress()) }
    val webView = remember(url) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    loaded = true
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val target = request?.url ?: return true
                    val allowed = isAllowedServerOrigin(
                        effectiveConfig.copy(port = port, protocol = protocol),
                        target.scheme.orEmpty(),
                        target.host.orEmpty(),
                        target.port,
                    )
                    if (!allowed) openUrl(context, target.toString())
                    return !allowed
                }
            }
            loadUrl(url)
        }
    }
    DisposableEffect(webView) { onDispose { webView.destroy() } }

    LaunchedEffect(webView, started) {
        while (started && progress.state == "running") {
            webView.evaluateJavascript(
                "(function(){var s=window.__risuManagerOptimization;if(!s)return '';return [s.state,s.current||0,s.total||0,s.compressed||0,s.skipped||0,s.totalSaved||0,encodeURIComponent(s.message||'')].join('|')})()",
            ) { encoded ->
                val decoded = runCatching { org.json.JSONArray("[$encoded]").getString(0) }.getOrDefault("")
                val parts = decoded.split('|', limit = 7)
                if (parts.size == 7 && parts[0].isNotBlank()) {
                    progress = Progress(
                        state = parts[0],
                        current = parts[1].toIntOrNull() ?: 0,
                        total = parts[2].toIntOrNull() ?: 0,
                        compressed = parts[3].toIntOrNull() ?: 0,
                        skipped = parts[4].toIntOrNull() ?: 0,
                        savedBytes = parts[5].toLongOrNull() ?: 0,
                        message = Uri.decode(parts[6]).ifBlank { null },
                    )
                }
            }
            delay(500)
        }
    }

    fun startOptimization() {
        progress = Progress(state = "running")
        started = true
        val script = """
            (function() {
              window.__risuManagerOptimization = {state:'running',current:0,total:0,compressed:0,skipped:0,totalSaved:0,message:''};
              (async function() {
                try {
                  const response = await fetch('/api/inlays/compress', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({quality: $quality})
                  });
                  if (!response.ok) throw new Error('HTTP ' + response.status + ': ' + (await response.text()).slice(0, 200));
                  if (!response.body) throw new Error('Streaming response is unavailable');
                  const reader = response.body.getReader();
                  const decoder = new TextDecoder();
                  let buffer = '';
                  while (true) {
                    const result = await reader.read();
                    buffer += decoder.decode(result.value || new Uint8Array(), {stream: !result.done});
                    const events = buffer.split(/\r?\n\r?\n/);
                    buffer = events.pop() || '';
                    for (const event of events) {
                      const line = event.split(/\r?\n/).find(function(value) { return value.startsWith('data: '); });
                      if (!line) continue;
                      const data = JSON.parse(line.slice(6));
                      window.__risuManagerOptimization = Object.assign({}, window.__risuManagerOptimization, data, {state: data.type === 'done' ? 'done' : data.type === 'error' ? 'error' : 'running'});
                    }
                    if (result.done) break;
                  }
                  if (window.__risuManagerOptimization.state === 'running') throw new Error('The server ended the stream without a completion event');
                } catch (error) {
                  window.__risuManagerOptimization = Object.assign({}, window.__risuManagerOptimization, {state:'error',message:String(error && error.message || error)});
                }
              })();
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Image optimization") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Quality $quality · authenticated active session required", style = MaterialTheme.typography.titleMedium)
                Text("This task can replace PNG, JPEG, GIF, or BMP images with smaller WebP files. Create an exported backup first.", style = MaterialTheme.typography.bodySmall)
                if (!confirmed) {
                    Button(onClick = { confirmed = true }, enabled = loaded, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text(if (loaded) "I have a backup — continue" else "Loading server…")
                    }
                } else if (!started) {
                    Button(onClick = ::startOptimization, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Start one-time optimization") }
                } else {
                    if (progress.total > 0) LinearProgressIndicator(
                        progress = { progress.current.toFloat() / progress.total.toFloat() },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text("${progress.current}/${progress.total} · compressed ${progress.compressed} · skipped ${progress.skipped}")
                    Text("Saved ${formatBytes(progress.savedBytes)}")
                    when (progress.state) {
                        "done" -> Text("Optimization completed.", color = MaterialTheme.colorScheme.primary)
                        "error" -> Text(progress.message ?: "Optimization failed.", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Text("Sign in here and keep this instance as the active session before starting.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            key(webView) {
                AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.2f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun InternalWebViewScreen(instance: RisuInstance, config: io.github.risumanager.domain.model.InstanceServerConfig?, onBack: () -> Unit) {
    val context = LocalContext.current
    val port = config?.port ?: instance.port
    val protocol = config?.protocol ?: ServerProtocol.HTTP
    val effectiveConfig = config ?: InstanceServerConfig(instance.instanceId, port = port, protocol = protocol)
    val url = validatedServerUrl(config, port)
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        fileCallback?.onReceiveValue(uri?.let { arrayOf(it) })
        fileCallback = null
    }
    val webView = remember(url) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?,
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = filePathCallback
                    filePicker.launch(fileChooserParams?.acceptTypes?.firstOrNull().orEmpty().ifBlank { "*/*" })
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val target = request?.url ?: return true
                    val allowed = isAllowedServerOrigin(
                        effectiveConfig.copy(port = port, protocol = protocol),
                        target.scheme.orEmpty(),
                        target.host.orEmpty(),
                        target.port,
                    )
                    if (!allowed) openUrl(context, target.toString())
                    return !allowed
                }
            }
            setDownloadListener(DownloadListener { downloadUrl, _, _, _, _ -> openUrl(context, downloadUrl) })
            loadUrl(url)
        }
    }
    DisposableEffect(webView) {
        onDispose {
            fileCallback?.onReceiveValue(null)
            webView.stopLoading()
            webView.destroy()
        }
    }
    BackHandler {
        if (webView.canGoBack()) webView.goBack() else onBack()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(BuiltInVariants.find(instance.variantId)?.displayName ?: "Risu") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { openUrl(context, url) }) { Icon(Icons.Default.OpenInBrowser, "Open in browser") } },
            )
        },
    ) { padding ->
        key(webView) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize().padding(padding))
        }
    }
}

private fun String?.shortRevision(): String = this?.take(8) ?: "Unknown"

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun formatTimestamp(value: Long?): String = value?.let {
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        .format(java.util.Date(it))
} ?: "Never"

private fun InstanceServerConfig.withTlsStateFrom(stored: InstanceServerConfig?): InstanceServerConfig {
    stored ?: return this
    return copy(
        imageQuality = stored.imageQuality,
        certificateMode = stored.certificateMode,
        tlsDomain = stored.tlsDomain,
        acmeEmail = stored.acmeEmail,
        acmeChallenge = stored.acmeChallenge,
        acmeChallengePort = stored.acmeChallengePort,
        certificateExpiresAt = stored.certificateExpiresAt,
        autoRenew = stored.autoRenew,
        lastRenewCheckAt = stored.lastRenewCheckAt,
        lastRenewSuccessAt = stored.lastRenewSuccessAt,
        lastRenewError = stored.lastRenewError,
        renewFailureCount = stored.renewFailureCount,
    )
}
