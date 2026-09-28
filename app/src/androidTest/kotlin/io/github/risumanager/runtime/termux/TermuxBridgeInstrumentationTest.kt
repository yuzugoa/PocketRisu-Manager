package io.github.risumanager.runtime.termux

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * End-to-end checks for the installed Termux RUN_COMMAND bridge.
 *
 * These tests deliberately use the app's production clients and the real
 * Termux service. They require a device with Termux 0.118.3 (or a compatible
 * RUN_COMMAND implementation), the RUN_COMMAND permission, and the bundled
 * helper asset.
 */
@RunWith(AndroidJUnit4::class)
class TermuxBridgeInstrumentationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var commandClient: TermuxCommandClient
    private lateinit var helper: TermuxHelperClient

    @Before
    fun createClients() {
        commandClient = TermuxCommandClient(context)
        helper = TermuxHelperClient(
            context = context,
            commandClient = commandClient,
            json = Json { ignoreUnknownKeys = true; explicitNulls = false },
        )
        assertThat(commandClient.isInstalled()).isTrue()
        assertThat(commandClient.hasPermission()).isTrue()
    }

    @Test
    fun handshakeUsesTheRealTermuxRunCommandService() = runBlocking {
        assertThat(helper.handshake()).isTrue()
    }

    @Test
    fun ensureInstalledInstallsAndVerifiesExpectedHelperVersion() = runBlocking {
        val response = helper.ensureInstalled()

        assertThat(response.ok).isTrue()
        assertThat(response.operation).isAnyOf("bootstrap", "version")
        assertThat(response.helperVersion).isEqualTo(TermuxHelperClient.EXPECTED_VERSION)
    }

    @Test
    fun doctorUsesTheInstalledHelperAndReportsVersion() = runBlocking {
        helper.ensureInstalled()
        val response = helper.doctor()

        assertThat(response.ok).isTrue()
        assertThat(response.operation).isEqualTo("doctor")
        assertThat(response.helperVersion).isEqualTo(TermuxHelperClient.EXPECTED_VERSION)
    }

    @Test
    fun nonexistentOperationIsReadOnlyAndReturnsExpectedProtocolError() = runBlocking {
        helper.ensureInstalled()
        val operationId = "bridge_probe_${UUID.randomUUID().toString().replace("-", "")}".take(48)

        val exception = try {
            helper.operation(operationId)
            null
        } catch (error: HelperProtocolException) {
            error
        }

        assertThat(exception).isNotNull()
        assertThat(requireNotNull(exception).code).isEqualTo("OPERATION_NOT_FOUND")
    }
}
