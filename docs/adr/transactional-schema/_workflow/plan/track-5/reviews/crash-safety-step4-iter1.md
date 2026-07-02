<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 6, matches: 0}
cert_index:
  - {id: C1, verdict: REFUTED, anchor: "#### C1 "}
  - {id: C2, verdict: REFUTED, anchor: "#### C2 "}
  - {id: C3, verdict: REFUTED, anchor: "#### C3 "}
  - {id: C4, verdict: REFUTED, anchor: "#### C4 "}
  - {id: C5, verdict: REFUTED, anchor: "#### C5 "}
  - {id: C6, verdict: REFUTED, anchor: "#### C6 "}
flags: [CONTRACT_OK]
-->

## Findings

No crash-safety or durability findings. The step is safe to ship on this
dimension. Invariant I-A2 ("a provisional collection id never reaches durable
bytes") holds across all five durable surfaces the focal question named (record
content, links, WAL, schema records, index entries), and the commit-window
rewrite composes correctly with the Track 4 / Step 2 failure and undo arms. The
six candidate holes traced below were each refuted; the reasoning is in
`## Evidence base`.

## Evidence base

Reference accuracy: mcp-steroid was reachable and the open project
(`transactional-schema-b4l1mcdq`) matches the working tree, so the symbol audits
below are PSI-backed, not grep.

#### C1 — The I-A2 guard is a production-disabled `assert`, so a slipped provisional id reaches durable bytes — REFUTED

Claim: `computeCommitWorkingSet` guards I-A2 only with
`assert !SchemaShared.isProvisionalCollectionId(...)` (AbstractStorage.java:2434),
which the JVM disables without `-ea`, so in production a provisional id that
survived the rewrite would flow into serialization and corrupt durable bytes.

Refutation. The assert is defense-in-depth, not the sole guard. Every durable
record-write path passes the record's collection id through
`doGetAndCheckCollection` (AbstractStorage.java:2070), which calls
`checkCollectionSegmentIndexRange` (AbstractStorage.java:6851): that method throws
`IllegalArgumentException` for any `collectionId < 0`, and every provisional id is
`<= -2`. The three write sites all hit it before any byte is written:
`computeCommitWorkingSet` for UPDATED/DELETED (:2452) and CREATED (:2471), the
position-allocation loop (:2712), and `commitEntry` itself (:6767) for all record
types. So a provisional id that somehow reached the working set in production
triggers a loud abort routed to `rollback` + `undoReconciledCollections`
(AbstractStorage.java:2808-2840), never a silent durable write. Note the CREATED
fix-path at :2460 keys on `COLLECTION_ID_INVALID` (`-1`), which a provisional id
(`<= -2`) does not match, so it falls straight to the throwing
`doGetAndCheckCollection` rather than being silently "fixed." The assert adds a
sharper message under test; the production floor is the negative-id rejection.

#### C2 — A provisional id embedded as a link inside another record's content reaches durable bytes — REFUTED

Claim: a committed-class record `bar` (UPDATED) that links to a tx-created-class
record `foo` (provisional collection) serializes `foo`'s provisional id into
`bar`'s content, and the rewrite only fixes `foo`'s own identity, not the link
copy inside `bar`.

Refutation. The rewrite goes through
`ChangeableRecordId.setCollectionAndPosition` (AbstractStorage.java:2508-2510),
which mutates `foo`'s identity object in place; the transaction's identity-change
listeners re-key against the same object. `foo` is a new record, so its identity
is a `ChangeableRecordId`, and a link to `foo` resolves the live identity at
serialization time — the identical mechanism the pre-existing temp-RID resolution
(negative position) relies on, which the database already depends on to serialize
links to same-tx-created records correctly. Ordering guarantees the link sees the
final real id: the provisional->real rewrite runs at :2679 (before the working
set), the temp-position->real rewrite runs in the allocation loop at :2740, and
`commitEntry` serialization runs only afterward at :2752. All allocations
complete before any serialization, so by the time `bar` serializes, `foo` carries
`#realId:realPos`. A record that links to a brand-new `foo` must itself be dirty
this tx (nothing durable could already point at `foo`), so it is in the working
set and re-serialized from the live graph, never from stale durable bytes.

#### C3 — A pure-data commit could carry a provisional-id record, skipping the rewrite entirely — REFUTED

Claim: `rewriteProvisionalRecordCollectionIds` runs only in the schema-carry
branch (`schemaContext != null`, AbstractStorage.java:2563/2679); a record with a
provisional id reaching the pure-data branch would never be rewritten.

