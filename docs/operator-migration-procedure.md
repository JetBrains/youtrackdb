# Database Migration Procedure (Export / Import)

This page is the operator runbook for migrating a YouTrackDB database between storage
format versions via JSON export/import. It is the **only** supported migration path: a
database created by an older release whose schema format differs is **not** migrated in
place — opening it with newer binaries is rejected with a redirect to this procedure
("Database schema is different. Please export your old database with the previous version
of YouTrackDB and reimport it using the current one.").

## The procedure

1. **Export with the OLD release** (the release that created the database):

   ```
   EXPORT DATABASE /backups/mydb.json.gz
   ```

   or the equivalent `DatabaseExport` tool invocation.

2. **Gate on the export exit status.** Proceed only if the export finished with **exit
   status 0** and the final dump file exists under its final name. A failed or interrupted
   export deletes its temporary file and promotes nothing — if the dump file is absent,
   the export did not complete; re-run it. Never transfer or import a dump whose export
   did not verifiably succeed.

3. **Create a FRESH target database with the NEW release** and keep it **out of
   service** — no application traffic, no other sessions — until the import has been
   verified. Never import into a database that already carries data you care about: the
   import deletes and replaces content.

4. **Import the dump** with the new release:

   ```
   IMPORT DATABASE /backups/mydb.json.gz
   ```

5. **Gate on the import exit status.** Import success means: **exit 0 = every dump entry
   was consumed and verified against the manifest** (record, class, index, and broken-RID
   counts all cross-checked, and the compressed stream fully consumed and validated). Do
   not read exit 0 as a stronger claim than that: two narrow, deliberately documented
   arms exist in which an individual dump entry is consumed but not applied (a record
   whose apply fails with a database-level error on a legacy-declared dump, and a
   metadata-tampered record marked as a schema/index-manager record, which is deleted
   silently). For a healthy dump produced by an honest exporter these arms are
   unreachable; if you need end-to-end certainty for a critical migration, verify
   application-level invariants (record counts per class, spot checks) after the import.

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
  record that the import failed is the importer's own exit status and output. Treat the
  exit status as the source of truth and discard the target on anything other than 0.
- **Rejections before any data lands are the exception, not the rule.** The importer
  validates the dump's info section (versions, mandatory fields) before touching the
  target, so those early rejections leave the target byte-for-byte untouched — but every
  later rejection is post-mutation by nature. Do not attempt to distinguish the cases in
  operation: on ANY failure, discard.

## What the importer accepts and rejects

| Dump | Outcome |
|---|---|
| Exporter version ≤ 14 (legacy dumps) | Imported through the legacy lenient path, unchanged |
| Exporter version 15 (current) | Imported under full structural validation: gzip framing mandatory, whole-stream verification, section presence, manifest cross-check |
| Exporter version ≥ 16 (newer binaries) | Rejected with a redirect naming both versions — import it with a release that supports that exporter version |
| No / unparseable exporter version | Rejected (unverifiable input) |
| Schema version outside the supported range | Rejected naming the declared and supported versions — export again with a supported release, or import with a newer one |
| Manually re-compressed / gunzipped v15 dump | Rejected — only the original gzip-framed export file can be verified; there is no override |
| Tampered v15 dump (missing/duplicated sections, count mismatches, trailing data) | Rejected loudly; the target is condemned |

## Best-effort dumps

A default export **aborts** on the first record it cannot read — fail-fast, so a
successful export is complete by construction. If you must salvage a damaged source
database, export with `-bestEffort=true`: unreadable records are skipped and recorded in
the dump (`brokenRids`), and the dump is marked best-effort. The importer refuses such a
dump unless you acknowledge the possible incompleteness explicitly with
`-acceptBestEffortDump=true`. Records referencing the broken RIDs have those links
removed on import.

## Crash residue from exports

A killed or crashed export can orphan two kinds of temporary files in the dump's
directory:

- `<final-name>.<uuid>.tmp` — the export's private temporary file,
- `ytdb-export-record-*.spill` — a large-record spill buffer.

Both are fail-safe residue: they are never promoted to a dump, never mistaken for one,
and an operator may delete them at any time.

## Databases that crashed during creation ("genesis incomplete")

A database whose **creation** (genesis) was interrupted by a crash refuses to open: the
open fails loudly with a genesis-incomplete error. There is no automated self-heal —
discard the database's directory and create the database again. The same applies to the
server's internal **OSystem** database: if its genesis crashed, the server refuses to
start up loudly until the corpse directory is discarded and the server can create it
afresh. This refusal is deliberate — a half-created database must never be silently
repaired or partially served.
