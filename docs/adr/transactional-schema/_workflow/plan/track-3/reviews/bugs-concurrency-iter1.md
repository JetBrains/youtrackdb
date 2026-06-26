<!--
MANIFEST
dimension: bugs-concurrency
iteration: 1
target_commit_range: 8bbe3d2d18011f1ca6b1702a35e3c252ceba20b1..HEAD
evidence_base: present
cert_index: C1,C2,C3,C4,C5,C6
flags: psi-backed
index:
  - id: BC1
    sev: blocker
    anchor: "#bc1-seed-time-re-entrant-engage-self-deadlocks-the-mutex-or-trips-the-order-assert"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java:2453-2457
    cert: C1
    basis: "PSI call-chain copyForTx->fromStream->setSuperClassesInternal->addCollectionIdToIndexes->addCollectionToIndex->recordMembershipChangeIntoTxLocalView->ensureTxSchemaState; custom-data marker set only after copyForTx returns"
  - id: BC2
    sev: should-fix
    anchor: "#bc2-permit-strands-when-close-throws-before-the-release-call"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/FrontendTransactionImpl.java:948-977
    cert: C2
    basis: "release call at line 976 is not in a finally; clear()/atomicOperation.deactivate() can throw above it"
  - id: BC3
    sev: should-fix
    anchor: "#bc3-engage-order-safety-is-assert-only-unenforced-with--da"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java:2485-2495
    cert: C3
    basis: "engage-order invariant guarded only by Java assert; production runs -da so a mis-ordered engage parks instead of failing"
  - id: BC4
    sev: suggestion
    anchor: "#bc4-recordmembershipchangeintotxlocalview-keys-on-any-active-tx-not-a-schema-tx"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java:193-228
    cert: C4
    basis: "gate is getTransactionInternal().isActive(); membership-ripple seeds a tx-local copy on the first such call inside any user data transaction that happens to ripple index membership"
  - id: BC5
    sev: suggestion
    anchor: "#bc5-getchangedclasses-and-markclasschanged-are-not-thread-safe-on-a-hashset"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/TxSchemaState.java:60-89
    cert: C5
    basis: "plain HashSet; thread-confined to the owning tx today, but getChangedClasses returns the live backing set"
  - id: BC6
    sev: suggestion
    anchor: "#bc6-deferred-index-handle-size-guard-covers-size-but-other-engine-reads-still-npe"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexAbstract.java:221-236
    cert: C6
    basis: "markDeferred leaves indexId=-1; only size() guards indexId<0; other engine-touching reads on the deferred handle are unguarded"
-->

## Findings

