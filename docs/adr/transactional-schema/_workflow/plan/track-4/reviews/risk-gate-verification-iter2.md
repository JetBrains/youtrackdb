<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: R1, verdict: VERIFIED}
  - {id: R2, verdict: VERIFIED}
  - {id: R3, verdict: VERIFIED}
  - {id: R4, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Risk review — gate verification (iteration 2)

Track 4: Commit-time reconciliation and the schema-carrying commit lock. All four
iteration-1 risk findings (R1/R2 should-fix, R3/R4 suggestion) were ACCEPTED and their
fixes applied to `track-4.md`. Each fix is re-checked below against the track file and,
where a Java-symbol claim is load-bearing, against the codebase through mcp-steroid PSI /
direct read. All four VERIFIED; no regression introduced; no new finding surfaced. Overall
PASS.

## Findings

<!-- Pure-verdict pass: no new finding surfaced during verification. -->

## Verification certificates

#### Verify R1: phantom in-memory registration on a failed commit (I-A4)
- **Original issue**: `doAddCollection` → `registerCollection`/`setCollection` and the
  `addIndexEngine` lambda publish into the live in-memory registries
  (`collections`/`collectionMap`/`indexEngines`/`indexEngineNameMap`) synchronously inside
  the atomic operation; the WAL revert undoes the on-disk file create but not those Java
  maps, so a failed commit leaves a phantom registration.
- **Fix applied**: D10 Risks/Caveats now carries the "In-memory registry publication must
  trail the atomic op (A1/R1)" note; `## Plan of Work` step 6 makes deferred/failure-reverting
  publication explicit; `## Interfaces and Dependencies` In-scope adds it; the I-A4 acceptance
  asserts the in-memory registries carry no entry after a forced apply failure.
- **Re-check**:
  - Track-file location: D10 Risks/Caveats (lines 71–72), Plan-of-Work step 6 (lines
    139–141), Interfaces In-scope (lines 220–222), Validation I-A4 (lines 181–183).
  - Codebase corroboration (the cited precedent and the hazard sites):
    - **Hazard confirmed.** `doAddCollection` calls `registerCollection(collection)` at
      `AbstractStorage.java:5026`, inside the `collection.create(atomicOperation)` block;
      `registerCollection` writes `collectionMap.put(...)` (4977) and `setCollection` writes
      `collections` (4983) synchronously, in-band with the atomic op. The `addIndexEngine`
      lambda writes `indexEngineNameMap.put(...)` (2811) and `indexEngines.add(engine)`
      (2812) inside `calculateInsideAtomicOperation`. None of these four map mutations is
      deferred; the WAL revert does not undo them. The hazard is real.
    - **Cited precedent confirmed exactly.** `deleteIndexEngine` defers its in-memory map
      mutation past the atomic op: the comment at `AbstractStorage.java:3053–3056` states
      "Update in-memory maps only AFTER the atomic operation commits successfully," and the
      deferred mutations `indexEngines.set(internalIndexId, null)` / `indexEngineNameMap.remove(...)`
      sit at 3057–3058, after the `executeInsideAtomicOperation(...)` call (3046–3051) returns.
      The finding's "~3053–3056" line cite lands precisely on the deferral comment; the
      "mirroring `deleteIndexEngine`'s existing defer discipline" wording is accurate.
  - Current state: the track now mandates splitting file/engine creation + id allocation
    (inside the atomic op, WAL-reverted) from registry publication (deferred to the
    post-`commitChanges` success path, or undone in the failure `finally`), and the I-A4
    test asserts no map entry survives a forced apply failure.
  - Criteria met: the failed-commit phantom-registration risk that D10 forbids is now both
    named in the design carrier (D10) and enforced by an acceptance test (I-A4). The cited
    precedent is a real, verbatim discipline in the same class, so the fix is implementable
    as described rather than aspirational.
- **Regression check**: checked that the deferred-publication requirement does not conflict
  with D3's ordering constraint (engines must be created and registered before `lockIndexes`).
  The two are reconcilable and the track keeps them distinct — D3/T1 requires the on-disk
  engine to exist and a lock-free `doGetIndexEngine(int)` resolver to be reachable during the
  commit window, while R1/A1 defers only the *shared in-memory registry* publication; the
  commit-local allocator (D10) and the lock-free resolver cover the in-window lookups without
  publishing into the shared maps early. No contradiction introduced. Clean.
- **Verdict**: VERIFIED

#### Verify R2: F59 root-omission clause and the D6 selective per-class write not hosted in Track 4
- **Original issue**: Track 2's G2 hand-off (the write-amplification win: selective per-class
  write keyed on the changed set, plus the F59 root-omission regression that the root record
  must be re-written when its non-link payload changes) was deferred into Track 4 but not
  actually hosted there.
