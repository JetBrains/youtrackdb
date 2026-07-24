<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 1, suggestion: 2}
index:
  - {id: TY1, sev: should-fix, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:1812, anchor: "### TY1 ", cert: C1, basis: "5 commit-window lock-free primitives read/mutate stateLock-guarded plain-list state with no loud out-of-window guard; a future off-window caller races silently instead of failing under -ea, and advanceVersion in this same diff already asserts its analogous precondition"}
  - {id: TY2, sev: suggestion, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CommitTimeIndexBuildTest.java:655, anchor: "### TY2 ", cert: C2, basis: "the Step-2 @Ignore crash breadcrumb scopes to engine build/delete only; the Step-4 provisional-id-in-durable-bytes crash arm (I-A2) has no deferral marker even though Step 4 is tagged Crash-safety/Durability"}
  - {id: TY3, sev: suggestion, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CommitTimeIndexBuildTest.java:322, anchor: "### TY3 ", cert: C3, basis: "failed-commit tests assert only in-process rollback cleanliness; no reopen confirms the durable config carries no phantom engine after a failed disk-profile commit (the I-A4 durable arm)"}
evidence_base: {section: "## Evidence base", certs: 3, matches: 3}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: PLAUSIBLE, anchor: "#### C3 "}
flags: [CONTRACT_OK]
-->

## Findings

### TY1 [should-fix] Commit-window lock-free primitives document a write-lock precondition but never assert it

**Production code:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java`
- `getApproximateRecordsCountInCommitWindow` (line 1812)
- `getExactRecordsCountInCommitWindow` (line 1836)
- `createIndexEngineInCommitWindow` (line 3286)
- `deleteIndexEngineInCommitWindow` (line 3369)
- `isIndexEngineRegisteredInCommitWindow` (line 3612)

**Invariant.** Each of these five methods reads or mutates `collections` / `indexEngines` (plain `ArrayList`s, not concurrent structures) without taking `stateLock`, and every Javadoc states the same contract verbatim: "Must be called with `stateLock.writeLock()` held and the commit window open." The held write lock is the sole source of both the mutual exclusion and the happens-before edge that makes the plain-list reads safe. The exact predicate for that state already exists in this class: `isCommitWindowActive()` (line 4582) returns true iff "the current thread is inside a commit window ... and so holds `stateLock.writeLock()`", and `callIndexEngine` (line 4463) already branches on it.

**Why it matters (evidence C1).** The contract is enforced only by prose. A future caller that invokes one of these lock-free primitives from an ordinary (non-commit) path compiles and runs: it reads `indexEngines`/`collections` with no lock and no happens-before edge, so it can observe a torn or stale slot (silent index/collection corruption) or busy-spin on nothing. Assertions cost nothing in production (disabled by default) and the coverage gate already excludes `assert` lines, so this is a zero-cost tripwire that would fail loudly under `-ea` the moment a misplaced caller appears. The same diff sets the precedent: `advanceVersion` (line 3607) asserts `lock.isWriteLockedByCurrentThread()` for exactly this class of precondition, and `createIndexEngineInCommitWindow` already asserts its slot-is-free precondition — the window/lock precondition is the one left unguarded.

**Suggested assertion** (add as the first statement of each of the five methods):

```java
assert isCommitWindowActive()
    : "commit-window primitive called outside the commit window (stateLock.writeLock() not held)";
```

`isCommitWindowActive()` is `private` and every target method is in `AbstractStorage`, so no visibility change is needed. The condition is a single existing predicate call, so no helper extraction is required for the JaCoCo/assert trap.

**Catches:** a refactor that reuses any commit-window primitive from a non-commit path (the classic "this read is cheap, I'll call the lock-free variant" mistake), which would otherwise surface as a hard-to-reproduce data race or an infinite busy-spin rather than a named assertion failure at the call site.

### TY2 [suggestion] The commit-time crash-deferral breadcrumb covers only the engine arm, not the Step-4 provisional-id durable-bytes arm

**File:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CommitTimeIndexBuildTest.java` (line 655, `crashRecoveryOfCommitTimeIndexEngineIsDeferredToIT`)

