<!--
MANIFEST
dimension: bugs-concurrency
step: 4
iteration: 1
target_commit_range: 5b08dfdc3766673914aef9ad0513bdf400c5b952~1..5b08dfdc3766673914aef9ad0513bdf400c5b952
finding_count: 3
high_water_mark_in: 0
high_water_mark_out: 3
evidence_base: "## Evidence base"
cert_index: [C1, C2, C3]
flags: []
index:
  - id: BC1
    sev: should-fix
    anchor: "#bc1-engage-before-seed-window-can-self-deadlock-the-owning-thread-or-strand-the-permit"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java:2449-2454
    cert: C1
    basis: psi+trace
  - id: BC2
    sev: suggestion
    anchor: "#bc2-non-volatile-metadatamutexengaged-read-cross-thread-on-pool-shutdown"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java:249
    cert: C2
    basis: psi+trace
  - id: BC3
    sev: suggestion
    anchor: "#bc3-null-class-fall-through-applies-the-shared-index-eagerly-after-the-mutex-is-engaged"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java
    cert: C3
    basis: psi+trace
-->

# Bugs & Concurrency Review — Track 3, Step 4 (MetadataWriteMutex), iteration 1

## Findings

### BC1 [should-fix] Engage-before-seed window can self-deadlock the owning thread or strand the permit

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` (lines 2449-2454, the body of `ensureTxSchemaState`)
- **Issue**: `ensureTxSchemaState` engages the mutex and only afterward seeds the tx-local copy, with a record-loading step between them that can throw. The order is:

  1. `engageMetadataWriteMutex()` — acquires the permit and sets `metadataMutexEngaged = true`.
  2. `committed.copyForTx(this)` — loads the committed root record (`session.load(identity)`) and runs a full `fromStream` re-parse of every per-class record.
  3. `tx.setCustomData(TX_SCHEMA_STATE_KEY, state)` — records that the seed exists.

  Step 2 can throw on a record-not-found, an I/O failure, a malformed per-class record, or a non-persistent linked RID (Track 2 documents that `SchemaShared.fromStream`/`load` raises a `ConfigurationException` for a non-persistent linked record id). When it throws, the permit is held and `metadataMutexEngaged` is `true`, but the custom-data marker that records "the seed already exists" was never written.

  Two consequences follow:

  - **Self-deadlock (the severe one).** If the caller catches the seed exception and triggers another schema write **in the same still-open transaction on the same thread**, `ensureTxSchemaState` re-runs. `existing` is `null` (custom data was never set), so it calls `engageMetadataWriteMutex()` again. `MetadataWriteMutex.engage` has no same-session re-engage guard — its same-thread loud-reject fires only for `current.session() != session` — so for the same session it falls straight to `permit.acquireUninterruptibly()` and blocks forever on the single permit this very thread already holds. The owning thread wedges with no exception and no timeout.
  - **Stranded permit.** Even with no re-entry, the engaged permit is released only if the transaction is eventually closed (`close()` → `releaseMetadataWriteMutexForTx()` reads `metadataMutexEngaged == true`). If the failed seed leaves the transaction open and it is later abandoned rather than committed/rolled back, the permit never returns and every future schema transaction blocks on it. (A transaction that *is* closed releases correctly — that part is sound.)

- **Evidence**: See cert **C1**. The engage/seed ordering is in the diff (`engageMetadataWriteMutex()` then `copyForTx` then `setCustomData`); `copyForTx` doing throwing record I/O is at `SchemaShared.java:189-205`; the missing same-session guard is at `MetadataWriteMutex.engage` (`engage` lines 81-96, reject condition `current.session() != session`). The `engage`-Javadoc explicitly rests on the invariant "Re-engaging through the same session is impossible because the caller only reaches this method on the first write of a transaction (the tx-local schema state seeds at most once)" — that invariant is broken precisely when the seed throws between the engage and the `setCustomData`.
- **Refutation considered**: I checked whether the seed step is throw-free — it is not; it does a record load plus a full re-parse, both documented to throw. I checked whether the same-session re-engage is caught by the loud-reject — it is not; the reject is gated on a *different* session. I checked whether the permit is released on the normal close path even after a failed seed — it is, *provided the transaction is closed*, because `metadataMutexEngaged` was set to `true` before the throw; that closes the stranded-permit hole for the common abort flow but not the self-deadlock-on-retry flow. The self-deadlock requires a caller that swallows the seed exception and retries a schema write in the same transaction, which is an unusual but legitimate pattern (per-statement DDL error handling, higher-level retry), so the trigger is narrow rather than impossible.
- **Suggestion**: Make the engage and the seed all-or-nothing. Wrap the seed in a try/catch that, on failure, undoes the engage before rethrowing:

  ```java
  engageMetadataWriteMutex();
  try {
    SchemaShared committed = getSharedContext().getSchema();
    var state = new TxSchemaState(committed.copyForTx(this));
    tx.setCustomData(TX_SCHEMA_STATE_KEY, state);
    return state;
  } catch (RuntimeException | Error e) {
    releaseMetadataWriteMutexForTx(); // clears metadataMutexEngaged and the permit
    throw e;
  }
  ```

  This both prevents the self-deadlock on retry and removes the stranded-permit window without weakening the engage-above-the-locks ordering. Alternatively (or additionally) give `MetadataWriteMutex.engage` a same-session early return so a re-engage through the same session is a no-op rather than a park — but the try/catch is the more direct fix because it also restores the "seeds at most once" invariant the engage Javadoc depends on.

### BC2 [suggestion] Non-volatile `metadataMutexEngaged` read cross-thread on pool shutdown

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` (line 249, the `private boolean metadataMutexEngaged` declaration; read at `releaseMetadataWriteMutexForTx`, line 2492-2497)
- **Issue**: `metadataMutexEngaged` is a plain non-volatile session field. It is written by the owner thread inside `engageMetadataWriteMutex` and read inside `releaseMetadataWriteMutexForTx`, which runs from `FrontendTransactionImpl.close()`. PSI confirms `close()` is reached from `doCommit` and `rollbackInternal`, and that `rollbackInternal`/`close` are documented as cross-thread-callable during pool shutdown (`DatabaseSessionEmbeddedPooled.realClose` → `activateOnCurrentThread()` → `super.close()` → `internalClose(true)` → `rollback()` → `currentTx.rollbackInternal()` → `close()`). So a pool-reaper thread distinct from the engaging owner thread can read `metadataMutexEngaged`. Without a happens-before edge between the owner's write and the reaper's read, the reaper can observe a stale `false` and skip a release that was actually needed (or, less likely, observe a stale `true`).
- **Evidence**: See cert **C2**. The cross-thread close path is PSI-traced; the field is non-volatile in the diff.
- **Refutation considered**: This is largely covered by the track's declared scope boundary. The wedged-owner / cross-thread-reaping case is explicitly deferred to Track 7 and YTDB-1114 (D5/D7 risks: "a wedged owner keeps the mutex; cross-thread reaping is out of scope"; "The abnormal-termination release handshake ... are Track 7"). For a cleanly returned pooled session, the engaging tx is normally committed/rolled back on the owner thread before the session returns to the pool, so the release runs on the owner thread (no cross-thread read), and the pool's own return/checkout handoff typically supplies a happens-before edge. The residual gap is the abnormal path Track 7 owns. I therefore rank this a suggestion, not a blocker — but flag it so Track 7's holder-record/handshake work explicitly carries the marker's cross-thread visibility (the `MetadataWriteMutex.holder` field is already `volatile`, so the mutex side is safe; only this session-side marker is not).
- **Suggestion**: Either mark `metadataMutexEngaged` `volatile`, or (preferred) fold the marker into the `MetadataWriteMutex` holder record that Track 7 hardens, so the release no longer depends on a non-volatile session field being visible across the pool-shutdown handoff. Document the intended happens-before edge for the clean pooled-return path if the field stays non-volatile.

