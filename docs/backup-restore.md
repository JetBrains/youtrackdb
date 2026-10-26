# Backup and Restore (Operator Guide)

This page describes the binary backup files of YouTrackDB. It covers supported backups,
older-backup migration, restore admission, and temporary disk space.

Export and import of a JavaScript Object Notation (JSON) dump is a different procedure.
See [Database Migration Procedure](operator-migration-procedure.md) for that procedure.

## Backup units and backup chains

One backup writes one **backup unit** file with the `.ibu` extension. A **full backup**
starts a chain. Each later **incremental backup** appends one unit to that chain. A restore
replays the full backup and each following increment from the same database.

Each unit carries a small **backup header** at its tail. The header records the database
identifier as a universally unique identifier (UUID). It also records the unit position,
covered log range, and full content hash code. This release writes format version 6.
It reads format versions 3, 4, 5, and 6. Versions 4, 5, and 6 have a metadata checksum
and a force-barrier flag. The flag states whether the writer made the payload durable
before it wrote the tail.

Version 6 has a 122-byte tail with optional **continuity** fields. Continuity identifies
tracker history with a tracker UUID and a generation UUID.
The changed-page tracker records which pages changed.
A generation groups those tracked changes.

An output with a force capability records the generation selected for consumption.
The tracker consumes that generation only after output and tracker completion are durable.
An unfinished earlier generation makes either kind of output record absent continuity.
Absent continuity uses zero identifier slots.

The metadata checksum protects the continuity fields.
The full content hash also protects them.
These fields do not change restore admission.

A matching pair alone does not prove that tracker history is safe to use after startup.

Page copying reads all current pages while the changed-page tracker records and consumes
history. It does not use selective page reads. Reading all pages does not mean replacing
the backup chain. An incremental backup still appends a unit.

Custom output without a force capability records the previous completed continuity pair,
or absent continuity when no pair exists. The tracker merges the pending generation back
into active history instead of consuming it. The force-barrier flag does not certify that
the final flush or close succeeded.

An output with a force capability can make preceding bytes and the file length durable.
The writer flushes the payload and padding before the first force call.
After writing and flushing the tail, it calls that capability a second time.
Complete-unit durability requires both a successful second force and successful close.
An output without this capability has no such durability guarantee.

Local file output also forces the file during close.
Local directory synchronization remains best effort.
An input/output error during directory synchronization does not fail the backup.
A successful local backup therefore does not strictly guarantee durable publication of
its new file name.

An incremental backup checks the chain before adding a unit. For version 4, 5, and 6 units, it checks
metadata without reading the payload when the input supplier can read the tail. A newest unit
with a valid metadata checksum and a present force-barrier flag needs only this check. A version
3 newest unit, a version 4, 5, or 6 newest unit without the flag, and a unit with a failed metadata
checksum need a full content hash check.

A unit below the chain head needs only header admission. With a tail-read supplier, the
extension reads only its tail, including for a version 3 unit below a version 4, 5, or 6 head.
Without tail reads, the supplier reads the complete unit, but admission still skips the
full content hash check when the metadata is valid. An unreadable tail stops extension.

A restore checks the full content hash of every unit and the metadata checksum of every
version 4, 5, or 6 unit. An extension does not detect payload damage in a unit admitted from its
header alone. Restore detects such damage.

## Run a backup or restore

Java applications use these public entry points:

- Call `YTDBGraphTraversalSource.fullBackup(Path)` to create a full backup.
- Call `YTDBGraphTraversalSource.backup(Path)` to create or extend an incremental chain.
- Call `YouTrackDB.restore(databaseName, path)` to create a database from a backup chain.
- Call `YouTrackDB.restartInterruptedRestore(...)` to restart an interrupted restore.
  This last entry point supports embedded connections only.

Server clients can call the `ytdbFullBackup` and `ytdbIncrementalBackup` Gremlin services.
Each service accepts a string `path` parameter. These service paths refer to the server host.

The `YouTrackDB.restore(...)` overloads also support remote connections. A remote restore path
refers to the server host.

## The header records the database format and creation completion evidence

A backup header of this release also records two semantic values:

- The **database format** identifies the storage features and on-disk storage layout.
- The **creation completion evidence** states that creation of the backed-up database finished.

Both values are admission conditions. Every selected unit must carry the supported database
format and accepted creation completion evidence. A restore refuses a different database format,
even when the chain content would otherwise fit this release.

The header evidence does not replace the final restore checks. After replay, the restore checks
that the restored database finished its creation. The restore writes all restored data to disk
before the database becomes available.

## Older backups are unsupported

This release reads backup format versions 3, 4, 5, and 6. It writes version 6.
Version 5 introduced a page-allocation write-ahead log (WAL) record that older releases
cannot replay. Version 5 uses the version 4 header layout, metadata checksum, and
force-barrier flag. Version 6 adds continuity fields to that header.

An incremental backup can append version 6 to supported version 3, 4, or 5 chains.
It does not rewrite existing units. Restore accepts mixed version 3, 4, 5, and 6 chains.