- **Fix applied**: D6 Risks/Caveats hosts the selective-write rule and the F59 regression;
  `## Plan of Work` step 5 adds the selective write + root-on-payload-change rule;
  `## Interfaces and Dependencies` In-scope adds the `SchemaShared.toStream` selective write;
  `## Validation and Acceptance` adds the F59 regression test and the one-record-per-changed-class
  assertion (I-U1).
- **Re-check**:
  - Track-file location: D6 Risks/Caveats (lines 57–58, the "Selective write and the F59
    guard (R2/A2), hosted here per Track 2's G2 hand-off" block), Plan-of-Work step 5 (lines
    137–138), Interfaces In-scope (lines 222–224), Validation I-U1 (lines 176–179).
  - Codebase corroboration: `SchemaShared.toStream(session)` at `SchemaShared.java:796`
    iterates `realClasses = new HashSet<>(classes.values())` (809) and writes every live class
    via `c.toStream(session, classRecord)` (835) — confirming the finding's "serializes every
    live class today" premise, so a selective write is a genuine change, not a no-op.
    `getChangedClasses()` exists at `TxSchemaState.java:87`, so the keying source the fix names
    is present (a Track 3 deliverable, consistent with the inter-track dependency claim).
  - Current state: the selective write keyed on `getChangedClasses()` (or record-layer
    dirty-mark suppression), the root-on-payload-change rule, and the F59 root-omission guard
    (committed property-create → restart → non-null `globalRef` + no colliding generated
    collection name) are all hosted in Track 4 across D6, Plan-of-Work, Interfaces, and a
    concrete acceptance test. The one-record-per-changed-class assertion is in I-U1.
  - Criteria met: Track 2's G2 hand-off is now closed — the deferred write-amplification win
    and its F59 safety regression have a definite home in this track with a test, rather than
    floating between tracks. The route from `getChangedClasses()` (Track 3) to the selective
    `toStream` write (Track 4) is symbol-consistent.
- **Regression check**: checked that hosting the selective write here does not collide with
  D6's other rule (the structural create/drop set is NOT derived from the changed-record set
  but from D9's set difference). The two coexist — selective *write* is keyed on
  `getChangedClasses()` for write-amplification, while structural *reconciliation* still uses
  the D9 collection-id set difference; the track keeps them in separate sentences within D6.
  No double-counting or contradiction. Clean.
- **Verdict**: VERIFIED

#### Verify R3: exhaustiveness of the "only two hot lock-based read sites" enumeration
- **Original issue**: the D19 claim that only two `SchemaShared.lock`-based hot read sites
  remain (`createVertexWithClass`, `getLowerSubclass`) was asserted but not committed to a
  verification step.
- **Fix applied**: `## Idempotence and Recovery` adds a "Read-site enumeration (D19, T3/R3)"
  note committing decomposition to confirm the enumeration is exhaustive.
