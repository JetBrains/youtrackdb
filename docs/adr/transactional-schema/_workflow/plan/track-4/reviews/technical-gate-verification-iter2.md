<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: T1, verdict: VERIFIED}
  - {id: T2, verdict: VERIFIED}
  - {id: T3, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Technical gate-verification (iteration 2) — Track 4

Re-check of the three ACCEPTED technical findings from iteration 1 after fixes
landed in `track-4.md`. All three are VERIFIED against the track file and, for
the Java-symbol claims, against the live PSI tree (project `transactional-schema`
open, matching this worktree). No new technical finding surfaced, so this is a
pure-verdict pass: PASS.

## Verdicts

#### Verify T1: Engine-lookup read-lock re-entry self-deadlocks the schema-carrying commit
- **Original issue**: under D19 the commit holds `stateLock.writeLock()` from
  entry, but `lockIndexes` → `IndexAbstract.acquireAtomicExclusiveLock` →
  `getIndexEngine(int)` re-acquires `stateLock.readLock()`, which busy-spins
  forever on the non-reentrant `ScalableRWLock`. Self-deadlock on every
  schema-or-index commit carrying an index op.
- **Fix applied**: the lock-free engine resolver `doGetIndexEngine(int)` is added
  across three track-file sections, plus an explicit enumeration requirement.
- **Re-check**:
  - Track-file location: `## Decision Log` D3 Risks/Caveats (lines 50–51),
    `## Plan of Work` step 3 (lines 127–130), `## Interfaces and Dependencies`
    In-scope (lines 217–219) and Signatures (lines 237–240).
  - Current state: D3 Risks/Caveats now carries the **"Engine-lookup re-entry
    (T1/T2)"** note. It states the chain `lockIndexes` →
    `IndexAbstract.acquireAtomicExclusiveLock` → `getIndexEngine(int)`
    re-acquires `stateLock.readLock()` and busy-spins on the non-reentrant
    `ScalableRWLock` once the D19 write lock is held; it requires a lock-free
    `doGetIndexEngine(int)` reading `indexEngines.get(id)` without `stateLock`,
    explicitly **mirroring `doGetAndCheckCollection`**; and it requires the track
    to **enumerate every `stateLock.readLock()`-taking method reachable from the
    commit body under the write lock** and confirm each is replaced by a
    lock-free variant. Plan-of-work step 3 adds the resolver ("add a lock-free
    engine resolver (`doGetIndexEngine`) for the commit window … with no
    `stateLock.readLock()` re-entry"). In-scope and Signatures both list
    `doGetIndexEngine(int)` as the new lock-free resolver "for the commit window
    (T1)".
  - PSI confirmation of the underlying hazard:
    - `AbstractStorage#getIndexEngine(int)` body acquires
      `stateLock.readLock().lock()` around `indexEngines.get(indexId)` —
      the read-lock re-entry is real.
    - `AbstractStorage#lockIndexes(...)` calls
      `changes.getIndex().acquireAtomicExclusiveLock(atomicOperation)`;
      `IndexAbstract#acquireAtomicExclusiveLock(AtomicOperation)` calls
      `storage.getIndexEngine(indexId)` — the call chain is exactly as the
      note states.
    - `AbstractStorage#doGetAndCheckCollection(int)` body is lock-free (range
      check + `collections.get(collectionId)` + null guard, **no `stateLock`**)
      — the cited precedent is genuine.
    - `doGetIndexEngine` returns 0 PSI matches — it does not yet exist,
      consistent with the track proposing it as new work.
  - Criteria met: the blocker is now recorded as a load-bearing risk with the
    concrete fix (lock-free resolver) and the broader safeguard (full read-lock
    re-entry enumeration), wired into the plan of work and the interface
    contract. Self-consistent across all four sites — the precedent name, the
    resolver name, and the signature all agree.
- **Regression check**: checked that the new resolver name does not collide with
  an existing symbol (PSI: 0 matches for `doGetIndexEngine`), that the precedent
  `doGetAndCheckCollection` is uniquely named and lock-free (PSI: 1 match,
  body confirmed), and that the enumeration requirement does not contradict the
  D19 four-lock order or the D10 deferred-publication discipline (it is a
  read-path concern, orthogonal). Clean.
- **Verdict**: VERIFIED

#### Verify T2: D3 rationale mischaracterized `lockIndexes`
- **Original issue**: D3 rationale claimed `lockIndexes` "resolves engines by id
  and throws on a missing one"; the throw framing was wrong and obscured the real
  read-lock-re-entry hazard.
- **Fix applied**: D3 rationale rewritten; the Risks/Caveats note records the
  retry-not-throw behavior.
- **Re-check**:
  - Track-file location: `## Decision Log` D3 Rationale (line 50) and
    Risks/Caveats (line 51).
  - Current state: the rationale now reads "index-engine creation must land
    before `lockIndexes` (which locks each tx index's engine through
    `IndexAbstract.acquireAtomicExclusiveLock` → `getIndexEngine`, so the engine
    must already be created and registered)". The Risks/Caveats note adds: "The
    earlier 'resolves by id and throws on a missing one' gloss was wrong: a
    missing engine loops on `InvalidIndexEngineIdException` retry, so the
    load-bearing hazard is the read-lock re-entry, not a propagated throw."
  - PSI confirmation: `IndexAbstract#acquireAtomicExclusiveLock(AtomicOperation)`
    body is `while (true) { try { engine = storage.getIndexEngine(indexId);
    break; } catch (InvalidIndexEngineIdException ignore) { doReloadIndexEngine();
    } }` — a missing/invalid engine id is caught and retried via
    `doReloadIndexEngine()`, not propagated. The corrected gloss matches the code
    exactly; the original "throws on a missing one" framing was indeed wrong.
  - Criteria met: the rationale now names the accurate call path and the
    Risks/Caveats note correctly identifies the read-lock re-entry (not a throw)
    as the load-bearing hazard.
- **Regression check**: checked that the corrected description stays consistent
  with the T1 note (same chain, same `stateLock.readLock()` re-entry conclusion)
  and with D3's lock-free-primitive rationale. No contradiction introduced. Clean.
- **Verdict**: VERIFIED

#### Verify T3: D19 "only two lock-based read sites remain" is a global property
- **Original issue**: D19's claim that only `createVertexWithClass` and
  `getLowerSubclass` remain as lock-based reads is a global property over all
  `getSchema()` readers; it should be confirmed during decomposition, and it is
  about `SchemaShared.lock` reads, not `stateLock`.
- **Fix applied**: a decomposition-verification note added to
  `## Idempotence and Recovery`.
- **Re-check**:
  - Track-file location: `## Idempotence and Recovery` (lines 207–210).
  - Current state: the note **"Read-site enumeration (D19, T3/R3)"** exists and
    reads "Decomposition confirms only the two named `SchemaShared.lock`-based
    hot reads (`createVertexWithClass`, `getLowerSubclass`) remain non-snapshot;
    every other production `getSchema()` reader must be already snapshot-routed,
    off the commit-contended hot path, or itself a schema-write path." This
    correctly frames the enumeration as a global property over `getSchema()`
    readers, correctly scopes it to `SchemaShared.lock` (not `stateLock`), and
    correctly defers the confirmation to decomposition.
  - Criteria met: the suggestion is recorded as a decomposition-verification
    obligation with the right lock scope and the right global framing.
- **Regression check**: checked that the note's lock scope (`SchemaShared.lock`)
  does not blur into the `stateLock` read-lock concern of T1/T2 — it is kept
  distinct, and the two named sites match the In-scope conversions
  (`YTDBGraphImplAbstract.createVertexWithClass`,
  `SQLMatchStatement.getLowerSubclass`). Consistent. Clean.
- **Verdict**: VERIFIED

## Findings

<!-- No new technical findings surfaced during verification. -->

## Summary

PASS. All three iteration-1 technical findings (T1 blocker, T2 should-fix, T3
suggestion) are VERIFIED. The T1 fix landed self-consistently across the
Decision Log, Plan of Work, and Interfaces sections, and its two load-bearing
Java-symbol claims hold under PSI: `getIndexEngine(int)` takes
`stateLock.readLock()` (the deadlock source) and `doGetAndCheckCollection(int)`
is genuinely lock-free (the cited precedent for `doGetIndexEngine`). The T2
retry-not-throw correction matches the `acquireAtomicExclusiveLock`
`InvalidIndexEngineIdException` retry loop verbatim. The T3 read-site
enumeration note is present and correctly scoped to `SchemaShared.lock`. No
regression and no new technical finding.
