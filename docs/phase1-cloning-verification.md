# Phase 1 cloning verification

This document records the verification of `Update-prompts/commonness.md` and
`Update-prompts/first_update_schedule.md`. Results below must distinguish host
contract fixtures from the Manager APK running the actual PocketRisu server
through Termux. A fixture or patched architecture check is not evidence of
supported PocketRisu integration.

## Acceptance boundaries

- Copy only after a durable record of the source running state and verified stop.
- Preserve installed server revision, application data, assets, and content settings.
- Give the target a separate identity, installation, data, port, and runtime state.
- Default the target to stopped, loopback HTTP without source TLS/ACME ownership.
- Publish only validated output; register target and configuration transactionally.
- Recover interrupted work using the original operation and target identities.
- Restore a previously running source after success or failure and surface failures.
- Delete only explicitly operation-owned provisional output.
- Keep update and recovery-point restoration execution unsupported in this phase.

## Required device scenarios

| Scenario | Required evidence | Result |
| --- | --- | --- |
| Stopped-source clone | Registered target, separate paths/ID/port, copied save/assets/config, target stopped | Pending |
| Running-source clone | Stop before copy, source restored, clone stopped | Pending |
| Independent lifecycle | Both real servers healthy on different ports; stopping one preserves the other | Pending |
| Independent data | Changes in one instance do not alter the other; mutable files have independent inodes | Pending |
| Version preservation | Source and clone installed revision and server files match at snapshot time | Pending |
| Network/TLS isolation | Loopback HTTP; no copied domain, renewal ownership, private key, or challenge state | Pending |
| Failure/interruption | Source preserved/restored; no partial normal instance; operation-owned cleanup | Pending |

## Execution record

Implementation and verification are in progress. No pending scenario is a pass.