### BC1 [blocker] Seed-time re-entrant engage self-deadlocks the mutex (or trips the order assert)

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` (line 2453-2457), interacting with `SchemaShared.copyForTx` (`SchemaShared.java:177-210`) and `IndexManagerEmbedded.recordMembershipChangeIntoTxLocalView` (`IndexManagerEmbedded.java:193-228`).
- **Issue**: `ensureTxSchemaState` engages the mutex, then calls `committed.copyForTx(this)`, and only writes the custom-data marker (`tx.setCustomData(TX_SCHEMA_STATE_KEY, state)`) *after* `copyForTx` returns. The seed's `copyForTx` → `fromStream` rebuilds the committed inheritance tree, which ripples a subclass's collection into a superclass index through `IndexManagerEmbedded.addCollectionToIndex`. That call now routes into `recordMembershipChangeIntoTxLocalView`, which (because a transaction is active) calls `session.ensureTxSchemaState()` **re-entrantly**. The custom-data marker is not set yet, so the re-entrant call takes the seeding branch again and calls `engageMetadataWriteMutex()` a second time on the same thread/session. `MetadataWriteMutex.engage` only loud-rejects a *different* session on the same thread; the *same* session falls through to `permit.acquireUninterruptibly()` on a single permit the same thread already holds → permanent park (self-deadlock). With assertions enabled, the engage-order assert `!sharedCtx.getSchema().isWriteLockHeldByCurrentThread()` fires instead (an `AssertionError`, not caught by the `catch (RuntimeException)` in `addPolymorphicCollectionIds`), because `copyForTx` holds the committed schema write lock during the re-parse — so the same scenario throws in tests and hangs in production (`-da`).
- **Evidence**: Trace (PSI-verified call edges, cert C1):
  `ensureTxSchemaState:2453 engage` → `:2456 copyForTx` (holds committed `SchemaShared.lock` write lock, `SchemaShared.java:182`) → `fromStream` (`SchemaShared.java:743 setSuperClassesInternal(...,false)`) → `SchemaClassEmbedded.setSuperClassesInternal:266 addBaseClass` → `SchemaClassImpl.addBaseClass:1497 addPolymorphicCollectionIdsWithInheritance` → `addPolymorphicCollectionIds:1622 addCollectionIdToIndexes` (note: `validateIndexes=false` is passed through only as `requireEmpty`; it does **not** gate the call) → `SchemaClassEmbedded.addCollectionIdToIndexes:661 IndexManager.addCollectionToIndex` → `IndexManagerEmbedded.addCollectionToIndex:112 recordMembershipChangeIntoTxLocalView` → `:225 session.ensureTxSchemaState()`. Back in `ensureTxSchemaState`, `existing` is `null` (marker set only at `:2457`, after `copyForTx`), so `engageMetadataWriteMutex():2453` runs again → `engage` → `acquireUninterruptibly()` parks forever (or the order assert fires first). The current test suite does not catch this only because the bootstrap/system schema in the test DB has no indexed class that already owns a committed subclass at seed time (the end-of-test "sanity" engage in `MetadataWriteMutexTest.engageOrderAssertFiresWhenIndexManagerLockHeld` succeeds, proving the seed does not ripple for that schema). Any database where a user created an indexed class with a subclass and then opens its first schema-changing transaction will hit this.
- **Refutation considered**: (a) Could the `catch (RuntimeException | Error e)` at `:2459` save it? No — in the production (`-da`) case the inner `engage` parks and never throws, so the catch never runs; in the assert-enabled case the `AssertionError` does propagate to the outer catch and is rethrown, surfacing the bug rather than hanging, but the operation still fails. (b) Could `validateIndexes=false` short-circuit the ripple during the seed? No — confirmed at `SchemaClassImpl.addPolymorphicCollectionIds:1622`, the flag is forwarded as `requireEmpty` only; `addCollectionIdToIndexes` is always invoked when the polymorphic-id set actually grows. (c) Could the marker be set before `copyForTx`? It is not (`:2457` is after `:2456`), which is exactly the gap. CONFIRMED.
- **Suggestion**: Break the re-entrancy. Options, in rough order of robustness: (1) make the seed phase non-routing — set a transient "seeding in progress" guard on the session so `recordMembershipChangeIntoTxLocalView` (and any de-guarded write reached during `fromStream`) treats the seed as the legacy/no-op path and does not call `ensureTxSchemaState`; (2) stash the `TxSchemaState` (with the not-yet-populated copy) into custom data *before* invoking `copyForTx`/`fromStream`, so the re-entrant `ensureTxSchemaState` returns the in-progress state instead of re-engaging — combined with making `engage` reentrant for the same session, or skipping engage when the marker already exists; (3) make `MetadataWriteMutex.engage` detect same-thread *same-session* re-entry and no-op (treat the permit as already held by this session) rather than blindly re-acquiring. Add a regression test that, before opening a schema transaction, commits an indexed class with a subclass at the top level, so the seed's `fromStream` exercises the inheritance-ripple path.

### BC2 [should-fix] Permit strands when close() throws before the release call

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/FrontendTransactionImpl.java` (line 948-977).
- **Issue**: `close()` calls `session.releaseMetadataWriteMutexForTx()` as its last statement (line 976), but the call is not in a `finally`. The statements above it can throw: `clear()` at line 949 (unloads records, clears caches), and the `atomicOperation.deactivate()` / `resetTsMin()` block at lines 951-967 (its inner `finally` only nulls `atomicOperation`; it does not swallow the throwable). If any of those throw, line 976 is skipped and the engaged permit is never released by the normal teardown. The single permit is then held indefinitely, and every subsequent schema-changing transaction (any session on the storage) blocks forever on `engage`.
- **Evidence**: `close()` is reached from `rollbackInternal:400` and `doCommit:698` (PSI, cert C2), each only at the outermost frame (`txStartCounter == 0`). The mutex was engaged on the first schema/index write and is meant to be released exactly here. Because the release is the final unguarded statement, an exception from `clear()` or `deactivate()` short-circuits it. The track ships the *normal* release only; the abnormal-termination heal (which would recover a stranded permit) is explicitly deferred to Track 7, so today there is no backstop.
- **Refutation considered**: Could a later listener-driven `internalClose()` re-enter `close()` and release? Even if it did, that re-entry is itself on the exception path and would have to reach line 976 without throwing; and `releaseMetadataWriteMutexForTx` is idempotent, so a second clean pass would release — but there is no guarantee a second pass happens or reaches the release. The hazard is real on the first exceptional close. CONFIRMED as a should-fix (severity bounded by the rarity of `clear()`/`deactivate()` throwing and by Track 7's eventual heal).
- **Suggestion**: Move `session.releaseMetadataWriteMutexForTx()` into a `finally` that wraps the body of `close()` (or at least wraps `clear()` and the `atomicOperation` block), so the permit is released even when teardown throws. `releaseMetadataWriteMutexForTx` is already idempotent and gated on `metadataMutexEngaged`, so a `finally` placement is safe.

### BC3 [should-fix] Engage-order safety is assert-only, unenforced with -da

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` (line 2485-2495).
- **Issue**: The invariant that the mutex is engaged strictly above the shared schema and index-manager write locks is enforced only by two Java `assert` statements. Production JVMs run with assertions disabled (`-da` is the default; the project's test `argLine` enables `-ea` only for tests). With assertions off, a mis-ordered engage (engaging while already holding `SchemaShared.lock` or the index-manager write lock) silently proceeds to `permit.acquireUninterruptibly()`. The class's own Javadoc spells out the consequence: a second transaction parks on the permit while holding a shared write lock, freezing lock-based reads and deadlocking against the commit-side lock acquisition. So the one invariant whose violation produces a cross-session deadlock is, in production, completely unchecked.
- **Evidence**: `engageMetadataWriteMutex` (cert C3) uses `assert !...isWriteLockHeldByCurrentThread()`. BC1 demonstrates one concrete path (the seed re-entry) that actually does hold the committed schema write lock at engage time — in tests that path trips this assert; in production it does not, and the deadlock-shape the assert was written to forbid is realised. Even setting BC1 aside, the de-guarded index-manager `dropIndex` path takes a shared lock around the changed-class lookup and the de-guarded `createIndex`/membership paths interleave with the index-manager lock, so the placement is genuinely lock-sensitive.
- **Refutation considered**: If the placement is always correct, the assert never matters and production is fine. That is the design's bet. But BC1 shows the placement is not always correct, and the assert is the only signal — which is absent precisely where the consequence is worst (production). CONFIRMED as a should-fix: the safety net should not vanish under `-da`.
- **Suggestion**: Promote the engage-order check from `assert` to an always-on guard inside `engageMetadataWriteMutex` (or inside `MetadataWriteMutex.engage`): throw an `IllegalStateException` when a shared metadata lock is already held by the current thread, rather than parking. Failing fast converts a silent production deadlock into a diagnosable exception. Keep the asserts too if desired, but the load-bearing check must survive `-da`.

### BC4 [suggestion] recordMembershipChangeIntoTxLocalView keys on any active tx, not a schema tx

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java` (line 193-228).
- **Issue**: The gate is `if (!session.getTransactionInternal().isActive()) return false;` — i.e. *any* active user transaction routes a membership change into the tx-local view and seeds a tx-local schema copy (engaging the mutex) on the first such call. A collection-membership ripple that happens to fire inside a plain data transaction (not a deliberate schema-changing transaction) would then silently engage the mutex and build a tx-local copy. Today the only production callers (`SchemaClassEmbedded.addCollectionIdToIndexes`, `SchemaEmbedded.createClassInternal`, `SchemaClassImpl.removeCollectionFromIndexes` — PSI, cert C4) all run on schema-mutation paths, so this is latent rather than active; but the gate's breadth makes the seam fragile against a future caller that ripples index membership during ordinary data work.
- **Evidence**: The legacy top-level `createClass` path is safe because `SchemaShared.saveInternal` throws if a transaction is active (`SchemaShared.java:1002`), so top-level creates run with no active tx and `recordMembershipChangeIntoTxLocalView` returns `false` — confirming the de-guard does not break the top-level path. The concern is the gate's reliance on "active tx ⇒ schema tx," which is an assumption, not an invariant the method checks.
- **Refutation considered**: Is there a current data-only caller? No production caller today ripples membership outside a schema-mutation path (PSI enumerated all four prod callers; the rest are tests). So this is not an active bug. SUGGESTION only.
- **Suggestion**: Either document the invariant explicitly at the gate ("reached only from schema-mutation paths; an active tx here always means a schema-changing tx"), or tighten the gate to also require evidence that a schema mutation is in flight (for example, route only when the receiver is operating on a tx-local schema view, or when a schema-write marker is set), so a stray data-transaction ripple cannot accidentally seed a tx-local schema copy.

### BC5 [suggestion] getChangedClasses and markClassChanged are not thread-safe on a HashSet

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/TxSchemaState.java` (line 60-89).
- **Issue**: `changedClasses` is a plain `HashSet`; `markClassChanged` mutates it and `getChangedClasses` returns the live backing set ("callers must not mutate it outside markClassChanged"). This is safe only as long as the `TxSchemaState` is strictly confined to the owning transaction's single thread. The committed-promotion track (Track 4) will read `getChangedClasses()` at commit; if any future code reads or iterates the returned live set while the owning thread still mutates it (or a foreign thread touches the state), this is an unsynchronized concurrent access / `ConcurrentModificationException` surface.
- **Evidence**: cert C5 — the field is a bare `HashSet`, the getter returns it directly. Today the state lives in the transaction's custom data and is reached only on the owning thread, so no race exists yet.
- **Refutation considered**: Is the state cross-thread today? No — the tx-local copy and its `TxSchemaState` are thread-confined to the owning transaction (custom data on a single-thread tx object), so there is no current data race. The mutex permit is the only cross-thread-touched artefact, and it does not touch `TxSchemaState`. SUGGESTION only — confinement is the current guarantee.
- **Suggestion**: Keep the confinement assumption documented on the class (it already states per-transaction scope), and have `getChangedClasses` return an unmodifiable copy (or snapshot) when consumed at commit, so a later cross-thread reader cannot observe a torn set or trip CME. No locking needed while confinement holds.

### BC6 [suggestion] Deferred index-handle size guard covers size() but other engine reads still NPE

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexAbstract.java` (line 221-236), `IndexMultiValues.java` / `IndexOneValue.java` `size()` (`indexId < 0` guard).
- **Issue**: `markDeferred` populates the definition and `collectionsToIndex` but leaves `indexId = -1` (no engine). The new `indexId < 0` guard was added only to `size()` in `IndexMultiValues` and `IndexOneValue`, returning 0 for the unbuilt handle. Other read methods on the deferred handle that dereference the engine via `storage.get...(indexId, ...)` are not guarded, so any public-path probe of the deferred handle other than `size()` (for example a `get`/`getRids`/stream probe) would pass `indexId = -1` into the storage engine and fail. The SQL `CREATE INDEX` path is covered (it only probes `size()`), but the contract "the handle answers name/definition/collection queries and size() sensibly" (per the `Index.markDeferred` Javadoc) does not extend to other engine-backed reads.
- **Evidence**: cert C6 — `markDeferred` sets `im` and `collectionsToIndex`, never assigns `indexId` (stays -1). Only `size()` carries the `indexId < 0` short-circuit. The de-guarded handle is returned from `IndexManagerEmbedded.createIndex` and is reachable on the public path.
- **Refutation considered**: Does the current SQL path touch anything beyond `size()` on the fresh handle? The covered test (`createIndexSqlInsideTransactionDoesNotNpeAndDefersToCommit`) exercises only the `size()` probe, and the deferred handle is intentionally absent from the shared registry, so most lookups never find it. The exposure is limited to callers that hold the returned handle directly and call engine-backed reads. SUGGESTION — the immediate SQL path is safe; the broader contract is not fully met.
- **Suggestion**: Either guard the other engine-touching reads on the deferred handle with the same `indexId < 0 ⇒ empty` short-circuit, or document on `markDeferred`/`Index.markDeferred` that only metadata, collections, and `size()` are valid on a deferred handle and any other engine-backed read is unsupported until commit builds the engine (so a future caller does not assume the handle is fully query-usable).

## Evidence base

#### C1 — Seed-time re-entrant engage (PSI-verified)
PSI `OverridingMethodsSearch` confirms `SchemaShared.newInstanceForCopy` has exactly one override (`SchemaEmbedded`), so `copyForTx` always re-parses into a `SchemaEmbedded` whose `fromStream` runs the inheritance rebuild. PSI caller search confirms `IndexManagerEmbedded.addCollectionToIndex` production callers are `SchemaClassEmbedded.addCollectionIdToIndexes:661` and `SchemaEmbedded.createClassInternal:202`; `recordMembershipChangeIntoTxLocalView` calls `DatabaseSessionEmbedded.ensureTxSchemaState` at `IndexManagerEmbedded.java:225`. Source reads confirm: `ensureTxSchemaState` sets the custom-data marker only after `copyForTx` returns (`DatabaseSessionEmbedded.java:2456-2457`); `copyForTx` holds the committed `SchemaShared.lock` write lock across `fromStream` (`SchemaShared.java:182,205`); `fromStream` calls `setSuperClassesInternal(...,false)` (`SchemaShared.java:743`); `addPolymorphicCollectionIds` invokes `addCollectionIdToIndexes` independent of `validateIndexes` (`SchemaClassImpl.java:1622`); `MetadataWriteMutex.engage` loud-rejects only a *different* same-thread session, otherwise `acquireUninterruptibly()`. The test-suite non-catch is explained by the sanity engage in `MetadataWriteMutexTest.engageOrderAssertFiresWhenIndexManagerLockHeld` succeeding, which means the test DB's committed schema does not ripple at seed time.

#### C2 — close() release placement (PSI-verified)
PSI confirms `FrontendTransactionImpl.close()` is invoked from `rollbackInternal:400` and `doCommit:698` (plus a reference in `assertOnOwningThread:130`). Source read confirms the release call sits at `close():976` as the final unguarded statement after `clear():949` and the `atomicOperation.deactivate()` block (`951-967`), with no enclosing `finally`. PSI also confirms `releaseMetadataWriteMutexForTx` callers are exactly `close():976` and the `ensureTxSchemaState` catch (`:2468`), so the normal teardown release is the sole non-exceptional release point this track ships.

#### C3 — Engage-order assert-only (CONFIRMED, survived)
`engageMetadataWriteMutex` (`DatabaseSessionEmbedded.java:2487,2490`) guards the lock-order invariant only with `assert`. Project test `argLine` enables `-ea`; production default is `-da`. BC1 supplies a concrete path that holds the committed schema write lock at engage time, demonstrating the invariant is violable.

#### C4 — Membership gate breadth (PSI-verified, SUGGESTION)
`recordMembershipChangeIntoTxLocalView` gates on `getTransactionInternal().isActive()` (`IndexManagerEmbedded.java:195`). PSI caller enumeration of `addCollectionToIndex` / `removeCollectionFromIndex` shows all current production callers are schema-mutation paths; `SchemaShared.saveInternal:1002` throwing on an active tx confirms the legacy top-level create runs with no active tx, so the de-guard does not regress it.

#### C5 — TxSchemaState set confinement (CONFIRMED, survived)
`TxSchemaState.changedClasses` is a `HashSet`; `getChangedClasses` returns the live set (`TxSchemaState.java:84-88`). State is confined to the owning transaction's custom data, reached only on the owning thread today; no current cross-thread reader.

#### C6 — Deferred handle engine guard (CONFIRMED, survived)
`IndexAbstract.markDeferred` sets `im`/`collectionsToIndex`, leaves `indexId = -1`. Only `IndexMultiValues.size()` and `IndexOneValue.size()` carry the `indexId < 0 ⇒ return 0` short-circuit; other engine-backed reads pass `indexId` straight to storage.
