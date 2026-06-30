<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 0, suggestion: 3}
index:
  - {id: PF1, sev: suggestion, loc: SchemaShared.java:877, anchor: "### PF1 ", cert: C1, basis: "per-slot String signature allocation in rootPayloadDiffersFrom; rare schema-commit path, negligible at scale"}
  - {id: PF2, sev: suggestion, loc: AbstractStorage.java:1897, anchor: "### PF2 ", cert: C2, basis: "getRealCollectionIds rebuilds an IntOpenHashSet on each of 3 commit-path calls; rare schema-commit path"}
  - {id: PF3, sev: suggestion, loc: YTDBGraphImplAbstract.java:127, anchor: "### PF3 ", cert: C3, basis: "snapshot-first read rebuilds the whole ImmutableSchema (and re-takes the schema read lock) in the post-forceSnapshot window; bounded, rare, matches existing snapshot-consumer behavior"}
evidence_base: {section: "## Evidence base", certs: 5, matches: 5}
cert_index:
  - {id: C1, verdict: MATCHES, anchor: "#### C1 "}
  - {id: C2, verdict: MATCHES, anchor: "#### C2 "}
  - {id: C3, verdict: MATCHES, anchor: "#### C3 "}
  - {id: C4, verdict: MATCHES, anchor: "#### C4 "}
  - {id: C5, verdict: MATCHES, anchor: "#### C5 "}
flags: [CONTRACT_OK]
-->

## Findings

