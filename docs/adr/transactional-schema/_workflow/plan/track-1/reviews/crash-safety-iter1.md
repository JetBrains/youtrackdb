<!--
MANIFEST
dimension: crash-safety
iteration: 1
commit_range: 057620f8a0~1..057620f8a0
verdict: pass
findings: 2
evidence_base: present
cert_index: present
flags: []
index:
  - id: CS1
    sev: suggestion
    anchor: "#cs1-suggestion-ibu-path-runs-the-consult-with-an-empty-deletednondurablefileids-set-document-the-coupling"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:5820-5851"
    cert: C1
    basis: "PSI find-usages (restoreFrom 2 callers / 0 overrides); read of DiskStorage.restoreFromIncrementalBackup and the deletedNonDurableFileIds writer sites"
  - id: CS2
    sev: suggestion
    anchor: "#cs2-suggestion-consult-relies-on-the-addfile-shrink0-invariant-add-an-assertion-or-comment-pinning-it"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:5826-5833"
    cert: C2
    basis: "Read of WOWCache.addFile(String,long,boolean) and WOWCache.exists(long); cross-checked against the pre-existing FileCreatedWALRecord replay branch"
-->

## Findings

### CS1 [suggestion] IBU path runs the consult with an empty `deletedNonDurableFileIds` set — document the coupling

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (line 5820-5851), with context at `DiskStorage.java:1872-1883` and `AbstractStorage.java:4739-4745`.

**Crash scenario**: An incremental-backup restore (`DiskStorage.restoreFromIncrementalBackup`) replays a WAL containing a committed file-creating unit whose page redo precedes its own `FileCreatedWALRecord`. The new consult fires and materializes the file. This is not a crash window per se; it is the second `restoreFrom` caller exercising the changed code with different surrounding state.

**Evidence**: `deletedNonDurableFileIds` is populated only inside the open-time recovery block (`AbstractStorage.java:4739`, then reset to a fresh empty set at `4745`). The IBU caller at `DiskStorage.java:1883` does not repopulate it, so when `restoreFrom` runs from IBU, `deletedNonDurableFileIds` is the empty set left behind by open-time recovery. The two `deletedNonDurableFileIds.contains(...)` guards (5653, 5711) that gate `ensureFileForReplay` therefore never short-circuit on the IBU path. That is harmless — IBU has no non-durable-file concept — but the new helper's Javadoc states "the caller has already applied the `deletedNonDurableFileIds` skip," which reads as a universal precondition when it is in fact empty for one of the two callers.

