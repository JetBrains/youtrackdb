<!--MANIFEST
dimension: test-crash-safety
prefix: TY
iteration: 1
verdict: pass
counts: { blocker: 0, should_fix: 1, suggestion: 2 }
evidence_base: { certs: 1 }
cert_index: [C1]
flags: { evidence_trail_exempt: false }
index:
  - id: TY1
    sev: should_fix
    anchor: "TY1"
    loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/PerClassSchemaRecordTest.java:createdClassPersistsAsStandaloneLinkedRecord (lines 323-344), droppedClassDeletesRecordAndUnlinks (lines 350-370), rootNonLinkPayloadSurvivesReopen (lines 377-397)"
    cert: C1
    basis: trace
  - id: TY2
    sev: suggestion
    anchor: "TY2"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java:fromStream (line 559)"
    cert: n/a
    basis: diff
  - id: TY3
    sev: suggestion
    anchor: "TY3"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java:toStream (lines 681-705)"
    cert: n/a
    basis: diff
-->

## Findings

### TY1 [should-fix] The "survives a reopen" round-trip tests do not force a `fromStream` re-parse under the default MEMORY test environment

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/PerClassSchemaRecordTest.java` — `createdClassPersistsAsStandaloneLinkedRecord` (lines 323-344), `droppedClassDeletesRecordAndUnlinks` (lines 350-370), `rootNonLinkPayloadSurvivesReopen` (lines 377-397)

**Production code**: `SchemaShared.fromStream` (lines 492-655), reached on reopen via `SharedContext.load` → `SchemaShared.load` → `fromStream`.

**Evidence (recovery-path trace, cert C1)**: All three tests state the durability claim the per-class-record format rests on — a class, its bound record RID, the root link-set membership, and the root's non-link payload all *survive a reopen*, i.e. survive a fresh `fromStream` re-parse of the on-disk records. The test forces that re-parse with `reOpen("admin", ADMIN_PASSWORD)`, whose comment reads "Reopen forces SchemaShared.fromStream to rebuild from the on-disk per-class records" (line 335).

That comment holds only under the DISK profile. `reOpen` (DbTestBase line 139) does `session.close()` + `youTrackDB.open(...)`. `DbTestBase.calculateDbType()` (lines 126-136) returns `MEMORY` unless `-Dyoutrackdb.test.env` is `ci`/`release`; the default unit-test run is therefore in-memory. On the open path, `YouTrackDBInternalEmbedded.getAndOpenStorage` reuses the existing storage ("THIS OPEN THE STORAGE ONLY THE FIRST TIME", line 431) and `getOrCreateSharedContext` (lines 779-787) returns the cached `SharedContext` keyed by `storage.getName()`. That `SharedContext` is removed only by `drop` (line 837), `forceDatabaseClose` (line 1045), or full instance `close` — never by an ordinary `session.close()`. So a MEMORY `reOpen` hands back the *same* `SchemaShared` instance with its live in-memory `classes` map intact, and `fromStream` is never re-invoked. The three assertions then read the in-memory objects the test just mutated, not a re-parse of the persisted bytes. Under DISK (`-Dyoutrackdb.test.env=ci`) the storage and its `SharedContext` are torn down on the last session close, the next open rebuilds the `SharedContext` and calls `SchemaShared.load` → `fromStream`, and the round-trip is genuinely exercised — which is the CI gate, so the format is not shipping unverified.

The net effect: under the default `./mvnw -pl core clean test` invocation these three tests pass without ever exercising the serializer round-trip they document. A `fromStream` regression in this step's link-set reader (lines 540-563) — for instance dropping the `cls.setRecordId(c.getIdentity())` bind at line 559, or mis-reading the `"classes"` link set — would be caught only by the CI/DISK run, not by a developer's local MEMORY run. This is exactly the "verify durable persistence (reopen / re-read), not just in-memory state" gap the round-trip claim is meant to close.

**Why it matters**: The per-class-record format is the persistence foundation every later track builds on, and the track's first acceptance criterion is that a `toStream`→`fromStream` round-trip preserves each class's record RID and the root payload "so Track 3 can re-parse a tx-local copy faithfully." A round-trip test whose re-parse is a no-op on the default profile gives false local confidence in precisely that invariant. The version-gate tests (`assertVersionRejected`, line 424) do not share this gap — they call `schemaShared().reload(session)` directly, and `reload` (lines 360-375) always re-reads the record via `session.load(identity)` → `fromStream` regardless of MEMORY/DISK — which makes the asymmetry inside the same test class easy to miss on review.

**Suggested fix**: Make the round-trip re-parse profile-independent so the local MEMORY run exercises the serializer too. The lowest-friction option reuses the path the version-gate tests already trust: drive an explicit `schemaShared().reload(session)` (which always re-parses) in addition to — or instead of — `reOpen`, and assert against the reloaded state. A `reload` rebinds `classes`, the per-class `recordId`, and the root payload from the record bytes on every profile.

```java
@Test
public void createdClassPersistsAsStandaloneLinkedRecord() {
  session.getMetadata().getSchema().createClass("RoundTripClass");

  var ridBefore = schemaShared().getClass("RoundTripClass").getRecordId();
  assertNotNull("a persisted class must carry its own record RID", ridBefore);
  assertTrue("the root link set must contain the class record RID",
      rootClassLinks().contains(ridBefore));

  // Force a re-parse of the on-disk per-class records on EVERY profile, not just DISK.
  // reload() re-reads the root record and rebuilds classes + bound RIDs via fromStream,
  // unlike reOpen() which reuses the cached SchemaShared under the default MEMORY profile.
  schemaShared().reload(session);

  var clsAfter = schemaShared().getClass("RoundTripClass");
  assertNotNull("class must survive a fromStream re-parse", clsAfter);
  assertEquals("the per-class record RID must be re-bound from the link set at load",
      ridBefore, clsAfter.getRecordId());
  assertTrue("the reloaded root link set must still contain the class record RID",
      rootClassLinks().contains(clsAfter.getRecordId()));
}
```

If the intent is specifically to also cover the full session-reopen path (not just `reload`), keep `reOpen` and add the `reload`-based re-parse alongside it, or annotate the three tests so a reader knows the durable round-trip is only asserted under the CI/DISK profile. Either way the local run should not silently skip the re-parse these tests are named for.

### TY2 [suggestion] Add an `assert` on the per-class record RID bound at load in `SchemaShared.fromStream`

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java` (line 559)

