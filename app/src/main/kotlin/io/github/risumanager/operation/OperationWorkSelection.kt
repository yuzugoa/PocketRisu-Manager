package io.github.risumanager.operation

import androidx.work.WorkInfo
import java.util.UUID

private val workOrder = compareBy<WorkInfo>(
    { it.tagValue(OperationCoordinator.CREATED_TAG_PREFIX)?.toLongOrNull() ?: Long.MIN_VALUE },
    { it.id.toString() },
)

/** KEEP can discard the requested ID, and the retained work may already have finished. */
internal fun selectAcceptedWork(workInfos: List<WorkInfo>, requestedId: UUID): WorkInfo? =
    workInfos.firstOrNull { it.id == requestedId }
        ?: newestActiveWork(workInfos)
        ?: workInfos.maxWithOrNull(workOrder)

internal fun newestActiveWork(workInfos: List<WorkInfo>): WorkInfo? = workInfos
    .filterNot { it.state.isFinished }
    .maxWithOrNull(workOrder)

internal fun WorkInfo.tagValue(prefix: String): String? = tags.firstNotNullOfOrNull { tag ->
    tag.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
}
