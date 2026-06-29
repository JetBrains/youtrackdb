<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 3, matches: 0}
cert_index:
  - {id: C1, verdict: REFUTED, anchor: "#### C1 "}
  - {id: C2, verdict: REFUTED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED-NO-HAZARD, anchor: "#### C3 "}
flags: [CONTRACT_OK]
-->

## Findings

## Evidence base

The dimension question for this step: does the selective per-class write
(rewrite only changed classes' records, skip unchanged ones, write the root only
on a link-set change or a payload diff) create or mask any concurrent-commit race
beyond the orchestrator-confirmed pre-existing MVCC stale-tx-local-seed bug
(`MetadataWriteMutexTest.twoConcurrentSchemaTransactionsSerializeWithoutAbort`,
deferred to Track 3 / Track 7)? Three candidate hazards were constructed and each
was refuted under the established lock discipline. No finding resulted, so this is
a clean pass. The reasoning is recorded in full because the candidate hazards
(C1, C2) were refuted rather than confirmed-as-issue.

#### C1 — Selective-write inputs as cross-thread shared state — REFUTED

Candidate: the selective write keys on three inputs read at
`AbstractStorage.applyCommitOperations` (the new call site,
`AbstractStorage.java:2506-2510`): `txSchemaState().getChangedClasses()`,
`txLocalSchema()`, and `rootPayloadDiffersFrom(committedSchema())`. If any of
these were shared mutable state touched by a concurrent commit thread, the
write-set selection could be computed against a racing mutation — a class changed
by thread B omitted from thread A's `changedClasses` would skip B's record write
and lose it.

Refutation (PSI, reference-accurate): `TxSchemaState` holds `Set<String>
changedClasses` and `SchemaShared txLocalSchema` as per-instance fields, and a
`TxSchemaState` is per-session / per-transaction state, not shared across threads.
`getChangedClasses()` has exactly one production caller
(`AbstractStorage.java:2506`); every other usage is in tests
(`CopyForTxTest`, `SchemaProxyRoutingTest`, `SchemaDeguardTest`). The new
`toStream(session, Set, boolean)` overload has exactly one production caller
(`AbstractStorage.java:2510`); its sibling references are the 1-arg overload
delegating to it (`SchemaShared.java:926` → `return toStream(session, null,
true)`) and the internal full-write path. `rootPayloadDiffersFrom` has exactly one
production caller (`AbstractStorage.java:2508`). All three reads sit inside the
held `stateLock.writeLock()` window (D19: a schema-carrying commit takes the write
lock from the start and serializes; D5/D7: a second schema tx blocks on the mutex,
never runs concurrently). The inputs are private to the committing transaction.
The candidate cross-thread share does not exist.

#### C2 — Unsynchronized read of the committed (shared) schema in the diff — REFUTED

Candidate: `rootPayloadDiffersFrom(committed)`
(`SchemaShared.java:1092-1113`) reads the committed `SchemaShared`'s
`collectionCounter`, `blobCollections`, and `properties` fields — and the
committed instance is shared across sessions. The method takes no read lock (its
Javadoc states the caller holds the write locks). A concurrent mutation of the
committed schema during this read would be a data race and could yield a stale
"no difference" verdict, omitting the root from the write set and losing the
payload.

Refutation: the only way another thread mutates the committed `collectionCounter`
/ `blobCollections` / `properties` is by promoting its own tx-local schema into
the committed instance at its commit — and promotion runs under the same schema
write lock plus the mutex that serializes schema commits (D5/D7/D19). The read
runs inside `applyCommitOperations` after `stateLock.writeLock()` was taken at
commit entry and after `txLocalSchema().acquireSchemaWriteLock(session)`
(`AbstractStorage.java:2492`). A pure-data commit on the read-lock fast path never
touches these three root-payload fields. No concurrent writer to the committed
payload can exist while this read holds the write lock; the read is safe under the
unchanged Track 2 / Track 4 lock contract, not newly exposed by this step.

#### C3 — Does the write reordering change what the existing racer exercises — CONFIRMED-NO-HAZARD

The selective write changes *which* records enter the commit working set (an
unchanged class's record is loaded read-only, not rewritten; the root is written
only on a link-set change or a payload diff). The orchestrator independently
confirmed that the pre-existing `MetadataWriteMutexTest` failure surfaces on a
different record id after this step (root vs class record) purely because the
write ordering changed, and is not a Step 5 defect. Assessed here: is that
reordering exercised by a concurrent path, and is added concurrency coverage owed
at this step?

The existing in-file concurrency test
`schemaCommitReloadAndIndexLoadRaceWithoutDeadlock`
(`SchemaCommitReconciliationTest.java:529`) runs a schema-commit racer that does
**create then drop** of a uniquely named class each round, 30 rounds, against a
concurrent schema reload (schema write lock) and index-manager load
(index-manager lock), under `CyclicBarrier(3)` coordination, `AtomicReference`
result collection, and `@Test(timeout=60_000)` as the deadlock detector — sound
coordination, no `Thread.sleep` for synchronization. Under Step 5 this racer now
exercises both selective-write arms concurrently with the other lock holders: a
create grows the `"classes"` link set (root written, new class record written) and
a drop shrinks it (root written, dropped record deleted, other classes skipped).
The second concurrency test
(`...DataCommitContends...`, `SchemaCommitReconciliationTest.java:~420-515`)
pins a schema commit inside the held write-lock window via
`setCommitWindowTestHook` and verifies a concurrent data commit is excluded until
release — the exclusivity guarantee the selective-write reads (C1/C2) rely on.

The reordering touches only per-transaction state and the committed schema, both
covered by the held write lock the existing tests already characterize. It
introduces no new interleaving, so no new concurrent-commit test is owed at this
step. The new Step 5 tests are single-threaded by design and correctly so: they
verify the write-set *selection mechanism* (record-version deltas: an unchanged
class record's version is unchanged; the root version increments on create/drop;
`rootPayloadDiffersFrom` detects each payload component) — a white-box concern, not
a concurrency one. Deeper concurrency hardening (mutex permit handshake, freezer
gate, and the stale-seed contention class that the pre-existing
`MetadataWriteMutexTest` failure belongs to) is the dedicated scope of Track 7 and
is correctly deferred there, not pulled forward into this write-amplification step.

Conclusion: the selective write introduces no new concurrent-commit hazard and
masks none; the existing concurrency coverage is adequate for the new write
ordering. Clean pass.