### PF1 [suggestion] Per-slot String signature allocation in `rootPayloadDiffersFrom`

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java` (line 877-893)

**Issue.** `rootPayloadDiffersFrom` compares the two global-property tables slot by slot
through `globalPropertySignature`, which builds a fresh `name + "|" + typeInternal` String
for each non-null slot on both the tx-local and the committed side. For a table of `P`
populated slots that is up to `2·P` String allocations (plus the `StringBuilder` churn of
the concatenation) per commit. The carried Step-5 PF1 suggestion named the same site; this
re-check confirms it survives at track scope but does not rise above suggestion.

**Evidence.** See `#### C1`. COST TRACE: schema-commit path only (`applyCommitOperations`
calls it once per schema-carrying commit, line 1522). DATA SCALE: `P` = global-property
count, bounded by the number of distinct property name/type pairs across all classes —
hundreds to low thousands in a large schema. ALLOCATIONS: ~`2·P` transient Strings per
commit. SCALE CHECK: at 1M records the schema-commit *rate* is the bound, not the table
size (D19's load-bearing premise), so even a 2000-slot table at one schema commit per
minute is sub-microsecond-per-commit overhead drowned by the commit's I/O and `forceSnapshot`
rebuild. VERDICT: NEGLIGIBLE in absolute terms; recorded as a suggestion only because it is
a trivially avoidable allocation on a path the track otherwise keeps lean.

**Impact.** Transient GC pressure of `O(P)` short-lived Strings per schema commit. Not
observable against the commit's actual cost.

**Suggestion.** Compare `name` and `typeInternal` field-by-field instead of building a
signature String (a two-field `equals` per slot, zero allocation), or leave as-is and accept
the documented suggestion — the readability of the signature comparison may be worth the
trivial cost. Either resolution is fine; do not block on it.

### PF2 [suggestion] `getRealCollectionIds` rebuilds a fresh set on each commit-path call

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (line 1897-1903, and `undoReconciledCollections` line 2023)

**Issue.** `SchemaShared.getRealCollectionIds()` (SchemaShared.java:524) allocates a fresh
`IntOpenHashSet` sized to `collectionsToClasses.size()` and scans the whole reverse map under
the schema read lock on every call. `reconcileCollections` calls it twice (committed +
tx-local, lines 1897 and 1903) and the failure-path `undoReconciledCollections` calls it once
more (line 2023). For a schema with `K` collections that is `O(K)` scan + one set allocation
per call.

**Evidence.** See `#### C2`. COST TRACE: schema-commit path only. DATA SCALE: `K` = live
collection count (roughly classes × collections-per-class, tens to low thousands). The set is
genuinely needed for the D9 set-difference, and the two `reconcileCollections` calls compute
two different sets (committed vs tx-local), so neither is redundant. The third call
(`undoReconciledCollections`) is on the failure path only. SCALE CHECK: bounded by schema-commit
rate (D19); `O(K)` per rare commit is invisible at any record scale. VERDICT: NEGLIGIBLE.

**Impact.** One `IntOpenHashSet` allocation and an `O(K)` scan per schema commit (two on the
success path). No record-scale component.

**Suggestion.** None required. If a future change makes schema commits frequent (it should not,
per D19), the committed-side set could be cached on `SchemaShared` and invalidated by
`forceSnapshot`, mirroring the snapshot field. Recorded for completeness; not actionable now.

### PF3 [suggestion] Snapshot-first hot reads rebuild the full `ImmutableSchema` in the post-commit invalidation window

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/YTDBGraphImplAbstract.java` (line 127) and `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLMatchStatement.java` (line 368)

**Issue.** The Step-6 conversions route the per-vertex-create and per-MATCH-step reads through
`getMetadata().getImmutableSchemaSnapshot()`. That call returns the pinned thread-local snapshot
only when one is pinned (`immutableCount > 0`); otherwise it falls through to
`schema.makeSnapshot()`, which returns the cached `SchemaShared.snapshot` field **unless that
field is null** — exactly the state a schema commit leaves behind, because its promotion calls
`forceSnapshot()` (AbstractStorage.java:1803), which nulls `snapshot`. The first reader after a
schema commit that is not running under a pinned thread-local snapshot therefore reconstructs the
entire `ImmutableSchema` (deep-copies every class, every property, and every index definition —
`ImmutableSchema` ctor) **and re-acquires `acquireSchemaReadLock()`** inside that reconstruction
(via `getClasses(session)`, SchemaShared.java). The Gremlin `addVertex` → `createVertexWithClass`
path does not pin a thread-local snapshot (`executeSchemaCode` just acquires a session and runs
the lambda), so a per-vertex-create immediately after a concurrent schema commit can hit this
rebuild — and that rebuild still stalls behind a concurrently-held schema write lock, the very
stall the conversion was meant to remove.

**Evidence.** See `#### C3`, `#### C4`. This is **not a regression** the track introduced for the
steady state, and it is **correct**: in steady state (no schema change) `SchemaShared.snapshot`
stays warm and both converted reads collapse to a single plain field read — strictly cheaper than
the old `acquireSchemaReadLock()` + map lookup. The heavy path appears only in the narrow window
after a (rare, per D19) schema commit, is a bounded one-time cost per affected thread until one
caller repopulates the cache, and is identical to the behavior every other `getImmutableSchemaSnapshot`
consumer already has. SCALE CHECK: MATTERS only as a brief post-schema-commit latency blip on the
converted hot paths, bounded by the schema-commit rate; NEGLIGIBLE in steady state. VERDICT:
acceptable as designed.

**Impact.** A bounded latency spike (one full `ImmutableSchema` rebuild + a schema-read-lock
acquisition) on the first unpinned converted read after each schema commit. No steady-state cost;
the steady-state change is a net improvement.

**Suggestion.** No change required for this track — the behavior matches the design premise and
every existing snapshot consumer. If the post-commit window ever shows up in MATCH/addVertex
latency profiling, the lever is to repopulate `SchemaShared.snapshot` eagerly at the end of
promotion (rebuild once on the committing thread under the write lock it already holds) instead of
leaving it null for the next reader to rebuild — moving the one-time cost off the hot-path readers.
Recorded so the track close-out and Track 5/6 (which also call `forceSnapshot`) are aware of the
window.

## Evidence base

#### C1 `rootPayloadDiffersFrom` allocation — MATCHES

`globalPropertySignature` (SchemaShared.java:892) returns `slot.getName() + "|" +
slot.getTypeInternal()` for each non-null slot; `rootPayloadDiffersFrom` (line 865-886) calls it
on `properties.get(id)` and `committed.properties.get(id)` inside a loop over `properties.size()`.
The diff confirms the call site is `applyCommitOperations` line 1522, fired once per
schema-carrying commit. Survived the scale check as a confirmed-but-negligible suggestion (rare
path × trivial per-call cost). Carried forward verbatim from the Step-5 deferred PF1; re-checked at
track scope and unchanged.

#### C2 `getRealCollectionIds` set rebuild — MATCHES

`getRealCollectionIds` (SchemaShared.java:525) allocates `new IntOpenHashSet(collectionsToClasses.size())`
under `acquireSchemaReadLock` and scans the key set. PSI/diff confirm three call sites, all on the
schema-commit path: `reconcileCollections` lines 1897 (committed) and 1903 (tx-local), and
`undoReconciledCollections` line 2023 (failure path). The two success-path calls compute distinct
sets and are not redundant. Survived as a confirmed-but-negligible suggestion: O(K) per rare commit.

#### C3 `getImmutableSchemaSnapshot` rebuild-on-null — MATCHES

`MetadataDefault.getImmutableSchemaSnapshot()` returns the pinned `immutableSchema` only when
non-null, else `schema.makeSnapshot()`. `SchemaShared.makeSnapshot(session)` returns the cached
`this.snapshot` field via double-checked locking, constructing `new ImmutableSchema(this, session)`
only when `this.snapshot == null`. `forceSnapshot()` (called at promotion, diff line 1803) sets
`snapshot = null`. PSI-confirmed: `makeThreadLocalSchemaSnapshot()` callers are `executeReadRecord`,
`commit`, and `getGlobalPropertyById` — none on the Gremlin `addVertex` → `createVertexWithClass`
lambda (`executeSchemaCode` does not pin), so the per-vertex path can run unpinned and hit the
rebuild in the post-commit window.

#### C4 `ImmutableSchema` construction re-takes the read lock — MATCHES

`ImmutableSchema(schemaShared, session)` ctor calls `schemaShared.getClasses(session)` three times
(sizing, iteration, init loop), and `SchemaShared.getClasses(session)` does
`acquireSchemaReadLock()` / `releaseSchemaReadLock()`. So a snapshot rebuild after `forceSnapshot`
both deep-copies all classes/properties/indexes and acquires the schema read lock — confirming the
post-commit-window read still contends on the same lock the conversion otherwise avoids. Net result
is still a steady-state win because the rebuild is rare and the steady-state path is a plain field
read.

#### C5 Per-record-read `ThreadLocal.get()` overhead — MATCHES (non-issue, not raised)

The window-aware read methods (`getPhysicalCollectionNameById`, `readRecordInternal`, `getIndexEngine`,
`isClosed`, `getCollectionNames`, `getCollectionIdByName`) now call `isCommitWindowActive()` =
`commitWindowDepth.get()[0] > 0` on every call, including the pure-data fast path.
`getPhysicalCollectionNameById` and `readRecordInternal` are reached per-record-read via
`executeReadRecord` (PSI-confirmed); `getIndexEngine` per-index-op via `IndexAbstract`. A single
`ThreadLocal.get()` is a thread-local-map hash lookup (single-digit ns) against a record read that
costs page-cache lookup + deserialization. Step 3's review already adjudicated this exact concern
(its PF1, "confirmed non-issue"); the track-scope re-check agrees and it is **not** raised as a
finding.
