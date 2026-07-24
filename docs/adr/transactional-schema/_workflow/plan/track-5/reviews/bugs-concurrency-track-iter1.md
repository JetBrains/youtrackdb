<!-- MANIFEST
findings: 4   severity: {blocker: 0, should-fix: 0, suggestion: 4}
index:
  - {id: BC1, sev: suggestion, loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/gql/executor/GqlExecutionPlanCache.java:98", anchor: "### BC1 ", cert: C1, basis: "Gremlin plan cache has no schema-tx bypass (the YQL sibling got one this track); a Gremlin query in a schema tx can serve stale rows or leak a provisional-id plan cross-session"}
  - {id: BC2, sev: suggestion, loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexAbstract.java:1211", anchor: "### BC2 ", cert: C2, basis: "onIndexEngineChange reload fallback calls readLock-taking loadIndexEngine; latent commit-window busy-spin, unreachable in normal build"}
  - {id: BC3, sev: suggestion, loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/id/ChangeableRecordId.java:87", anchor: "### BC3 ", cert: C3, basis: "setCollectionId no-op guard compares new id against old position (wrong field); pre-existing, not triggered by this track, sole prod caller immune"}
  - {id: BC4, sev: suggestion, loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:3546", anchor: "### BC4 ", cert: C4, basis: "engineFilesPresent prefix match false-positives across sibling index names; benign over-report on best-effort cleanup path"}
evidence_base: {section: "## Evidence base", certs: 9, matches: 4}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4, verdict: CONFIRMED, anchor: "#### C4 "}
  - {id: C5, verdict: REFUTED, anchor: "#### C5 "}
  - {id: C6, verdict: REFUTED, anchor: "#### C6 "}
  - {id: C7, verdict: REFUTED, anchor: "#### C7 "}
  - {id: C8, verdict: REFUTED, anchor: "#### C8 "}
  - {id: C9, verdict: REFUTED, anchor: "#### C9 "}
flags: [CONTRACT_OK]
-->

## Findings

No blockers and no should-fix issues survived verification. The commit-time
index reconciliation, the provisional-id-in-RID mechanism, the tx-aware snapshot,
and the tx-local overlay are correct and well-tested; the deadlock, cross-session
leak, and durable-provisional-id hazards this dimension worries about are all
handled (see certs C5-C9). The four suggestions below are latent or out-of-diff
imperfections worth closing, none of which blocks merge.

### BC1 [suggestion] Gremlin plan cache lacks the schema-tx bypass its YQL sibling gained

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gql/executor/GqlExecutionPlanCache.java` (getInternal line 98, putInternal line 83)

**Issue**: This track added a schema-tx bypass to `YqlExecutionPlanCache`
(`getInternal` returns null and `put` skips while `db.getTxSchemaState() != null`,
`YqlExecutionPlanCache.java:99` and `:140`), so a query planned during a
schema/index transaction re-plans against the tx-aware snapshot and never leaks a
tx-shaped plan into the shared cross-session cache. The sibling
`GqlExecutionPlanCache` received no such guard. A Gremlin query run inside a
schema-changing transaction can (a) serve a stale committed-state plan that misses
a tx-created subclass and returns wrong rows, and (b) publish a plan shaped by the
tx-local schema (its scan set carrying a tx-created subclass's provisional
collection id) into the shared cache, where a foreign session then scans a
collection that does not exist and the entry survives the rollback, since only a
schema commit invalidates the cache.

**Evidence**: `YqlExecutionPlanCache.getInternal` (line 140) and `put` (line 99)
short-circuit on `getTxSchemaState() != null`. `GqlExecutionPlanCache.getInternal`
(line 98) and `putInternal` (line 83) call `cache.getIfPresent` / `cache.put` with
no tx-state check (grep confirms the file has no `getTxSchemaState` reference). The
Step 4 episode already records this as a residual follow-up.

**Refutation considered**: The file is outside the track diff, but the asymmetry is
a direct product of this track's YQL change, and the wrong-rows / cross-session-leak
shape is identical to the YQL bug the track deliberately fixed. Not merely a
pre-existing gap: the track made same-tx schema queries correct for YQL and left
Gremlin behind.

**Suggestion**: Mirror the YQL guard in `GqlExecutionPlanCache.getInternal` and
`putInternal` (bypass when `db.getTxSchemaState() != null`), or file and link an
explicit follow-up so a Gremlin-during-schema-tx query is not silently wrong.

### BC2 [suggestion] onIndexEngineChange's reload fallback would busy-spin on stateLock inside the commit window

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexAbstract.java:1211` (reached from `buildEngineAtCommit`, same file ~line 261-274)

**Issue**: `buildEngineAtCommit` runs inside the commit window holding
`stateLock.writeLock()` and calls `onIndexEngineChange`, whose `while (true)` retry
catches `InvalidIndexEngineIdException` and calls `doReloadIndexEngine()` →
`storage.loadIndexEngine(name)`, which takes `stateLock.readLock()` unconditionally.
The rest of the commit window is carefully lock-free (`getIndexEngine`,
`callIndexEngine`, `getCollectionIdByName`, `readRecord` all self-route on
`isCommitWindowActive()`). If that catch branch ever fires inside the commit
window, `loadIndexEngine` busy-spins forever on the non-reentrant `stateLock`,
hanging the commit thread with no signal.

**Evidence**: `callIndexEngine` gained a commit-window lock-free body
(`AbstractStorage.java:4597`), but its caller's recovery path
(`doReloadIndexEngine` at `IndexAbstract.java:1211`) still routes to the
readLock-taking `loadIndexEngine(String)`. The path is unreachable in the normal
build: `createIndexEngineInCommitWindow` publishes the engine at `indexId`
immediately before `onIndexEngineChange`, so `checkIndexId` passes and the
exception does not fire. So the hazard is latent, not a live deadlock, and the
`assert indexId >= 0` only guards the negative-id case under `-ea`.

**Refutation considered**: Traced `buildEngineAtCommit` →
`createIndexEngineInCommitWindow` (publishes engine) → `onIndexEngineChange` →
`callIndexEngine`; `checkIndexId` cannot fail right after publish. Confirmed the
recovery branch nonetheless calls a readLock-taking method, inconsistent with the
track's otherwise-uniform lock-free commit-window discipline.

**Suggestion**: Make `doReloadIndexEngine` (or the `onIndexEngineChange` retry)
commit-window-aware, or fail hard rather than retry-reload inside the commit window,
so a future regression cannot turn this defensive branch into a silent commit hang.

### BC3 [suggestion] ChangeableRecordId.setCollectionId compares the new id against the old position

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/id/ChangeableRecordId.java:87`

**Issue**: The no-op guard reads `if (collectionId == oldRecordId.collectionPosition())
return;`. It compares the incoming `collectionId` against the old
`collectionPosition` instead of the old `collectionId`. When those two values
coincide the method returns early, skipping both `checkCollectionLimits` and the
actual id change; conversely, on the real no-op (new id equals old id) it does not
short-circuit and fires the identity-change listeners over an unchanged rid. The
correct guards in `setCollectionPosition` (line 106) and `setCollectionAndPosition`
(lines 123-124) compare like fields.

**Evidence**: PSI find-usages returns three callers: two tests and
`DatabaseSessionEmbedded.newInternalInstance` (`:1015`). The file is not in the
track diff. Step 4's provisional-id rewrite routes through
`setCollectionAndPosition` (correct field), so it never reaches this guard. The sole
production caller is immune: it sets a real (`>= 0`) internal collection id on a
fresh `ChangeableRecordId` whose initial position is a negative sentinel, so the two
values live in disjoint ranges and never collide. The Step 4 episode already flags
this as a latent wrong-field guard.

**Refutation considered**: Confirmed via PSI that no provisional-id path in this
track reaches `setCollectionId`. Genuinely not triggered, but it is a real
wrong-comparison bug sitting one method away from the track's core mechanism: a
future change that switches the rewrite to `setCollectionId` would silently no-op a
real collection rewrite whenever the new id equals the old position.

**Suggestion**: Fix the guard to compare `oldRecordId.collectionId()` (a one-line
change), independent of this track, since the provisional-id machinery lives next
door and will keep pulling attention to this setter.

### BC4 [suggestion] engineFilesPresent prefix match can false-positive across sibling index names

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:3546` (`engineFilesPresent`, used by `revertCreatedIndexEngineStructure`)

**Issue**: `engineFilesPresent(engineName)` returns true when any write-cache file
name `startsWith(engineName.toLowerCase())`, with no separator or suffix boundary.
Index engine names are class-property strings, so `Foo.name` is a prefix of
`Foo.name2` and its file `foo.name2.cbt`. On the failed-create cleanup path this can
report a created engine's files as present when only an unrelated sibling index's
files exist, driving an unnecessary `doDeleteIndexEngine` attempt.

**Evidence**: The prefix scan (lines 3552-3557) uses `startsWith`. The consequence is
bounded: `doDeleteIndexEngine(atomicOperation, engine)` deletes the specific
captured engine's own files (not by prefix), and the whole arm is wrapped in a
log-and-swallow try/catch, so a false positive causes at most a swallowed no-op
delete on the disk profile (where the create's files were already reverted). It
never deletes a sibling's files and never misses a real orphan: the check can only
over-report, never under-report.

**Refutation considered**: Verified `engineFilesPresent` is used only in
`revertCreatedIndexEngineStructure` (best-effort failure path) and that
`doDeleteIndexEngine` targets the specific captured engine object, so no collateral
deletion and no missed cleanup are possible.

**Suggestion**: Match the engine's known file basenames, or require a separator
boundary after the prefix, so the presence check is exact and two index names
sharing a prefix produce neither a spurious delete attempt nor log noise.

## Evidence base

#### C1 [CONFIRMED] Gremlin plan-cache bypass gap (BC1)
Survived refutation: `GqlExecutionPlanCache` (getInternal :98, putInternal :83) has
no `getTxSchemaState` guard, while the YQL mirror was added in-diff
(`YqlExecutionPlanCache.java:99,140`); the wrong-rows and cross-session-leak shape
is identical to the YQL bug the track fixed.

#### C2 [CONFIRMED] onIndexEngineChange commit-window reload deadlock (BC2)
Survived refutation: the `while (true)` recovery branch calls the readLock-taking
`loadIndexEngine` (`IndexAbstract.java:1211`), latent (unreachable after a
just-published engine) but inconsistent with the otherwise lock-free commit window.

#### C3 [CONFIRMED] setCollectionId wrong-field guard (BC3)
Survived refutation: line 87 compares the new id against the old position; PSI
confirms the track never reaches it (`setCollectionAndPosition` used) and the sole
production caller `newInternalInstance` is immune via disjoint value ranges.

#### C4 [CONFIRMED] engineFilesPresent prefix false-positive (BC4)
Survived refutation: `startsWith` with no boundary over-reports across sibling index
names; benign because the delete targets the specific engine object and the arm is
best-effort log-and-swallow, so no collateral delete and no missed cleanup.

#### C5 [REFUTED] Commit-window self-deadlock via record/collection reads
Hypothesis: the new commit-time index code (`enrollReconciledIndexRecords`,
`rejectNonEmptySourceCollection`, `populateTxCreatedIndex`, `buildEngineAtCommit`)
runs while the schema-carry commit holds the non-reentrant `stateLock.writeLock()`;
any read that re-acquires `stateLock` would busy-spin.
Checked: `getCollectionIdByName` is commit-window-aware (`AbstractStorage.java:2153`,
`isCommitWindowActive()` → lock-free); `doPut` → `putRidIndexEntryInternal`
(`:4662`) reads `indexEngines` directly with no `stateLock`; `onIndexEngineChange`
→ `callIndexEngine` has a commit-window lock-free branch (`:4597`);
`transaction.loadEntity` → `readRecord` has a commit-window lock-free branch
(`:2114`); `isCommitWindowActive` is a per-thread `ThreadLocal` depth (`:4582`), so
the lock-free routing is scoped to the committing thread and a concurrent reader on
another thread still takes the read lock.
Verdict: REFUTED. Every commit-window read path self-routes lock-free on the
committing thread; the only residual is BC2's unreachable recovery branch.

#### C6 [REFUTED] RecordIteratorCollection provisional scan drops or duplicates rows
Hypothesis: skipping the storage phase for a provisional collection could miss the
transaction's own rows in one direction, or run the storage iterator on a negative
id.
Checked: forward path calls `moveTxIdForward` then `initStorageIterator`, which
returns early for a provisional id (`RecordIteratorCollection.java:218`,
`storageIterator` stays null), so only tx records are served; backward path computes
`minTxPosition` then calls `moveTxIdBackward` directly for a provisional id (skipping
`initStorageIterator`, `:107-115`), and `hasNext`'s storage re-init (`:150`) is gated
on `forwardDirection`, so it never runs for the backward provisional case; both
directions terminate via the `storageIterator == null` / `nextTxId == null` return.
Tests cover forward, WHERE, and `order by @rid desc`.
Verdict: REFUTED. Both directions serve exactly the tx-phase rows and never call
`browseCollection` for a provisional id.

#### C7 [REFUTED] getClassIndexes override applies the wrong overlay resolution
Hypothesis: overriding `getClassIndexes` with `resolveClassRawIndexes` could over-
or under-include relative to the parent's `getClassIndexes` semantics.
Checked: `IndexManagerAbstract.getClassRawIndexes` (`:85`) and `getClassIndexes`
(`:166`) have byte-identical bodies (iterate `classPropertyIndex` values, addAll);
`resolveClassRawIndexes` applies the tx delta to whatever committed collection it is
handed, and each override passes its own committed set. The overlay result is a
de-duped `LinkedHashSet`, which at most tightens the composite-index duplicate
behavior of a List-passing caller.
Verdict: REFUTED. The two parent methods are identical, so the shared overlay
resolution is correct for both overrides.

#### C8 [REFUTED] Tx-local overlay or tx-aware snapshot leaks across sessions, or version collision
Hypothesis: the memoized tx snapshot or the overlay could leak into the
process-shared cache, or a version-number collision could serve a stale cached
immutable class.
Checked: `makeUncachedSnapshot` never writes the volatile `SchemaShared.snapshot`;
the memo lives on per-session `TxSchemaState` and `MetadataDefault` is
per-session/thread-bound; `makeSnapshot`'s tx-aware branch is gated on
`getTxSchemaState() != null`, and `getImmutableSchemaSnapshot` returns the fresh
(TxSchemaState-memoized) snapshot without caching it into the session field. Version
numbers come from a process-wide `AtomicInteger` (`SchemaShared.VERSION_GENERATOR`),
so committed and tx-local version spaces are disjoint, and `advanceVersion` asserts
the write lock with all three call sites holding it. Two concurrent-reader tests
(overlay and tx snapshot) confirm no leak.
Verdict: REFUTED. Session-private snapshot construction plus the process-wide
version generator close both the leak and the collision.

#### C9 [REFUTED] A provisional collection id reaches durable bytes, or a same-tx create+drop loses rows
Hypothesis: the provisional-id-in-RID mechanism could persist a `<= -2` id, or a
same-tx create-then-drop could silently lose the class's rows.
Checked: `rewriteProvisionalRecordCollectionIds` rewrites every provisional record
id to the reconciled real id before the working set is gathered, and
`computeCommitWorkingSet` then asserts no provisional id survives; a provisional id
with no resolution (class dropped or re-abstracted mid-tx) throws `StorageException`
loudly instead of dropping the record; `reconcileCollections` intersects allocated
provisional ids with `getOwnedProvisionalCollectionIds` (read from class id-arrays,
not the `collectionsToClasses` reverse map) so a create-then-drop publishes no orphan
collection; references to a tx-created record come only from records in the same
working set, so no stale provisional link reaches durable bytes. Reload and
create+drop tests cover both.
Verdict: REFUTED. The mechanism fails closed on the no-resolution case and rewrites
all live provisional ids before serialization.
