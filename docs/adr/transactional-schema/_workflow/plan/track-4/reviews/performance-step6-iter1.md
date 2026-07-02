<!--MANIFEST
dimension: performance
prefix: PF
step: "Track 4 Step 6 — convert createVertexWithClass and getLowerSubclass to snapshot-first reads (I-U5)"
commit_range: 2bf7d95305f8b14ba01430dde79e109752e9e8d0~1..2bf7d95305f8b14ba01430dde79e109752e9e8d0
verdict: PASS
blocker_count: 0
should_fix_count: 0
suggestion_count: 0
findings_total: 0
high_water_mark: 0
evidence_base: present
cert_index: [C1, C2, C3, C4]
flags: []
index: []
-->

## Findings

No performance findings. Both read-site conversions are a strict per-call cost
reduction on their hot paths and introduce no new snapshot-rebuild trigger. The
evidence base records the cost comparison and the two refuted regression
candidates the review focus named.

## Evidence base

The step swaps two lock-based schema reads for the lock-free immutable snapshot.
The review's three focus questions resolve cleanly: the snapshot accessor returns
a cached immutable instance (it does not copy or rebuild on each call in steady
state), the swap shifts no rebuild cost to a worse place, and the per-call
overhead drops rather than rises. Detail below; CONFIRMED claims compressed to one
line per the YTDB-1069 roster rendering, refuted/non-passing claims in full.

#### C1 — The snapshot accessor returns a cached immutable instance, lock-free in steady state (review-focus question, confirmed)
CONFIRMED: `MetadataDefault.getImmutableSchemaSnapshot()` (MetadataDefault.java:106) returns the thread-local cached `immutableSchema` field when a query has frozen it via `makeThreadLocalSchemaSnapshot()`; otherwise it falls to `schema.makeSnapshot()` → `SchemaProxy.makeSnapshot()` → `SchemaShared.makeSnapshot(session)`, whose first statement reads the `this.snapshot` field and returns it with no lock when non-null (double-checked: the `acquireSchemaReadLock()` + `snapshotLock` rebuild arm runs only when `this.snapshot == null`, i.e. immediately after a `forceSnapshot()` invalidation). No allocation, no copy, no rebuild on the steady-state call. `ImmutableSchema.getClass(String)` is a plain `classes.get(name)` `HashMap` lookup returning the cached `SchemaClassInternal` directly, with no per-call wrapper allocation.

#### C2 — `createVertexWithClass`: the swap removes a per-vertex-create read-lock acquisition (confirmed net win)

The per-vertex-create read is the hotter of the two paths (every `addVertex`). The conversion is a strict reduction.

- PRIOR (commit `…e8d0~1`): `session.getSharedContext().getSchema()` returns the committed `SchemaShared` directly; `SchemaShared.getClass(label)` does `acquireSchemaReadLock()` / `releaseSchemaReadLock()` around a `classes.get(label)`. So every vertex create took the schema read lock — exactly the reader that I-U5 identifies as stalling for the whole duration of a schema-carrying commit that holds `stateLock.writeLock()`/`SchemaShared` write lock.
- CURRENT: `session.getMetadata().getImmutableSchemaSnapshot().getClass(label)` — lock-free cached-snapshot read plus a `HashMap` lookup. The lock-based `getSharedContext().getSchema()` path is now reached only on the `vertexClass == null` branch (a label not yet a class), which calls `getOrCreateClass` under the write lock; that is a one-time-per-new-label create, not the steady-state per-vertex read.
- COST TRACE for `createVertexWithClass` at YTDBGraphImplAbstract.java:121-145: OPERATION = one snapshot field read + one `HashMap.get` + an `isVertexType()` field read; COMPLEXITY = O(1) per vertex create; ALLOCATIONS = none on the existing-class path (the snapshot and the `SchemaImmutableClass` are cached); I/O = none; LOCK HOLD TIME = none on the read (was a read-lock acquire/release per call before).
- VERDICT: net win at every scale. Removing the read-lock acquire/release also removes the I-U5 stall: a concurrent schema-carrying commit no longer blocks the per-vertex-create read.

#### C3 — `getLowerSubclass`: the swap removes two `SchemaClassProxy` allocations per call (confirmed net win)

The per-MATCH-alias-step read fires once per pair of adjacent aliases per MATCH step, so a traversal touching many aliases calls it repeatedly.

- PRIOR: `session.getMetadata().getSchema()` returns the `SchemaProxy`; `SchemaProxy.getClass(String)` runs the tx-local-vs-committed `resolve()` and, for each non-null result, allocates `new SchemaClassProxy(cls, session)`. Two calls (`className1`, `className2`) meant up to two proxy allocations per `getLowerSubclass` invocation, plus the `resolve()` work, plus the `isSubClassOf` then running on a proxy.
- CURRENT: `getImmutableSchemaSnapshot().getClass(name)` twice — two lock-free `HashMap` lookups returning the cached `SchemaImmutableClass` objects directly; `isSubClassOf` runs on the immutable instances. No per-call proxy allocation.
- COST TRACE for `getLowerSubclass` at SQLMatchStatement.java:364-385: OPERATION = two snapshot `HashMap` lookups + two `isSubClassOf` checks; COMPLEXITY = O(1) per call beyond the class-hierarchy walk `isSubClassOf` already did; ALLOCATIONS = zero (was up to two `SchemaClassProxy` per call); I/O = none.
- VERDICT: net win — eliminates up to two short-lived `SchemaClassProxy` allocations per MATCH-alias-step and drops the `resolve()` path. Both classes were already required to exist (the method throws otherwise), so the snapshot lookups carry the same semantics.

#### C4 — The swap shifts no rebuild cost to a worse place (review-focus question, refuted as a regression)

The review focus asks whether routing these two reads onto the snapshot forces a snapshot rebuild on every schema mutation that the read now provokes. It does not.

- The snapshot is invalidated by `SchemaShared.forceSnapshot()`, which nulls `this.snapshot`; the next `makeSnapshot()` rebuilds via `new ImmutableSchema(this, session)` — an O(live-class-count) build of two maps plus one `SchemaImmutableClass` per class. That rebuild is **pre-existing** and is the canonical schema-read substrate already used by `newEntity` and the query engine (the track notes `newEntity` resolves through the immutable snapshot, not the tx-local view). `forceSnapshot()` is fired exactly once per schema-carrying commit (D8's single trailing `forceSnapshot`, landed in Step 4).
- This step adds **no new `forceSnapshot` call and no new invalidation trigger** — the diff touches only two read sites; it neither mutates the schema nor calls `forceSnapshot`. It routes two additional readers onto an already-warmed cache. Invalidation frequency is unchanged (still once per schema commit, D19's load-bearing low-rate premise); the rebuild is amortized across all snapshot readers, not charged to these two.
- The one new per-call detail: outside a `makeThreadLocalSchemaSnapshot()` frozen window, `MetadataDefault.immutableSchema` is null, so each call routes through `schema.makeSnapshot()` (two virtual dispatches, an `assert`-guarded `assertIfNotActive`, and the lock-free `this.snapshot` field read). The `assert` is a no-op in production (`-ea` off), and the field read is far cheaper than the read-lock acquisition (`createVertexWithClass`) and proxy allocations (`getLowerSubclass`) it replaced.
- VERDICT: NEGLIGIBLE / net win. No rebuild cost is shifted; the swap reduces steady-state per-call work on both paths.
