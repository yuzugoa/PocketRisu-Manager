package io.github.risumanager

import android.app.Application
import io.github.risumanager.app.AppContainer
import io.github.risumanager.operation.AcmeRenewScheduler
import kotlinx.coroutines.launch

class RisuManagerApplication : Application() {
    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        AcmeRenewScheduler.schedule(this)
        // Durable clone records are authoritative. Re-enqueue even when an earlier WorkManager
        // record ended as failed/cancelled; helper publication and DB registration are idempotent.
        container.applicationScope.launch { runCatching { container.clones.reconcilePending() } }
        // The helper journal is authoritative for interrupted updates. Reconciliation restores a
        // verified pre-update state before a failure can become terminal. Cleanup is best effort;
        // both SQLite and the helper independently enforce ownership, expiry, and in-use guards.
        container.applicationScope.launch {
            runCatching { container.updates.reconcilePending() }
            runCatching { container.updates.cleanupExpired() }
        }
    }
}
