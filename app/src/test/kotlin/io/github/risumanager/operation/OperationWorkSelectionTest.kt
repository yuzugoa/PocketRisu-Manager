package io.github.risumanager.operation

import androidx.work.WorkInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.UUID

class OperationWorkSelectionTest {
    @Test
    fun `accepted request is preferred even when it already finished`() {
        val requested = work(2, WorkInfo.State.SUCCEEDED, createdAt = 100)
        val otherActive = work(1, WorkInfo.State.RUNNING, createdAt = 200)

        assertThat(selectAcceptedWork(listOf(otherActive, requested), requested.id)).isEqualTo(requested)
    }

    @Test
    fun `ignored KEEP request resolves to existing running work`() {
        val ignoredRequestId = id(9)
        val existing = work(1, WorkInfo.State.RUNNING, createdAt = 100)

        assertThat(selectAcceptedWork(listOf(existing), ignoredRequestId)).isEqualTo(existing)
    }

    @Test
    fun `existing work that finishes before lookup is still returned`() {
        val ignoredRequestId = id(9)
        val existing = work(1, WorkInfo.State.SUCCEEDED, createdAt = 100)

        assertThat(selectAcceptedWork(listOf(existing), ignoredRequestId)).isEqualTo(existing)
    }

    @Test
    fun `accepted selection prefers active work over a newer finished record`() {
        val ignoredRequestId = id(9)
        val active = work(1, WorkInfo.State.ENQUEUED, createdAt = 100)
        val finished = work(2, WorkInfo.State.SUCCEEDED, createdAt = 200)

        assertThat(selectAcceptedWork(listOf(finished, active), ignoredRequestId)).isEqualTo(active)
    }

    @Test
    fun `recovery selects newest active work by creation tag`() {
        val older = work(1, WorkInfo.State.RUNNING, createdAt = 100)
        val newer = work(2, WorkInfo.State.ENQUEUED, createdAt = 200)

        assertThat(newestActiveWork(listOf(newer, older))).isEqualTo(newer)
    }

    @Test
    fun `recovery uses UUID as deterministic tie breaker including legacy work`() {
        val lower = work(1, WorkInfo.State.BLOCKED)
        val higher = work(2, WorkInfo.State.RUNNING)

        assertThat(newestActiveWork(listOf(higher, lower))).isEqualTo(higher)
    }

    @Test
    fun `selection returns null for empty or terminal-only recovery input`() {
        assertThat(selectAcceptedWork(emptyList(), id(9))).isNull()
        assertThat(newestActiveWork(listOf(work(1, WorkInfo.State.FAILED, createdAt = 100)))).isNull()
    }

    private fun work(number: Int, state: WorkInfo.State, createdAt: Long? = null): WorkInfo {
        val tags = createdAt?.let { setOf("${OperationCoordinator.CREATED_TAG_PREFIX}$it") }.orEmpty()
        return WorkInfo(id(number), state, tags)
    }

    private fun id(number: Int): UUID = UUID(0, number.toLong())
}
