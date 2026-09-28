package io.github.risumanager.operation

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Serializes lifecycle-changing operations for a managed instance in this app process. */
class InstanceOperationGate {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withInstance(instanceId: String, block: suspend () -> T): T =
        locks.computeIfAbsent(instanceId) { Mutex() }.withLock { block() }
}
