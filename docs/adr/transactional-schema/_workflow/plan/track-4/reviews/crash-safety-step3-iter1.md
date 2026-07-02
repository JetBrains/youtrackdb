<!--
MANIFEST
dimension: crash-safety
step: 4.3
commit: af97354a82
iteration: 1
verdict: PASS
blockers: 0
findings_total: 3
evidence_base: 4 certs (C1-C4), PSI-backed
cert_index: C1,C2,C3,C4
flags: none
index:
  - id: CS1
    sev: suggestion
    anchor: "#cs1-suggestion-unguarded-and-unguardable-commit-window-precondition"
    loc: "AbstractStorage.java:3380-3382,3970,4786"
    cert: C1
    basis: "PSI: ScalableRWLock has no isWriteLockedByCurrentThread; enterCommitWindow does not verify the write lock; only JUC ReentrantReadWriteLock defines the query."
  - id: CS2
    sev: suggestion
    anchor: "#cs2-suggestion-leaked-window-disables-read-lock-with-no-lock-held-step-4-wiring-risk"
    loc: "AbstractStorage.java:3380-3395,3970-3973,4786-4789"
    cert: C2
    basis: "PSI: exitCommitWindow assert catches over-exit only; no production caller wires the window yet (substrate-only); leak direction unguarded."
  - id: CS3
    sev: suggestion
    anchor: "#cs3-suggestion-substrate-covers-exactly-the-two-commit-thread-read-methods-informational-confirmation"
    loc: "AbstractStorage.java:3962-4006,4770-4804; DatabaseSessionEmbedded.java:1129,1176,1785"
    cert: C3
    basis: "PSI find-usages: session.load -> executeReadRecord reaches getPhysicalCollectionNameById (security) + readRecord (cache miss); recordExists is off the load path and correctly keeps the read lock."
-->

# Crash Safety & Durability Review — Track 4, Step 3 (lock-free commit-window read substrate)

Commit `af97354a82`. Reviewed for crash safety and durability only.

## Findings

### CS1 [suggestion] Unguarded (and unguardable) commit-window precondition

**File:** `core/.../storage/impl/local/AbstractStorage.java` (lines 3380-3382 `enterCommitWindow`; 3970, 4786 the two lock-free branches)

**Concern (not a crash scenario — a missing safety net):** The whole substrate rests on one invariant the Javadoc declares load-bearing: *window-active on a thread ⟹ that thread holds `stateLock.writeLock()`*. That held write lock is the only thing excluding concurrent registrars and supplying the visibility edge for the lock-free reads of the plain `collections` `ArrayList`. But `enterCommitWindow()` (line 3380-3382) only increments the depth counter; it never verifies the write lock is held. There is no production guard for the precondition the comments treat as mandatory.

**Why no assert fixes it cheaply:** `stateLock` is a `ScalableRWLock`, which is a `StampedLock`-plus-per-thread-reader-state lock, not a JUC `ReentrantReadWriteLock`. PSI confirms it exposes no current-thread write-holder query (`isWriteLockedByCurrentThread` is defined project-wide only on `java.util.concurrent.locks.ReentrantReadWriteLock`; `ScalableRWLock` has only `sharedLock`/`exclusiveLock` primitives and no owner field). So the natural `assert stateLock.isWriteLockedByCurrentThread()` at the top of the lock-free branch is simply not available on this lock type. The Track 2 `toStream` `isWriteLockedByCurrentThread()` assert noted in the track episode is on a different lock (`SchemaShared.lock`, a `ReentrantReadWriteLock`), not `stateLock`.

**Recovery / durability impact:** None on this substrate in isolation — no production code opens the window yet, so the lock-free branch is never taken in production at this commit (CS3 confirms this via PSI). The risk is latent: once Step 4 wires `enterCommitWindow()`, a wiring slip that opens the window without the write lock is a silent data race on `collections`/`collectionMap` with no detector, and the corrupt read it produces (wrong collection name, wrong record, or NPE on a half-applied `collections`) feeds the schema serialization/promotion the reconciliation core depends on.

**Refutation considered:** Could a guard be added against a *different* held lock? No clean one exists — the substrate's contract is specifically about `stateLock`, and the other locks in the four-lock order do not imply `stateLock.writeLock()` is held. Could it be added as a debug-only thread check (record the window-opening thread and assert reads run on it)? That guards thread-identity but not lock-holding, so it would not catch the actual failure mode. The precondition is genuinely hard to assert on this lock.