**Recovery impact**: None observed. On the IBU path the consult either finds a matching `FileCreatedWALRecord` and materializes the file (correct), falls through to `restoreFileById`, or throws for a genuinely incomplete unit — the same three outcomes as the open-time path. IBU additionally pre-deletes orphan files at `DiskStorage.java:1872-1877` before replay, which does not collide with the consult (deleted files are absent, so the consult's `!writeCache.exists` precondition holds and a later create record re-adds them).

**Refutation considered**: I checked whether the empty skip-set on the IBU path could let the consult re-materialize a file the IBU teardown intentionally deleted. It cannot: IBU deletes orphans that the backup does not contain, and any such file has no `FileCreatedWALRecord` in the replayed log (it was not created in the backed-up window), so the consult's forward scan finds nothing and falls through. No data-loss or double-materialize path exists.

**Suggestion**: Soften the helper's Javadoc to note that the `deletedNonDurableFileIds` skip is applied by the open-time caller and is an empty no-op set on the IBU path, so a future reader does not assume the skip is always meaningful before this helper runs. No code change.

### CS2 [suggestion] Consult relies on the `addFile` `shrink(0)` invariant — add an assertion or comment pinning it

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (line 5826-5833).

**Crash scenario**: A committed file-creating unit's physical `addFile` was lost in the crash window, but a `FileClassic` object for that id remains registered in `WOWCache.files` (for example, a partially-initialized registration) while the physical file on disk does not exist. The consult calls `readCache.addFile(name, id, writeCache)`, which routes to `WOWCache.addFile(fileName, fileId, false)` and reaches `fileClassic.shrink(0)`.

**Evidence**: In `WOWCache.addFile(String,long,boolean)`, when `nameIdMap.get(fileName)` is null (the consult's precondition, since `!writeCache.exists(name)` was checked at line 5830) and `files.get(fileId)` is non-null, the method calls `fileClassic.shrink(0)` — it truncates the existing `FileClassic` to zero length. The torn-recovery danger would be: this truncates a file that still holds committed data. The danger does not materialize because the consult only runs when `!writeCache.exists(fileId)`, and `WOWCache.exists(long)` returns true iff `files.get(...) != null && file.exists()`. So at consult time either the `FileClassic` is unregistered (the `else` branch creates a fresh file, no truncation) or the physical file does not exist (`shrink(0)` on an absent file is a no-op). Either way no live data is truncated.

**Recovery impact**: None. The new consult uses the identical `readCache.addFile(name, id, writeCache)` call the pre-existing `FileCreatedWALRecord` replay branch uses (`AbstractStorage.java:5642-5647`), and both lean on the same `!writeCache.exists` invariant. The fix introduces no new truncation path.

**Refutation considered**: I traced both `addFile` arms (`files.get` non-null vs null) against the `WOWCache.exists(long)` definition to confirm the `shrink(0)` branch cannot reach a file with live content under the consult's precondition. Confirmed safe; reporting only because the safety is invariant-dependent and silent — a future change to `exists` or `addFile` could break it without an obvious failure at this call site.

**Suggestion**: Optionally add a one-line comment at the consult `addFile` call noting that the `!writeCache.exists` precondition is what makes the downstream `shrink(0)` a no-op (so no committed file is truncated), mirroring the reasoning already present on the sibling `FileCreatedWALRecord` branch. No code change required.

## Evidence base

#### C1 — IBU path consult coupling (CS1)
- **Reference accuracy (PSI)**: `restoreFrom` has exactly 2 callers — `AbstractStorage.restoreFromBeginning` (`AbstractStorage.java:5505`, open-time) and `DiskStorage.restoreFromIncrementalBackup` (`DiskStorage.java:1883`, IBU) — and 0 overrides. `ensureFileForReplay` is private with exactly 2 call sites (`5657`, `5715`) and 0 overrides. The load-bearing two-caller / zero-override claim is PSI-confirmed, not grep-inferred.
- `deletedNonDurableFileIds` writer sites: `AbstractStorage.java:4739` (populate, open-time only) and `4745` (reset to empty, in the `finally` of the open-time replay). No IBU repopulation. The IBU `restoreFrom` therefore runs with the empty set.
- **Phase-5 refutation (survived → one line)**: "Empty skip-set on IBU lets the consult re-materialize an intentionally-deleted file" — CONFIRMED non-issue: orphan files deleted at `DiskStorage.java:1872-1877` have no `FileCreatedWALRecord` in the replayed window, so the consult's forward scan finds nothing and falls through; reduced the finding to documentation-only (suggestion).

#### C2 — `addFile` `shrink(0)` invariant (CS2)
- `WOWCache.addFile(String,long,boolean)`: the `fileClassic.shrink(0)` truncation is reached only when `files.get(fileId) != null`; the `else` branch creates a fresh file.
- `WOWCache.exists(long)`: returns `files.get(composeFileId(id,intId)) != null && file.exists()`.
- `LockFreeReadCache.addFile(String,long,WriteCache)` → `checkFileIdCompatibility` (strips/recomposes storage high bits) → `writeCache.addFile(fileName, fileId)` → `addFile(fileName, fileId, false)`. The consult's `internalFileId` match (`5829`) is the correct equality under backup high-bit rewrite, since `internalFileId`/`extractFileId` mask to the low 32 bits.
- Pre-existing parity: the `FileCreatedWALRecord` replay branch (`AbstractStorage.java:5642-5647`) uses the same `readCache.addFile(name, id, writeCache)` under the same `!writeCache.exists(name)` guard. The consult does not introduce a new write or truncation path.
- **Phase-5 refutation (survived → one line)**: "Consult truncates a file holding committed data via `shrink(0)`" — CONFIRMED non-issue: the `!writeCache.exists(fileId)` precondition forces either an unregistered `FileClassic` (fresh-create branch) or an absent physical file (`shrink(0)` no-op); no live data is truncated. Reduced to documentation-only (suggestion).

#### Refuted / non-passing claims (full record)
- **R1 — "A page redo is applied before the file's WAL `FileCreatedWALRecord` is consulted, so the file's create is not logged before the mutation (WAL-ordering violation)."** REFUTED. The `FileCreatedWALRecord` is present in the same durable atomic unit (the unit's end record is durable — that is the F55 premise); the consult scans the *whole* unit, not just records after the current position, so it finds the create regardless of intra-unit ordering. WAL contains the create before the crash; only the *physical apply* was lost. The mutation's redo reconstructs page content from the `UpdatePageRecord.getChanges()` / `PageOperation.redo`, which is itself the durable WAL record. No mutation reaches disk without its WAL record having been made durable first. Safe.
- **R2 — "The materialized empty file plus the unit's own later `FileCreatedWALRecord` double-create the file (non-idempotent replay)."** REFUTED. After the consult's `readCache.addFile`, `writeCache.exists(name)` becomes true, so the unit's own `FileCreatedWALRecord` branch (`5642`) fails its `!writeCache.exists(fileName)` guard and replays as a no-op. The regression test `wireConsultMaterializes` models exactly this `exists`-flip transition. Idempotent. Safe.
- **R3 — "The genuinely-incomplete-unit discard path is weakened, so a unit whose create was never made durable is now silently restored."** REFUTED. `ensureFileForReplay` still throws `StorageException` when neither the pending-create consult nor `restoreFileById` recovers the file (`5847-5850`); the outer `catch (RuntimeException)` in `restoreFrom` still discards later units in that case. The regression `testGenuinelyIncompleteUnitStillThrows` asserts the throw survives. The fix narrows the throw to genuinely-unrecoverable units; it does not erase it. Safe.
- **R4 — "Page LSN handling changed, risking lost updates on replay."** REFUTED. The fix touches only file materialization; the LSN-gated redo blocks (`UpdatePageRecord` 5681-5700, `PageOperation` 5741-5755), the `loadOrAddForWrite` totality assert, the pin/`releaseFromWrite` balance in the `finally`, and `setLsn` after redo are all unchanged by the diff. No LSN-comparison or page-LSN-propagation path is in scope. Safe.
