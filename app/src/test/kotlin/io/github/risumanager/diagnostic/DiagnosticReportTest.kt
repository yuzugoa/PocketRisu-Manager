package io.github.risumanager.diagnostic

import com.google.common.truth.Truth.assertThat
import io.github.risumanager.domain.model.EnvironmentState
import io.github.risumanager.domain.model.EnvironmentStatus
import io.github.risumanager.domain.model.LogLine
import io.github.risumanager.domain.model.OperationRecord
import io.github.risumanager.domain.model.OperationResultKind
import io.github.risumanager.domain.model.OperationType
import io.github.risumanager.domain.model.RollbackEvent
import io.github.risumanager.domain.model.RollbackResult
import io.github.risumanager.domain.model.RollbackStage
import io.github.risumanager.domain.model.RisuInstance
import io.github.risumanager.domain.model.RuntimeState
import io.github.risumanager.domain.model.RuntimeStatus
import io.github.risumanager.domain.model.RuntimeType
import org.junit.Test

class DiagnosticReportTest {
    @Test
    fun `redacts representative secret formats while preserving context`() {
        val input = """
            Authorization: Bearer top-secret-token
            apiKey: JSON-SECRET-123
            https://service.example.com/api?token=url-secret&mode=debug
            postgres://dbuser:database-password@db.example.com/risu
            contact owner@example.com from 203.0.113.10
            multi-line error: first line
            password=second-line-secret
        """.trimIndent()

        val result = DiagnosticRedactor.text(input)

        assertThat(result).doesNotContain("top-secret-token")
        assertThat(result).doesNotContain("JSON-SECRET-123")
        assertThat(result).doesNotContain("url-secret")
        assertThat(result).doesNotContain("database-password")
        assertThat(result).doesNotContain("owner@example.com")
        assertThat(result).doesNotContain("203.0.113.10")
        assertThat(result).doesNotContain("second-line-secret")
        assertThat(result).contains("mode=debug")
        assertThat(result).contains("multi-line error")
    }

    @Test
    fun `report keeps structured stages but excludes freeform user content`() {
        val report = DiagnosticReportGenerator(maxLogLinesPerInstance = 1).generate(
            DiagnosticSnapshot(
                generatedAt = 123L,
                environment = DiagnosticEnvironment(
                    managerVersion = "0.1.0",
                    androidRelease = "15",
                    androidSdk = 35,
                    environments = listOf(
                        EnvironmentStatus(EnvironmentState.TERMUX_READY, "Termux ready", helperVersion = 15, diagnostics = mapOf("Node.js" to "v22.1.0")),
                    ),
                ),
                instances = listOf(
                    DiagnosticInstance(
                        instance = RisuInstance(
                            instanceId = "pocketrisu-test",
                            displayName = "Alice's private server",
                            variantId = "pocketrisu",
                            channelId = "stable",
                            runtimeType = RuntimeType.TERMUX_NODE,
                            repository = "PocketRisu/PocketRisu",
                            installedVersion = "1.2.3",
                            installedRevision = "abc123",
                            createdAt = 1L,
                            updatedAt = 2L,
                            lastKnownStatus = RuntimeState.FAILED,
                        ),
                        config = null,
                        runtime = RuntimeStatus(RuntimeState.FAILED),
                        operations = listOf(
                            OperationRecord(
                                "op", "pocketrisu-test", OperationType.ROLLBACK, 1L,
                                result = OperationResultKind.FAILURE,
                                stage = "FAILED",
                                detail = "Conversation: private discussion about my health",
                                errorCode = "ROLLBACK_RESTORE_FAILED",
                            ),
                        ),
                        rollbackEvents = listOf(
                            RollbackEvent("op", "pocketrisu-test", RollbackStage.PRE_ROLLBACK_VERIFIED, 11L, RollbackResult.RUNNING),
                            RollbackEvent("op", "pocketrisu-test", RollbackStage.FAILED, 12L, RollbackResult.FAILURE, "ROLLBACK_RESTORE_FAILED"),
                        ),
                        logs = listOf(LogLine("server", "Authorization: Bearer secret-value"), LogLine("server", "private discussion about my health")),
                    ),
                ),
            ),
        )

        assertThat(report).contains("Manager version: 0.1.0")
        assertThat(report).contains("Installed version: 1.2.3")
        assertThat(report).contains("ROLLBACK_RESTORE_FAILED")
        assertThat(report).contains("stage=FAILED")
        assertThat(report).contains("stage=PRE_ROLLBACK_VERIFIED result=RUNNING")
        assertThat(report).contains("stage=FAILED result=FAILURE")
        assertThat(report).contains("<redacted-name-")
        assertThat(report).doesNotContain("Alice's private server")
        assertThat(report).doesNotContain("secret-value")
        assertThat(report).doesNotContain("private discussion about my health")
    }
}
