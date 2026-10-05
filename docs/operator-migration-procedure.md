# Database Migration Procedure (Export / Import)

This page is the operator runbook for migrating a YouTrackDB database between storage
format versions via JSON export/import. It is the **only** supported migration path: a
database created by an older release whose schema format differs is **not** migrated in
place — opening it with newer binaries is rejected with a redirect to this procedure
("Database schema is different. Please export your old database with the previous version
of YouTrackDB and reimport it using the current one.").

## How the tools are invoked

Export and import are **programmatic tools**, run from a small JVM program (or script
runner such as `jshell`/JBang) with the `youtrackdb-core` jar of the appropriate release
on the classpath. There is no query-language command and no standalone CLI for them. The
tool classes live in an internal package (`com.jetbrains.youtrackdb.internal.core.db.tool`)
— they are the supported migration surface, but their signatures may change between
releases, so always compile the snippet against the release you are running.

Both tools report progress through a listener and **signal every failure by throwing an
exception**. "Exit status" in this page therefore means the exit status of *your* wrapper
process: let the exception propagate out of `main` (do not catch and log it), and the JVM
exits non-zero on failure — that is the observable the gates below rely on.

## The procedure

1. **Export with the OLD release** (the release that created the database), keeping the
   database otherwise idle. In particular, **quiesce schema changes (DDL)** for the
   duration of the export: the records section is read from a single snapshot taken when
   the export begins, but the schema, collection, blob-collection, and index sections are
   read at later instants — an export taken under concurrent DDL is internally verifiable
   (the manifest counts what was written) but is not a cross-section point-in-time
   snapshot of the database.

   ```java
   import com.jetbrains.youtrackdb.api.YourTracks;
   import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
   import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseExport;

   public class ExportMyDb {
     public static void main(String[] args) throws Exception {
       var adminPassword = "<admin password>";
       try (var manager = (YouTrackDBImpl) YourTracks.instance("/path/to/databases")) {
         try (var session = manager.open("mydb", "admin", adminPassword)) {
           new DatabaseExport(session, "/backups/mydb.json.gz", System.out::print)
               .exportDatabase();
         }
       }
     }
   }
   ```

2. **Gate on the export exit status.** Proceed only if the wrapper process finished with
   **exit status 0** (equivalently: `exportDatabase()` returned without throwing) and the
   final dump file exists under its final name. On an in-process failure the export
   deletes its temporary file and promotes nothing; a **killed or crashed** export also
   promotes nothing but may leave temporary files behind (see
   [Crash residue from exports](#crash-residue-from-exports)). Either way: if the dump
   file is absent under its final name, the export did not complete — re-run it. Never
   transfer or import a dump whose export did not verifiably succeed.

3. **Create a FRESH target database with the NEW release** and keep it **out of
   service** — no application traffic, no other sessions — until the import has been
   verified. Never import into a database that already carries data you care about: the
   import deletes and replaces content.

4. **Import the dump** with the new release:

   ```java
   import com.jetbrains.youtrackdb.api.DatabaseType;
   import com.jetbrains.youtrackdb.api.YourTracks;
   import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
   import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseImport;

   public class ImportMyDb {
     public static void main(String[] args) throws Exception {
       var adminPassword = "<admin password>";
       try (var manager = (YouTrackDBImpl) YourTracks.instance("/path/to/databases")) {
         manager.create("mydb", DatabaseType.DISK, "admin", adminPassword, "admin");
         try (var session = manager.open("mydb", "admin", adminPassword)) {
           new DatabaseImport(session, "/backups/mydb.json.gz", System.out::print)
               .importDatabase();
         }
       }
     }
   }
   ```

5. **Gate on the import exit status.** Import success means: **exit 0 = every dump entry
   was consumed and verified against the manifest** (equivalently: `importDatabase()`
   returned without throwing). For a current-format (v15) dump that includes record,
   class, index, and broken-RID counts all cross-checked against the manifest, and the
   number of committed records checked against the declared record count. The compressed
   stream is fully consumed and validated. **A legacy (≤ 14) dump carries no
   manifest and receives no structural verification at all** — exit 0 there means only
   that every entry the dump contained was consumed; there is nothing to cross-check
   against, which is exactly why re-exporting with the old release on any doubt is
   cheaper than trusting a questionable legacy dump. Do not read exit 0 as a stronger
   claim than the above for legacy dumps: a record whose apply fails with a database-level
   error can be consumed but not applied on a legacy-declared dump. Legacy dumps can also
   contain records marked as schema manager or index manager. The importer discards those
   records, while records marked only `@internal` follow the normal commit path. Version 15
   rejects all three markers and any record-apply failure. Version 15 keeps class names
   exactly, including dots. A linked class missing from the schema stops the import.
   For a healthy dump
   produced by an honest exporter these arms are unreachable; if you need end-to-end
   certainty for a critical migration, verify application-level invariants (record
   counts per class, spot checks) after the import.

6. **Only then** put the target database into service.

## Any failure condemns the target

If the import fails **for any reason** — a rejected dump, a mid-import error, an
operator abort, a process **crash**, or a power loss — the partially imported target
database is **condemned**: discard it and import again into a fresh database. A crash
during import is equivalent to any other failure.

Two properties make this rule absolute:

- **A condemned target remains openable and carries no in-database signal.** A target
  whose import failed after the data phase began (for example on a manifest mismatch or
  a truncated stream detected at the end) looks like a normal database when opened —
  there is **no in-database signal** that distinguishes it from a healthy one. The only
  record that the import failed is the importer's thrown exception — your wrapper's exit
  status and output. Treat that exit status as the source of truth and discard the
  target on anything other than 0.
- **Rejections before any data lands are the exception, not the rule.** The importer
  validates the dump's info section (versions, mandatory fields) before touching the
  target, so those early rejections leave the target byte-for-byte untouched — but every
  later rejection is post-mutation by nature. Do not attempt to distinguish the cases in
  operation: on ANY failure, discard.

**Discarding** a condemned target is the ordinary drop:

```java
manager.drop("mydb");
```

then re-create and re-import into a fresh database. If the same dump would fail again,
resolve the cause in the source database and export again before retrying.

## What the importer accepts and rejects

| Dump | Outcome |
|---|---|
| Exporter version ≤ 14 (legacy dumps) | Imported through the legacy lenient path, unchanged — no manifest, section, or stream verification exists for these dumps |
| Exporter version 15 (current) | Imported under full structural validation: gzip framing mandatory, whole-stream verification, section presence and exporter order, manifest cross-check of parsed and committed records |
| Exporter version ≥ 16 (newer binaries) | Rejected with a redirect naming both versions — import it with a release that supports that exporter version |
| No / unparseable exporter version | Rejected (unverifiable input) |
| Schema version outside the supported range (v15 dumps) | Rejected naming the declared and supported versions — export again with a supported release, or import with a newer one. Legacy dumps skip this check (lenient path) |
| Manually re-compressed / gunzipped v15 dump | Rejected — only the original gzip-framed export file can be verified; there is no override |
| Best-effort-marked dump without the acknowledgment flag | Rejected regardless of the dump's declared version (see [Best-effort dumps](#best-effort-dumps)) |
| Tampered v15 dump (missing, duplicated, or reordered sections, trailing data) | Rejected loudly. Sections must appear in this order: info, collections, schema, records, brokenRids, indexes, manifest. Discard the target. Do not edit the dump. Export again from the source with the current exporter and import into a fresh target |
| V15 dump with a manifest count mismatch, including the number of committed records | Rejected loudly. Discard the target. Do not edit the dump. Export again from the source with the current exporter and import into a fresh target |
| V15 dump with a record marked as internal, schema manager, or index manager | Rejected naming the record ID. Discard the target. Do not edit the dump. Export again from the source with the current exporter and import into a fresh target |
| V15 dump with a schema class named exactly `___exportImportRIDMap` | Rejected naming the source class and reserved class. Discard the target. Drop this leftover helper class from the source database, then export again and import into a fresh target. Do not delete the class until its data is no longer needed |
| V15 dump with a record in the reserved `___exportImportRIDMap` class | Rejected naming the record ID. Discard the target. Remove the leftover helper class from the source database after preserving any needed data. Export again and import into a fresh target |
| V15 dump imported with `-migrateLinks=false` or `setMigrateLinks(false)` | Rejected after the info section, before target data is changed. Discard the target. Leave link migration enabled (the default) and import again into a fresh target |
| V15 dump with a missing linked class | Rejected naming the property and missing class. Discard the target. Fix the schema in the source database, export again, and import into a fresh target |
| V15 dump with a schema-apply or record-write error | Rejected naming the failed check. Schema errors name the class when the failure belongs to a class. Discard the target. Fix the named class in the source database, for example a missing superclass, and export again. If no class is named, use the error cause to find the schema problem. For record errors, fix the record named by its ID in the source database and export again. Import into a fresh target |
| Dump truncated inside a section, damaged/dangling info fields | Rejected loudly, on every path and for every declared version |

## Best-effort dumps

A default export **aborts** on the first record it cannot read — fail-fast, so a
successful export is complete by construction. If you must salvage a damaged source
database, pass `setOptions("-bestEffort=true")` on the exporter before running it:
unreadable records are skipped and recorded in the dump (`brokenRids`), and the dump is
marked best-effort. The importer refuses such a dump unless you acknowledge the possible
incompleteness explicitly — `setOptions("-acceptBestEffortDump=true")` on the importer.
Records referencing the broken RIDs have those links removed on import.

## Crash residue from exports

A **killed or crashed** export (in-process failures clean up after themselves) can
orphan two kinds of temporary files in the dump's directory:

- `<final-name>.<uuid>.tmp` — the export's private temporary file,
- `ytdb-export-record-*.spill` — a large-record spill buffer.

Both are fail-safe residue: they are never promoted to a dump and never mistaken for
one. Once no export is running, an operator may delete them at any time.

## Databases that crashed during creation ("genesis incomplete")

A database whose **creation** (genesis) was interrupted by a crash refuses to open: the
open fails loudly with a genesis-incomplete error. There is no automated self-heal —
drop the corpse (`manager.drop("mydb")` — the drop path deliberately works on such
corpses) and create the database again. The same applies to the server's internal
**OSystem** database: if its genesis crashed, the server refuses to start up loudly
until the corpse directory is discarded and the server can create it afresh (the OSystem
database has no drop surface — remove its directory while the server is down). This
refusal is deliberate — a half-created database must never be silently repaired or
partially served.
