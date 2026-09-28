# PocketRisu instance cloning

Cloning is a PocketRisu-only operation. It preserves the installed server version
and a stopped snapshot of its content. It does not fetch a release or run an
update. The user chooses a display name; generated instance and operation IDs
own the filesystem paths and recovery records.

## Publication and recovery

Android reserves the source, target identity, and automatic port in a durable
clone record before dispatch. WorkManager prevents duplicate queued work while
the process-local instance gate serializes Android operations. Helper instance
locks protect the filesystem snapshot independently of the Android process.

The helper records whether the source is running before stopping it. It requires
a graceful stop and a verified stopped process before copying. It creates output
under an operation-specific staging directory, validates the copy, applies the
clone identity and network policy, and restores the source's previous state.
The target remains stopped.

The helper's publication operation moves only validated output into the target
installation path. Android then registers the instance, local server settings,
and completed clone record in one SQLite transaction. Recovery reuses the same
operation and target identity. A helper publication followed by Android process
loss must be completed by idempotent registration, without copying again or
deleting the published target.

Before publication, interrupted output is provisional. Cleanup must derive its
paths from validated operation identifiers and verify ownership. Source data,
other instances, and existing recovery backups are outside that cleanup scope.
A failed source restart is an explicit recovery error, not successful cloning.

## Identity and network policy

Installation, data, runtime files, and port are independent. The clone defaults
to loopback HTTP. Source domain settings, certificate private keys, temporary
ACME challenges, renewal ownership, PIDs, and one-time transfer credentials are
not inherited. Application data and user login configuration remain distinct
from instance-specific server credentials and sessions.

The automatic port allocator considers registered instance ports, persisted
server configurations, pending clone reservations, and listener availability.
The helper verifies availability again before publication. There is no manual
port field in the clone dialog.

## Historical recovery-point source

Clone policy creates a new identity. A clone requested from a Manager recovery
point uses the selected historical server, data, and configuration together.
It still applies independent identity, automatic port, and loopback HTTP policy
before publication. The historical point itself remains intact.

`RecoveryPointId` and `RecoveryPoint` describe a complete historical state with
integrity and compatibility metadata. A source revision by itself is not a
recovery point. The recovery-point UI and whole-instance restoration are
described in [PocketRisu recovery points](pocketrisu-rollbacks.md).

The helper protocol and Android durable clone record are versioned alongside
the APK. Internal stages identify preparation, stopping, copying, validation,
identity transformation, source restoration, publication, and registration;
they are recovery boundaries rather than percentage estimates.

See [the verification record](phase1-cloning-verification.md) for executed checks
and the distinction between deterministic helper fixtures and real PocketRisu
AVD integration.
