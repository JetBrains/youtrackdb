<!-- MANIFEST
findings: 6   severity: {blocker: 0, should-fix: 3, suggestion: 3}
index:
  - {id: A1, sev: should-fix, loc: "plan/track-3.md:54-59 (D7 engage facet)", anchor: "### A1 ", cert: "Violation I-C2", basis: "engage-before-shared-lock is asserted but no step-level mechanism named; de-guarded addCollectionToIndex itself takes the index exclusive lock, so a write path that reaches it without prior engage violates I-C2"}
  - {id: A2, sev: should-fix, loc: "plan/track-3.md:131-135 (Validation I-A7); design.md:296-302", anchor: "### A2 ", cert: "Violation I-A7", basis: "I-A7 leak mechanism is the eager shared-Index collectionsToIndex.add (IndexAbstract.addCollection), not a nested self-commit; nesting-aware commitImpl means executeInTxInternal does not self-commit inside an open tx"}
  - {id: A3, sev: should-fix, loc: "plan/track-3.md:98-118 (Plan of Work)", anchor: "### A3 ", cert: "Challenge D8/D7 sizing", basis: "~168 proxy methods across 3 classes + 28 capture sites + mutex + TxSchemaState + de-guards in one track; step decomposition must split the proxy-routing surface from the mutex primitive or the high step risks under-scoping"}
  - {id: A4, sev: suggestion, loc: "plan/track-3.md:174-177 (Signatures); SchemaShared.java", anchor: "### A4 ", cert: "Assumption copyForTx", basis: "SchemaShared has only a no-arg ctor and fromStream is a void re-parse into this; copyForTx must new SchemaShared()+fromStream(toStream(committed)), a two-step round-trip, not a clone or factory"}
  - {id: A5, sev: suggestion, loc: "plan/track-3.md:157-164 (In scope); SchemaProxy.java:getClass", anchor: "### A5 ", cert: "Assumption tier-1 untouched", basis: "proxy reads currently call delegate.X under a session-active (not tx-active) assert; routing must add a tx-state branch to ~168 methods, and getClass wraps results in new SchemaClassProxy, so tier-3 re-resolution must reach freshly-wrapped proxies too"}
  - {id: A6, sev: suggestion, loc: "plan/track-3.md:54-59 (D7); design.md:738-742", anchor: "### A6 ", cert: "Assumption same-thread loud-reject", basis: "I-C4 loud-reject keys on the holder thread, but the holder record (session, ordinal, thread) is Track 7 work; this track must ship at least the thread field at engage or the loud-reject has nothing to test against"}
evidence_base: {section: "## Evidence base", certs: 7, matches: 7}
cert_index:
  - {id: "Challenge D8/D7 sizing", verdict: WEAK, anchor: "#### Challenge: Decision D8/D7 sizing "}
  - {id: "Challenge D8 overlay", verdict: SURVIVES, anchor: "#### Challenge: Decision D8 "}
  - {id: "Violation I-C2", verdict: CONSTRUCTIBLE, anchor: "#### Violation scenario: I-C2 "}
  - {id: "Violation I-A7", verdict: CONSTRUCTIBLE, anchor: "#### Violation scenario: I-A7 "}
  - {id: "Assumption copyForTx", verdict: HOLDS, anchor: "#### Assumption test: copyForTx "}
  - {id: "Assumption tier-1 untouched", verdict: FRAGILE, anchor: "#### Assumption test: proxy routing "}
  - {id: "Assumption same-thread loud-reject", verdict: FRAGILE, anchor: "#### Assumption test: I-C4 holder thread "}
flags: [CONTRACT_OK]
-->

# Track 3 adversarial review — iteration 1

Devil's-advocate review of Track 3 (tx-local schema view, transactional
enablement, the metadata-write mutex). Narrowed per the Track Pre-Flight
contract to track *realization*, not re-litigation of D1/D4/D5/D7/D8 (those
passed the Phase-0→1 research-log gate). Three axes: scope/sizing,
cross-track-episode reality, invariant violation. PSI-grounded against the
open `transactional-schema` IDE project; no grep fallbacks were needed for
any symbol claim below.

