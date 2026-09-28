package io.github.risumanager.domain.planning

import com.google.common.truth.Truth.assertThat
import io.github.risumanager.domain.model.RisuInstance
import io.github.risumanager.domain.model.RuntimeState
import io.github.risumanager.domain.model.RuntimeType
import org.junit.Test

class ClonePlanningTest {
    @Test fun `allocator skips all managed reservations and occupied sockets`() {
        val occupied = setOf(6003)
        val selected = ClonePlanner.allocatePort(setOf(6001, 6002, 6004), 6001) { it !in occupied }
        assertThat(selected).isEqualTo(6005)
    }

    @Test fun `clone names are metadata but reject control characters and excessive input`() {
        assertThat(ClonePlanner.normalizeName("  My local copy  ")).isEqualTo("My local copy")
        assertThat(ClonePlanner.normalizeName("bad\nname")).isNull()
        assertThat(ClonePlanner.normalizeName("x".repeat(81))).isNull()
    }

    @Test fun `only installed Termux PocketRisu is cloneable in phase one`() {
        val pocket = instance("pocketrisu", RuntimeType.TERMUX_NODE, "a".repeat(40))
        assertThat(ClonePlanner.validateSource(pocket)).isNull()
        assertThat(ClonePlanner.validateSource(instance("haejeok", RuntimeType.TERMUX_NODE, "a".repeat(40)))).contains("only for PocketRisu")
        assertThat(ClonePlanner.validateSource(pocket.copy(installedRevision = null))).contains("verified installed revision")
        assertThat(ClonePlanner.validateSource(pocket.copy(installedRevision = "main"))).contains("verified installed revision")
    }

    private fun instance(variant: String, runtime: RuntimeType, revision: String?) = RisuInstance(
        instanceId = "$variant-source",
        variantId = variant,
        channelId = "stable",
        runtimeType = runtime,
        repository = "PocketRisu/PocketRisu",
        installedRevision = revision,
        createdAt = 1,
        updatedAt = 1,
        lastKnownStatus = RuntimeState.STOPPED,
    )
}
