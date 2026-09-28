package io.github.risumanager.runtime.termux

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class TermuxHelperContractTest {
    private fun helperSource(): String {
        val candidates = listOf(
            File("app/src/main/assets/termux-helper/install.sh"),
            File("src/main/assets/termux-helper/install.sh"),
        )
        return candidates.first { it.isFile }.readText()
    }

    private fun runtimeAdapterSource(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/io/github/risumanager/runtime/termux/TermuxRuntimeAdapter.kt"),
            File("src/main/kotlin/io/github/risumanager/runtime/termux/TermuxRuntimeAdapter.kt"),
        )
        return candidates.first { it.isFile }.readText()
    }

    @Test fun androidAndShellAgreeOnVersionAndStructuredConfigCommands() {
        val source = helperSource()
        assertThat(TermuxHelperClient.EXPECTED_VERSION).isEqualTo(24)
        assertThat(source).contains("HELPER_VERSION=\"24\"")
        assertThat(source).contains("\"operation\":\"bootstrap\",\"helperVersion\":24")
        assertThat(source).contains("config-get)")
        assertThat(source).contains("with_instance_lock \"${'$'}1\" config-get command_config_get \"${'$'}@\"")
        assertThat(source).contains("with_instance_lock \"${'$'}1\" config-set command_config_set \"${'$'}@\"")
        assertThat(source).contains("with_instance_lock \"${'$'}1\" config-validate command_config_validate \"${'$'}@\"")
    }

    @Test fun helperRejectsTraversalAndRestrictsNumericSettings() {
        val source = helperSource()
        assertThat(source).contains("*[!a-z0-9-]*")
        assertThat(source).contains("[ \"${'$'}port\" -ge 1024 ]")
        assertThat(source).contains("[ \"${'$'}body_mb\" -le 1024 ]")
        assertThat(source).contains("write_config_file \"${'$'}(config_file \"${'$'}instance_id\")\"")
    }

    @Test fun acmeFlowIsPinnedValidatedLockedAndNeverDisablesTlsVerification() {
        val source = helperSource()
        assertThat(source).contains("ACME_VERSION=\"3.1.4\"")
        assertThat(source).contains("e5f8e187bbf5251e0cd8891f2622daab9850366bd17bea9f92c2fe2ee091fd32")
        assertThat(source).contains("acme-install) shift; command_acme_install")
        assertThat(source).contains("acme-preflight) shift; command_acme_preflight")
        assertThat(source).contains("with_instance_lock \"${'$'}1\" acme-issue command_acme_issue \"${'$'}@\"")
        assertThat(source).contains("with_instance_lock \"${'$'}1\" acme-renew-check command_acme_renew_check \"${'$'}@\"")
        assertThat(source).contains("acme-certificate-status) shift; command_acme_certificate_status")
        assertThat(source).contains("with_instance_lock \"${'$'}1\" acme-disable command_acme_disable \"${'$'}@\"")
        assertThat(source).contains("--server letsencrypt_test")
        assertThat(source).contains("--server letsencrypt")
        assertThat(source).contains("--keylength ec-256")
        assertThat(source).contains("port_available 0.0.0.0 \"${'$'}challenge_port\"")
        assertThat(source.indexOf("lock=\"${'$'}ROOT/locks/acme-install\"")).isLessThan(
            source.indexOf("pkg install -y curl tar openssl-tool coreutils dnsutils"),
        )
        assertThat(source).contains("options.lookup = (_host, _options, callback) => callback(null, '127.0.0.1', 4)")
        assertThat(source).contains("options.ca = fs.readFileSync(certificate)")
        assertThat(source).contains("require(secure ? 'https' : 'http').get(options")
        assertThat(source).contains("tls_restore_backup")
        assertThat(source).contains("mkdir \"${'$'}lock\"")
        assertThat(source).doesNotContain("curl -k")
        assertThat(source).doesNotContain("curl | sh")
        assertThat(source).doesNotContain("curl ... | sh")
    }

    @Test fun terminalInstallAndBackupFailuresRemainReadableAsOperationResults() {
        listOf("install", "backup", "clone", "update", "rollback").forEach { operation ->
            val failure = HelperResponse(
                ok = false,
                operation = operation,
                state = "FAILED",
                code = "BUILD_FAILED",
                message = "partial build retained",
            )
            assertThat(failure.requireReadableOperationResult()).isSameInstanceAs(failure)
        }
    }

    @Test fun cloneSupportsPristineInstallWithoutWeakeningUnsafeDatabaseChecks() {
        val source = helperSource()
        assertThat(source).contains("initialize_clone_sqlite()")
        assertThat(source).contains("CREATE TABLE IF NOT EXISTS kv")
        assertThat(source).contains("DATABASE_INITIALIZATION_FAILED")
        assertThat(source).contains("SOURCE_DATABASE_UNSAFE")
        assertThat(source).doesNotContain("SOURCE_DATABASE_MISSING")
    }

    @Test fun unrelatedNonOkOperationResponseStillRaisesItsProtocolError() {
        val exception = assertThrows(HelperProtocolException::class.java) {
            HelperResponse(ok = false, operation = "doctor", code = "BROKEN", message = "details")
                .requireReadableOperationResult()
        }
        assertThat(exception.code).isEqualTo("BROKEN")
        assertThat(exception.message).isEqualTo("details")
    }

    @Test fun handshakeAcceptsOnlyExpectedTokenWithOptionalLineEndings() {
        assertThat(matchesTermuxHandshake("RISU_MANAGER_OK")).isTrue()
        assertThat(matchesTermuxHandshake("RISU_MANAGER_OK\n")).isTrue()
        assertThat(matchesTermuxHandshake("RISU_MANAGER_OK\r\n")).isTrue()
        assertThat(matchesTermuxHandshake(" RISU_MANAGER_OK\n")).isFalse()
        assertThat(matchesTermuxHandshake("RISU_MANAGER_OK extra\n")).isFalse()
    }

    @Test fun managedPatchRequiresEveryExpectedUpstreamContextBeforeRename() {
        val source = helperSource()
        val mismatch = source.indexOf("managed patch context mismatch")
        val rename = source.indexOf("fs.renameSync(temp, file)")
        assertThat(mismatch).isAtLeast(0)
        assertThat(rename).isGreaterThan(mismatch)
        assertThat(source).contains("server.listen(port, RISU_BIND_HOST")
    }

    @Test fun pocketRisuBuildExposesBundledNpmNodeGypToPnpmLifecycleScripts() {
        val source = helperSource()
        assertThat(source).contains("lib/node_modules/npm/node_modules/node-gyp/bin/node-gyp.js")
        assertThat(source).contains("PATH=\"${'$'}ROOT/bin:${'$'}PATH\" bash scripts/termux/build.sh")
        assertThat(source).contains("chmod 700 \"${'$'}node_gyp_shim.tmp.${'$'}${'$'}\"")
    }

    @Test fun installOperationTracksItsProcessAndFailsStaleRunningState() {
        val source = helperSource()
        assertThat(source).contains("operation_pid_file()")
        assertThat(source).contains("grep -q '\"operation\":\"install\"'")
        assertThat(source).contains("risu-manager _install")
        assertThat(source).contains("INSTALL_PROCESS_LOST")
    }

    @Test fun pocketRisuRunsTheTrustedTermuxBuildOnAndroidArm64() {
        val source = helperSource()
        assertThat(source).doesNotContain("native_dependency_preflight")
        assertThat(source).contains("run_trusted_build \"${'$'}profile_build_script\" \"${'$'}repo\" \"${'$'}log\"")
        assertThat(source).contains("install_error_code=\"BUILD_FAILED\"")
    }

    @Test fun doctorReportsArchitectureAndNodeAbiForAarchDiagnostics() {
        val source = helperSource()
        assertThat(source).contains("deviceArchitecture")
        assertThat(source).contains("nodeArchitecture")
        assertThat(source).contains("nodeAbi")
        assertThat(source).contains("process.versions.modules")
    }

    @Test fun runtimeMapsTermuxExternalAppsFailureToActionableEnvironmentState() {
        val source = runtimeAdapterSource()
        assertThat(source).contains("catch (exception: HelperProtocolException)")
        assertThat(source).contains("TERMUX_INTERNAL_ERROR")
        assertThat(source).contains("allow-external-apps")
        assertThat(source).contains("TERMUX_EXTERNAL_COMMANDS_DISABLED")
    }

    @Test fun installerExposesDetailedLogsAndResetsOnlyIncompleteManagedArtifacts() {
        val source = helperSource()
        assertThat(source).contains("operation-log) shift; command_operation_log")
        assertThat(source).contains("append_operation_log")
        assertThat(source).contains("Reset ${'$'}reset_count stale manager-owned installation artifact(s) before resuming.")
        assertThat(source).contains("${'$'}repo/save")
        assertThat(source).contains("INSTANCE_ALREADY_EXISTS")
        assertThat(source).contains("rm -rf \"${'$'}owned_path\"")
    }

    @Test fun packageRecoveryBypassesPkgWrapperAndPreservesExistingConffiles() {
        val source = helperSource()
        assertThat(source).contains("DEBIAN_FRONTEND=noninteractive dpkg --force-confold --configure -a")
        assertThat(source).contains("DEBIAN_FRONTEND=noninteractive apt-get update")
        assertThat(source).contains("Dpkg::Options::=--force-confold --fix-broken install -y")
        assertThat(source).contains("Dpkg::Options::=--force-confold full-upgrade -y")
        assertThat(source).contains("Dpkg::Options::=--force-confold install -y \"${'$'}@\"")
        assertThat(source).doesNotContain("pkg clean")
        assertThat(source).doesNotContain("pkg update")
    }

    @Test fun backupExplainsFreshDatabaseAndRestartsRunningServerBeforeFailure() {
        val source = helperSource()
        val restart = source.indexOf("if command_start \"${'$'}instance_id\" >> \"${'$'}log\" 2>&1; then return 0; fi")
        val emptyDatabase = source.indexOf("BACKUP_DATABASE_EMPTY")
        assertThat(restart).isAtLeast(0)
        assertThat(emptyDatabase).isGreaterThan(restart)
        assertThat(source).contains("database/database.bin is missing")
        assertThat(source).contains("finish its initial setup")
        assertThat(source).contains("while [ \"${'$'}restart_attempts\" -lt 10 ]")
        assertThat(source).contains("The backup failed and PocketRisu could not be restarted automatically")
    }
}
