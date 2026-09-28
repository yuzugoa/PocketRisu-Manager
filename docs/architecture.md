# Architecture

Risu Manager keeps product identity, source discovery, and execution mechanism separate.

```text
Compose UI / ViewModels
        |
OperationCoordinator + WorkManager
        |
VariantDefinition + VariantProfile ---- SourceRepository
        |
RisuRuntime
   |                 |
TermuxRuntimeAdapter NativeApkRuntimeAdapter
   |
TermuxCommandClient -> Manager-owned allowlisted helper -> Termux Node process
```

## Domain boundaries

`VariantProfile` is the declarative Termux Node contract: official repository, release selection, one of the built-in `InstallStrategy` values, asset patterns, architectures, Node requirement, allowlisted build ID, entry point, health path, isolated data layout, integrity requirements, and capabilities. It is data, not executable remote configuration. `VariantDefinition` supplies the product catalog entry.

`SourceRepository` resolves a channel to an immutable `SourceRevision` and enumerates release metadata. The GitHub adapter accepts only repository coordinates already present in a trusted variant.

`RisuRuntime` owns install and day-to-day lifecycle behavior. A single `TermuxRuntimeAdapter` interprets trusted PocketRisu and Haejeok profiles; Compose screens contain no distribution shell commands. The unused native adapter remains an internal extension point and no APK item appears in the product catalog.

`RisuInstance`, `InstanceServerConfig`, operation history, and backup metadata are stored in versioned SQLite. Server settings use their own table so instance identity and mutable runtime policy stay separate. The Termux helper is the source of truth for desired/applied runtime values; Android mirrors the confirmed result and records pending or failed application state.

## Operations

Long installs run through WorkManager as foreground data-sync work. The operation ID is persisted both by Android and by the Termux helper, so reopening the app can recover the current stage. Short lifecycle commands use the same runtime interface and structured result model.

Install resolution is immutable: stable tags and branches are resolved by GitHub to a full 40-character commit before the helper receives them. The helper then performs a detached checkout and persists the actual installed commit.

PocketRisu clones use a dedicated durable record and worker. Android reserves
the source, target ID, display name, and port before dispatch; helper locks and
an operation journal protect the stopped filesystem snapshot after Android
process loss. The helper validates provisional output before publication, then
Android atomically registers the target, configuration, and completed record.
The startup reconciler reuses pending operation identities. This is a bounded
clone transaction, not a workflow engine. See [instance cloning](instance-cloning.md).

PocketRisu updates and manual rollbacks have durable Android operation records,
WorkManager reconciliation, helper journals, and verified recovery artifacts.
Rollback planning refers to a complete `RecoveryPoint` with server, data,
configuration, integrity, and compatibility metadata. A prior source revision
alone is insufficient. The helper saves the active state as a second recovery
point before activating the selected historical state. Successful publication
requires checkout, process, and HTTP health checks. See
[PocketRisu recovery points](pocketrisu-rollbacks.md).

## Web content boundary

The internal WebView derives scheme and port from the instance configuration and permits only that loopback origin. Other links are sent to the system browser. No privileged JavaScript interface is exposed. The image-maintenance view uses same-origin `fetch`, so PocketRisu still enforces its session and active-client checks. TLS errors are never bypassed.

## Phase boundaries

PocketRisu installation, backups, cloning, updates, and manual recovery-point
rollback are implemented. Uninstall, custom repository execution, and native
APK installation are not exposed as supported operations.

Manual backup is a foreground WorkManager operation. A versioned, APK-owned Termux helper gracefully stops a running instance, reads PocketRisu's SQLite KV store (including chunked database blobs, plugin storage, assets, cold storage, and NodeOnly inlays), and writes the same length-prefixed `.bin` records used by PocketRisu. A random-token loopback server exposes the completed file for one bounded transfer; Android streams it into a Storage Access Framework document while checking byte count and SHA-256.