BLUF: no blockers. The track realizes D1/D4/D5/D7/D8 against a codebase that
matches the track file's assumptions: Track 2's per-class `recordId` field is
present and bound at load, so the `fromStream` seed (D8) is sound. The three
`should-fix` findings sharpen the I-C2/I-A7 violation mechanisms and the step
decomposition; they strengthen rationale rather than overturn decisions.

## Findings

### A1 [should-fix]
**Certificate**: Violation scenario: I-C2 (mutex engages above the shared locks, never inside them)
**Target**: Invariant I-C2 / Decision D7 (engage facet)
**Challenge**: The track states the mutex must engage "strictly above any
shared metadata lock and before the tx-local seed" and lists the engage hook
at "the write-routing decision point above the shared locks." But one of the
de-guarded entry points, `IndexManagerEmbedded.addCollectionToIndex`, itself
takes the index's own exclusive lock (`acquireExclusiveLock(transaction)` ->
`IndexAbstract.addCollection` -> `acquireExclusiveLock()`). If any write path
reaches that index-lock site without having engaged the mutex first at the
`SchemaProxy` / index-routing entry, the engage would happen inside (or
after) a shared-lock acquisition, which is exactly the I-C2 hazard. With 28
`SchemaClassProxy` construction sites and direct internal callers of
`SchemaEmbedded` (`doRealCreateClass`), the track needs a named, testable
mechanism that the engage is unconditionally first on every write path, not
just on the canonical `SchemaProxy.createClass` path.
**Evidence**: `IndexAbstract.addCollection` does `collectionsToIndex.add(...)`
under `acquireExclusiveLock()`; `addCollectionToIndex` wraps it in
`acquireExclusiveLock(transaction)`. The membership ripple reaches it from
`SchemaClassEmbedded#addCollectionIdToIndexes` (called by
`addPolymorphicCollectionId(s)`) and `SchemaEmbedded#createClassInternal`,
none of which is the proxy-layer engage point.
**Proposed fix**: In step decomposition, make the engage a precondition
asserted at every shared-lock acquisition site this track touches (an assert
"mutex already held by this session" at the top of the de-guarded membership
path and the index-manager write path), and add a test that drives the
membership ripple through a captured pre-tx proxy and asserts the mutex was
engaged before the index exclusive lock. State the assertion in the track's
`## Idempotence and Recovery` or a step's acceptance line.

