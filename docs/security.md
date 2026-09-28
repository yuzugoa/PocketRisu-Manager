# Security model

## Trust boundaries

Risu Manager trusts its APK-bundled variant definitions and Termux helper template. GitHub responses and repository contents are untrusted inputs. A source adapter may resolve metadata, but it cannot supply shell command templates.

PocketRisu's upstream build script is executed only for the fixed official repository at a resolved commit. This is still third-party code running with the Termux app's privileges; the install screen identifies the source and revision so the user can make that decision.

## Command safety

- Android passes a fixed executable path plus an argument array to Termux.
- The helper exposes a small verb allowlist.
- Instance, operation, variant, revision, archive URL/hash, ports, numeric settings, enums, and line-count inputs are validated and bounded.
- User-controlled values are not evaluated, expanded, or concatenated into shell programs.
- Process operations target a PID only after command-line and working-directory ownership checks.
- Remote manifests and README instructions never become commands. Build behavior is selected by a compiled enum/allowlist.
- Release archives must match an official profile URL/name, two matching SHA-256 sources, and safe tar paths before extraction.
- Per-instance configuration and PostgreSQL credentials are created with mode 600 and are never returned to Android or command-line arguments.

## Android boundary

The Termux result receiver is not exported, and every call uses an explicit one-shot `PendingIntent`. Package visibility is limited to Termux. Internal WebViews have no privileged JavaScript bridge and restrict navigation to the configured loopback origin. The maintenance view calls the server from its authenticated origin. Certificate errors are not ignored.

## Data and privacy

The Manager has no analytics, advertising, telemetry, Manager-operated backend, or cloud account. GitHub requests are the only Manager-originated network traffic in the MVP; PocketRisu and Termux retain their own independent network behavior.

The Manager database contains metadata, not PocketRisu conversations or credentials. PocketRisu data remains in its Termux instance or private Manager-owned Termux recovery points unless the user explicitly chooses **Backup (.bin)** and an Android document destination. Recovery points can contain credentials and are checked for format, ownership, integrity, and compatibility before restoration. Backup bytes travel only over loopback from a short-lived server protected by a 256-bit random token; redirects and system proxies are disabled, and the Manager verifies the declared size and SHA-256 while streaming. Temporary Termux backup files expire after 15 minutes and are removed after transfer. Android backup rules include only the Manager database/preferences, so a system restore cannot silently recreate the external installation.

Diagnostic reports retain structured rollback stages and results but omit server
log text, free-form operation details, and recovery artifact contents.

## Not yet claimed

Native APK signature verification, uninstall, and secure arbitrary repository
execution remain outside the supported scope.
