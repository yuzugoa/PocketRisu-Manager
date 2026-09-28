package io.github.risumanager.diagnostic

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import java.io.IOException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticReportScreen(
    state: DiagnosticUiState,
    onBack: () -> Unit,
    onGenerate: () -> Unit,
    onShare: (String) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    var saveError by remember { mutableStateOf<String?>(null) }
    val saveReport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        val report = state.report ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val result = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(report.toByteArray(Charsets.UTF_8))
            } ?: throw IOException("The selected document provider did not open the file.")
        }
        result.onSuccess { /* The system document provider owns the saved file. */ }
            .onFailure { error ->
                // Keep the failure visible without putting report contents in a log.
                saveError = error.message ?: "The report could not be saved."
            }
    }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(saveError) { saveError?.let { snackbar.showSnackbar(it); saveError = null } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostic Report") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Create a local report for installation, execution, cloning, updates, or rollback issues. " +
                    "It is not uploaded automatically.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "The report uses an allowlist and masks domains, emails, IP addresses, names, and secret-like values. " +
                    "Review the preview before saving or sharing.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.loading) {
                Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            if (state.report == null && !state.loading) {
                Button(onClick = onGenerate, modifier = Modifier.fillMaxWidth()) { Text("Generate report") }
            }
            state.report?.let { report ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { clipboard.setText(AnnotatedString(report)) }, modifier = Modifier.weight(1f)) {
                        Text("Copy")
                    }
                    OutlinedButton(onClick = { saveReport.launch("risu-manager-diagnostic.txt") }, modifier = Modifier.weight(1f)) {
                        Text("Save file")
                    }
                }
                OutlinedButton(onClick = { onShare(report) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Open share sheet")
                }
                Text("Preview", style = MaterialTheme.typography.titleMedium)
                Text(
                    report,
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Sharing uses a temporary local file and grants read access only to the selected Android share target.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
