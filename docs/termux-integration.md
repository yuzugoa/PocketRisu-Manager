# Termux integration

Compatible distributions run as normal Node.js processes inside Termux. Risu Manager communicates through Termux's documented `RUN_COMMAND` service; it does not scrape a terminal UI.

## Onboarding state machine

1. Check package visibility for `com.termux`.
2. Check `com.termux.permission.RUN_COMMAND`.
3. Run a harmless `printf` handshake through `RunCommandService` and receive the result through an explicit, app-private mutable `PendingIntent`.
4. If Termux does not answer, instruct the user to set `allow-external-apps=true` and reload settings.
5. Gzip and Base64-encode the APK-bundled bootstrap script, send it through Termux standard input, and decode it in Termux before running it. This keeps the `RUN_COMMAND` Binder request below the tablet's transaction limit. The script installs a versioned helper at `~/.risu-manager/bin/risu-manager`.
6. Call `version` and `doctor`; never infer readiness from terminal text.

The app requests only the documented Termux permission. Android 12 and newer require the result `PendingIntent` to be mutable because Termux adds the result bundle before delivery. The exported `RunCommandService` is launched with `startService` while the Manager is visible or its operation worker is running in the foreground. Termux handles its own service lifecycle after accepting the command. Using `startForegroundService` for this short-lived receiver caused repeated `ForegroundServiceDidNotStartInTimeException` crashes on a Samsung Android 14 tablet with Termux 0.118.3.

## Helper protocol

The Android-facing helper protocol uses these allowlisted commands (the helper also has private worker entry points for dispatched jobs):

```text
version
doctor
dispatch install <operation-id> <instance-id> <profile-id> <full-commit> [<archive-url> <sha256> <checksum-url>]
dispatch backup <operation-id> <instance-id>
dispatch clone <operation-id> <source-instance-id> <target-instance-id> <port>
operation <operation-id>
operation-log <operation-id> <1..2000>
backup-cleanup <operation-id> <instance-id> <transfer-token>
clone-publish <operation-id>
clone-abort <operation-id>
start <instance-id>
status <instance-id>
stop <instance-id>
restart <instance-id>
logs <instance-id> <1..1000>
config-get <instance-id>
config-set <instance-id> <port> <bind-scope> <protocol> <body-mb> <save-seconds> <snapshot-minutes> <snapshot-count> <snapshot-mb>
config-validate <instance-id>
acme-install <instance-id>
acme-preflight <instance-id> <domain> <email> <challenge-port>
acme-issue <instance-id> <domain> <email> <challenge-port>
acme-renew-check <instance-id>
acme-certificate-status <instance-id>
acme-disable <instance-id>
recovery-estimate <instance-id>
recovery-inspect <instance-id> <recovery-point-id>
recovery-pin <recovery-point-id> <1|0>
recovery-delete <recovery-point-id>
recovery-cleanup <recovery-point-id> <epoch-ms>
dispatch update <operation-id> <instance-id> pocketrisu <target-sha> <pre-update-point-id> <retention-seconds>
dispatch rollback <operation-id> <instance-id> <selected-point-id> <pre-rollback-point-id> <retention-seconds>
dispatch recovery-clone <operation-id> <source-instance-id> <selected-point-id> <target-instance-id> <port>
```

Inputs are validated before they are used. Repository URL, build-script path, server entry point, port, and filesystem roots come from the trusted helper template rather than remote JSON or README instructions. The Android side also requires a verified full commit SHA.

Helper state lives below `~/.risu-manager/`:

```text
bin/risu-manager
bin/risu-backup-export.cjs
bin/risu-backup-server.cjs
instances/<instance-id>/repo
instances/<instance-id>/run/server.pid
instances/<instance-id>/run/server.log
instances/<instance-id>/manager/server.conf
instances/<instance-id>/manager/applied.conf
instances/<instance-id>/manager/db.conf              # PostgreSQL profiles only, mode 600
instances/<instance-id>/postgres/{data,socket}        # Haejeok only
state/<operation-id>.json
logs/<operation-id>.log
backups/
backups/install-recovery/<operation-id>/attempt-<n>/  # retained recovery evidence
recovery-points/<recovery-point-id>/                # private whole-instance snapshots
update-staging/<operation-id>/                     # isolated update/rollback staging
state/<operation-id>.rollback                      # durable rollback journal
```

## Process ownership

