<!--
MANIFEST
dimension: bugs-concurrency
track: track-1
iteration: 1
range: f1cf786fbdc40e99c0a2c4b3f0ad2ab736d56eb7..HEAD
verdict: CHANGES_REQUESTED
counts: {blocker: 0, should-fix: 3, suggestion: 2}
evidence_base: present
cert_index: [C1, C2, C3]
flags: []
index:
  - id: BC1
    sev: should-fix
    anchor: "#bc1-cachecodedepth-guard-leak-persists-across-reused-transaction-objects"
    loc: "FrontendTransactionImpl.java:134,189-218; CachedResultSetView.java:301-306; DatabaseSessionEmbedded.java:774-813"
    cert: C1
    basis: "source-read + grep (field-reset enumeration is a whole-file scan, done directly)"
  - id: BC2
    sev: should-fix
    anchor: "#bc2-cross-thread-exitcachecode-trips-assertonowningthread-on-the-tx-end-clear-path"
    loc: "FrontendTransactionImpl.java:158-163,991-1025,1387-1391; CachedResultSetView.java:301-306; DatabaseSessionEmbedded.java:3752-3756"
    cert: C2
    basis: "source-read (call chain traced through actual declarations)"
  - id: BC3
    sev: should-fix
    anchor: "#bc3-truncate-class-inside-a-sql-script-bypasses-cache-invalidation"
    loc: "DatabaseSessionEmbedded.java:974-1043; SqlScriptExecutor.java:79-151"
    cert: C3
    basis: "source-read (script-executor dispatch path read directly)"
  - id: BC4
    sev: suggestion
    anchor: "#bc4-dead-field-cachedentryk0invalidationcount"
    loc: "CachedEntry.java:882,1006-1012"
    cert: null
    basis: "source-read + grep (carried from Step 3 deferred BC1)"
  - id: BC5
    sev: suggestion
    anchor: "#bc5-overflows-metric-javadoc-does-not-match-its-only-increment-site"
    loc: "QueryCacheMetrics.java; QueryResultCache.java:2213-2214 (diff)"
    cert: null
    basis: "source-read (carried from Step 3 deferred BC2)"
-->

# Track 1 — Bugs & Concurrency review (iteration 1)

## Findings