**Suggestion (optional, non-blocking):** Leave the lock unchanged, but consider hardening `enterCommitWindow()` so it can only be entered through the commit path — e.g., have it accept (or assert against) a token the commit obtains only after `stateLock.writeLock().lock()`, or add an `assert`-level invariant on `ScalableRWLock` (a cheap `volatile Thread exclusiveOwner` set under `exclusiveLock`/cleared under `exclusiveUnlock`, queried only inside `assert`) so the substrate *can* assert write-lock ownership. If neither is worth the cost, treat the wiring discipline as a Step 4 review obligation and keep the precondition prominent in the Step 4 contract. Properly Step 4's to enforce; flagged here because the substrate ships the unguarded primitive.

### CS2 [suggestion] Leaked window disables the read lock with no lock held (Step 4 wiring risk)

**File:** `core/.../storage/impl/local/AbstractStorage.java` (lines 3380-3395 enter/exit; 3970-3973, 4786-4789 the branches)

**Crash/corruption scenario:** If a future caller (Step 4) executes `enterCommitWindow()` and then fails to reach a balanced `exitCommitWindow()` — an exception escaping the body before the `finally`, an early `return` outside `try/finally`, or a path that opens the window twice but exits once — the depth counter stays positive on a *pooled* thread after the commit releases `stateLock.writeLock()`. The next, entirely innocent, `session.load` on that recycled thread then takes the lock-free branch in `getPhysicalCollectionNameById` / `readRecordInternal` with **no lock held at all**, racing concurrent registrars (`rebuild`, `loadExternalIndexEngine`, `recreateIndexes`, and the next schema commit) on the plain `collections`/`collectionMap`. That is the phantom-read / torn-structural-view the design forbids.

**Evidence:** `exitCommitWindow()` carries an underflow assert (`depth[0] > 0`), which catches *over*-exit (more exits than enters) but not the leak direction (*under*-exit / no exit). The leak direction is the dangerous one and is unguarded. The depth-as-ThreadLocal design is correct for re-entrancy, but it makes a leak survive across unrelated transactions on the same pooled thread rather than failing fast.

**Recovery impact:** A lock-free read of a concurrently-mutated `collections` can return a stale or wrong collection (or NPE), which during a later schema commit's serialization (Step 4) would write wrong structural identity. This does not corrupt the WAL directly, but it can drive the reconciliation core to serialize an inconsistent schema. The blast radius is Step 4's; the substrate is where the leak-tolerant primitive lives.

**Refutation considered:** Is this real at *this* commit? No — PSI confirms zero production wiring (CS3), so no leak can occur in production yet. Is it the test's problem? The tests balance enter/exit correctly in `finally`. The finding is a forward-looking caveat about the primitive's leak-tolerance, correctly belonging to Step 4's wiring review; recorded here so Step 4 carries it.

**Suggestion (optional):** The Javadoc already mandates `finally`-balancing; consider making the contract enforceable — e.g., expose the window only as a `try`-with-resources `AutoCloseable` handle or a `runInCommitWindow(Runnable)` wrapper so a caller cannot open without a guaranteed close, instead of two free-standing `enterCommitWindow()`/`exitCommitWindow()` calls. Non-blocking; the substrate is correct as written for a disciplined caller.

### CS3 [suggestion] Substrate covers exactly the two commit-thread read methods (informational confirmation)

**File:** `AbstractStorage.java:3962-4006` (`getPhysicalCollectionNameById`), `4770-4804` (`readRecordInternal`); `DatabaseSessionEmbedded.java:1129, 1176, 1785`

**Observation (positive — no fix needed):** I verified via PSI find-usages that the storage methods reachable on the commit thread through `session.load` → `executeReadRecord` are exactly the two this step made lock-free:
- the security check `getCollectionNameById(rid)` → `getPhysicalCollectionNameById` (`executeReadRecord` line 1129), and
- the cache-miss `storage.readRecord` → `readRecordInternal` (line 1176).

`recordExists` / `executeExists` also calls `getPhysicalCollectionNameById` (line 1792) but only in its *error-message* path, and `executeExists` itself is **not** reached by `session.load` — it is the `existsRecord` path, off the schema-serialization read path — so it correctly keeps `stateLock.readLock()`. Along the lock-free path, `checkOpennessAndMigration()` (reads the `status` field, no lock) and `doGetAndCheckCollection` → `checkCollectionSegmentIndexRange` → `doReadRecord` → `collection.readRecord` take no `stateLock`, so the substrate genuinely eliminates the read-lock re-entry with no residual deadlock.