### BC3 [suggestion] Null-class fall-through applies the shared index eagerly after the mutex is engaged

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java` (`recordMembershipChangeIntoTxLocalView`, reached from the de-guarded `addCollectionToIndex` / `removeCollectionFromIndex`)
- **Issue**: `recordMembershipChangeIntoTxLocalView` calls `session.ensureTxSchemaState()` (which engages the mutex and seeds the copy) and *then*, if the index's owning class resolves to `null`, returns `false` to make the caller fall through to the legacy eager apply path (`executeInTxInternal` → `acquireExclusiveLock` → mutate the shared `Index`). By that point the mutex is already engaged. The result is a schema transaction that holds the metadata-write mutex while it also eagerly mutates the shared `Index.collectionsToIndex` — the exact shared-mutation-without-isolation the de-guard exists to prevent — for the null-class case. The comment states this is unreachable for a real class index, so the practical exposure is small, but the engage-then-eager-apply combination is a latent inconsistency in the seam this step funnels through.
- **Evidence**: See cert **C3**. The helper engages before resolving the class and falls through on `null`; the engage placement is what Step 4 introduces, so the interaction is in scope to flag even though the helper body is Step 3.
- **Refutation considered**: This is primarily Step-3 control flow; Step 4 only adds the engage inside `ensureTxSchemaState`. The null-class branch is documented unreachable for a real class index (a class index's definition always carries a class name), so this is not a confirmed live bug. I rank it a suggestion and flag it only because Step 4's engage placement is what turns the fall-through from "harmless legacy apply" into "eager shared apply while holding the mutex".
- **Suggestion**: On the null-class branch, either do not engage (resolve the class before seeding) or treat an unresolved class as a loud regression rather than a silent fall-through to the eager shared apply, so an engaged mutex never coexists with an eager shared-`Index` mutation. Confirm with Track 5 (overlay routing) whether this branch should exist at all once the overlay lands.

## Evidence base

#### C1 — Engage-before-seed window (PSI + execution trace) [basis: psi+trace]
- `ensureTxSchemaState` order (diff, `DatabaseSessionEmbedded.java` ~2444-2454): `engageMetadataWriteMutex()` → `committed.copyForTx(this)` → `tx.setCustomData(...)`. `engageMetadataWriteMutex` sets `metadataMutexEngaged = true` after `engage(this)` returns.
- `SchemaShared.copyForTx` (`SchemaShared.java:177-210`, read this session) performs `session.load(identity)` and `copy.fromStream(session, committedRoot)` between the engage and the `setCustomData` — both throwing operations (Track 2 episode documents `fromStream`/`load` raising `ConfigurationException` on a non-persistent linked record id).
- `MetadataWriteMutex.engage` (`MetadataWriteMutex.java:81-96`, read this session) rejects only when `current.thread() == Thread.currentThread() && current.session() != session`; for the **same** session it falls to `permit.acquireUninterruptibly()`. The single permit (`new Semaphore(1)`) means a same-thread re-acquire blocks forever.
- Release reachability (PSI find-usages of `close()`): callers are `rollbackInternal` (`FrontendTransactionImpl.java:400`) and `doCommit` (`:698`) only. `commitInternalImpl` calls `doCommit` only at `txStartCounter == 0`; `rollbackInternal` calls `close()` only at `txStartCounter == 0`; `doCommit`'s catch routes to `rollbackInternal`. So a *closed* tx always releases (because `metadataMutexEngaged` was set true before the throw) — the stranded-permit hole is only for a tx left open after the failed seed; the self-deadlock hole is for a same-tx retry.
- **Verdict: CONFIRMED-as-issue** — survived refutation (the seed is not throw-free; the same-session re-engage is not caught; the self-deadlock requires a swallow-and-retry caller, so should-fix not blocker).

#### C2 — Cross-thread non-volatile marker read (PSI + execution trace) [basis: psi+trace]
- `metadataMutexEngaged` field usages (PSI `ReferencesSearch`): declared `DatabaseSessionEmbedded.java:249`; read/written only in `engageMetadataWriteMutex` (`:2478`) and `releaseMetadataWriteMutexForTx` (`:2493`, `:2496`). No volatile.
- `FrontendTransactionImpl.close()` callers (PSI): `rollbackInternal`, `doCommit` — both in `FrontendTransactionImpl`. `assertOnOwningThread` Javadoc (`:126-138`) excludes `close()`/`rollbackInternal()` because pool shutdown (`DatabaseSessionEmbeddedPooled.realClose`) calls them cross-thread.
- `DatabaseSessionEmbeddedPooled.realClose` (PSI): `activateOnCurrentThread(); super.close();` → `internalClose(true)` → `rollback()` → `currentTx.rollbackInternal()` (only when `currentTx.isActive()`) → `close()` → `session.releaseMetadataWriteMutexForTx()` reads the non-volatile marker on the reaper thread.
- Scope: D5/D7 in the plan/track explicitly defer cross-thread reaping of a wedged owner to Track 7 / YTDB-1114; the `MetadataWriteMutex.holder` field is already `volatile`. Only the session-side marker is non-volatile.
- **Verdict: CONFIRMED-as-issue (suggestion)** — survived refutation but bounded by the declared Track-7 scope boundary for the abnormal path; flagged for Track 7 to absorb.

#### C3 — Null-class fall-through with engaged mutex (PSI) [basis: psi+trace]
- `recordMembershipChangeIntoTxLocalView` (PSI dump this session): calls `session.ensureTxSchemaState()` (engage + seed) before resolving `changedClass`; on `changedClass == null` returns `false`, and the de-guarded `addCollectionToIndex`/`removeCollectionFromIndex` then run `executeInTxInternal` → `acquireExclusiveLock` → eager shared-`Index` mutation, all while the mutex is engaged.
- Engage-order on the de-guarded paths is otherwise correct: `createIndex` (tx branch) and `dropIndex` and the membership helper all call `ensureTxSchemaState` strictly **above** any lock, and only ever take the *shared* (read) lock at engage time, never the write lock — so the engage-above-the-shared-locks contract (D7/I-C2) holds in production, not only under the assert.
- **Verdict: CONFIRMED-as-issue (suggestion)** — the null branch is documented unreachable for a real class index, so not a confirmed live bug; flagged because Step 4's engage placement is what makes the fall-through hold the mutex during an eager shared apply.

#### Refuted / cleared hypotheses (full rendering)

- **H: The release is not reached on all commit/rollback/executeInTx exit paths.** REFUTED. PSI find-usages of `FrontendTransactionImpl.close()` returns exactly two production callers, `doCommit` and `rollbackInternal`, each gated on `txStartCounter == 0` (the outermost frame). `doCommit`'s exception path routes through `rollbackInternal`, which also reaches `close()`. `executeInTx*` wrappers commit/rollback through these same paths. So `close()` — and thus `releaseMetadataWriteMutexForTx()` — fires exactly once per outermost frame on every commit, rollback, wrapper, and commit-failure path. Release-exactly-once is sound.
- **H: The custom-data wipe destroys the release marker.** REFUTED. The marker is the session field `metadataMutexEngaged`, not transaction custom data. `close()` runs `clear()` (which calls `clearUnfinishedChanges()` → `userData.clear()`) *before* `releaseMetadataWriteMutexForTx()`, so a custom-data marker would indeed be gone — but the session-field marker survives. The implementer's stated rationale (diff comment at `DatabaseSessionEmbedded.java:239-248`) is correct and verified.
- **H: The same-thread loud-reject mis-compares the holder.** REFUTED. `engage` reads the `volatile holder` once into `current` and rejects on `current.thread() == Thread.currentThread() && current.session() != session`. The TOCTOU between reading `current` and `acquireUninterruptibly()` is benign: a concurrent release only frees the permit, and the reject path is reached only when the current thread itself holds the permit (no other thread can release it). Holder publication is correct: `holder` is `volatile`, written after `acquire()` and read by a foreign-thread `releaseFor`; `releaseFor` clears `holder` before `permit.release()`, so a successor engage cannot observe a stale owner.
- **H: The `releaseFor` compare-and-clear can double-release the single permit.** REFUTED. `releaseFor` no-ops unless `holder.session() == session`, then sets `holder = null` before `permit.release()`. The session-side `releaseMetadataWriteMutexForTx` additionally flips `metadataMutexEngaged = false` before calling `releaseFor`, so a second call no-ops at the session gate. Track 7's later compare-and-clear targets the same session-keyed holder and observes a cleared/foreign holder, so the two releasers never both increment the counter. Idempotence holds.
- **H: Retyping `IndexManagerEmbedded.lock` from `ReadWriteLock` to `ReentrantReadWriteLock` breaks an existing lock user.** REFUTED. PSI `ReferencesSearch` on the field shows all existing uses go through `readLock()`/`writeLock()` (declared on the `ReadWriteLock` interface, still present on the subtype) plus the new `isWriteLockedByCurrentThread()` (which requires the concrete type, so the retype was necessary). No caller depended on the interface-typed declaration. `SchemaShared.lock` was already `ReentrantReadWriteLock` and already used `isWriteLockedByCurrentThread()` at `toStream` (Track 2), so the new accessor is consistent. Both accessors are correct.
- **H: The engage-order is enforced only by an assert, so production can engage from inside a held shared lock and deadlock.** REFUTED. PSI dumps of every de-guarded write path (`createIndex` tx branch, `dropIndex`, `recordMembershipChangeIntoTxLocalView`) show `ensureTxSchemaState` (the engage) is called strictly above any lock acquisition, and those paths take only the *shared* (read) lock around the seed, never the write lock. The assert is the test-time tripwire the track intends (`## Idempotence and Recovery`), and production correctness does not rely on it because the real paths are already well-ordered.