### A2 [should-fix]
**Certificate**: Violation scenario: I-A7 (every mutation entry point rides the user transaction; the self-commit membership leak is the silent failure)
**Target**: Invariant I-A7 / the Validation & Acceptance I-A7 bullet
**Challenge**: The track (and design §"The tx-local schema view") frames the
self-commit leak as: `addCollectionToIndex` "wraps its work in
`session.executeInTxInternal(...)`, a nested transaction that commits the
moment the method returns ... escapes the user transaction ... survives a
rollback." That mechanism description is imprecise. `executeInTxInternal`
calls `begin()` then `finishTx(ok)`, and `commitImpl` short-circuits when
`amountOfNestedTxs() > 1` ("This just do count down no real commit here") —
the real `doCommit` fires only at `txStartCounter` 1->0. So once the
throw-guards are removed and the membership site runs *inside* an open user
tx, the nested `executeInTxInternal` does NOT self-commit; the record write
rides the user tx correctly. The actual leak is narrower and shared-state:
`IndexAbstract.addCollection` eagerly does `collectionsToIndex.add(name)` on
the **shared** `Index` object, visible to concurrent sessions immediately and
not reverted in-memory on a user-tx rollback (only the `save(transaction)`
record write rides the tx). The track's Validation bullet correctly names
"the shared `Index`'s `collectionsToIndex` untouched [on rollback]" as the
assertion, so the test target is right; the rationale prose mis-locates the
mechanism, which risks the implementer fixing the wrong thing (the nested-tx
boundary instead of the eager shared-set mutation).
**Evidence**: `commitImpl`: `if (currentTx.amountOfNestedTxs() > 1) { return
currentTx.commitInternal(); }`; `FrontendTransactionImpl.commitInternalImpl`
decrements `txStartCounter` and only calls `doCommit` at 0.
`IndexAbstract.addCollection`: `collectionsToIndex.add(collectionName)` under
the index exclusive lock, then `save(transaction)`.
**Proposed fix**: Correct the D1/I-A7 rationale (in this track's Decision Log
D1 caveat and/or the design's §"tx-local schema view" self-commit paragraph)
to name the eager shared-`collectionsToIndex.add` as the leak, not a nested
commit boundary. Keep the Validation bullet as-is (it already tests the right
set). This is a rationale-precision fix; the de-guarding work itself is
unaffected.

### A3 [should-fix]
**Certificate**: Challenge: Decision D8/D7 sizing — too much for one track / natural split point
**Target**: Decision D8 + D7 (track scope/sizing)
**Challenge**: The ~16-file footprint understates the method surface. The
routing change (D8) touches three proxy classes carrying ~168 methods total
(`SchemaProxy` 40, `SchemaClassProxy` 79, `SchemaPropertyProxy` 49), each of
which must gain a tx-state branch, plus 28 `SchemaClassProxy` capture sites
to keep consistent. Bundled with that are four independently-shaped concerns:
(a) `TxSchemaState` + `copyForTx`, (b) three-tier proxy routing, (c)
entry-point de-guarding, (d) the `MetadataWriteMutex` primitive + engage +
release. The mutex primitive (D5/D7, pure concurrency, net-new class) shares
no files with the proxy routing (D8, isolation) except the single engage
call. A natural split is: a step for the mutex primitive + engage + release,
and a separate step for the tx-local view + proxy routing + de-guarding —
which is also the order the Plan of Work already implies ("Build the tx-local
view first ... Then de-guard ... Introduce the MetadataWriteMutex").
**Evidence**: PSI method counts above; `MetadataWriteMutex`, `TxSchemaState`,
`IndexOverlay` are all net-new (0 existing). `ProxedResource<T>` holds the
captured `delegate`, shared by all three proxies, so the routing change is
one base-pattern applied ~168 times.
**Proposed fix**: This is decomposer guidance, not a decision change. The
single planned `high` step should decompose into at least two atomic steps
along the mutex-vs-view seam (each independently testable and committable),
with the proxy-routing step further checked for whether the per-proxy-class
surface fits one commit. Record the seam in `## Concrete Steps` during
decomposition.

### A4 [suggestion]
**Certificate**: Assumption test: copyForTx is a re-parse, not a clone or factory
**Target**: Assumption (the `SchemaShared.copyForTx() : SchemaShared` signature)
**Challenge**: The track signs `SchemaShared.copyForTx() : SchemaShared`. PSI
shows `SchemaShared` has only a no-arg constructor and `fromStream` is a
`void` instance method that re-parses INTO `this` (not a static factory
returning a fresh instance). So `copyForTx` must `new SchemaShared()`, then
`copy.fromStream(session, committed.toStream(session))` — a full serialize +
re-parse round-trip on every first schema write, not a field clone. The track
already commits to "a `fromStream` re-parse, not a field clone," so the
intent is right; the signature just hides that the round-trip goes through
`toStream` on the committed instance first.
**Evidence**: `SchemaShared` ctor: `()` only. `fromStream(DatabaseSessionEmbedded, EntityImpl)`
returns `void`; `toStream(DatabaseSessionEmbedded)` returns `EntityImpl`.
`fromStream` binds `cls.setRecordId(boundRid)` from the `"classes"` LinkSet,
so RIDs survive the round-trip (Track 2 contract, test-verified).
**Proposed fix**: Note in the track's Plan of Work or `copyForTx` step that
the copy is `new SchemaShared(); copy.fromStream(session, committed.toStream(session))`,
and that `toStream` requires the caller's write lock per Track 2's
`isWriteLockedByCurrentThread()` assertion — so `copyForTx` must hold the
committed `SchemaShared.lock` write lock while serializing the base.

### A5 [suggestion]
**Certificate**: Assumption test: proxy routing rides a tx-active branch the proxies do not have today
**Target**: Assumption (three-tier proxy resolution; tier-1 snapshot untouched)
**Challenge**: Proxy reads today call `delegate.X(...)` after
`assert session.assertIfNotActive()` — which checks the **session** is the
active one, NOT whether a tx is active. So there is no existing tx-state
branch to extend; the track adds a brand-new tx-active (more precisely,
schema-tx-write-view-active) decision to every routed method.
`SchemaProxy.getClass` also wraps every result in `new SchemaClassProxy(cls,
session)`, so tier-3 name re-resolution must apply to freshly-minted proxies
created mid-tx, not only to pre-tx captured ones. The "tier-1 snapshot
reads a separate untouched family" claim holds (the snapshot path is
`makeSnapshot`/`ImmutableSchema`, distinct from the live proxy reads), but the
tier-2/tier-3 boundary is a per-call branch the track must add uniformly or a
missed method silently reads shared state mid-tx.
**Evidence**: `SchemaClassProxy.getName`: `assert this.session.assertIfNotActive(); return delegate.getName();`.
`assertIfNotActive` checks `activeSession.get()`, not tx status.
`SchemaProxy.getClass`: `var cls = delegate.getClass(iClassName); return cls == null ? null : new SchemaClassProxy(cls, session);`.
**Proposed fix**: In decomposition, make the routing decision a single shared
helper on `ProxedResource` or `SchemaProxy` (resolve-target-for-current-view)
that every routed method calls, rather than open-coding the tier branch 168
times; add a test that a proxy created mid-tx (via `getClass`) routes to the
tx-local class, not only a pre-tx captured one.

### A6 [suggestion]
**Certificate**: Assumption test: I-C4 same-thread loud-reject needs the holder thread, which Track 7 owns
**Target**: Invariant I-C4 / Decision D7 (this track ships engage + loud-reject; Track 7 ships the holder record)
**Challenge**: The track ships "the engage, the same-thread loud-reject
(I-C4), and the normal release," while D7's full holder record
`(session, ordinal, thread)` and the CAS-clear release machinery are Track 7.
The same-thread loud-reject keys on "the current thread already holds the
mutex through a different session" — which requires reading a holder's
**thread** at engage time. If this track ships only a bare `Semaphore(1)` +
session-keyed release and defers the whole holder record to Track 7, the
loud-reject has no thread to compare against and I-C4's test cannot pass in
this track. The track must ship at least the thread (and a session key for
the normal release) at engage, even though the ordinal and the CAS-clear
abnormal-release handshake are Track 7.
**Evidence**: design.md §"The schema-write mutex": "The engage path also
throws when the current thread already holds the mutex through a different
session." design.md §"Mutex lifecycle": the holder record
`(owning session, acquire ordinal, acquiring thread)` and the CAS-clear are
in the Track-7-owned section. Track-3 Validation bullet 5 requires the
same-thread loud-reject test to pass *in this track*.
**Proposed fix**: State explicitly in the track's signatures / Plan of Work
that this track ships a partial holder record sufficient for I-C4 (at least
`session` + `thread` written at engage and read by the loud-reject), and that
Track 7 extends it with `ordinal` and the CAS-clear abnormal-release. This
keeps the Track-3-vs-Track-7 boundary testable on both sides.

## Evidence base

#### Challenge: Decision D8/D7 sizing — too much for one track?
- **Chosen approach**: One track delivers the tx-local view (D8), proxy
  three-tier routing, entry-point de-guarding, and the mutex primitive +
  engage + release (D5/D7), at a ~16-file footprint.
- **Best rejected alternative**: Split along the mutex-vs-view seam — the
  `MetadataWriteMutex` primitive + engage + release as one unit, the tx-local
  `SchemaShared` copy + proxy routing + de-guarding as another.
- **Counterargument trace**:
  1. The routing change is a base-pattern (`ProxedResource<T>.delegate`)
     applied across ~168 proxy methods (`SchemaProxy` 40, `SchemaClassProxy`
     79, `SchemaPropertyProxy` 49, confirmed by PSI), plus consistency across
     28 `SchemaClassProxy` capture sites.
  2. The mutex is a net-new `Semaphore(1)` class sharing no files with the
     proxy routing except the single engage call site.
  3. A single `high` step covering both risks under-decomposition: a reviewer
     cannot bisect a routing regression from a mutex-ordering regression in
     one commit.
- **Codebase evidence**: PSI method counts; `MetadataWriteMutex` /
  `TxSchemaState` / `IndexOverlay` all net-new (0 existing).
- **Survival test**: WEAK — the decisions are sound (the seam is real and the
  Plan of Work already orders the work mutex-last); the *single-step*
  decomposition is what needs the explicit split. Severity should-fix as
  decomposer guidance.

#### Challenge: Decision D8 — full copy vs deferred overlay
- **Chosen approach**: A full working `SchemaShared` copy via `fromStream`
  re-parse (D8), reusing the existing mutation machinery to recompute derived
  state.
- **Best rejected alternative**: The deferred overlay (approach B) — an
  immutable committed base plus a changed-class map.
- **Counterargument trace**:
  1. The overlay avoids the per-first-write `toStream`+`fromStream`
     round-trip cost the full copy pays.
  2. But every read would then merge base + changed-class and recompute the
     `polymorphicCollectionIds` union across the merged hierarchy on each
     access — new logic in the correctness-critical read path.
  3. The full copy reuses `createClassInternal` / `addPolymorphicCollectionId`
     unchanged, which already recompute that derived state.
- **Codebase evidence**: `addCollectionIdToIndexes` is reached from
  `addPolymorphicCollectionId(s)`, the existing derived-state ripple the copy
  reuses for free; `fromStream` rebinds RIDs and reconstructs sibling links.
- **Survival test**: SURVIVES — the low schema-change rate (the plan's
  load-bearing premise) makes the round-trip cost negligible, and the overlay
  is deferred, not rejected. No finding beyond the A4 round-trip-cost note.

#### Violation scenario: I-C2 (the mutex engages above the shared locks, never inside them)
- **Invariant claim**: The mutex engages strictly above any shared metadata
  lock; a hook that engaged from inside a shared-lock acquisition would park a
  second tx on the mutex while holding a shared write lock, freezing
  lock-based reads and deadlocking against the commit-side schema lock.
- **Violation construction**:
  1. Start state: tx A open, no mutex engaged yet; a de-guarded write path
     reaches the membership ripple through a captured pre-tx proxy whose
     mutating call was NOT routed through the proxy-layer engage hook.
  2. Action sequence: the ripple calls
     `SchemaClassEmbedded#addCollectionIdToIndexes` ->
     `IndexManagerEmbedded.addCollectionToIndex`, which does
     `acquireExclusiveLock(transaction)` ->
     `IndexAbstract.addCollection` -> `acquireExclusiveLock()`.
  3. Intermediate state: the index exclusive lock is held; if engage is wired
     here (or "on first write" is interpreted at the index-routing layer
     rather than strictly before the lock) the mutex acquire now happens
     under a held shared lock.
  4. Violation point: `IndexManagerEmbedded.addCollectionToIndex` (the
     `acquireExclusiveLock` line) — engage-after-lock.
  5. Observable consequence: a second schema tx parks on the mutex while the
     first holds the index exclusive lock, stalling lock-based index reads and
     risking the commit-side deadlock I-C2 forbids.
- **Feasibility**: CONSTRUCTIBLE — the index exclusive lock site is real and
  reachable from the de-guarded path; whether the violation fires depends
  entirely on the (not-yet-written) engage wiring, which is exactly why a
  named precondition + test is needed. -> A1.

#### Violation scenario: I-A7 (every mutation entry point rides the user tx; the self-commit membership leak is the silent failure)
- **Invariant claim**: A polymorphic membership ripple is not observed by a
  concurrent session before commit, and a rollback leaves the shared `Index`'s
  `collectionsToIndex` untouched.
- **Violation construction**:
  1. Start state: tx A open after de-guarding; tx A creates a class whose
     superclass has an indexed subclass, triggering the membership ripple.
  2. Action sequence: `addCollectionToIndex` ->
     (if NOT reworked) `IndexAbstract.addCollection` ->
     `collectionsToIndex.add(collectionName)` on the **shared** `Index`.
  3. Intermediate state: the shared `Index.collectionsToIndex` now contains
     the new collection name; a concurrent session B reading the index sees it.
  4. Violation point: `IndexAbstract.addCollection`
     (`collectionsToIndex.add(...)`) — the eager shared-set mutation.
  5. Observable consequence: cross-session visibility before commit, and on
     tx A rollback the in-memory set is not reverted (only the
     `save(transaction)` record write rolls back), so the leak persists.
- **Feasibility**: CONSTRUCTIBLE — and note the design's "nested self-commit
  escapes the user tx" framing is the *imprecise* part: with
  `commitImpl`'s `amountOfNestedTxs() > 1` count-down, the nested
  `executeInTxInternal` does not self-commit inside an open tx; the leak is the
  eager shared-set mutation, which the track's Validation bullet already
  targets. -> A2.

#### Assumption test: copyForTx is a re-parse round-trip, not a clone or factory
- **Claim**: `SchemaShared.copyForTx() : SchemaShared` builds a tx-local copy
  via `fromStream` re-parse.
- **Stress scenario**: The implementer reads the signature as a factory or a
  field clone and either adds a static factory `SchemaShared` cannot support
  or clones fields (which D8 explicitly forbids — `owner` is `final`).
- **Code evidence**: `SchemaShared` ctor is `()` only; `fromStream` is `void`
  re-parse into `this`; `toStream` returns the `EntityImpl`. So the copy is
  `new SchemaShared(); copy.fromStream(session, committed.toStream(session))`.
  `fromStream` binds `setRecordId(boundRid)`, so RIDs survive (Track 2).
- **Verdict**: HOLDS — the track already commits to a re-parse; the signature
  just hides the `toStream`-first round-trip and the write-lock requirement on
  the committed base. -> A4 (suggestion).

#### Assumption test: proxy routing rides a tx-active branch the proxies lack today
- **Claim**: Proxy reads/writes route to the tx-local structure during a
  schema tx via three-tier resolution; tier-1 snapshot is a separate untouched
  family.
- **Stress scenario**: The track assumes a tx-state branch exists to extend.
  It does not: proxies call `delegate.X` after a **session**-active assert. A
  method missed during the ~168-method conversion silently reads shared state
  mid-tx.
- **Code evidence**: `SchemaClassProxy.getName`:
  `assert this.session.assertIfNotActive(); return delegate.getName();`;
  `assertIfNotActive` checks `activeSession`, not tx status. `SchemaProxy.getClass`
  mints `new SchemaClassProxy(cls, session)` per call, so mid-tx-created proxies
  also need tier-3.
- **Verdict**: FRAGILE — the tier-1 claim holds (snapshot is `ImmutableSchema`,
  separate), but the tier-2/tier-3 branch is net-new on a large surface and
  must be centralized to stay consistent. -> A5 (suggestion).

#### Assumption test: I-C4 same-thread loud-reject needs the holder thread Track 7 owns
- **Claim**: This track ships the engage and the same-thread loud-reject
  (I-C4); the holder record and CAS-clear are Track 7.
- **Stress scenario**: If this track ships only a bare `Semaphore(1)` plus
  session-keyed normal release and defers the entire `(session, ordinal,
  thread)` holder record to Track 7, the loud-reject has no thread to compare
  and I-C4's Track-3 test (Validation bullet 5) cannot pass.
- **Code evidence**: `MetadataWriteMutex` is net-new; the holder record and
  CAS-clear live in design.md §"Mutex lifecycle" (Track 7). I-C4 is assigned
  to Track 3 in the plan's Invariants block.
- **Verdict**: FRAGILE — passable only if the track ships a partial holder
  record (at least `session` + `thread` at engage) now and Track 7 extends it.
  The split must be stated so both tracks stay testable. -> A6 (suggestion).
