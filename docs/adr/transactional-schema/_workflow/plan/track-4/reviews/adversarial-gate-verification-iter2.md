<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: A1, verdict: VERIFIED}
  - {id: A2, verdict: VERIFIED}
  - {id: A3, verdict: VERIFIED}
  - {id: A4, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Adversarial gate verification — Track 4 (iteration 2)

All four iteration-1 adversarial findings were ACCEPTED with fixes applied
to `track-4.md`. Each fix is re-checked below against the amended track file
and the live codebase (PSI-verified, IDE reachable, project
`transactional-schema` open and matching the worktree). No fix introduced a
regression and no new finding surfaced. Overall: PASS.

The D14 Fable-5 pin for the adversarial review is unavailable in this
environment; this verification ran on the session-default model per D14's
documented degradation. The conclusions rest on PSI re-checks, not model
identity, so the degradation does not weaken the verdict.

## Findings

(none — pure-verdict pass)

## Verification certificates

#### Verify A1: phantom in-memory registration on a failed commit (== R1)
- **Original issue**: the lock-free creation primitives publish into the live
  in-memory registries synchronously inside the atomic operation, but the WAL
  revert undoes only the on-disk file create, not the Java maps. Reusing the
  primitives verbatim leaves a phantom registration on a failed commit.
  Registry-revert was absent from D2's closed five-item patch list.
- **Fix applied**:
  - D10 Risks/Caveats gained the "In-memory registry publication must trail
    the atomic op (A1/R1)" note: split file/engine creation + id allocation
    (inside the atomic op, WAL-reverted) from registry publication (deferred
    to post-`commitChanges` success, or undone in the failure `finally`),
    mirroring `deleteIndexEngine`.
  - `## Plan of Work` step 6 now states the deferral explicitly
    (`collections` / `collectionMap` / `indexEngines` / `indexEngineNameMap`).
  - `## Interfaces and Dependencies` → In scope adds "the deferred /
    failure-reverting in-memory registry publication for the create
    primitives (A1/R1)".
  - `## Validation and Acceptance` I-A4 now asserts the in-memory registries
    carry no entry for a forced-fail-at-apply commit and the next commit
    reuses the ids.
- **Re-check**:
  - Track-file location: D10 caveat (lines ~71–72), Plan of Work step 6
    (lines ~139–141), In-scope (line ~219), Validation I-A4 (lines ~181–183).
  - Codebase (PSI, `AbstractStorage`): the defect is real and precisely
    described. `doAddCollection` → `registerCollection(collection)` runs
    synchronously inside the atomic op; `registerCollection` writes
    `collectionMap.put(...)` and calls `setCollection(...)` which writes the
    `collections` list. The `addIndexEngine` atomic-operation lambda writes
    `indexEngineNameMap.put(...)` and `indexEngines.add(engine)`. The cited
    mirror is real: `deleteIndexEngine` performs its `indexEngines.set(id,
    null)` / `indexEngineNameMap.remove(...)` AFTER
    `executeInsideAtomicOperation` returns, carrying the explanatory comment
    "Update in-memory maps only AFTER the atomic operation commits
    successfully." So the deferral discipline the fix invokes exists verbatim
    in the codebase.
  - Criteria met: the same-defect-class-as-R1 hazard is now carried as a
    load-bearing caveat, surfaced in the work sequence, listed in scope, and
    pinned by an acceptance test; the prescribed remedy matches an existing
    in-tree pattern.
- **Regression check**: checked D10's other caveats (commit-local allocator
  seed under the write lock, F88 pin, F55 crash-recovery dependency) and the
  Plan of Work ordering constraints — the new step 6 sits correctly after
  step 5 (provisional resolution) and before step 7 (promotion), consistent
  with "registry publication only after `commitChanges`" in the load-bearing
  ordering list. Clean.
- **Verdict**: VERIFIED

#### Verify A2: D6 write-amplification win must land in `SchemaShared.toStream`
- **Original issue**: the D6/D14 write-amplification win keyed on
  `getChangedClasses()` must land in `SchemaShared.toStream`, which serializes
  every live class today, but `SchemaShared` was absent from the in-scope list.
