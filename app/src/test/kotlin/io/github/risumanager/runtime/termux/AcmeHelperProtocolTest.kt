package io.github.risumanager.runtime.termux

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class AcmeHelperProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun acmeResponseMapsDnsAndRenewalMetadataWithoutSensitiveMaterial() {
        val response = json.decodeFromString<HelperResponse>(
            """{"ok":true,"operation":"acme-certificate-status","domain":"risu.example.com","email":"admin@example.com","challengePort":8080,"certificateMode":"LETS_ENCRYPT","certificateExpiresAt":1700000000000,"autoRenew":true,"lastRenewCheckAt":1600000000000,"lastRenewSuccessAt":1600000000100,"renewed":false,"renewFailureCount":0,"dnsA":["203.0.113.10"],"dnsAAAA":[],"internalPortAvailable":true,"externalPortCheck":"CHECK_REQUIRED"}""",
        )
        assertThat(response.domain).isEqualTo("risu.example.com")
        assertThat(response.dnsA).containsExactly("203.0.113.10")
        assertThat(response.dnsAAAA).isEmpty()
        assertThat(response.internalPortAvailable).isTrue()
        assertThat(response.certificateExpiresAt).isEqualTo(1_700_000_000_000L)
        // The protocol intentionally has no private-key or account-key fields.
        assertThat(response.lastRenewError).isNull()
    }
}
