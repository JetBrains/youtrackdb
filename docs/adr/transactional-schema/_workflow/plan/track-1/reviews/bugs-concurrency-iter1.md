<!--
MANIFEST
dimension: bugs-concurrency
prefix: BC
iteration: 1
commit_range: 057620f8a0~1..057620f8a0
verdict: pass
findings: 1
blocker: 0
should_fix: 0
suggestion: 1
evidence_base: 1 certainty entry (C1)
cert_index: C1
flags: none
index:
  - id: BC1
    sev: suggestion
    anchor: "#bc1-suggestion-consult-forward-scan-relies-on-an-undocumented-cross-record-precondition"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:5827-5834
    cert: C1
    basis: PSI find-usages + impl reads (LockFreeReadCache.addFile, WOWCache.addFile/exists, AbstractWriteCache.checkFileIdCompatibility); track-file F67 note
-->

## Findings

### BC1 [suggestion] Consult forward-scan relies on an undocumented cross-record precondition

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (line 5827-5834)
- **Issue**: `ensureFileForReplay` scans the *entire* atomic unit for the first
  `FileCreatedWALRecord` whose internal id matches the missing file and whose
  name is not yet present, then calls `readCache.addFile(name, id, writeCache)`.
  `readCache.addFile` delegates to `WOWCache.addFile(name, fileId)`, which throws
  `StorageException` when the name already maps to a positive id in `nameIdMap`
  (`existingFileId != null && existingFileId >= 0`). The only guard against that
  throw is `!writeCache.exists(fileCreated.getFileName())`. `exists(name)` is a
  *stronger* predicate than "no positive `nameIdMap` entry": it additionally
  requires `files.get(externalId) != null` and `fileClassic.exists()` on disk
  (verified in `WOWCache.exists(String)`). So an inconsistent cache state —
  positive `nameIdMap` entry for the name but no `files` entry / no on-disk file —
  would slip past the guard and make `addFile` throw, which the outer
  `catch (RuntimeException)` in `restoreFrom` would turn into the very
  later-unit discard this fix exists to prevent.
- **Evidence**: PSI-confirmed call chain
  `ensureFileForReplay` → `LockFreeReadCache.addFile(name,id,writeCache)`
  (asserts `id>=0`, `checkFileIdCompatibility`, then `writeCache.addFile(name,id)`)
  → `WOWCache.addFile(String,long)` (throws on positive `nameIdMap` entry). The
  guard `!writeCache.exists(name)` does not cover the positive-`nameIdMap`/
  absent-file window.
- **Refutation considered**: This window is **not reachable in the scenario the
  fix targets and is not newly introduced**. (1) In the targeted scenario the
  physical `addFile` was *lost*, and `nameIdMap.put` lives inside `addFile` under
  the same `filesLock` write section and is persisted by `writeNameIdEntry`; a
  lost `addFile` therefore leaves no `nameIdMap` entry at all, so `addFile`
  re-runs cleanly. (2) The existing `FileCreatedWALRecord` branch at
  AbstractStorage.java:5642 already uses the identical `!writeCache.exists(name)`
  guard before the same `readCache.addFile` call, so the consult inherits a
  pre-existing idiom rather than weakening recovery. (3) The track explicitly
  notes (F67) that the file-recycle shape emits no `FileCreatedWALRecord`, so the
  consult never fires for the one same-unit delete-then-recreate pattern that
  could otherwise produce a stale positive `nameIdMap` entry. Because the trigger
  requires a cache-state inconsistency outside the durable-end-record-before-apply
  window this track scopes itself to, this is a latent edge of a shared idiom, not
  a bug in this change.
- **Suggestion**: Optional. Either leave as-is (the idiom is established and the
  scenario unreachable), or add a one-line code comment at the consult noting that
  the `!exists(name)` guard assumes the name's `nameIdMap` entry and physical file
  are consistent — the same assumption the sibling `FileCreatedWALRecord` branch
  makes — so a future reader does not mistake the guard for full collision
  protection. No behavior change recommended.

## Evidence base

#### C1 — Consult correctness, id-encoding safety, and discard-path preservation

The three load-bearing correctness questions all resolved in the fix's favor; the
one residual edge is captured as BC1 (suggestion).

- **Id-encoding pass-through after backup/restore — CONFIRMED correct (survived).**
  The consult matches a `FileCreatedWALRecord` on `internalFileId` but passes the
  record's *external* id (`fileCreated.getFileId()`, possibly carrying stale high
  bits from a different storage) into `readCache.addFile`. `LockFreeReadCache.addFile`
  calls `AbstractWriteCache.checkFileIdCompatibility(writeCache.getId(), fileId)`,
  which is `composeFileId(storageId, extractFileId(fileId))` — it discards the high
  32 bits and recomposes with the *live* storage id. The match-on-internal /
  pass-external pattern is therefore correct, and is byte-for-byte what the existing
  `FileCreatedWALRecord` branch (AbstractStorage.java:5642) already does. The
  Javadoc's claim ("matched on internalFileId because backup/restore rewrites the
  external high bits") is accurate.

- **Raw-vs-normalized fileId at the call site — CONFIRMED correct (survived).**
  `ensureFileForReplay(atomicUnit, fileId)` is invoked with the raw record
  `fileId` *before* the `fileId = writeCache.externalFileId(writeCache.internalFileId(fileId))`
  re-normalization line in both arms. Inside the method, `exists(long)` and
  `internalFileId(long)` use only the low 32 bits, so the raw id is equivalent to
  the normalized id for every operation the method performs. This matches the
  pre-fix inline code, which also called `writeCache.exists(fileId)` on the raw id.

- **Genuinely-incomplete discard preserved — CONFIRMED correct (survived).**
  The final `throw new StorageException(...)` is reached only when neither the
  pending-create consult nor `restoreFileById` recovers the file. PSI walk of the
  type chain confirms `StorageException` extends `CoreException` → `BaseException`
  → `java.lang.RuntimeException`, so it still routes to `restoreFrom`'s
  `catch (RuntimeException)` (AbstractStorage.java:5602) exactly as before. The
  `testGenuinelyIncompleteUnitStillThrows` regression pins this.

- **Thread safety — no finding.** `restoreAtomicUnit`/`ensureFileForReplay` run on
  the single recovery thread (open-time `restoreFromBeginning` and IBU
  `DiskStorage.restoreFromIncrementalBackup`, the only two PSI-confirmed callers of
  `restoreFrom`, with zero overrides). `ensureFileForReplay` is a stateless private
  instance method introducing no shared mutable field; the caches it touches take
  their own `filesLock` internally. No new lock order, publication, or race.

- **`restoreFrom` caller / override topology (PSI):** `restoreFrom` has exactly two
  production callers (`AbstractStorage.restoreFromBeginning` ~5505,
  `DiskStorage.restoreFromIncrementalBackup` ~1883) and zero overrides;
  `restoreAtomicUnit` and `ensureFileForReplay` have zero overrides; the only
  non-test caller of `restoreAtomicUnit` is `restoreFrom` itself. The fix's blast
  radius is confined to the shared replay path, matching the track's stated scope.
