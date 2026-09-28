package io.github.risumanager.domain.source

import io.github.risumanager.domain.model.ChannelDefinition
import io.github.risumanager.domain.model.RisuRelease
import io.github.risumanager.domain.model.SourceRevision
import io.github.risumanager.domain.model.VariantDefinition

interface SourceRepository {
    suspend fun resolve(variant: VariantDefinition, channel: ChannelDefinition, requestedCommit: String? = null): SourceRevision
    suspend fun latestRelease(repository: String): RisuRelease
    suspend fun branchExists(repository: String, branch: String): Boolean
}
