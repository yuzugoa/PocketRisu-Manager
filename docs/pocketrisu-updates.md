# PocketRisu updates and recovery

Risu Manager updates only trusted `TERMUX_NODE` PocketRisu instances. It does
not expose an update action for Haejeok, APK/PWA targets, custom repositories,
inconsistent checkouts, or targets that cannot be built and verified.

## Supported update paths

Any two distinct full commit SHAs from the trusted PocketRisu repository may be
used as the installed source and selected target. The Manager resolves the
target from the configured release or branch channel, then pins that commit for
the operation. The source metadata must match the actual Git `HEAD`. The helper
fetches and checks out the exact target SHA, builds it in isolation, verifies
the managed server layout, and reads its version from the staged `package.json`.
Build or runtime incompatibility stops the update; a successful build alone does
not guarantee that application data can migrate between arbitrary versions.

The helper verifies the resulting `HEAD` before any installed repository is
replaced.

## Transaction boundary

An update is a durable, per-instance operation:

1. Android reserves the operation and its recovery identity atomically.
2. The helper validates the PocketRisu identity, source and target commits,
   local Node toolchain, storage estimate, and private staging/recovery paths.
3. The target is fetched and built under an operation-owned staging directory.
   The update build uses private pnpm/cache/corepack paths and never installs or
   changes shared Termux packages.
4. The helper rechecks that the source did not change, records stop intent, and
   gracefully stops it if it was running.
5. The complete instance directory is copied to a private recovery point. A
   file manifest, full tree digest, data digest, configuration digest, immutable
   source/target identities, and manifest digest are generated and verified.
6. Only after recovery verification are the staged server and installed server
   exchanged. User save data, backups, configuration, and TLS identity move
   together as one instance state.
7. Success is published only after the target version and commit are active,
   the server process survives, and HTTP health succeeds. An instance that was
   originally stopped is returned to stopped state after verification.

The helper journal is authoritative during process loss. If Android, the
helper shell, or the update process disappears after a recovery point becomes
required, the next operation poll attempts automatic whole-instance restore.
Restore first verifies both content integrity and that the recovery manifest's
operation, instance, source commit, and target commit match the durable journal.
The resulting pre-update point can later be selected on the instance's
**Recovery Points** screen for a whole-instance manual rollback. That action
first creates a separate, complete pre-rollback point. See
[manual rollback](pocketrisu-rollbacks.md).

## Failure behavior

Failures before activation leave the installed instance unchanged. Failures
after a verified recovery point is created restore the complete pre-update
instance and its prior running/stopped state. A restoration failure remains a
visible recovery-required condition and is not reported as update success.

Dispatch rejections such as an invalid revision, insufficient storage, a
recovery ID conflict, or an unsafe private path are terminal pre-mutation
failures. Transport failures remain reconcilable because Android cannot assume
whether the helper received the request.

The helper refuses to use Manager roots that are symlinks, non-directories, or
cannot be protected with owner-only permissions. Recovery creation uses new
operation-owned directories and treats permission failures as fatal before any
instance data is copied.

## Retention and cleanup

The default recovery retention is seven days and the UI accepts 1–365 days.
Expiry starts at successful update completion, not update start. A recovery
point is never cleaned while it is pinned, in use, required for restoration, or
referenced by a running update operation.

After the helper confirms physical deletion, Android keeps a hidden audit
tombstone so terminal update history can retain its foreign-key identity. Active
recovery queries and subsequent cleanup exclude that tombstone.

## Verification

Run the deterministic helper contract from Windows with WSL:

```powershell
wsl sh tools/test-termux-update.sh app/src/main/assets/termux-helper/install.sh
```

It covers success, source-stopped state preservation, expiry cleanup,
invalid revisions, multiple source versions, failure restoration, other-instance isolation, SIGKILL
reconciliation, recovery identity mismatch rejection, and a symlinked recovery
root. Existing helper, install-recovery, and clone contract suites should also
pass.

Android unit tests cover update planning, presentation, preference bounds, and
pre-dispatch error classification. Instrumentation tests exercise the real
SQLite foreign-key path through progress publication, successful completion,
cleanup tombstoning, and retained terminal update history.

An x86_64 AVD can validate the Manager UI, database migrations, WorkManager
wiring, and Termux bridge, but cannot perform a product-valid PocketRisu update
because the trusted PocketRisu Termux profile is ARM64-only. The final end-to-end
end-to-end update therefore requires an authorized ARM64 device and a
dedicated disposable test instance; existing user instances must not be used.