**Evidence (invariant analysis)**: At load, each class is bound to its standalone record's identity:

```java
cls.setRecordId(c.getIdentity());
```

`c` is a record loaded from the root's `"classes"` link set (lines 540-546). The bound `recordId` is the handle Track 4's commit path will use to load-by-RID and selectively rewrite a single class record (`toStream` line 692, `classRecord = session.load(c.getRecordId())`). The invariant is that a class loaded from the link set always carries a valid, persistent identity — a link set holds persisted RIDs by construction, so a null or non-persistent identity here would mean the link set itself is corrupt, and it would surface much later as a confusing NPE or a load against a bogus RID inside an unrelated commit. There is no current enforcement at the bind site.

**Invariant**: A class bound from the persisted root link set has a non-null, persistent record identity.

**Suggested assertion**:

```java
var classRid = c.getIdentity();
assert classRid != null && classRid.isPersistent()
    : "schema class '" + name + "' loaded from the root link set must carry a persistent"
        + " record id, got " + classRid;
cls.setRecordId(classRid);
```

**Catches**: A regression in the link-set reader (or in the `toStream` membership write) that puts a temporary or null RID into the persisted `"classes"` set — caught at load during a test run rather than as a downstream load-by-RID failure inside a Track 4 commit. Zero production cost (assertions disabled by default). Per the JaCoCo+`assert` guidance the failure-message line is excluded from coverage, so this adds no coverage burden.

