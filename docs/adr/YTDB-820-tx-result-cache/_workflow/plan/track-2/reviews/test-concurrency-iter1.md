<!-- MANIFEST
dimension: test-concurrency
iteration: 1
high_water_mark: 0
findings: 0
verdict: pass
evidence_base: present
cert_index: present
flags: none
index: []
-->

## Findings

No concurrency-testing findings. Track 2 adds no genuinely multi-threaded
production behavior, so no concurrent test is owed. The basis for that
conclusion is in the Evidence base below.

## Evidence base

The track concern handed down from Track 1 was specific: *if the aggregate
splice calls `QueryResultCache.lookup` outside the `cacheCodeDepth` bracket,
the lookup-level `inFlightLookup` guard becomes reachable and needs end-to-end
coverage; cross-thread guard release must use `exitCacheCodeUnchecked`.* Both
halves were checked against the as-built code and both refuted as live concerns
for this track.

#### C1 — Does the aggregate path reach `cache.lookup` outside the `cacheCodeDepth` bracket?

CONTRACT: the lookup-level `inFlightLookup` guard is defense-in-depth and needs
coverage only if a Track-2 path calls `lookup` outside the tx-level
`cacheCodeDepth` bracket.

TEST TRACE / production trace:
`DatabaseSessionEmbedded.serveThroughCache` (line 741) enters the bracket with
`tx.enterCacheCode()` at line 782, performs the single `cache.lookup(key, ...)`
at line 785 (inside the bracket), then on an aggregate miss calls
`populateAndBuildAggregateView` at line 808 — still inside the bracket, with the
`finally` at line 832 releasing the guard when no view took ownership. The
aggregate populate method (`populateAndBuildAggregateView`, line 941) builds a
per-execution plan copy, splices the tap, eager-drives the plan, puts the entry,
and calls `buildView`. It never calls `cache.lookup`. Grep over the whole track
diff confirms `lookup` appears only at the one in-bracket call site; the
aggregate path adds none.

VERDICT: the `inFlightLookup` guard is NOT newly reachable in Track 2. The
precondition for owing it coverage ("lookup called outside the bracket") does
not hold. CONFIRMED that no new test is owed.

#### C2 — Does Track 2 introduce a new cross-thread guard release?

CONTRACT: any view-owned cross-thread guard release must use
`exitCacheCodeUnchecked`, and such a path would need coverage.

TEST RACE CHECK / production trace: the `viewOwnsGuard` transfer in the
aggregate branch (line 809) is byte-for-byte the RECORD pattern — a built
`CachedResultSetView` owns the guard and releases it on close/exhaustion; an
uncached fallback leaves `viewOwnsGuard` false so the synchronous `finally`
releases it. `exitCacheCodeUnchecked` appears zero times in the track diff. No
new thread, executor, `CompletableFuture`, or `submit/execute` call is
introduced (grep confirms: the only `Runnable` is the synchronous
`onOverflow` overflow callback, invoked inline from `AggregateState.observe` on
the owning thread; the only `Executor` token is absent). The sole cross-thread
path in the subsystem — tx-end `clear()` via `exitCacheCodeUnchecked`, already
present and covered in Track 1 — is unchanged by this track.

VERDICT: no new cross-thread release path. CONFIRMED that no new test is owed.

#### C3 — Is the new production state genuinely single-threaded?

PREMISE P1: `AggregateState` (contributingValues/Rids, distinctBuckets,
sumAccumulator, currentScalar) is shared mutable state protected by NO lock —
its Javadoc states "Single-transaction state observed only by the owning thread;
no field is synchronised."
PREMISE P2: `AggregateCacheTapStep` and its inner `ObservingStream` claim no
thread-safety and carry the same single-thread Javadoc; they run on the
plan-driving thread.
PREMISE P3: expected access pattern — all aggregate observe/applyMutation/copy/
toResult calls run under `FrontendTransactionImpl.assertOnOwningThread()`
(plan-slim "Single-thread tx model"; Track-1 invariant I2). The copy-per-view
discipline (`buildForAggregate` copies the seeded state before replay) means the
entry's seeded state is never mutated after populate, so even the
populate-thread vs view-thread distinction reduces to one logical owner.

This matches the plan constraint exactly: "All cache mutation paths run under
`assertOnOwningThread()`; only tx-end `clear()` is cross-thread ... No locking is
added." The aggregate replay logic is single-threaded by construction;
suggesting a contention or interleaving test for it would contradict the design
contract and the reviewer guideline against inventing concurrent tests for
single-threaded code.

VERDICT: single-threaded replay logic. No concurrency test is appropriate.

Tooling note: traces above rest on call-site enumeration of `cache.lookup`,
`exitCacheCodeUnchecked`, and thread-spawning APIs within the track diff plus the
as-built `DatabaseSessionEmbedded.serveThroughCache`. The reachability claims
(lookup never called from the aggregate path; no new cross-thread release) are
diff-scoped and grep-confirmed against the changed files; they do not depend on
enumerating polymorphic implementers, so the grep basis is sufficient here.
