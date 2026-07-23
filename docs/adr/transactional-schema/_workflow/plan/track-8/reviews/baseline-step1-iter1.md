# Code review — code-baseline perspective — Track 8 Step 1 (commit `6611cbf6b2`), iteration 1

**Reviewer scope:** correctness & bugs, code quality, test quality of the Step-1 diff, checked
against the binding spec: `plan/track-8.md` Step 1 and `plan/track-8-design-drafts.md`
(§0 ruling R3, §G2.b, single-read pin CN50, test pins G.5 #7/#8). Read-only review; no Maven
runs were performed — build/test/coverage results are cited from the track file's Episode log
as *reported, not re-verified*.

**In-scope files (== `git show 6611cbf6b2 --stat`):**

1. `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/SharedContext.java`
2. `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java`
3. `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/StorageEmbeddedBlobCollectionsTest.java` (new)
4. `core/src/test/java/com/jetbrains/youtrackdb/internal/core/record/impl/EntityImplTest.java`
5. `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/CommandExecutorSQLTruncateTest.java`
6. `tests/src/test/java/com/jetbrains/youtrackdb/junit/StringsTest.java`

---

## 1. Decision criteria

A finding is a **blocker** if it breaks a binding design pin (R3 / G2.b / CN50 / G.5 #7-#8), a
correctness invariant, or crash-safety of storage create on either profile. **Should-fix** if it
is a real defect or a materially weakened test that regresses assertion strength vs the pre-change
baseline, but with contained blast radius. **Suggestion** if it is behavior-parity fragility,
hygiene, or hardening. A **null verdict** (no finding) requires tracing the relevant code path to
its handling site and stating why the outcome is acceptable — behavior parity with the pre-change
code counts as acceptable for this step, because Step 1's contract is layout/atomicity change with
otherwise-unchanged semantics.

## 2. Premises (traced facts)

All file:line references are at commit `6611cbf6b2` / HEAD `550eed6673` (identical for these files).

* **P1** — `AbstractStorage.doCreate` reads `STORAGE_BLOB_COLLECTIONS_COUNT` once from the
  `contextConfiguration` **parameter** (the create-time config) and creates `"$blob" + i` for
  `i = 0..N-1` via `doAddCollection` — `AbstractStorage.java:1520-1525`.
* **P2** — That loop sits inside the single storage-create WAL atomic operation: the whole
  `doCreate` body from configuration create through `postCreateSteps()` is one
  `atomicOperationsManager.executeInsideAtomicOperation(...)` lambda
  (`AbstractStorage.java:1493-1531`), immediately after the `internal` collection create at
  `:1510` — matching the R3 precedent citation (`design-drafts §G2.b`, "next to `:1510`").
* **P3** — This is the **only** production read of `STORAGE_BLOB_COLLECTIONS_COUNT` in
  `core/src/main`: grep finds `GlobalConfiguration.java:274` (declaration, default 8),
  `AbstractStorage.java:1522` (the read), and two comment-only mentions in
  `SharedContext.java:210` — CN50's "no second config read" holds by construction.
* **P4** — `SharedContext.create`'s blob loop (`SharedContext.java:213-218`) enumerates
  `storage.getCollectionNames()`, filters with `BLOB_COLLECTION_NAME_PATTERN`
  (`\$blob\d+`, `SharedContext.java:38`), and calls
  `schema.addBlobCollection(session, storage.getCollectionIdByName(name))` — register-only, no
  storage structural op, no config read.
* **P5** — Storage collection names are stored lower-cased: `doCreateCollection` lower-cases at
  `AbstractStorage.java:7085` and `registerCollection` keys `collectionMap` on
  `getName().toLowerCase(Locale.ROOT)` at `:7013`; `getCollectionIdByName` folds its argument at
  `:2378`. The pattern-javadoc claim at `SharedContext.java:35-37` is therefore accurate.
* **P6** — `SchemaShared.addBlobCollection` (`SchemaShared.java:1598-1606`) validates via
  `checkCollectionCanBeAdded` (`:542-571`) and adds to the in-memory `blobCollections` set;
  persistence rides `releaseSchemaWriteLock(session)` → default `iSave=true` →
  `saveInternal(session)` (`SchemaShared.java:791-817`) — i.e. the legacy self-commit root save,
  unchanged by this step. The schema-root persistence baseline is preserved (and directly pinned
  by the new test's `persistedBlobCollectionIds` helper).
* **P7** — Both storage profiles share the change: `DiskStorage.doCreate`
  (`DiskStorage.java:310-317`) only creates the directory and calls `super.doCreate`;
  `DirectMemoryStorage` does not override `create`/`doCreate` at all (no `doCreate`/`create`
  override present in `DirectMemoryStorage.java`), so both inherit
  `AbstractStorage.create:1403` → `doCreate`.
* **P8** — `SharedContext.create` has exactly one production call site:
  `DatabaseSessionEmbedded.createMetadata:602`, reached only from `internalCreate:585` — i.e.
  genesis on a virgin storage. (`DatabaseImport.java:404` calls `security.create`, not
  `SharedContext.create`.)
* **P9** — `ContextConfiguration.getValue` falls back to the process-global mutable
  `iConfig.getValue()` when the key is absent (`ContextConfiguration.java:90-96`), and
  `getValueAsInteger` maps `null` → `0` (`ContextConfiguration.java:170-176`) — no NPE path.
* **P10** — Blob record placement consults **only** the schema-registered set:
  `DatabaseSessionEmbedded.java:4459-4465` picks a random id from `getBlobCollectionIds()`
  (`:5028-5031`, schema-backed) and throws `DatabaseException` when the set is empty. Storage is
  never consulted, so physically-present-but-unregistered `$blob*` collections are inert (FM-G5's
  premise re-verified).
* **P11** — Old-path comparison: pre-change, each `session.addCollection("$blob"+i)` routed
  through `AbstractStorage.addCollection:1683-1713` — its **own** atomic operation per collection
  (`calculateInsideAtomicOperation` at `:1696-1697`) — confirming the commit message's "each in
  its own atomic operation" claim and the atomicity improvement.

## 3. Binding-pin conformance matrix

| Pin | Verdict | Evidence |
|---|---|---|
| Blob names are `$blob<i>`, `i = 0..N-1` | **MATCH** | P1 (`"$blob" + i`, `AbstractStorage.java:1524`) |
| Created inside the same WAL atomic op as `internal` (R3) | **MATCH** | P2 |
| Count read exactly ONCE, from create-time `contextConfiguration` (CN50) | **MATCH** | P1 + P3; the read uses the method parameter, not `storage.getContextConfiguration()` |
| No second config read in `SharedContext` (CN50) | **MATCH** | P3, P4 |
| Register-only loop resolves by name against the storage's actual collections | **MATCH** | P4, P5 |
| Register-only preserves the schema-root persistence baseline | **MATCH** | P6; pinned by `persistedBlobCollectionIds` + the disk-reopen test |
| Both profiles get identical behavior | **MATCH** | P7; explicitly tested per profile |
| Layout: `internal` = 0, `$blob0..N-1` = 1..N | **MATCH** | id allocation is first-free-slot (`AbstractStorage.java:7024-7035`) on a virgin collections list; test-pinned |
| G.5 #7 (registration equality + blob round-trip, both profiles) | **DELIVERED** | test §5 below |
| G.5 #8 (sweep) | Executed per Episode log — workflow-perspective item; the three casualty fixes it produced are reviewed in §6 |
| Loop stays on the legacy self-commit path (tx-wrapping = Step 3's seam) | **MATCH** | P6 — `saveInternal` self-commits per registration, unchanged |

## 4. Changed execution paths — exhaustive enumeration

### 4.1 `AbstractStorage.doCreate` (new lines 1512-1525)

1. **Normal path (default count 8).** 8 `doAddCollection` calls after `internal`; slots 1..8 by
   first-free-slot allocation. Checked — test-pinned on both profiles. ✅
2. **count = 0.** Loop is a no-op; no `$blob*` collections; `SharedContext` registers nothing
   (empty enumeration); first `newBlob` save throws `DatabaseException` "Cannot save blob (2) …
   no collection defined" (`DatabaseSessionEmbedded.java:4459-4464`). **Null verdict:** exact
   behavior parity — the old code read the same config with the same count and produced the same
   end state. See BG12 for the (pre-existing, parity) diagnosability note.
3. **count < 0.** `for (i < negative)` is a no-op → identical to case 2. Null verdict (parity).
4. **Huge count.** N collections created inside ONE atomic operation (previously N separate
   ops, P11). No validation ceiling on the config value. Practical exposure requires operator
   misconfiguration; outcome parity in collection count. Recorded under BG12 (suggestion).
5. **Config value present but non-Integer.** `getValueAsInteger` does
   `Integer.parseInt(v.toString())` (`ContextConfiguration.java:175`) → `NumberFormatException`
   → propagates out of the atomic-op lambda → WAL rollback → `create()`'s catch
   (`AbstractStorage.java:1424-1428`). Null verdict: same parse, same failure shape as the old
   read site; storage-create failure is loud and rolled back.
6. **Config value absent everywhere (`null`).** `getValueAsInteger` → 0 (P9) → case 2. Null
   verdict (cannot happen with the shipped default of 8, `GlobalConfiguration.java:274-276`).
7. **`IOException` from `doAddCollection` mid-loop.** Propagates out of the lambda → the atomic
   operation rolls back the whole create (config, `internal`, partial blobs together);
   `create()` wraps into `StorageException` + `closeIfPossible()` (`AbstractStorage.java:1416-1420`).
   ✅ This is precisely the FM-G4 mechanism ("no partial blob set is ever exposed"); crash-mid-op
   is the WAL-revert arm of the same envelope.
8. **Profile split.** Disk: `DiskStorage.doCreate:310-317` runs directory creation *before*
   `super.doCreate`, so the blob loop is inside the shared body. Memory: inherited unchanged.
   Checked — no profile-divergent branch exists in the changed region. ✅

### 4.2 `SharedContext.create` blob loop (new lines 204-218)

1. **Normal path.** Enumerates lower-cased names (P5), matches `\$blob\d+` (anchored
   `matches()`, ASCII-only `\d`), registers each id. Set-valued destination
   (`IntOpenHashSet`, `SchemaShared.java:172`) — iteration order irrelevant. ✅
2. **Zero matches** (count = 0 at birth). Empty registration; parity (§4.1 case 2). Null verdict.
3. **`getCollectionIdByName` returns -1.** Impossible on this path: the names come from the same
   `collectionMap` the id lookup reads, genesis is single-threaded (factory monitor + the
   `SharedContext.lock` held at `SharedContext.java:192`), and nothing in the loop body drops
   collections. Null verdict with justification.
4. **`addBlobCollection` throws `SchemaException`** (id already blob / already a class's
   collection, `SchemaShared.java:553-567`). Impossible at the sole call site (P8): blob ids 1..N
   are allocated before any class collection (classes start at N+1), classes created earlier in
   `create` (`SharedContext.java:194-206`) own ids ≥ N+1, and `create` runs once per storage
   lifetime. A mid-loop throw would propagate and leave a half-genesis DB — that containment is
   explicitly Step 3's §A1 seam; pre-change code had the identical exposure. Null verdict
   (parity + out-of-scope by plan).
5. **Collision with a user collection named `$blob5`** (charter case). Cannot occur at the only
   call site: at genesis the storage contains exactly `internal` + `$blob0..N-1` (P8). A user
   collection named `$blob99` created *post*-genesis is never re-enumerated — `load`/`reload`
   read the persisted root set (`SchemaShared.java:1052-1053`), not the storage names — so it is
   a plain collection, never misclassified as a blob. Null verdict with justification. (The
   dump-import blob-id path, `DatabaseImport.java:528-541`, resolves raw ids and is a **known
   deferred defect** — FM-M16/§A3, owned by Step 5 per the binding plan; explicitly out of scope
   here.)
6. **Regex robustness.** `matches()` anchors the whole name — `$blobx`, `x$blob1`, `$blob1x`
   don't match; `$blob01`/very long digit runs would match but no producer creates such names at
   genesis and post-genesis enumeration never happens (path 5). Java `\d` without
   `UNICODE_CHARACTER_CLASS` is ASCII-only — no Unicode-digit surprises. `\$` correctly escapes
   the metacharacter. ✅
7. **Live-view iteration.** `getCollectionNames()` returns
   `Collections.unmodifiableSet(collectionMap.keySet())` — a **live view**, not a copy
   (`AbstractStorage.java:2323-2337`). The loop body triggers self-committing root saves (P6);
   traced: a root save writes the pre-allocated schema record into the existing `internal`
   collection and cannot add/remove collections, so no `ConcurrentModificationException` today.
   Fragility recorded as CQ14.
8. **Locking.** The loop takes `stateLock.readLock` per `getCollectionNames`/
   `getCollectionIdByName` call while holding `SharedContext.lock`; the replaced code took
   `stateLock.writeLock` via `addCollection` under the same outer lock — no new lock-order edge.
   Null verdict.

### 4.3 Paths downstream of the layout change (checked for parity)

* **Blob placement/read:** schema-set-only (P10) — renumbering-safe by construction. ✅
* **Import round-trip on the new layout:** importer skips already-registered ids
  (`DatabaseImport.java:534-537`); same-layout round-trips green per Episode log. Cross-layout
  v14 dumps → FM-M16, Step 5 (out of scope). ✅
* **Backup/restore:** binary restore reproduces the source layout; no config-derived ids. IT
  suite (`StorageBackup*`, `LocalPaginatedStorageRestore*`) reported green in the Episode log
  (not re-verified here). ✅

## 5. Test quality — the 4 new tests (`StorageEmbeddedBlobCollectionsTest`)

Regression-power check (would each pinned property go red if regressed?):

| Pinned property | Failing regression | Verdict |
|---|---|---|
| Layout `internal`=0, `$blob<i>`=i+1 (`:112-118`) | Reverting the storage embed (blobs return to top slots) → `assertEquals(i+1, …)` fails | **RED** ✅ |
| Exactly N `$blob*` exist (`:120-122`) | Prefix drift between creator and pattern (e.g. `$blb`) → test's own enumeration finds 0 | **RED** ✅ |
| Schema registration == storage ids, in memory (`:126-127`) | Deleting the `SharedContext` register loop → empty set | **RED** ✅ |
| Registration == storage ids on the **persisted root** (`:128-130`, helper `:81-89` reads `blobCollections`, matching `SchemaShared.java:1244`) | Registration kept in-memory only → `assertNotNull`/equality fails | **RED** ✅ |
| Blob round-trip lands in a storage-birth collection (`:134-146`) | Misregistration (bogus ids) → containment assert fails | **RED** ✅ |
| Registration survives root re-parse (`:148-153` via `SchemaShared.reload:727`) | `fromStream` losing the set | **RED** ✅ |
| Both profiles (`:161-176`) | Profile-divergent regression | **RED** ✅ (P7 makes divergence structurally hard, and the tests would still catch it) |
| Frozen-at-birth count (`:185-213`): create-time 3 vs process default 8, layout + equality re-checked | `doCreate` reading the process-global value instead of the create-time config → 8 collections, expects 3; `SharedContext` re-reading the *process-global* count and resolving `$blob3..7` → id -1 pollutes the registered set → equality fails | **RED** ✅ — includes an explicit premise guard (`:196-197`) that fails loudly if the shipped default ever becomes 3 |
| Disk reopen (`:216-241`): fresh `SharedContext` from disk, registration + payload survive | Persistence regression | **RED** ✅ |

Residual, non-catchable-by-black-box window: a hypothetical `SharedContext` re-read via
`storage.getContextConfiguration()` would return the *stored* create-time value (3) and pass —
CN50's actual hazard (default-config create + process-global mutation *mid-genesis*) is not
externally injectable. The pin therefore rests on P3 (single production read, grep-verifiable) +
the enumeration-by-name construction. Recorded as TQ12 (no action demanded, worth keeping on the
record for the Step-3 reviewer).

Hygiene: `tearDown` (`:49-55`) is null-safe; per-profile DBs are dropped in `finally`. One gap
recorded as TQ13.

## 6. Test quality — the 3 casualty fixes (assertion-strength check)

1. **`EntityImplTest.testSerializerDeltaRoundTripPreservesTypedProperties`**
   (`EntityImplTest.java:543-576`). `new RecordId(2, 1)` (a genesis record that accidentally
   existed pre-renumber; post-renumber collection 2 = `$blob1`, position 1 nonexistent → the
   lazy-load materialized `null`) replaced by a committed entity created in the test. The
   round-trip assertion (`assertEquals(linkRid, clone.<RID>getProperty("lnk"))`) keeps identical
   shape; the target's existence is now guaranteed rather than accidental.
   **Strength: preserved (arguably strengthened).** ✅
2. **`CommandExecutorSQLTruncateTest.testTruncateAPI`** (`CommandExecutorSQLTruncateTest.java:32-58`).
   The `#1:3` load was scenario *setup* (pull a committed internal record into the tx before the
   polymorphic truncate sweep), not an asserted value; it is now resolved via
   `select from OSecurityPolicy limit 1` — same class of record (a genesis security record),
   deterministic on a fresh DB (default roles always create policies). The test's observable
   contract (truncate sweep over non-`OSecurity` classes completes) is unchanged.
   **Strength: preserved.** ✅ (Minor: the query's `ResultSet` is never closed — TQ13.)
3. **`StringsTest.testDocumentSelfReference`** (`StringsTest.java:104-125`). The literal
   `"O#7:-2{ref:#7:-3,selfref:#7:-2} v0"` is now built from the entities' actual provisional
   RIDs. The declared pin (self-reference renders as the RID without recursing; property
   rendering shape; `v0` suffix; class prefix `O`) is preserved. Two incidental pins are lost:
   the literal RID text format (`#c:p`) and the *distinctness* of the two provisional RIDs — the
   new assertion is self-referential (both sides render through `RID.toString()`), so it cannot
   catch a rendering-format regression, and it would pass if both entities were (buggily) handed
   the same provisional RID. **Strength: mildly reduced** — recorded as TQ11 (suggestion; RID
   format is pinned elsewhere in RecordId-level tests, and the lost pins are allocation/format
   details orthogonal to this test's stated intent).

## 7. Alternative-hypothesis log

* **H1: config collections could occupy slot 0 before `internal`.** Rejected —
  `CollectionBasedStorageConfiguration.create` precedes the `internal` add inside the same lambda
  (`AbstractStorage.java:1495-1510`) and the test's `internal == 0` pin passes; pre-change layout
  also had `internal` = 0 (design G.0 baseline table).
* **H2: the register loop might run against a non-virgin storage somewhere** (import, restore,
  reInit). Rejected — single call site (P8); `reInit`/`load`/`reload` never call `create`
  (`SharedContext.java:174-190, 238-249`).
* **H3: `checkCollectionCanBeAdded` could reject blob ids because genesis classes claimed 1..N.**
  Rejected — classes are created *after* the blobs physically exist, so allocation starts at N+1
  (first-free-slot, `AbstractStorage.java:7026-7033`); `collectionsToClasses` cannot contain
  1..N at registration time.
* **H4: the loop's self-commit root saves could mutate `collectionMap` mid-iteration** (CME).
  Traced and rejected for today's code — root save writes an existing record; no collection
  creation on that path. Kept as fragility CQ14.
* **H5: memory profile diverges** (e.g. skipping WAL semantics). Rejected — `DirectMemoryStorage`
  overrides neither `create` nor `doCreate`; its in-memory WAL still scopes the atomic operation;
  both profiles test-pinned.
* **H6: something in production depends on blobs holding the highest collection ids.** No such
  dependence found in the changed paths; the design's sweep (t60.e2, WI5) plus the one recorded
  exception (`DatabaseImport.java:528-541`, deferred to Step 5 by the binding plan) cover this;
  the reported-green suite runs corroborate. Out-of-scope residue is explicitly owned by Step 5.

## 8. Findings

### BG (correctness & bugs)

* **BG12 — suggestion — `AbstractStorage.java:1520-1525` (+ `GlobalConfiguration.java:274`)** —
  `STORAGE_BLOB_COLLECTIONS_COUNT` is consumed unvalidated at storage birth: `0`/negative yields
  a database that permanently cannot store blobs, failing only at first blob save with
  "Cannot save blob (2) … no collection defined" (`DatabaseSessionEmbedded.java:4459-4464`) —
  no create-time signal, and the value is now frozen for the DB's lifetime; an absurdly large
  value creates unbounded collections inside a single storage-create atomic operation (previously
  N separate ops, P11). *Counterexample:* create a DB with the config set to `0` (or `"8x"` for
  the parse-failure flavor of the same gap) → create succeeds silently; every later `newBlob`
  commit throws with a message that never names the misconfigured knob. Exact behavior parity
  with pre-change code (same read, same outcome), hence suggestion, not should-fix: a one-line
  `< 0`/sanity guard or a create-time log line would close it, ideally in Step 3 alongside the
  §A1 containment.

*No blocker or should-fix correctness findings. All binding pins verified MATCH (§3); all changed
execution paths enumerated in §4 are either test-pinned, behavior-parity, or explicitly owned by
a later step of the binding plan.*

### CQ (code quality)

* **CQ13 — suggestion — `AbstractStorage.java:1524` / `SharedContext.java:38`** — the `"$blob"`
  name prefix is a stringly-typed cross-file contract: the creator builds `"$blob" + i`, the
  registrar matches `Pattern.compile("\\$blob\\d+")`, and the test re-hardcodes the same regex —
  with no shared constant, unlike the `internal` precedent
  (`MetadataDefault.COLLECTION_INTERNAL_NAME`, used at `AbstractStorage.java:1510`).
  *Counterexample:* a future rename touching only one site compiles clean and fails only at
  test time (the new tests do catch it — registration-equality goes red — but a
  `MetadataDefault.BLOB_COLLECTION_NAME_PREFIX` constant plus a shared matcher would make the
  coupling compile-time instead of test-time).
* **CQ14 — suggestion — `SharedContext.java:213`** — the register loop iterates the **live**
  unmodifiable view returned by `AbstractStorage.getCollectionNames()`
  (`Collections.unmodifiableSet(collectionMap.keySet())`, `AbstractStorage.java:2337`) while the
  loop body executes self-committing schema writes (P6). Safe today (traced in §4.2.7 — the root
  save cannot add/remove collections), but any future commit-path change that allocates a
  collection mid-save turns this into a `ConcurrentModificationException` at every DB create.
  *Counterexample:* Step 3 wraps this loop in the genesis tx — if the commit (or any
  instrumentation added to `saveInternal`) ever creates a collection while the iterator is live,
  genesis throws CME. A defensive `List.copyOf(storage.getCollectionNames())` is one line and
  removes the trap.

### TQ (test quality)

* **TQ11 — suggestion — `tests/.../StringsTest.java:118-122`** — the rewritten assertion is
  self-referential: both the expected string and the actual rendering route through
  `RID.toString()`, so the test no longer pins the RID text format nor that the two entities'
  provisional RIDs differ (the old literal `#7:-2` vs `#7:-3` pinned both incidentally).
  *Counterexample:* a bug making `newEntity()` hand both entities the same provisional RID — or
  a global RID-rendering format change — passes this test unchanged. Cheap hardening:
  `assertNotEquals(rid, ridTwo)` and/or asserting `rid.toString().startsWith("#")`. The declared
  pin (self-reference renders without recursion) is preserved, hence suggestion only.
* **TQ12 — suggestion (record-keeping) — `StorageEmbeddedBlobCollectionsTest.java:185-213`** —
  the frozen-at-birth test cannot distinguish "enumerate by name" from a hypothetical re-read via
  `storage.getContextConfiguration()` (the explicit create-time value 3 is stored there, so both
  return 3); CN50's true hazard window (default-config create + process-global mutation
  *mid-genesis*) is black-box untestable. *Counterexample:* reintroduce
  `storage.getContextConfiguration().getValueAsInteger(STORAGE_BLOB_COLLECTIONS_COUNT)` in
  `SharedContext` and resolve `$blob0..count-1` by name — all four tests stay green. The pin's
  enforcement therefore rests on the single-production-read fact (P3) — recommend the Step-3 /
  track-level reviewer re-run that grep after the tx-wrapping lands (no code action now).
* **TQ13 — suggestion — `StorageEmbeddedBlobCollectionsTest.java:222-229` and
  `CommandExecutorSQLTruncateTest.java:41-42`** — hygiene: (a) `blobLayoutSurvivesDiskReopen`
  drops the DB only in the second block's `finally`; an assertion failure in the *first* session
  block leaks the on-disk DB directory for the run (contained to the per-class target dir,
  `DbTestBase.getBaseDirectoryPathStr`, but inconsistent with the other three tests' guaranteed
  drop); (b) the `OSecurityPolicy` query's `ResultSet` is never closed. *Counterexample:* make
  the first-block `newBlob` commit fail → the `blobDiskReopen` directory survives the test run.

## 9. Verdict

**No blockers, no should-fixes.** The implementation matches every binding pin of R3/G2.b/CN50
and the Step-1 plan entry (§3), the atomicity and both-profile claims are traced to mechanism
(§2 P2/P7, §4.1.7), the four new tests genuinely pin G.5 #7 and would fail on the realistic
regressions (§5), and the three casualty fixes preserve assertion strength (with one mild,
acknowledged reduction in `StringsTest`, TQ11). Five suggestion-level findings: BG12, CQ13,
CQ14, TQ11, TQ12/TQ13.