### TY3 [suggestion] Add an `assert` that every RID entering the root link set / live-record set is non-null in `SchemaShared.toStream`

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java` (lines 681-705)

**Evidence (invariant analysis)**: The write path builds the durable class membership from per-class RIDs:

```java
if (c.getRecordId() == null) {
  classRecord = session.newInternalInstance();
  c.setRecordId(classRecord.getIdentity());
  classLinks.add(classRecord.getIdentity());
} else {
  classRecord = session.load(c.getRecordId());
}
c.toStream(session, classRecord);
liveRecords.add(c.getRecordId());          // line 695
```

`liveRecords` (line 695) is the set the drop pass (lines 699-705) diffs against `previouslyLinked` to decide which records to delete and unlink. If `c.getRecordId()` were ever null when added to `liveRecords` — e.g. a future refactor that lets `newInternalInstance()` yield a null identity, or a reordering that adds before the bind — the diff would silently treat a still-live class's record as a drop candidate (because `liveRecords.contains(null)` would not match a real `previouslyLinked` RID), deleting a live class's record from the durable schema. This is a consistency invariant between the membership set and the records actually written; it is not a restatement of the preceding line because the null could enter from either branch, not only the just-assigned one.

**Invariant**: Every RID written into the root `"classes"` link set and the `liveRecords` diff set is non-null.

**Suggested assertion** (one assert covering the value just before it joins `liveRecords`):

```java
c.toStream(session, classRecord);
assert c.getRecordId() != null
    : "schema class '" + c.getName() + "' must have a bound record id before it joins"
        + " the live-record set written to the root link set";
liveRecords.add(c.getRecordId());
```

**Catches**: A null RID slipping into the durable membership diff, which would mis-classify a live class as dropped and delete its record — a silent durable-schema-corruption bug — surfaced at the write site during testing rather than as a missing class after a later reopen. Zero production cost.

## Evidence base

#### C1 — Recovery-path trace: do the round-trip tests assert the correct post-re-parse state, and do they reach the re-parse?

**Claim under test**: The three round-trip/create-drop tests verify that the per-class-record format survives a `fromStream` re-parse (durable persistence), per the track's first acceptance criterion.

**Recovery path traced**: reopen → `SharedContext.load` (SharedContext.java line 109) → `SchemaShared.load` (SchemaShared.java line 754) → `executeInTx` → `session.load(identity)` → `fromStream` (line 492). `fromStream` clears `classes` (line 565) and rebuilds it from the `"classes"` link set, rebinding each `recordId` at line 559 — a genuine re-parse of the persisted bytes.

**Refutation check (does the test reach this path on the default profile?)**: NOT on MEMORY. `DbTestBase.reOpen` (line 139) does `session.close()` + `youTrackDB.open`; `calculateDbType` (line 126) defaults to MEMORY; `getAndOpenStorage` reuses the storage (line 431 "ONLY THE FIRST TIME") and `getOrCreateSharedContext` (line 779) returns the cached `SharedContext` — removed only by `drop`/`forceDatabaseClose`/instance-`close`, never by `session.close()`. So the same `SchemaShared` instance with a live `classes` map is returned and `fromStream` is not re-invoked; the assertions read in-memory state. Confirmed via PSI find-usages that the single-arg `SchemaClassImpl.toStream` no longer exists and the new two-arg form has exactly one caller (`SchemaShared.toStream` line 694), and that `SchemaShared.toStream` has one production caller (`saveInternal` line 874) — so the write path the tests drive and the read path a true reopen would drive are the audited ones; the only gap is whether the read path is reached. On DISK (`-Dyoutrackdb.test.env=ci`) the storage + `SharedContext` are torn down on last close and the next open re-parses, so the round-trip is exercised under the CI gate.

**Verdict**: CONFIRMED-as-issue. The recovery-path assertions are correct *for the state they read*, but on the default MEMORY profile they do not reach the `fromStream` re-parse they document, so a serializer regression in this step's link-set reader is invisible to the default local test run (caught only by CI/DISK). → TY1, should-fix.

**Note on the version-gate tests (not an issue)**: `assertVersionRejected` (line 424) calls `schemaShared().reload(session)` directly; `reload` (line 360) always re-reads the record via `session.load(identity)` → `fromStream` on every profile, hits the tightened gate at line 506 (`schemaVersion != CURRENT_VERSION_NUMBER`) before the link-set parser at line 540, and throws `ConfigurationException` for both the v4 and legacy-v5 stamped records. Both reject arms genuinely exercise the gate on both profiles — no gap.