- **Re-check**:
  - Track-file location: `## Idempotence and Recovery` (lines 208–210).
  - Codebase corroboration: both named sites exist and are schema reads —
    `YTDBGraphImplAbstract.createVertexWithClass` (`YTDBGraphImplAbstract.java:121`) reads the
    shared schema (`session.getSharedContext().getSchema().getClass(label)`), and
    `getLowerSubclass` exists at `SQLMatchStatement.java:365` and `MatchExecutionPlanner.java:4925`.
    Both are real, current read sites, so the enumeration's two anchors are accurate.
  - Current state: the note now states "Decomposition confirms only the two named
    `SchemaShared.lock`-based hot reads … remain non-snapshot; every other production
    `getSchema()` reader must be already snapshot-routed, off the commit-contended hot path,
    or itself a schema-write path." The exhaustiveness obligation is recorded as a
    decomposition-time verification rather than an un-evidenced assertion — the correct
    disposition for a suggestion (the full enumeration is a Phase-A decomposition task, not a
    plan-time deliverable).
  - Criteria met: the suggestion is satisfied — the track now owns the obligation to confirm
    the read-site set is closed, with a stated test (every other `getSchema()` reader falls
    into one of three safe buckets).
- **Regression check**: checked that this addition does not over-claim. The note correctly
  defers the actual enumeration to decomposition rather than asserting completeness now, so it
  introduces no false plan-time guarantee. Clean.
- **Verdict**: VERIFIED

#### Verify R4: no built-in failure-injection hook for I-A4's "force commit to fail at apply"
- **Original issue**: I-A4 requires faulting the commit at/after `commitChanges`, but no
  built-in failure-injection hook was named; faulting before `commitChanges` would not
  exercise the deferred-publication window.
- **Fix applied**: I-A4 acceptance now says "fault injected at or after `commitChanges`, not
  before"; `## Idempotence and Recovery` names the Mockito spy / `@VisibleForTesting` seam and
  the window.
- **Re-check**:
  - Track-file location: Validation I-A4 (lines 181–183, "fault injected at or after
    `commitChanges`, not before"), `## Idempotence and Recovery` "Failed-commit registry
    cleanliness (I-A4, R4)" (lines 199–204).
  - Current state: the recovery note specifies the fault point as a Mockito spy or a
    `@VisibleForTesting` seam on the commit, fired at or after `commitChanges`, ties it to
    Track 1's settled `RestoreAtomicUnit*` Mockito harness, states explicitly that failing
    before `commitChanges` does not exercise the deferred-publication window, and adds a
    fallback ("If no reusable fault hook exists, decompose a small testability seam rather than
    improvising one per test"). This is consistent with R1's deferred-publication semantics —
    the window the test must hit is precisely between `commitChanges` success and the deferred
    registry publication, so faulting after `commitChanges` is the only point that exposes a
    phantom registration if the deferral is wrong.
  - Criteria met: the suggestion is satisfied — the test's fault point, the seam mechanism,
    the precise window, and a no-hook fallback are all named, removing the "no built-in hook"
    gap.
- **Regression check**: checked the I-A4 acceptance text and the recovery note for internal
  consistency — both now say at/after `commitChanges`, and the recovery note's window
  description matches R1's deferred-publication boundary. No contradiction between the two
  sites. Clean.
- **Verdict**: VERIFIED

## Summary

PASS. All four iteration-1 risk findings VERIFIED:
- R1 (should-fix) — the deferred/failure-reverting registry-publication discipline is hosted
  in D10, Plan-of-Work step 6, Interfaces, and the I-A4 acceptance; the cited `deleteIndexEngine`
  precedent (`AbstractStorage.java:3053–3058`) and the synchronous-publication hazard sites
  (`doAddCollection`→`registerCollection`:5026/4977/4983, `addIndexEngine` lambda:2811–2812)
  are confirmed in the codebase.
- R2 (should-fix) — Track 2's G2 hand-off is closed: the D6 selective per-class write keyed on
  `getChangedClasses()` and the F59 root-omission guard are hosted across D6, Plan-of-Work
  step 5, Interfaces, and a concrete I-U1 acceptance test; `SchemaShared.toStream` (all-class
  serialization today) and `TxSchemaState.getChangedClasses()` confirmed present.
- R3 (suggestion) — the read-site enumeration is committed as a decomposition-time verification
  obligation; both named sites confirmed to exist as schema reads.
- R4 (suggestion) — the failure-injection seam, fault window, and no-hook fallback are named.

No new finding surfaced. No regression introduced by any fix.