### BC1 [should-fix] cacheCodeDepth guard leak persists across reused transaction objects

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/FrontendTransactionImpl.java` (line 134, 189-218, 1377-1400); `core/.../sql/executor/cache/CachedResultSetView.java` (line 301-306); `core/.../db/DatabaseSessionEmbedded.java` (line 774-813)

**Issue.** The tx-level re-entrancy guard `cacheCodeDepth` is entered once per cache-served `query()` (`serveThroughCache`, line 774) and released exactly once by the returned view's `releasePin()` (`CachedResultSetView.java:301-306`, which calls `tx.exitCacheCode()`). Release happens only on view `close()` or natural exhaustion. `cacheCodeDepth` is **never reset at any tx-end path** — grep confirms the only writes are `enterCacheCode`/`exitCacheCode` (lines 1379, 1390); `beginInternal` resets `queryResultCache` (line 216) but not the depth counter, and `clear()` does not touch it. Because the `FrontendTransactionImpl` object is **reused across transactions** (the begin-clear comment at line 213 says so: "carried over from a prior transaction on this reused transaction object"), any path that returns a view but never reaches `releasePin()` strands the counter at `> 0` for the lifetime of the transaction object.

Two ordinary triggers leave a view un-released:

1. **Abandoned view (embedded mode).** `query(...)` returns a `CachedResultSetView` wrapped in a `LocalResultSetLifecycleDecorator` registered in `activeQueries`, which is a `WeakValueHashMap` in embedded mode (`DatabaseSessionEmbedded.java:280`). A caller that consumes a prefix and drops the reference — the `findFirst()` / early-`break` pattern that dominates the DNQ/Hub workload this feature targets — lets the view be GC'd before tx-end. `closeActiveQueries()` then iterates a map the view has already dropped out of, so `view.close()` never fires, so `exitCacheCode()` never runs. The I2 balance test (`TxResultCacheInvariantsTest.i2_cacheCodeDepthBalancedAfterQuery`, line 591) covers only the **fully-drained** view; the abandoned-prefix case is untested.
2. **Exception mid-iteration.** An exception thrown from `view.next()` / `pullOneFromStream()` (a UDF in WHERE throws, or a storage error during the lazy pull) bypasses the exhausted/close release tail (the Step 5 episode already records this as the BC3 pin-leak; the same path also strands the guard).

**Failure scenario.** Tx A on a pooled `FrontendTransactionImpl` runs `query()` with the flag on, gets a view, consumes one row, drops it. `cacheCodeDepth` is now 1. Tx A commits; the tx object returns to the pool. Tx B reuses the same object: `beginInternal` clears `queryResultCache` but leaves `cacheCodeDepth == 1`. Every `query()` in Tx B reads `tx.getCacheCodeDepth() > 0` (`serveThroughCache`, line 793) and bypasses the cache. The feature is silently and permanently disabled for that tx object — no exception, no log, just lost caching, which is the symptom the feature exists to prevent and the hardest to diagnose in production.

**Evidence.** `serveThroughCache` enters the guard at line 774 and transfers ownership to the view at line 782/803; the view's only release site is `releasePin()` (`CachedResultSetView.java:301`), reached from `hasNext()`-exhaustion (line 148) and `close()` (line 319). Neither fires for a GC'd-before-close view. `grep -n "cacheCodeDepth"` over `FrontendTransactionImpl.java` returns only the declaration and the enter/exit/get methods — no tx-end reset. `beginInternal` (189-218) and `clear()` (1015-1025) confirm no reset.

**Refutation considered.** Could `closeActiveQueries()` still close a GC'd view? No — `WeakValueHashMap.values()` cannot return a collected value, so the iterate-and-close loop (`DatabaseSessionEmbedded.java:3753`) skips it. Could `beginInternal` reset depth indirectly? No — read end-to-end, it resets only `status`, local cache, `atomicOperation`, `storageTxThreadId`, and `queryResultCache`. Could a fresh `FrontendTransactionImpl` be allocated per tx so reuse never happens? The begin-clear comment and the lazy-cache design both assume reuse; even without pooling, trigger 2 (mid-iteration exception) strands the guard within a single tx and disables caching for the rest of that tx. Is this an I10 correctness violation? No — uncached execution is always correct, so this is feature-degradation, not data corruption; hence should-fix, not blocker.

**Suggestion.** Reset `cacheCodeDepth = 0` in `beginInternal` inside the `txStartCounter == 0` guard, beside the existing `queryResultCache.clear()` (line 216), so a leaked guard from a prior tx cannot disable caching for the next. The depth counter is a within-tx re-entrancy guard with no meaning across the tx boundary, so an unconditional reset at fresh-tx start is sound. Add an invariant test that creates a view, consumes a prefix, abandons it without close, ends the tx, then asserts the next tx caches.

### BC2 [should-fix] Cross-thread exitCacheCode trips assertOnOwningThread on the tx-end clear path

**File:** `core/.../tx/FrontendTransactionImpl.java` (line 158-163, 991-1025, 1387-1391); `core/.../sql/executor/cache/CachedResultSetView.java` (line 301-306); `core/.../db/DatabaseSessionEmbedded.java` (line 3752-3756)

**Issue.** `releasePin()` calls `tx.exitCacheCode()` (`CachedResultSetView.java:305`), and `exitCacheCode()` begins with `assertOnOwningThread()` (`FrontendTransactionImpl.java:1387-1391`). The tx-end sink `clear()` calls `session.closeActiveQueries()` **first** (line 1016), which closes every live view, which calls `releasePin()` → `exitCacheCode()` → `assertOnOwningThread()`. But `clear()` is reachable cross-thread: `close()` (line 991, which calls `clear()` at 992) and `rollbackInternal()` are the documented exceptions to the owning-thread rule (`assertOnOwningThread` Javadoc, line 155-156: "called cross-thread during pool shutdown"). `DatabaseSessionEmbeddedPooled.realClose()` activates the session on the pool-cleanup thread, not the thread that began the tx (which set `storageTxThreadId`).

**Failure scenario.** A pooled session is abandoned with a live, un-closed `CachedResultSetView` still strongly held in `activeQueries` (server mode uses a strong `HashMap`, `DatabaseSessionEmbedded.java:280`, so the view reliably survives to pool shutdown). The pool's cleanup thread calls `realClose()` → `close()` → `clear()` → `closeActiveQueries()` → `view.close()` → `releasePin()` → `exitCacheCode()` → `assertOnOwningThread()`. `storageTxThreadId` is the begin thread, the current thread is the cleanup thread, so the assert fails. Under `-ea` (every test env, per the track's Validation note) this is an `AssertionError` thrown from pool shutdown; in production (`-ea` off) the assert is a no-op so behaviour is correct, but the new code has quietly added an owning-thread assertion onto a path the existing I2/I6 contract guarantees is cross-thread-safe.

**Evidence.** Call chain traced through actual declarations: `close()` (991) → `clear()` (1015) → `closeActiveQueries()` (1016) → `rs.close()` (`DatabaseSessionEmbedded.java:3754`); the registered `rs` is the `LocalResultSetLifecycleDecorator` wrapping the `CachedResultSetView` (`queryStartedLifecycle` else-branch, line 1127); decorator close propagates to `CachedResultSetView.close()` (319) → `releasePin()` (301) → `exitCacheCode()` (305) → `assertOnOwningThread()` (`FrontendTransactionImpl.java:1388`). The pre-existing `clear()` was cross-thread-safe precisely because nothing on it asserted the owning thread; this track adds the assertion via the new view-owned guard.

**Refutation considered.** Could the view always be closed on its own thread before pool shutdown? Not for an abandoned-but-strongly-held view in server mode — that is exactly the case `closeActiveQueries()` exists to mop up, and it is the cross-thread case the contract calls out. Could `enterCacheCode()` (also asserting) fire cross-thread? No — entry is only on the synchronous `query()` path, always owner-thread; only the *release* is reachable cross-thread via the close sink. Is this purely cosmetic? In production it is benign (assert disabled), but it makes the I6 "safe under cross-thread invocation" guarantee depend on `-ea` being off, and it converts a clean pool-shutdown into an `AssertionError` under test — a real regression of the documented contract.

**Suggestion.** Make the guard release not assert the owning thread on the tx-end path. Cleanest: have `releasePin()` decrement the depth through a path that does not call `assertOnOwningThread()` (e.g. an internal `exitCacheCodeUnchecked()` used by the view, keeping the asserting `exitCacheCode()` for the synchronous session path), or drop the assert from `exitCacheCode()` since the floored-decrement already makes an off-thread call harmless. Pair with BC1 — a tx-end `cacheCodeDepth` reset would also make the cross-thread release unnecessary on the clear path.

### BC3 [should-fix] TRUNCATE CLASS inside a SQL script bypasses cache invalidation

**File:** `core/.../db/DatabaseSessionEmbedded.java` (line 974-1043); `core/.../command/SqlScriptExecutor.java` (line 79-151)

**Issue.** The bulk-DML invalidation hook `invalidateCacheForBulkDml` (line 974) drops every cache entry on a `TRUNCATE CLASS` because truncate removes stored records without flowing through `addRecordOperation`, so the delta builder cannot see the change. The hook is installed only at `executeInternal` (line 1043), the `execute()` / `command()` path. SQL scripts do not route through it: `SqlScriptExecutor.executeInternal` builds a `ScriptExecutionPlan` by calling `stm.createExecutionPlan(scriptContext)` per statement (lines 98, 128, 139) and runs the chained plan directly — `invalidateCacheForBulkDml` is never reached for any statement in a script.

**Failure scenario.** Inside one explicit transaction with the flag on: a `query("SELECT FROM C WHERE p")` populates a RECORD entry; then a `command("...; TRUNCATE CLASS C; ...")` script truncates `C` without touching `recordOperations` and without invalidating the cache; then a second identical `query()` hits the stale entry and returns rows for records that no longer exist. That is a direct I10 violation (enabling the cache changed result cardinality), the hard correctness floor the feature must never cross.

**Evidence.** `invalidateCacheForBulkDml` is called at exactly one site (grep over `DatabaseSessionEmbedded.java` → line 1043, inside `executeInternal`). `SqlScriptExecutor.executeInternal` (read in full, lines 79-151) dispatches every statement via `createExecutionPlan` and returns a `LocalResultSet` over the script plan; it never calls back into `executeInternal`/`command`, so a script-embedded `TRUNCATE CLASS` cannot reach the hook.

**Refutation considered.** Do scripts run with the cache null (Non-Goals: "scripts ... cache field stays null")? That covers scripts *populating* the cache and the auto-commit `FrontendTransactionNoTx` case, not a script *invalidating* a cache populated by a sibling `query()` in the same explicit tx — the cache lives on `FrontendTransactionImpl`, which a script inside `BEGIN ... COMMIT` shares with surrounding `query()` calls. Is TRUNCATE-in-script reachable mid-tx? Yes — scripts accept explicit `BEGIN`/`COMMIT` blocks (`SqlScriptExecutor` handles `SQLBeginStatement`/`SQLCommitStatement`, lines 93-134), and a mixed `query()` + `command(script)` sequence in one tx is ordinary. The window is narrow (flag on, explicit tx, mixed query/script touching the same class), but the consequence is stale rows, so should-fix rather than suggestion.

**Suggestion.** Either route script statement execution through a shared invalidation check (call `invalidateCacheForBulkDml` per statement before `createExecutionPlan`, or have the `TRUNCATE CLASS` execution step itself invalidate the tx cache), or, more conservatively for v1, treat the presence of any non-SELECT/MATCH statement in a script as a full `invalidateAll()` on the tx cache. Document the chosen path next to the `invalidateCacheForBulkDml` Javadoc, which currently claims TRUNCATE is the only mid-tx bulk op and implies it is fully covered.

### BC4 [suggestion] Dead field CachedEntry.k0InvalidationCount

**File:** `core/.../sql/executor/cache/CachedEntry.java` (line 882, 1006-1012)

**Issue.** `CachedEntry.k0InvalidationCount` plus `getK0InvalidationCount()` / `incrementK0InvalidationCount()` are unused: the K0 strike count is tracked in `QueryResultCache.k0Strikes` (a per-key map on the cache, `QueryResultCache.java:2105`) because an invalidation removes the entry and an entry-local counter would be lost. The Step 3 episode records this as deferred BC1. Carried here for the track-level pass.

**Refutation considered.** Confirmed unused for the strike path by reading the K0 gate in `QueryResultCache.lookup` (it uses `k0Strikes.merge`, never the entry field). No later track in this diff references it.

**Suggestion.** Remove the field and its two accessors.

### BC5 [suggestion] overflows metric Javadoc does not match its only increment site

**File:** `core/.../sql/executor/cache/QueryCacheMetrics.java`; `core/.../sql/executor/cache/QueryResultCache.java` (line 2213-2214 in the diff)

**Issue.** The `overflows` counter Javadoc (`QueryCacheMetrics`) says "entries removed because populating crossed the per-entry record cap", but the only `incrementOverflows()` call site is `evictEldestIfUnpinned` (LRU eviction at the `maxEntries` bound), not the per-entry `maxRecordsPerEntry` cap. The Step 3 episode records this as deferred BC2. The per-entry-cap overflow path described in the config knob (`QUERY_TX_RESULT_CACHE_MAX_RECORDS_PER_ENTRY`) is not wired in this track, so the metric currently counts only LRU evictions.

**Refutation considered.** grep for `incrementOverflows` confirms the single LRU-eviction call site; the per-entry record cap is documented in `GlobalConfiguration` but has no enforcement code in the Track 1 diff.

**Suggestion.** Reconcile the Javadoc to describe the LRU-eviction increment, or split into two counters when the per-entry cap is wired (whichever track lands it). At minimum align the wording so the metric is not read as a per-entry-cap signal.

## Evidence base

#### C1 — BC1 guard leak (CONFIRMED-as-issue)
Survived refutation: grep shows no tx-end reset of `cacheCodeDepth`; `beginInternal` (read in full) resets `queryResultCache` but not the depth; `WeakValueHashMap` in embedded mode lets an abandoned view be GC'd before `closeActiveQueries`; the I2 balance test covers only the fully-drained view. Reference accuracy: the field-write enumeration is a whole-file scan (declaration + enter/exit/get), not a polymorphic-dispatch question, so grep is sufficient; the call-site set for `exitCacheCode` was cross-checked against the view's release path.

#### C2 — BC2 cross-thread assert (CONFIRMED-as-issue)
Survived refutation: the `close()` → `clear()` → `closeActiveQueries()` → `view.close()` → `exitCacheCode()` → `assertOnOwningThread()` chain was traced through actual declarations (`FrontendTransactionImpl.close` 991-992, `clear` 1015-1016, `assertOnOwningThread` Javadoc 155-156 naming `close()`/`rollbackInternal()` as cross-thread, `exitCacheCode` 1387-1391, `CachedResultSetView.releasePin` 301-306). `DatabaseSessionEmbeddedPooled.realClose` activates on the cleanup thread, leaving `storageTxThreadId` set to the begin thread. Server-mode strong `HashMap` makes the live-view-at-shutdown case reliable.

#### C3 — BC3 script TRUNCATE bypass (CONFIRMED-as-issue)
Survived refutation: `invalidateCacheForBulkDml` has a single call site (`executeInternal`, line 1043); `SqlScriptExecutor.executeInternal` (read in full) dispatches statements via `createExecutionPlan` and never re-enters `executeInternal`/`command`, so a script-embedded TRUNCATE cannot reach the hook. Script `BEGIN`/`COMMIT` handling confirms scripts can run inside an explicit tx that also serves `query()`-populated cache entries.

**Tooling note.** mcp-steroid was reachable and the project open, but PSI `steroid_execute_code` was not needed for these three confirmed findings: each rests on a whole-file field-write enumeration or a concrete method-to-method call chain read directly from the declarations, not on a polymorphic find-usages question where grep silently misses dispatch sites. No finding depends on an unverified caller/override set, so no reference-accuracy caveat applies.
