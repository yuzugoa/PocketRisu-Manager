# Upstream analysis

Inspection date: 2026-09-06 (Asia/Seoul)

These are versioned observations from official GitHub sources. They are rechecked before changing a built-in profile.

## PocketRisu

- Official repository: `PocketRisu/PocketRisu`
- Latest stable release observed: `v1.11.2`
- Release commit: `278251f85a19bfdfd4cf3faae780e62682878f9e`
- Official Termux build: `scripts/termux/build.sh`, ARM64/aarch64
- Server entry point: `server/node/server.cjs`
- Package requirement: Node.js 22.12.0 or newer

The server reads `PORT`, defaults to 6001, serves `/`, and chooses HTTPS when `server/node/ssl/certificate/server.key` and `server.crt` exist. JSON and text parsers use independent 100 MB defaults; backup/octet-stream endpoints keep their own larger limit. Save batching defaults to 5000 ms. Snapshot creation uses `POCKETRISU_BACKUP_INTERVAL_MS` with a five-minute default and SQLite KV limits of 1–100 snapshots and 10 MB–50 GB, defaulting to 20 and 500 MB.

`POST /api/inlays/compress` checks session authentication and the active session, then returns SSE progress with target count, completed count, compressed/skipped counts, and saved bytes. It can replace supported images with WebP. The Manager invokes it only inside the existing same-origin authenticated WebView.

The management patch is deliberately narrow. It converts only the fixed save interval, JSON/text limits, and two listen calls into validated environment-driven values. It first verifies exact context counts, writes a temporary file, and renames only after the complete result is valid. A source layout change fails installation without overwriting the original.

References:

- https://github.com/PocketRisu/PocketRisu/releases/tag/v1.11.2
- https://github.com/PocketRisu/PocketRisu/blob/v1.11.2/docs/en/termux.md
- https://github.com/PocketRisu/PocketRisu/blob/v1.11.2/scripts/termux/build.sh
- https://github.com/PocketRisu/PocketRisu/blob/v1.11.2/server/node/server.cjs
- https://github.com/PocketRisu/PocketRisu/blob/v1.11.2/package.json

## Haejeok RisuAI

- Official repository: `nevaeh5379/HaejeokRisuai`
- Latest release observed: `b6787`
- Release commit: `47a329d8894f4ffbf19f3f48e1035a0a198772d`
- Termux asset: `RisuAI-Termux-b6787.tar.gz`
- Observed SHA-256: `1bb1d130a5b4f6d881742f2cb9424b1b61557bd82f0dc38b715f9a484b86bc74`
- Separate official checksum asset: `RisuAI-Termux-b6787.tar.gz.sha256`

The archive has top-level `dist`, `server`, `packages`, `deploy`, `termux`, package manifests, and `RELEASE`. Its Node server supports `PORT` and `RISU_HOST`, exposes `/api/health`, and uses PostgreSQL. The official Termux script assumes global service names and a shared default database port, so it cannot safely manage several Manager instances unchanged. The built-in profile therefore uses only the verified archive layout and variables while creating one PostgreSQL data directory, socket, random credential, save directory, server PID, and port per Manager instance.

Haejeok advertises only the network capability until its other server APIs are verified against the PocketRisu settings contract.

References:

- https://github.com/nevaeh5379/HaejeokRisuai/releases/tag/b6787
- https://github.com/nevaeh5379/HaejeokRisuai
- https://github.com/nevaeh5379/HaejeokRisuai/tree/b6787/deploy/termux

## Stock RisuAI

Latest stock RisuAI releases were inspected, but no official Termux Node archive or documented reproducible Termux runtime contract was found. The Manager does not guess a build command or claim built-in support. A stock profile can be added after an official entry point, dependency/version contract, data layout, health check, and integrity policy can be verified.

References:

- https://github.com/kwaroran/Risuai
- https://github.com/kwaroran/Risuai/releases

## Termux API contract

The Android integration follows Termux's `RUN_COMMAND` intent contract: package `com.termux`, service `com.termux.app.RunCommandService`, action `com.termux.RUN_COMMAND`, documented path/argument/stdin/workdir/background extras, and a result `PendingIntent`.

References:

- https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent
- https://github.com/termux/termux-app/blob/master/termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java
