# Variants

A variant combines identity and compatibility metadata without owning execution code. Both supported products use the same `TERMUX_NODE` runtime. The trusted profile selects only app-implemented `SOURCE_BUILD` or `RELEASE_ARCHIVE` behavior.

## PocketRisu

- Repository: `PocketRisu/PocketRisu`
- Runtime: `TERMUX_NODE`
- Channels: latest stable GitHub release, `develop`, or a specific full commit
- Install: `SOURCE_BUILD` with the allowlisted `POCKETRISU_TERMUX_BUILD` strategy
- Architecture: `aarch64`
- Node: 22.12.0 or newer
- Default health endpoint: `/` on HTTP loopback port 6001
- Trust: built-in template
- Managed install root: `~/.risu-manager/instances/<instance-id>/repo`
- Persistent upstream paths: `save/` (including `risuai.db`, WAL, and SHM files) and `backups/`

The stable channel resolves the latest non-draft, non-prerelease GitHub release tag to a full commit before installation. Advanced channels are labeled as such in the UI.

Capabilities: network/body-limit/autosave/snapshots/image optimization/HTTPS management/manual `.bin` backup. The settings screen supports Let's Encrypt HTTP-01 issuance, status, renewal checks, and disabling managed HTTPS; an existing manually installed certificate/key pair can also be validated. The Manager does not provide manual private-key import. The manager patch for server settings is applied only when every expected v1.11.2 context matches, and it is idempotently revalidated.

## Haejeok RisuAI Termux

- Repository: `nevaeh5379/HaejeokRisuai`
- Runtime: `TERMUX_NODE`
- Channel: stable GitHub release
- Install: `RELEASE_ARCHIVE`, matching `RisuAI-Termux-b<number>.tar.gz`
- Integrity: HTTPS plus both GitHub's asset digest and the release `.sha256` asset
- Entry point: `server/node/server.cjs`; health path: `/api/health`
- Node: 20.19.x or 22.12.0 and newer (Node 21 and early Node 22 are rejected)
- Data: an isolated PostgreSQL cluster, socket, credentials file, save directory, logs, and backups per instance

Only network settings are advertised for Haejeok because the other PocketRisu-specific APIs have not been verified as compatible. The official global Termux service script is not used: the Manager starts its own per-instance PostgreSQL cluster and Node process so instances cannot share live data or ports.

## Adding variants later

Stock RisuAI is not built in because no official, reproducible Termux Node release contract was found. A future profile must declare data and capabilities locally; it must not cause README commands or arbitrary remote scripts to execute. APK and PWA catalog items are excluded from this scope.
