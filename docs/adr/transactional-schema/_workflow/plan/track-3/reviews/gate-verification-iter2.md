<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: R1, verdict: VERIFIED}
  - {id: R2, verdict: VERIFIED}
  - {id: R3, verdict: VERIFIED}
  - {id: R4, verdict: VERIFIED}
  - {id: R5, verdict: VERIFIED}
  - {id: R6, verdict: VERIFIED}
  - {id: A1, verdict: VERIFIED}
  - {id: A2, verdict: VERIFIED}
  - {id: A3, verdict: VERIFIED}
  - {id: A4, verdict: VERIFIED}
  - {id: A5, verdict: VERIFIED}
  - {id: A6, verdict: VERIFIED}
  - {id: T1, verdict: VERIFIED}
  - {id: T2, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Track 3 Phase A gate verification (iteration 2, consolidated)

Re-check of all 14 iteration-1 findings (technical, risk, adversarial) after
fixes landed in the track file. All PASS. PSI grounded every load-bearing code
claim against the `transactional-schema` project: proxy method counts,
`ProxedResource.delegate`, `SchemaShared` no-arg constructor / `void fromStream`
/ `toStream` write-lock assert, `commitImpl` reentrancy short-circuit,
`IndexAbstract.addCollection` eager apply under the index exclusive lock, and
the membership sites' `executeInTxInternal` + index-manager + index-exclusive
lock layering. No fix introduced an inconsistency or regression.

#### Verify R1: Track-3 commit contract + eager-allocation intermediate state unpinned
- **Original issue**: the commit contract for the intermediate Track-3 state was unpinned — unclear whether the track promotes, eagerly allocates, or persists end-to-end.
- **Fix applied**: `## Plan of Work` adds a "Track-3 commit contract (intermediate state)" paragraph (no promotion, no eager-allocation change — both Track 4; a no-tx change keeps the legacy `saveInternal` / `forceSnapshot` save path; an in-tx change is isolation+rollback tested only; end-to-end persistence is a Track-4 forward reference). `## Idempotence and Recovery` documents the eager-allocation stray-collection intermediate-state condition closed by Track 4 (D10/D2). `## Concrete Steps` validation block re-scoped per line.
- **Re-check**:
  - Location: track-3.md `## Plan of Work` lines 166-174; `## Idempotence and Recovery` "Eager-allocation intermediate state" bullet lines 249-256.
  - Current state: the commit contract paragraph is present and explicit (legacy path for no-tx, tx-local route for in-tx, promotion/persistence deferred to Track 4 as a forward reference). The intermediate-state stray-collection condition is documented as a known Track-4-closed condition, not a Track-3 bug.
  - Criteria met: intermediate state is now pinned; the Track-3/Track-4 boundary is explicit.
- **Regression check**: cross-checked against D1 (enablement facet, lines 50-55) and D10 (plan line 172) — the "enablement half only, structure deferred to Track 4" split is consistent with both. Clean.
- **Verdict**: VERIFIED

#### Verify R2: ~168-method proxy routing surface needs a single seam
- **Original issue**: the tier-3 proxy routing spans ~168 methods; one missed method silently breaks isolation mid-tx; per-method edits are fragile.
- **Fix applied**: `## Plan of Work` mandates a single `resolve()` seam on `ProxedResource` / `SchemaProxy` (tier 1 snapshot / tier 2 captured-delegate / tier 3 name-rebind) that every method funnels through, including proxies minted mid-tx via `getClass`. Step-level realization deferred to decomposition.
- **Re-check**:
  - Location: track-3.md `## Plan of Work` lines 140-149.
  - Current state: the seam is described as a single `resolve()` helper on `ProxedResource` / `SchemaProxy` covering all three tiers, with mid-tx-minted proxies routed through the same seam. The prose captures the guidance; no step roster is expected yet (decomposition follows this gate).
  - Criteria met: the single-seam requirement is captured in prose — the gate-pass condition for a "realized in decomposition" finding.
  - PSI: `ProxedResource` exists at `...db.record.ProxedResource` with a captured `delegate:T` field, confirming the delegate-dereference pattern the seam replaces. Method counts: SchemaProxy 39 + SchemaClassProxy 78 + SchemaPropertyProxy 48 = 165, consistent with the prose's "~168" (approximate; the seam guidance does not depend on the exact integer).
- **Regression check**: the "~168" figure is approximate vs the PSI-measured 165 — a harmless rounding in prose, not a load-bearing count. No regression.
- **Verdict**: VERIFIED

#### Verify R3 / A1: I-C2 engage-placement deadlock at the index-locking membership sites
- **Original issue**: de-guarded membership sites (`addCollectionToIndex` / `removeCollectionFromIndex`) take the index-manager lock and `IndexAbstract.addCollection` takes the index exclusive lock; the mutex engage must be provably first on every write path or it deadlocks against the commit-side schema-lock acquisition.
- **Fix applied**: `## Plan of Work` ordering constraints require engage provably first on every write path including the index-locking membership sites and direct `SchemaEmbedded` callers; `## Idempotence and Recovery` adds a Java `assert` at the engage point (permit held => no shared metadata lock held by this thread); `## Concrete Steps` validation block carries an I-C2 engage-placement acceptance line asserted on every write path including the de-guarded membership sites.
- **Re-check**:
  - Location: track-3.md `## Plan of Work` lines 176-183 (ordering constraints); `## Idempotence and Recovery` "Engage-order assert" bullet lines 232-239; `## Concrete Steps` I-C2 line 215-218.
  - Current state: the engage-first-on-every-write-path constraint is explicit and names the membership sites and direct `SchemaEmbedded` callers; the engage-order assert guards against engaging from inside a shared-lock acquisition; the I-C2 acceptance line is engage-placement-scoped.
  - Criteria met: the engage-first guidance plus the engage-first test guidance are captured in prose — the gate-pass condition for a "realized in decomposition" finding.
  - PSI: `IndexManagerEmbedded.addCollectionToIndex` / `removeCollectionFromIndex` take `acquireSharedLock()` (index-manager lock) and call `session.executeInTxInternal(...)` whose inner work takes `acquireExclusiveLock(transaction)` (the index exclusive lock). `IndexAbstract.addCollection` / `removeCollection` wrap `collectionsToIndex.add/remove` in `acquireExclusiveLock()` / `releaseExclusiveLock()`. The two-lock layering the finding warned about is confirmed.
- **Regression check**: the index-manager lock is a *shared* lock (`acquireSharedLock`), not exclusive; the track's "engage above any shared metadata lock" framing and the assert's "no shared metadata lock held" wording both cover a shared index-manager lock correctly, so the shared-vs-exclusive nuance does not weaken the guard. Clean.
- **Verdict**: VERIFIED

#### Verify R4: Validation lines straddle the Track-3/Track-4 boundary
- **Original issue**: validation lines mixed Track-3-testable assertions with Track-4 deferred ones without per-line scoping.
- **Fix applied**: the `## Concrete Steps` validation block re-scopes each line, marking it **Track 3** vs deferred (post-commit visibility => Track 4; whole-commit no-outage => Track 4/Track 7).
- **Re-check**:
  - Location: track-3.md `## Concrete Steps` annotation block lines 191-224.
  - Current state: every acceptance line carries an explicit **(Track 3)** tag or a deferral note. The I-A5 line splits isolation (Track 3) from post-commit transition (Track 4); the last line splits mutex-orthogonality (Track 3) from D19 no-outage (Track 4) and freeze-vs-read-outage (Track 7).
  - Criteria met: per-line scoping is present; no line claims a Track-4/Track-7 guarantee as Track-3 testable.
  - Note: these per-line annotations sit under `## Concrete Steps` (the placeholder block that the prompt says is intentionally still empty for the step *roster*). The annotation block is acceptance-scoping prose, not a step roster, so its presence does not violate the "Concrete Steps roster still empty" expectation.
- **Regression check**: cross-checked the deferrals against the plan invariant map (lines 255-282) — I-A5/I-A6/I-A7 are Track 3, I-P1/I-U5/I-C1 are Track 4, I-C3/I-freezer-1 are Track 7. The deferrals are consistent. Clean.
- **Verdict**: VERIFIED

#### Verify R5: Self-commit leak is conditional on top-level
- **Original issue**: the self-commit framing was stated as unconditional; it is correct only today, because the throw-guards force DDL top-level.
- **Fix applied**: `## Context and Orientation` states the today-only condition.
- **Re-check**:
  - Location: track-3.md `## Context and Orientation` lines 98-115.
  - Current state: the prose says the `executeInTxInternal` self-commit "is the dangerous part *today*" and then explains that once the throw-guards are removed and the same site runs inside a user tx, `commitImpl` short-circuits while `amountOfNestedTxs() > 1`, so the nested call no longer self-commits.
  - Criteria met: the today-only condition is stated; the corrected reentrancy mechanism is the consequence.
  - PSI: `DatabaseSessionEmbedded.commitImpl` contains `if (currentTx.amountOfNestedTxs() > 1)` — confirms the short-circuit the prose relies on.
- **Regression check**: consistent with the A2 correction (same mechanism). Clean.
- **Verdict**: VERIFIED

#### Verify R6: No-freeze test over-claims D19/Track 7
- **Original issue**: a validation line claimed a no-freeze / no-outage guarantee that belongs to D19 (Track 4) and the freezer gate (Track 7).
- **Fix applied**: the last `## Concrete Steps` validation line is re-scoped to mutex-orthogonality, with an explicit D19/Track 4 and Track 7 deferral note.
- **Re-check**:
  - Location: track-3.md `## Concrete Steps` last annotation bullet lines 219-224.
  - Current state: the line asserts only "a concurrent snapshot-based read and a concurrent lock-based read both proceed" while a mutex permit is held (mutex-orthogonality), and explicitly defers the whole-commit no-outage (D19, Track 4) and the freeze-vs-read-outage (Track 7).
  - Criteria met: no D19/Track-7 over-claim remains; the Track-3 assertion is mutex-orthogonality only.
- **Regression check**: D19 is plan-scoped to Track 4 (plan line 239), I-freezer-1 to Track 7 (plan line 267) — the deferrals match. Clean.
- **Verdict**: VERIFIED

#### Verify A2 / T1: I-A7 mechanism mis-located (self-commit vs eager shared apply)
- **Original issue**: the I-A7 leak was framed as a nested-commit escape via `executeInTxInternal`; PSI showed `executeInTxInternal` is reentrant (`commitImpl` short-circuits on `amountOfNestedTxs() > 1`), so the real leak is the eager `IndexAbstract.addCollection` => `collectionsToIndex.add` on the shared `Index`, not a self-commit escape once de-guarded.
- **Fix applied**: `## Context and Orientation` self-commit paragraph corrected to the reentrancy mechanism; `## Plan of Work` de-guard sentence corrected (replace the `executeInTxInternal` body, route into the tx-local changed set, not merely strip the guard); `## Surprises & Discoveries` logs the `design.md` discrepancy for Phase 4 reconciliation. The I-A7 validation line is kept (already tests the shared `collectionsToIndex`-untouched set).
- **Re-check**:
  - Location: track-3.md `## Context and Orientation` lines 98-115; `## Plan of Work` de-guard sentence lines 151-157; `## Surprises & Discoveries` lines 27-41; `## Concrete Steps` I-A7 line 204-207.
  - Current state: the orientation prose names the reentrancy short-circuit and identifies the residual leak as the eager `IndexAbstract.addCollection` => `collectionsToIndex.add` on the shared `Index` under the index exclusive lock, unreverted on user-tx rollback. The de-guard sentence requires replacing the `executeInTxInternal` body so the membership change records into the tx-local changed set. The discovery logs the frozen-`design.md` discrepancy for Phase 4.
  - Criteria met: the mechanism is corrected at all three carriers; the I-A7 test (shared `collectionsToIndex` untouched on rollback) still asserts the right set.
  - PSI: `commitImpl` short-circuit confirmed; `IndexAbstract.addCollection` does `if (collectionsToIndex.add(collectionName))` under `acquireExclusiveLock()`, `collectionsToIndex` field present — confirms the corrected leak location.
- **Regression check**: the correction is consistent with the R5 today-only framing and the D1 enablement-half split. The `## Surprises & Discoveries` entry correctly notes `design.md` is frozen and the reconciliation is a Phase 4 action, not an in-track edit. Clean.
- **Verdict**: VERIFIED

#### Verify A3: Sizing — split along the mutex-vs-view seam
- **Original issue**: ~168 proxy methods + 28 capture sites + 3 net-new classes is a large step; split along the mutex-vs-view seam.
- **Fix applied**: `## Plan of Work` separates the mutex primitive from the tx-local-view/proxy-routing/de-guard work; the step split is realized in decomposition.
- **Re-check**:
  - Location: track-3.md `## Plan of Work` — the view-build paragraph (lines 131-138), the proxy-routing paragraph (140-149), the de-guard paragraph (151-157), and the mutex paragraph (159-164) are distinct, ordered work units.
  - Current state: the four work units are separable; the mutex primitive is described independently of the view/proxy/de-guard work. The prose captures the seam along which decomposition can split steps.
  - Criteria met: the split guidance is captured in prose — the gate-pass condition for a "realized in decomposition" finding.
- **Regression check**: the ordering constraints (lines 176-183) preserve the dependency (proxy routing in place before de-guarding; engage above shared locks) across the split, so a step split cannot reorder into an unsafe sequence. Clean.
- **Verdict**: VERIFIED

#### Verify A4: copyForTx is a toStream+fromStream round trip needing the committed write lock
- **Original issue**: `copyForTx` is a serialize-then-re-parse round trip that needs the committed `SchemaShared` write lock held while serializing.
- **Fix applied**: `## Plan of Work` states `copyForTx` = `new SchemaShared(); copy.fromStream(session, committed.toStream(session))`, holding the committed `SchemaShared.lock` write lock.
- **Re-check**:
  - Location: track-3.md `## Plan of Work` lines 131-138.
  - Current state: the prose gives the exact round-trip expression and states the write lock is held while serializing, citing Track 2's `toStream` assert.
  - Criteria met: the round-trip + write-lock requirement is captured.
  - PSI: `SchemaShared` has a no-arg constructor (`ctors=[[]]`), `void fromStream(DatabaseSessionEmbedded, EntityImpl)`, and `EntityImpl toStream(DatabaseSessionEmbedded)` whose body asserts `lock.isWriteLockedByCurrentThread()`. The `committed.toStream(session)` result (an `EntityImpl`) feeds `fromStream(session, EntityImpl)` exactly — signatures match.
- **Regression check**: the assert in `toStream` means a `copyForTx` that does not hold the write lock would fail loudly in tests, so the prose's "holding the committed write lock" is enforceable, not aspirational. Clean.
- **Verdict**: VERIFIED

#### Verify A5: Proxy routing rides a tx-state branch proxies lack today; centralize
- **Original issue**: tier-3 routing needs a tx-state branch the proxies do not have today; spreading it per-method is error-prone; centralize.
- **Fix applied**: same single-seam guidance as R2, plus explicit mid-tx-minted-proxy routing through the same seam.
- **Re-check**:
  - Location: track-3.md `## Plan of Work` lines 140-149.
  - Current state: the `resolve()` seam centralizes the tier decision; mid-tx-minted proxies (via `SchemaProxy.getClass`) route through the same seam.
  - Criteria met: centralization captured (shared with R2).
  - PSI: `ProxedResource.delegate` is the captured handle every proxy method dereferences; the seam is the single point where the tier-1/2/3 decision lives. Confirmed.
- **Regression check**: consistent with R2 (same seam). The mid-tx-minted clause closes the instance-capture-bypass gap D7 names. Clean.
- **Verdict**: VERIFIED

#### Verify A6: I-C4 same-thread loud-reject needs the holder thread at engage
- **Original issue**: the same-thread loud-reject must read the holder thread, but the engage only records `session`; without the thread the reject cannot fire.
- **Fix applied**: `## Plan of Work` states the holder records at least `session` + acquiring `thread` at engage (Track 7 extends to `(session, ordinal, thread)` with CAS-clear); `## Concrete Steps` I-C4 line notes the partial holder is enough for Track 3.
- **Re-check**:
  - Location: track-3.md `## Plan of Work` lines 159-164; `## Concrete Steps` I-C4 line 211-214.
  - Current state: the holder records `session` + acquiring `thread` at engage; the same-thread loud-reject reads the holder thread; Track 7 extends the holder to `(session, ordinal, thread)` with the CAS-clear. The I-C4 acceptance line states the partial `session` + `thread` holder is sufficient for the Track-3 loud-reject.
  - Criteria met: the holder carries the thread at engage; the reject can fire.
- **Regression check**: the Track-7 holder extension (ordinal + CAS-clear) is additive — Track 3's partial holder is a subset, so no contract conflict with Track 7. The signatures block (lines 279-282) lists `releaseFor(session, ordinal)`, consistent with the Track-7 extension. Clean.
- **Verdict**: VERIFIED

#### Verify T2: "outermost commit/rollback teardown finally" is not a single existing site
- **Original issue**: there is no single existing "teardown finally" covering both explicit `commit()`/`rollback()` and `executeInTx*` wrappers; the release site claim was imprecise.
- **Fix applied**: `## Plan of Work` says "release once the outermost transaction frame closes"; `## Idempotence and Recovery` adds a release-idempotence bullet (pin the concrete site covering explicit `commit()`/`rollback()` and `executeInTx*` wrappers, gated on the outermost frame; idempotent against Track 7's compare-and-clear).
- **Re-check**:
  - Location: track-3.md `## Plan of Work` line 164; `## Idempotence and Recovery` "Release idempotence" bullet lines 240-248.
  - Current state: the prose acknowledges there is no single existing teardown finally (`commitImpl` has no top-level finally, `rollback()` is a separate method, an `executeInTx*` wrapper does not pass through the explicit path) and defers pinning the concrete site to decomposition, gated on the outermost frame and made idempotent against Track 7's compare-and-clear.
  - Criteria met: the "not a single site" reality is acknowledged; the release-site selection is correctly deferred to decomposition with the idempotence constraint captured.
  - PSI: `commitImpl` reentrancy short-circuit confirmed (no top-level commit for nested frames), consistent with "release once the outermost frame closes."
- **Regression check**: the idempotence-against-Track-7 constraint prevents a double-release of the `Semaphore(1)` permit when the normal releaser and Track 7's abnormal releaser both fire. Consistent with D7's "exactly one releaser" intent. Clean.
- **Verdict**: VERIFIED

## Findings

(none — pure-verdict pass)

## Summary

PASS. All 14 iteration-1 findings VERIFIED. Every fix landed at the cited
carriers in track-3.md and introduced no inconsistency or regression. PSI
grounded all load-bearing code claims (proxy counts, `ProxedResource.delegate`,
`SchemaShared` constructor / `fromStream` / `toStream` write-lock assert,
`commitImpl` reentrancy, `IndexAbstract.addCollection` eager apply under the
index exclusive lock, membership-site lock layering). The "realized in
decomposition" findings (R2, R3/A1, A3) pass on the prose-capture criterion: the
guidance is recorded in Plan of Work / Idempotence / Concrete Steps acceptance
annotations; the empty step roster under `## Concrete Steps` is expected, as
decomposition follows this gate.