Readers limited to versions 3, 4, and 5 refuse version 6 restore and extension.
They refuse before target replay or mutation and before removing source units.
See the separate exception below for releases older than format version 3.
Keep a copy of the old chain before upgrading if a downgrade may be needed.

Versions 1 and 2 lack the database format and creation completion evidence. This release also
refuses units with an unknown format version. A refusal leaves every source unit in place:

- A restore of that chain fails.
- An incremental backup refuses to extend that chain.
- A restart of an interrupted restore from that chain fails.

**Migration:** create a full backup in a new, empty directory. Use
`YTDBGraphTraversalSource.fullBackup(Path)` or the `ytdbFullBackup` service. Keep the old
directory unchanged until the new chain is complete.

A full backup replaces every unit for its database in the target directory, including
units with an unknown format version. The new, empty directory protects the old chain from
that overwrite behavior. A build older than format version 3 can remove a newest unit
that it cannot validate. This includes version 3, 4, 5, and 6 units. Do not extend a
newer chain with such a build. Use a new, empty directory.

## Unreadable backup output needs an operator decision

A failed backup call does not prove that its output is absent or unusable for restore.
A final flush, force, or close failure can leave a complete unit.
The writer keeps that output. It does not rewrite its tail or automatically delete it.
Tracker completion stays unchanged when output publication fails.
Normal chain admission checks decide whether a later extension can use it.
A later extension can admit a unit whose backup call failed.
That admission does not prove its durability.
Retrying does not automatically force the old unit.

A crash during a backup write can leave output without a readable header. An incremental
extension cannot classify that output. It preserves the file and refuses to extend the chain.

Inspect the file before changing it. After confirming that it is interrupted output, move it
outside the backup directory or remove it. Then call `YTDBGraphTraversalSource.backup(Path)` or
the `ytdbIncrementalBackup` service again.

You can instead create a full backup in a new, empty directory. This option leaves the existing
chain unchanged.

An incremental backup removes recognized incomplete version 3, 4, 5, or 6 units above the chain
head only after a full content hash check fails for each one.
Removal starts only after the entire chain passes inspection. A failed metadata checksum alone never allows removal. An incremental
extension never removes an unclassifiable file. It leaves unclassifiable output from a
process or host crash for the operator. A full backup still replaces `.ibu` files with this
database's UUID prefix in its target directory, even when their headers are unreadable.

## Tracker completion warnings

A backup can succeed with a warning when tracker completion cannot be saved.
The tracker keeps history unavailable for trusted use until it can prove that history is safe.
A later backup reads all pages. A failed attempt to invalidate tracker history never permits
removal of WAL files.

## Restore admission runs before every target change

A restore performs its checks in this order:

1. It checks the database name, target directory, target existence, reserved prefixes, and
   expected database identifier. These checks do not read the backup source or change a file.
2. It copies every selected unit into temporary files and checks the full content hash of each
   unit. It also checks the metadata checksum of each version 4, 5, or 6 unit and validates the chain.
3. It creates the target, replays only the temporary copies, performs final checks, and makes
   the database available.

A fresh restore refuses a database name that already exists. YouTrackDB does not support an
in-place restore. Use a new name, or explicitly drop an unwanted target before a fresh restore.

An unsupported chain therefore leaves no fresh restore target. Source changes after preparation
do not affect the prepared copies or replay.

Preparation does not hold a snapshot lock on the source directory. A concurrent backup can
rotate or remove a unit that preparation already listed. The restore then fails before changing
the target. Retry the restore after that backup finishes.

A concurrent unfinished unit can also fail selection or validation. Retry the restore after the
backup finishes or after resolving the unfinished file.

## Restarting an interrupted restore

An interrupted restore leaves a target in the restore-in-progress state. That target accepts no
session. Two actions resolve such a target:

- Drop the target and restore again.
- Call `YouTrackDB.restartInterruptedRestore(...)` for that target through an embedded connection.

A restart deletes the interrupted target and restores the named backup into the same name. It
validates the complete chain before deletion. It also repeats target eligibility immediately
before deletion. A restart therefore refuses a healthy database without deleting it.

## Temporary disk space and cleanup

A restore needs temporary space for the complete selected chain. An interrupted-restore restart
needs that space beside the interrupted target because preparation happens before deletion.

The temporary files use the host temporary directory. Insufficient temporary space fails the
restore and leaves the target unchanged.

YouTrackDB attempts cleanup when each restore request ends. A preparation-phase failure uses
suppressing cleanup. A cleanup failure is attached to that preparation failure as a suppressed
failure.

After preparation returns, ordinary cleanup applies to target refusal, replay failure, and
successful activation. A cleanup failure only logs a warning that names the temporary path. It
never changes the restore result. The operator must remove that directory manually.

A process or host crash can also leave a temporary directory. YouTrackDB performs no automatic
reclamation of that directory. The operator must remove it manually.