**Caveat for Step 4 (not this step):** the substrate covers the *currently known* reachable set. If Step 4's promotion (`committedSchema.fromStream`) or the position-allocation / `commitEntry` loop reaches any other `stateLock.readLock()`-taking storage method under the held write lock (e.g., a different `getCollectionName`/`getCollectionIdByName` overload, or `recordExists` if a promotion path ever calls `existsRecord`), that method would deadlock the same way and needs the same treatment. The track's Step 4 PSI enumeration obligation (D3/T1, extended to the record/schema read path) is the place to close this; recorded here so the enumeration is not assumed complete by the substrate alone.

**Refutation considered:** Could the lock-free reader observe half-applied structural state mid-reconciliation? No. The window is a `ThreadLocal` depth — only the commit thread sees it active, so only the commit thread ever takes the lock-free branch. Every other reader still blocks on `readLock()` behind the commit's `writeLock()`. The commit thread's own lock-free reads are sequential with its own `collections` mutations (single thread), so there is no intra-window concurrency and no torn view. The "reader sees half-applied state during commit" hazard is not real for this substrate; it would require a second thread to enter the window, which the ThreadLocal design prevents.

## Evidence base

The Phase-5 refutation roster. CONFIRMED-as-issue claims (survived refutation) are one line; refuted claims appear in full.

#### C1 — Precondition is unguarded and unguardable on `ScalableRWLock` — CONFIRMED (CS1)
PSI: `isWriteLockedByCurrentThread` defined project-wide only on `java.util.concurrent.locks.ReentrantReadWriteLock`; `stateLock` is `ScalableRWLock` (`StampedLock` + per-thread reader state, methods `readLock/writeLock/sharedLock/exclusiveLock/...`, no owner field, no current-thread write-holder query). `enterCommitWindow()` increments the depth unconditionally with no write-lock check.

#### C2 — Leaked (under-balanced) window survives on a pooled thread and disables the read lock — CONFIRMED (CS2)
`exitCommitWindow()` asserts `depth[0] > 0` (over-exit only); the leak direction (enter without exit) is unguarded; the depth is a per-thread ThreadLocal so a leak persists across later transactions on the recycled thread. No production caller wires the window yet, so the risk is latent and Step-4-owned.

#### C3 — Reachable readLock set on the commit thread = the two methods made lock-free — CONFIRMED-as-safe (CS3, informational)
PSI find-usages: `executeReadRecord` reaches `getPhysicalCollectionNameById` (security, line 1129) and `storage.readRecord` (cache miss, line 1176); both are the methods made lock-free. `recordExists`/`executeExists` is off the `session.load` path and keeps the read lock. Lock-free path's `checkOpennessAndMigration`/`checkCollectionSegmentIndexRange`/`doGetAndCheckCollection`/`doReadRecord` take no `stateLock`. Substrate is deadlock-free along the known reachable set.

#### C4 — "Lock-free reader sees half-applied structural state during commit" — REFUTED
Hypothesis: a reader on the lock-free path could observe `collections`/`collectionMap` mid-reconciliation (a collection registered but its config not yet built, or dropped but its reverse-map entry lingering) and return a torn structural view, corrupting the schema the commit serializes.
Evidence checked:
- The window is `ThreadLocal<int[]>` depth (`commitWindowDepth`, lines 3376-3377 of the source / diff lines 23-24). `isCommitWindowActive()` reads the current thread's slot only. A thread that did not open the window reads depth `0` and takes the normal `readLock()` branch.
- Therefore only the single commit thread that holds `stateLock.writeLock()` ever takes the lock-free branch. Every other reader blocks on `readLock()` behind the held write lock and, on acquiring it, sees the fully-applied `collections` (write-release → read-acquire happens-before).
- The commit thread's lock-free reads and its `collections`/`collectionMap` mutations (`registerCollection`/`setCollection`, `dropCollectionInternal`) run sequentially on one thread inside the window — no concurrency, so no atomicity or visibility gap.
- No async fan-out re-reads under the window: the window is not inherited by other threads (ThreadLocal), so a background task triggered during the read cannot take the lock-free branch.
Verdict: REFUTED. The corruption / half-applied-view hazard does not exist for this substrate; it would require a second thread inside the window, which the ThreadLocal design structurally prevents. The remaining concerns are the precondition guard (C1) and the leak-tolerance of the primitive (C2), both Step-4 wiring obligations.

#### Write path / durability scope
This step changes no WAL record types, no page writes, no LSN handling, no atomic-operation boundaries, no checkpoint or dirty-page logic. The conditionally-elided lock surrounds unchanged read code (`doReadRecord` → `collection.readRecord`); the read itself still runs inside the commit's atomic operation (`calculateInsideAtomicOperation` in the test; `tx.getAtomicOperation()` in production). No new path bypasses the WAL, and recovery semantics are untouched. The substrate is crash-safety-neutral; its only durability-adjacent surface is the concurrency precondition analyzed above.