- **Fix applied**:
  - D6 Risks/Caveats now hosts the selective write ("the inherited
    `SchemaShared.toStream` serializes every live class today, so this track
    adds the selective write keyed on `getChangedClasses()`"), with the F59
    root-omission guard attached.
  - `## Plan of Work` step 5 names the selective per-class write.
  - In-scope adds "the selective per-class write in `SchemaShared.toStream`
    keyed on `getChangedClasses()`".
  - Validation I-U1 asserts a one-class change writes exactly one per-class
    record (plus the root record only when its payload changed).
- **Re-check**:
  - Track-file location: D6 caveat (line ~58), Plan of Work step 5 (lines
    ~136–138), In-scope (lines ~221–222), Validation I-U1 (lines ~177–179).
  - Codebase (PSI, `SchemaShared`): `toStream(DatabaseSessionEmbedded)`
    iterates `realClasses = new HashSet<>(classes.values())` and writes a
    record per live class (`c.toStream(session, classRecord)`) — it serializes
    every live class today, exactly as the finding claims. `getChangedClasses()`
    exists (in `TxSchemaState`, the Track-3 tx-local schema state, returning
    `Set<String>`), so the selective key the fix names is a real, resolvable
    symbol. The selective write is genuinely net-new on top of today's
    write-everything `toStream`.
  - Criteria met: `SchemaShared.toStream` is now in scope, the win is hosted
    in D6, sequenced in the work plan, and pinned by I-U1; the keying symbol
    resolves.
- **Regression check**: checked the F59 root-omission guard wording (D6 caveat
  and I-U1) — the guard remains coherent: the root write must accompany a
  changed non-link payload, otherwise a committed property-create restarts
  into a null `globalRef`. No contradiction introduced by the selective-write
  addition. Clean.
- **Verdict**: VERIFIED

#### Verify A3: engine-id allocator and multi-class re-key ordering unstated
- **Original issue**: D2's five-item patch list is collection-shaped; the
  engine-id allocator (`indexEngines.size()`) is a separate allocation axis
  not enumerated, and multi-class reverse-map re-key ordering was unstated.
- **Fix applied**:
  - D2 Risks/Caveats now states the engine-id allocator (`indexEngines.size()`)
    is a second allocation axis, separate from the collection-id allocator,
    following the identical commit-local-seed discipline (D10); and that a
    multi-class/multi-index commit resolves every provisional id first, then
    re-keys `collectionsToClasses` and re-points property values, so
    cross-class references settle before any record serializes.
  - I-A3 (Validation) strengthened: the two-class commit asserts cross-class
    references settle correctly.
- **Re-check**:
  - Track-file location: D2 caveat (line ~44), Plan of Work step 5 (the
    engine-id-allocator clause, lines ~135–136), Validation I-A3 (lines
    ~160–163).
  - Codebase (PSI, `AbstractStorage`): the engine-id allocator is real and
    distinct — `addIndexEngine` allocates with `var genenrateId =
    indexEngines.size();`, whereas the collection-id allocator in
    `doAddCollection` scans `collections` for the first null slot (defaulting
    to `collections.size()`). Two separate axes, as the fix states.
    `collectionsToClasses` exists on `SchemaShared`
    (`Int2ObjectOpenHashMap<SchemaClassImpl>`), so the named re-key target
    resolves. The resolve-all-then-rekey ordering is consistent with the D3/D9
    "provisional → real resolution before serialization" constraint.
  - Criteria met: both gaps (engine-id axis, multi-class ordering) are now
    enumerated in D2 and pinned by I-A3; both named symbols resolve.
- **Regression check**: checked the D2 five-item patch list against the new
  engine-axis sentence — the five collection-shaped items are unchanged and
  the engine allocator is added as a clearly-labelled second axis rather than
  silently folded into the five, so the patch-list count stays accurate.
  Checked D10's commit-local-allocator caveat referenced by the fix — it
  already covers "collection/engine ids" so the cross-reference is sound.
  Clean.
- **Verdict**: VERIFIED

#### Verify A4: scope/sizing density — decomposition guidance
- **Original issue (suggestion)**: ~15 files + 8 strictly-ordered concerns is
  dense; the engine-primitive extraction and the two snapshot-first read
  conversions are separable from the reconciliation core. Disposition:
  ACCEPTED AS DECOMPOSITION GUIDANCE — to be applied in the upcoming
  `## Concrete Steps` roster, with no track-file structural change since the
  track survives the soft footprint bound.
- **Fix applied**: none to the track structure (by design); guidance carried
  forward to the decomposition sub-step.
- **Re-check**:
  - Track-file location: `## Concrete Steps` (lines ~153–154) is still the
    Phase A placeholder (`<!-- Phase A placeholder. -->`), so the roster that
    will isolate the engine-primitive extraction (now Plan-of-Work step 3) and
    the two snapshot-first read conversions (step 8) from the reconciliation
    core (steps 1–2, 4–7) has not yet been written — consistent with "applied
    in the upcoming roster."
  - Current state: nothing in the amended `## Purpose / Big Picture`,
    `## Plan of Work`, or `## Interfaces and Dependencies` contradicts the
    separability claim. The Plan-of-Work sequence already isolates the
    extraction (step 3) and the read conversions (step 8) as distinct
    work units, which the decomposition can map to standalone steps.
  - Criteria met: guidance accepted; no structural change required; the track
    remains internally consistent with the guidance.
- **Regression check**: confirmed the soft-footprint argument holds — the
  track stays a single track, the Plan-of-Work units that A4 flags as
  separable are already enumerated as discrete steps (3 and 8), and no
  amendment from A1/A2/A3 reshuffled them. Clean.
- **Verdict**: VERIFIED (guidance accepted; roster written in the next
  decomposition sub-step)

## Summary

PASS. All four iteration-1 adversarial findings (A1 blocker, A2/A3 should-fix,
A4 suggestion) are VERIFIED. Every fix landed where the disposition said it
would, every Java symbol the fixes invoke (`doAddCollection` /
`registerCollection` / `setCollection`, the `addIndexEngine` lambda's
`indexEngines.size()` allocator and `indexEngineNameMap`/`indexEngines`
writes, `deleteIndexEngine`'s post-atomic-op deferral mirror,
`SchemaShared.toStream`'s write-every-live-class body, `getChangedClasses()`
on `TxSchemaState`, and the `collectionsToClasses` reverse map) is
PSI-confirmed to exist and behave as the track now describes, and no fix
introduced a regression. No new findings.
