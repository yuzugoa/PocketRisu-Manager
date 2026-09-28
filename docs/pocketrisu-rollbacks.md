# PocketRisu recovery points and manual rollback

Manual rollback applies only to Manager owned PocketRisu Termux recovery points.
A point contains one consistent instance snapshot: server checkout, saves and
databases, assets, runtime configuration, version metadata, and the integrity
manifest. A Git revision or exported `.bin` file alone is not a rollback target.

## Choosing a point

Open **Recovery Points** on the PocketRisu instance. The list displays when each
point was made, its server version, size, expiration, pin state, and availability.
Selecting one checks the helper's actual private artifact and compares its
version and category digests with the current instance. The preview does not
read or show conversation text, filenames, credentials, or configuration values.

The confirmation identifies the selected timestamp. Restoring it discards all
conversations and configuration changes made later in the active instance.
Server, saves, assets, and settings move together. The Manager first makes a
separate, verified point containing the entire current state. By default this
pre-rollback point is retained for seven days from successful completion. If
that snapshot cannot be created and verified, restoration does not start.

## Safety and completion

The helper checks the selected point's format, PocketRisu identity, source
commit, required server files and dependencies, file manifest, full tree hash,
data hash, and configuration hash before changing the installed instance.
It also checks free space and uses a private operation staging directory.
An operation journal records the immutable selected and pre-rollback point IDs
and the stage reached. Instance and recovery-point locks prevent conflicting
changes. After a process interruption, the next operation poll reconciles the
journal and restores the pre-rollback state if activation had begun.

After switching to the historical snapshot, the helper checks the active
checkout and version, launches PocketRisu, verifies its process and HTTP health,
then returns an originally stopped instance to stopped state. Android publishes
the restored version and settings only after those checks succeed. A failure
after activation triggers restoration of the complete pre-rollback state; a
failed restoration remains visibly marked as requiring recovery.

## Keeping, removing, and comparing states

Pinning protects a point from expiration cleanup. Unpinning returns it to its
original expiration schedule. Explicit deletion requires an unpinned point
that is neither in use nor needed by an active or failed operation. The helper
deletes the private artifact first; Android then hides its metadata while
retaining operation history. Neither cleanup nor explicit deletion follows
untrusted links or removes another instance's data.

**Create New Instance from Recovery Point** uses the historical snapshot as a
clone source. The new instance receives its own identity, credentials, data,
automatic port, and local HTTP settings, and is registered stopped. This lets
the user inspect the selected historical state and the retained pre-rollback
state separately.

Recovery artifacts remain private inside Termux and may contain credentials.
Diagnostic reports include bounded rollback stages, results, and error codes;
they exclude artifact contents, server log text, and free-form details.

## Verification boundary

The local helper fixture exercises version and data restoration, corruption
rejection, pre-rollback preservation, interruption recovery, pin/delete policy,
and historical cloning. This fixture uses synthetic files and processes. It
does not establish that the feature works with a real PocketRisu process in
Termux; that requires a separate device integration run.
