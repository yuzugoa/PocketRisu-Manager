# Troubleshooting

## Termux is not installed

Install a current Termux build from its official GitHub releases or F-Droid. Builds from unrelated stores can be obsolete or use an incompatible signing lineage.

## RUN_COMMAND permission is missing

Use **Grant permission** in the integration screen. If Android no longer shows a prompt, open Android app settings for Risu Manager and review permissions, then check again.

## External commands are disabled or the handshake times out

Open Termux and run the exact one-time command shown by Risu Manager. It adds `allow-external-apps=true` to `~/.termux/termux.properties` and reloads Termux settings. Then return and tap **Check again**. Do not copy commands from unofficial setup guides into the Manager.

## Installation fails

Open the operation or server logs and keep the error code. Common causes are insufficient storage, interrupted package mirrors, network failure, or an upstream native dependency failing to compile. The helper retains the bounded operation log at `~/.risu-manager/logs/<operation-id>.log` for diagnosis. A package failure is retried once after package cleanup/update; a recoverable project build or download failure gets one clean retry. Failed attempt contents remain at `~/.risu-manager/backups/install-recovery/<operation-id>/attempt-1/`, including the log's recovery path. Trust, checksum mismatch, unsafe archive, revision mismatch, patch compatibility, database, and preexisting-data errors stop immediately. Never delete that recovery directory until the operation has been diagnosed.

The install screen shows a bounded live tail of this operation log and can copy it for support. Selecting **Clean leftovers and retry** after a failed install reuses the failed instance ID and resets only incomplete Manager-owned artifacts. Existing metadata, PocketRisu saves, and PostgreSQL data are never cleaned automatically.

On Android arm64, native packages listed in PocketRisu's lockfile are not by themselves evidence that the Termux build will fail. The Manager runs the trusted upstream build and uses its result. `msgpackr-extract` may report that an Android binary is unavailable while pnpm continues with msgpackr's JavaScript decoder.

## The server is unhealthy

`RUNNING` means both the stored PID belongs to the instance and `http://127.0.0.1:6001/` answers. `UNHEALTHY` means the process exists but the health request failed. Review logs, then try Restart. If the PID no longer belongs to the instance, the helper removes the stale PID file rather than signaling it.

The helper checks local HTTP and HTTPS health with Node.js. This avoids a false failure when Termux's `curl` cannot start because its `libcurl` and OpenSSL packages have incompatible versions. A broken `curl` can still affect downloads for other install strategies and should be repaired through Termux package updates.

## A recovery point cannot be restored

Open **Recovery Points** and review the point's availability and the reported
error code. `RECOVERY_INVALID` indicates failed format, identity, dependency,
or integrity validation; the installed instance is left untouched.
`INSUFFICIENT_DISK_SPACE` means there is not enough room to preserve the current
state and stage the historical one. Free space without deleting a needed
recovery point, then inspect again. A port conflict must be resolved before
creating a new instance from a point. Points marked in use or required for
failure recovery cannot be deleted. Pinning protects a point from automatic
expiration cleanup; unpinning does not extend its original expiry.

If a rollback reports restoration required after interruption or failure, keep
both the selected and pre-rollback points intact. Capture a diagnostic report
with the operation ID and error code before attempting another state change.
The report records structured stages while excluding server logs and user data.

## Android stops the process

Risu Manager coordinates Termux's wake lock, but Android and vendor memory policies can still stop background work. Exclude Termux—not only Risu Manager—from aggressive battery optimization if this is acceptable for your device. No Android application can guarantee indefinite background execution.

## Haejeok Android APK installation is unavailable

The Haejeok Termux release archive is supported. Its Android APK is not in the catalog because the inspected stable release did not provide an APK that could be verified against the expected package and signing identity. Risu Manager refuses to choose a similarly named desktop artifact.