**Evidence (C2).** Step 2 added an `@Ignore`d placeholder documenting that hard-crash + WAL-replay recovery of the commit-time engine build/delete is deferred to the `LocalPaginatedStorageRestoreFromWALIT` layer. That breadcrumb is correct and mirrors the Track-4 collection-arm precedent (`SchemaCommitReconciliationTest`). Its Javadoc, however, scopes explicitly to "a commit-time index engine build/delete." Step 4 introduced a second Crash-safety/Durability surface — the provisional-collection-id-in-RID rewrite whose durability claim is I-A2 (no provisional id `<= -2` reaches durable bytes). That arm is covered on the **clean-reopen** path (`TxAwareSchemaSnapshotTest.committedTxCreatedClassRecordCarriesARealCollectionIdAfterReopen`, which flushes via `reload` + `reOpen`) and by the in-process `computeCommitWorkingSet` assert, but the **hard-crash** arm (crash after the provisional-id rewrite and after the commit's WAL is durable but before pages flush, then reopen and confirm the record carries a real id, never a provisional one, in the replayed bytes) has no marker at all.

**Missing scenario.** A visible, self-documenting deferral for the Step-4 durable arm, so the I-A2 crash gap is as discoverable at the test surface as the engine-arm gap. This is the cross-step observation a single-step review could not make: the two Crash-safety/Durability steps share one crash-recovery IT dependency, but only one of them left a breadcrumb.

**Suggested test** (an `@Ignore`d placeholder in `TxAwareSchemaSnapshotTest`, mirroring line 655):

```java
@Test
@Ignore("crash recovery of the provisional-id-in-RID rewrite (I-A2): after a crash between the "
    + "schema-carry commit becoming WAL-durable and the page flush, WAL replay must land the "
    + "tx-created class's record under its reconciled real collection with no provisional id in "
    + "durable bytes; needs the LocalPaginatedStorageRestoreFromWALIT harness, deferred to the IT layer")
public void crashRecoveryOfProvisionalIdRewriteIsDeferredToIT() {
  // Intentionally empty: forceDatabaseClose after commit-durable, reopen, assert the reloaded
  // record's collection id is >= 0 and the row survives (or is absent if the crash preceded commit).
}
```

### TY3 [suggestion] Failed-commit engine tests verify only in-process rollback cleanliness, never a reopen confirming durable cleanliness

**File:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CommitTimeIndexBuildTest.java`
- `failedEngineCreatingCommitLeavesNoPhantomEngineAndReusesId` (line 322)
- `failedDropCommitLeavesTheSurvivingCommittedIndexUsable` (line 393)

**Evidence (C3).** Both failed-commit tests inject a `CommandInterruptedException` through `postEngineBuildTestHook` and then assert against the still-OPEN storage: the in-memory engine registry carries no phantom entry and the freed id is reused (create side), and the surviving committed index is queryable (drop side). This exercises the in-process rollback plus the in-process undo/restore arms (`undoReconciledIndexEngines`, `restoreReconciledDroppedIndexEngines`). Those arms are rollback mechanisms, not crash-recovery mechanisms — on a real crash they never run, and recovery instead re-derives the registry from durable config on reopen. The I-A4 durable arm ("the engine's config/file intent actually reverted with the atomic operation on disk") is therefore verified only transitively through in-memory id-reuse, never by a reopen that re-reads the durable config.

**Missing scenario.** After the failed commit, add a `reOpen(...)` and re-assert `engineIsRegistered(indexName)` is false (create side) and the survivor's engine loads (drop side). On the disk profile (`-Dyoutrackdb.test.env=ci`) this re-derivation from durable config catches a regression where `createIndexEngineInCommitWindow`'s `configuration` write did not revert with the rolled-back atomic operation — a durability hole the current in-memory-only assertions would miss. This is a cheap add short of the full crash IT that TY2 tracks; the two are complementary (TY3 hardens the existing rollback tests; TY2 marks the genuinely-deferred hard-crash arm).

**Suggested test skeleton** (extend the create-side test):

```java
// after the failed commit and the in-memory phantom assertions:
reOpen("admin", ADMIN_PASSWORD);
assertFalse("a failed engine-creating commit must leave no phantom engine in the durable config",
    engineIsRegistered(indexName));
```

## Evidence base

#### C1 CONFIRMED — commit-window primitives lack the write-lock precondition assert
Refutation attempted: "is the precondition enforced by something other than prose (a caller-side assert, re-entrancy, or an internal lock)?" Refuted on all three — the five methods take no `stateLock` (that is their entire purpose, per each Javadoc), carry no window/lock assert (only `createIndexEngineInCommitWindow` and `deleteIndexEngineInCommitWindow` assert unrelated slot/id invariants), and read plain `ArrayList` state; `isCommitWindowActive()` (line 4582) is the available and exact predicate, and `advanceVersion` (line 3607) already asserts the analogous write-lock precondition in this same diff, so the omission is an inconsistency, not a considered choice. Finding survives: add the zero-cost precondition assert.

#### C2 CONFIRMED — Step-4 durable-bytes crash arm has no deferral marker
Refutation attempted: "does the Step-2 engine breadcrumb, or an existing test, already cover the Step-4 I-A2 hard-crash arm?" Refuted — the `@Ignore` Javadoc at line 655 scopes verbatim to "a commit-time index engine build/delete"; the only I-A2 durable coverage (`committedTxCreatedClassRecordCarriesARealCollectionIdAfterReopen`) uses a flushing clean reopen, not a `forceDatabaseClose`/WAL-replay crash. Finding survives as a suggestion (a real crash test is reasonably deferred to the IT harness, matching the accepted step-level disposition; what is missing is the visible marker for the second durable surface).

#### C3 PLAUSIBLE — failed-commit tests never reopen to confirm durable cleanliness
Refutation attempted: "is a reopen redundant because the in-process rollback already reverts the durable config write?" Partially holds — `createIndexEngineInCommitWindow` routes its config write through the commit atomic operation, so on the disk profile the rollback should revert it, which means the reopen is expected to be green rather than exposing a known defect. The finding is therefore a hardening, not a proven gap: today the only assertion of durable config cleanliness after a failed engine-creating commit is the transitive in-memory id-reuse check, so a regression that left the durable config entry behind (while the in-memory undo arm cleaned the map) would pass the current tests and fail only a reopen. Rated suggestion; the disk profile is where it has teeth, and it is strictly cheaper than the deferred crash IT.