Refutation. A provisional collection id exists only for a class created inside the
current still-open transaction (Step 2 allocates `<= -2` for tx-created classes),
and creating a class is a schema write that seeds `TxSchemaState`. The commit
decides the branch from exactly that signal:
`schemaCarry = session.getTxSchemaState() != null` (AbstractStorage.java:2336-2337),
read at commit entry before any teardown. So a record carrying a provisional id
implies `TxSchemaState != null` implies the schema-carry branch implies the
rewrite runs. The invariant is closed by construction, and C1's
`doGetAndCheckCollection` floor is the backstop if the premise were ever violated
(loud abort, not corruption). A rolled-back tx's stale record cannot resurface in
a later pure-data commit either (see C5).

#### C4 — The (-2,-2) null-RID sentinel collides with provisional collection id -2 on the durable path — REFUTED

Claim: `RecordIdInternal.serialize` writes collection `-2` for a null RID and
`deserialize` treats `collection == -2 && pos == -2` as null
(RecordIdInternal.java:184-206); provisional collection id `-2` now being a valid
id, a record RID of `#-2:-2` would round-trip to null.

Refutation. PSI find-usages on `RecordIdInternal.serialize(RID, DataOutput)` and
`deserialize(DataInput)` returns zero production callers — every reference is in
`RecordIdTest`. The overlap cannot be reached on any durable path. Independently,
the collision could not occur even if a caller existed on the commit path: a
provisional id is rewritten to a non-negative real id before the first durable
serialization (C2 ordering), so no `#-2:*` RID is ever serialized durably. The
implementer's "inert" judgment is confirmed.

#### C5 — The early rewrite leaves record identities on reverted real collections after a failed commit, enabling a wrong-collection write on retry — REFUTED

Claim: the rewrite mutates identities from provisional to real at :2679, well
before most failure points (working-set validation, lock acquisition, allocation,
`commitEntry`, index build, `endTxCommit`). On failure, `undoReconciledCollections`
reverts the real collection but the in-memory record keeps `#realId:tempPos`. If
the app re-saves that entity and slot `realId` is later reused by a different
collection, the record lands in the wrong collection — a durable inconsistency,
and a wider window than the pre-existing late temp-position rewrite.

Refutation. The stale identity is inert because a failed commit unloads it. The
storage-level failure rethrows (AbstractStorage.java:2801-2807) up through
`commitSchemaCarry` -> `internalCommit` -> `doCommit`, whose catch calls
`rollbackInternal` (FrontendTransactionImpl.java:690).
`invalidateChangesInCacheDuringRollback` (:418-428) calls `rec.unload()` on every
tx record and clears the local cache, and `rollbackInternal` closes the
transaction (`recordOperations` asserted empty at :405). Re-adding an unloaded
record throws at `addRecordOperation` (:518, "not bound to session, please call
Transaction.load(record)"), and reloading is impossible for a temp
(non-persistent) position. So the mutated-but-reverted entity can never be
re-committed; the widened window carries no durable consequence. No crash is
involved either, so this sits outside the crash/recovery lane regardless.

#### C6 — A provisional id enters the WAL, so recovery replays a provisional collection id — REFUTED

Claim: if any record or schema record is serialized before provisional-id
resolution, the WAL carries a provisional id and replay reconstructs a broken
collection reference.

Refutation. Write-path trace of the schema-carry apply window
(AbstractStorage.java:2561-2788): `reconcileCollections` creates real collections
at `nextFreeCollectionId` and writes the `FileCreated` WAL with real ids (:2572);
`resolveProvisionalCollectionIds` patches the tx-local schema in memory (:2589,
no WAL); `toStream` (:2622) and `enrollReconciledIndexRecords` (:2636) enroll
schema/index records into the transaction (written later, and their
collection-id content already patched); the rewrite fixes record identities
(:2679); `computeCommitWorkingSet` (:2684) and the allocation loop (:2691) assign
real ids and positions; the first record content is written to the WAL only at
`commitEntry` (:2752), by which point every id is real; engine builds (:2775) key
on the real record RIDs; `endTxCommit` (:2854) flushes. The earliest WAL byte for
any record or file carries a real id, so replay of a committed unit reconstructs
real collections and real record ids with no provisional id present. A crash
before `endTxCommit` discards the incomplete unit (or restores it lazily via the
Track 1 fix), leaving no partial provisional state.