Starting records the exact child PID. Before signaling it, the helper loads the instance's trusted profile, checks `/proc/<pid>/cmdline` for that entry point, and verifies the process working directory equals that instance's repository. Stop sends `SIGTERM`, waits up to ten seconds, and uses `SIGKILL` only for that verified PID if necessary. It never uses `pkill`, broad process-name matching, or a shell assembled from user input.

The first managed process requests `termux-wake-lock`; the last stopped process releases it. A wake lock improves reliability but cannot override Android or vendor memory-management policy.

Recovery-point commands verify Manager ownership, format, integrity, and the
instance's identity. Pinning and deletion update the helper's manifest as well
as Android metadata. An in-use or recovery-required point cannot be deleted.
Rollback and cloning use instance and recovery-point locks and durable journals
so that an interrupted operation can be reconciled using its original ID.

Backup uses a stricter stop policy: it sends `SIGTERM` and waits up to 30 seconds, then aborts instead of using `SIGKILL`. A server that was running is restarted before the backup becomes downloadable. The completed temporary file is served only on `127.0.0.1` with a per-operation token and removed after the verified transfer or a 15-minute expiry.

## Installation

For PocketRisu, the helper clones the fixed official repository, fetches an immutable revision, performs a detached checkout, and maps the trusted build ID to upstream `scripts/termux/build.sh`. It verifies architecture, Node, entry point, frontend output, and commit.

For Haejeok, Android selects an official stable archive and matching checksum asset from the trusted patterns. The helper validates the exact official URL shape, checks the downloaded bytes against GitHub's SHA-256 and the `.sha256` asset, rejects absolute or parent-traversal tar entries, verifies required files, installs locked runtime dependencies, and creates isolated PostgreSQL storage.

Install recovery is bounded and keeps each failed attempt available for diagnosis. A Termux package failure gets one clean/update/install retry. A recoverable source or release project failure gets one clean retry (two total attempts); before retrying, the first attempt's repository, staging directory, and downloaded artifacts are moved under `backups/install-recovery/<operation-id>/attempt-1/`. Recovery uses the same verified revision or archive digest and starts from an empty project path. Trust, checksum mismatch, unsafe archive, revision mismatch, patch compatibility, database, and preexisting-data failures are terminal and are never retried. Recovery backups remain after success or failure; use the operation log to find the exact retained path.

Retrying a failed installation reuses its instance ID. Before resuming, the helper removes only allowlisted Manager-owned transient paths such as an incomplete repository, staging directory, downloaded archive, run directory, and generated manager configuration. It refuses cleanup when verified metadata or user/database paths are present, and it rejects symbolic links. Stale install locks are removed only after their recorded installer process is proven absent.

Each install keeps a private timestamped log at `logs/<operation-id>.log`. It records stage transitions, failure codes, bounded environment diagnostics, package/build output, recovery actions, and free-space information. The install screen reads the latest bounded tail through the allowlisted `operation-log` helper command and supports refresh and copy without exposing arbitrary Termux files.

## Server settings

Each instance has a strict nine-line configuration file written through a mode-600 temporary file and atomic rename. Values are validated on Android and again by the helper. Saving while running stops the verified PID, writes and validates the full configuration, restarts storage/server, performs the configured health check, and copies the configuration to `applied.conf`. Failure triggers a best-effort rollback and restart. A stopped instance remains stopped.

PocketRisu receives `PORT`, bind host, body limit, autosave milliseconds, and snapshot interval through environment variables. Snapshot retention and capacity are written to its documented SQLite KV keys while stopped. Haejeok receives `PORT`, `RISU_HOST`, `RISU_SAVE_PATH`, and its private `DATABASE_URL`.

## Current limits

The helper validates an already installed PocketRisu certificate/key pair and follows the resulting HTTP or HTTPS protocol for health checks and URLs. The settings screen supports Let's Encrypt HTTP-01 issuance, renewal checks, status, and disabling Manager-managed HTTPS. It checks DNS and local challenge-port availability, but external TCP 80 forwarding and public reachability still require user verification. Android schedules network-constrained renewal opportunities through WorkManager; the helper decides whether a production renewal is due. Manual certificate/key import is not provided, so manual mode can only use an existing valid pair.

PocketRisu does not expose a supported “snapshot now” or snapshot-history endpoint in the inspected release. The Manager configures its existing interval and retention keys but does not invent a direct database call while the server is live, so last snapshot success time is unavailable. Haejeok backup, image compression, body-limit, autosave, snapshot, and HTTPS controls remain hidden until their contracts are verified.
